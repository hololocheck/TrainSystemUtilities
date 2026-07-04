package com.trainsystemutilities.station;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 列車運行スケジュールの集約キャッシュ (server-side).
 *
 * <p>1 秒に 1 回 ({@link #updateAll}) で {@link Create#RAILWAYS}.trains を走査し、
 * 各列車の現在駅 / 目的地駅 / ETA / 周回スケジュール を {@link Snapshot} に格納。
 *
 * <p>駅は {@link StationGroup} 単位で扱う (Create の GlobalStation を group ID に解決)。
 * 経路探索 / 乗換案内端末はこのキャッシュを参照する。
 *
 * <p>Thread-safety: 更新は server tick thread のみ。読出しは any thread で immutable
 * snapshot を取得する。
 */
public final class TrainScheduleCache {

    /** 列車進行速度がこの値より大きければ「走行中」と判定 (blocks/tick)。 */
    private static final double MOVING_SPEED_THRESHOLD = 0.005;
    /** train.speed が 0 になっても speed-based 推定で使う巡航速度 fallback。 */
    private static final double DEFAULT_CRUISE_SPEED = 0.4; // ~ 8 m/s

    /**
     * @param trainId            列車 UUID
     * @param trainName          表示名
     * @param currentGroupId     停車中駅グループ (null = 走行中)
     * @param nextGroupId        次の目的地グループ (null = 目的地不明)
     * @param etaTicksToNext     次の駅到着までの推定 tick (停車中は 0)
     * @param upcomingGroupIds   現在以降の周回スケジュール上のグループ ID 列 (重複排除済)
     * @param upcomingStationNames 上記に対応する駅名 (UI 表示用)
     */
    public record Snapshot(
            UUID trainId,
            String trainName,
            UUID currentGroupId,
            UUID nextGroupId,
            int etaTicksToNext,
            List<UUID> upcomingGroupIds,
            List<String> upcomingStationNames,
            /** upcomingGroupIds[i] に対応する Create GlobalStation の UUID (= 番線解決用)。 */
            List<UUID> upcomingStationIds
    ) {}

    /** 駅グループ → そのグループに停車する全列車 ID。経路探索用 reverse index。 */
    public record TrainsByGroup(Map<UUID, Set<UUID>> map) {}

    private static volatile Map<UUID, Snapshot> snapshots = Map.of();
    private static volatile TrainsByGroup trainsByGroup = new TrainsByGroup(Map.of());
    private static volatile long lastUpdateGameTime = 0;

    private TrainScheduleCache() {}

    public static Map<UUID, Snapshot> all() { return snapshots; }
    public static TrainsByGroup trainsByGroup() { return trainsByGroup; }
    public static long lastUpdateGameTime() { return lastUpdateGameTime; }

    /** 1 秒に 1 回 (= 20 ticks) サーバ tick から呼ばれる。 */
    public static void updateAll(MinecraftServer server) {
        if (server == null) return;
        Map<UUID, Snapshot> next = new HashMap<>();
        Map<UUID, Set<UUID>> byGroup = new HashMap<>();

        // 1. station name → groupId index を構築
        StationGroupSavedData savedData = StationGroupSavedData.get(server);
        Map<UUID, UUID> stationIdToGroupId = new HashMap<>();
        for (StationGroup g : savedData.all()) {
            for (UUID stationId : g.stationBlockIds()) {
                stationIdToGroupId.put(stationId, g.id());
            }
        }
        // 駅名 → globalStation UUID (DestinationInstruction の filter regex で resolve)
        // GlobalStation を全件 enumerate して name と id のペアを保持
        Map<String, UUID> stationNameToId = new HashMap<>();
        try {
            for (var graph : Create.RAILWAYS.trackNetworks.values()) {
                for (GlobalStation s : graph.getPoints(EdgePointType.STATION)) {
                    if (s.name != null && !s.name.isEmpty()) {
                        stationNameToId.put(s.name, s.getId());
                    }
                }
            }
        } catch (Throwable t) {
            // Create が読めない / 互換性問題 → 空のままで return
            snapshots = Map.of();
            trainsByGroup = new TrainsByGroup(Map.of());
            return;
        }

        // 2. 全 train を走査
        try {
            for (Train train : Create.RAILWAYS.trains.values()) {
                if (train == null || train.id == null) continue;
                Snapshot snap = buildSnapshot(train, stationNameToId, stationIdToGroupId);
                if (snap == null) continue;
                next.put(snap.trainId(), snap);
                for (UUID gId : snap.upcomingGroupIds()) {
                    byGroup.computeIfAbsent(gId, k -> new LinkedHashSet<>()).add(snap.trainId());
                }
            }
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn("[TrainScheduleCache] update failed: {}", t.getMessage());
        }

        snapshots = Collections.unmodifiableMap(next);
        Map<UUID, Set<UUID>> immutable = new HashMap<>();
        byGroup.forEach((k, v) -> immutable.put(k, Collections.unmodifiableSet(v)));
        trainsByGroup = new TrainsByGroup(Collections.unmodifiableMap(immutable));
        lastUpdateGameTime = server.overworld() == null ? 0 : server.overworld().getGameTime();
    }

    private static Snapshot buildSnapshot(
            Train train,
            Map<String, UUID> stationNameToId,
            Map<UUID, UUID> stationIdToGroupId) {

        UUID currentGroupId = null;
        var currentStation = train.getCurrentStation();
        if (currentStation != null) {
            currentGroupId = stationIdToGroupId.get(currentStation.getId());
        }

        UUID nextGroupId = null;
        int etaTicks = 0;
        if (train.navigation != null && train.navigation.destination != null) {
            UUID destStationId = train.navigation.destination.getId();
            nextGroupId = stationIdToGroupId.get(destStationId);
            etaTicks = (int) Math.min(Integer.MAX_VALUE, computeEtaTicks(train));
        }

        // 上限つきで未来の停車駅 schedule を抽出
        List<UUID> upcomingGroups = new ArrayList<>();
        List<String> upcomingNames = new ArrayList<>();
        List<UUID> upcomingStationIds = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        if (train.runtime != null && train.runtime.getSchedule() != null) {
            var schedule = train.runtime.getSchedule();
            int n = schedule.entries.size();
            if (n > 0) {
                int start = Math.max(0, train.runtime.currentEntry);
                // cyclic schedule なら 1 周分まで; 非 cyclic なら end まで
                int max = schedule.cyclic ? n : (n - start);
                for (int i = 0; i < max && upcomingGroups.size() < 16; i++) {
                    int idx = (start + i) % n;
                    ScheduleEntry entry = schedule.entries.get(idx);
                    if (!(entry.instruction instanceof DestinationInstruction)) continue;
                    String filter = entry.instruction.getData().contains("Text")
                            ? entry.instruction.getData().getString("Text") : null;
                    if (filter == null || filter.isEmpty()) continue;
                    // wildcard regex で station 名にマッチ → 最初のヒットを採用
                    UUID stationId = resolveStationByFilter(filter, stationNameToId);
                    if (stationId == null) continue;
                    UUID groupId = stationIdToGroupId.get(stationId);
                    if (groupId == null) continue;
                    if (seen.add(groupId)) {
                        upcomingGroups.add(groupId);
                        upcomingNames.add(filter);
                        upcomingStationIds.add(stationId);
                    }
                }
            }
        }

        return new Snapshot(
                train.id,
                train.name == null ? train.id.toString().substring(0, 8) : train.name.getString(),
                currentGroupId, nextGroupId, etaTicks,
                Collections.unmodifiableList(upcomingGroups),
                Collections.unmodifiableList(upcomingNames),
                Collections.unmodifiableList(upcomingStationIds));
    }

    /**
     * フィルタ文字列 (Create のワイルドカード *) を station 名にマッチさせて UUID を返す。
     * 完全一致が優先、なければ regex マッチで最初のヒット。
     */
    private static UUID resolveStationByFilter(String filter, Map<String, UUID> stationNameToId) {
        // 完全一致 fast path
        UUID exact = stationNameToId.get(filter);
        if (exact != null) return exact;
        // Create の wildcard 規約: * は任意の文字列。filter→regex 変換
        String regex = filterToRegex(filter);
        try {
            Pattern p = Pattern.compile(regex);
            for (Map.Entry<String, UUID> e : stationNameToId.entrySet()) {
                if (p.matcher(e.getKey()).matches()) return e.getValue();
            }
        } catch (PatternSyntaxException ignored) { TrainSystemUtilities.LOGGER.debug("[ScheduleCache] filter regex compile failed", ignored); }
        return null;
    }

    private static String filterToRegex(String filter) {
        StringBuilder sb = new StringBuilder();
        for (char c : filter.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if ("\\.[](){}+?^$|".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 列車の次駅到着までの ticks を 3 段キネマティクスモデルで推定。
     *
     * <p>モデル:
     * <ol>
     *   <li>加速期: v0 → vmax まで a で加速 (距離 dA = (vmax² - v0²) / 2a)</li>
     *   <li>巡航期: vmax で残距離 dC = D - dA - dB を走行</li>
     *   <li>減速期: vmax → 0 まで b で減速 (距離 dB = vmax² / 2b)</li>
     * </ol>
     * 距離が短くて巡航期が成立しない場合は三角形プロファイル (加速→即減速)。
     *
     * <p>信号待ち補正: navigation.ticksWaitingForSignal の半分を加算。
     */
    private static long computeEtaTicks(Train train) {
        if (train.navigation == null || train.navigation.destination == null) return 0;
        double dist = train.navigation.distanceToDestination;
        if (dist <= 0) return 0;

        double v0 = Math.abs(train.speed);
        double vmax = DEFAULT_CRUISE_SPEED;
        double a = ACCEL;
        double b = DECEL;

        // 加速距離 dA: v0 → vmax
        double dA = Math.max(0, (vmax * vmax - v0 * v0) / (2 * a));
        // 減速距離 dB: vmax → 0
        double dB = vmax * vmax / (2 * b);

        double ticks;
        if (dA + dB <= dist) {
            // 通常 3 段プロファイル
            double dC = dist - dA - dB;
            double tA = (vmax - v0) / a;
            double tC = dC / vmax;
            double tB = vmax / b;
            ticks = tA + tC + tB;
        } else {
            // 三角形プロファイル: 距離不足で巡航期なし。
            // ピーク速度 vp で加速→減速。
            // dist = (vp² - v0²) / 2a + vp² / 2b
            // → vp² = (2 * dist * a * b + b * v0²) / (a + b)
            double vp2 = (2 * dist * a * b + b * v0 * v0) / (a + b);
            double vp = Math.sqrt(Math.max(0, vp2));
            if (vp <= v0) {
                // すでに減速期に入っている前提
                ticks = dist / Math.max(v0, MOVING_SPEED_THRESHOLD);
            } else {
                double tA = (vp - v0) / a;
                double tB = vp / b;
                ticks = tA + tB;
            }
        }

        // 信号待ち補正
        if (train.navigation.waitingForSignal != null && train.navigation.ticksWaitingForSignal > 0) {
            ticks += train.navigation.ticksWaitingForSignal / 2.0;
        }
        return Math.max(0, (long) Math.ceil(ticks));
    }

    /** 列車の標準加減速度 (blocks/tick²)。Create のデフォルトに近い値。 */
    private static final double ACCEL = 0.025;
    private static final double DECEL = 0.05;
}
