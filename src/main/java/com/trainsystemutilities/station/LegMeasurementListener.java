package com.trainsystemutilities.station;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 列車の駅間遷移を監視し、{@link SegmentStatsStore} に走行時間 + 停車時間 (dwell) サンプルを蓄積する。
 *
 * <p>Phase C 拡張:
 * <ol>
 *   <li>スケジュールハッシュ計算: upcoming groups の order を hash 化、変更検知で stats reset</li>
 *   <li>Dwell time 学習: 列車が駅に停車している時間を記録</li>
 * </ol>
 */
public final class LegMeasurementListener {

    private static final class Track {
        UUID lastCurrentGroup;
        long lastCurrentEnterDayTime; // 駅に到着した時刻
        UUID departedFrom;
        long departedAtDayTime;
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private LegMeasurementListener() {}

    public static void update(MinecraftServer server) {
        if (server == null) return;
        long dayTime = server.overworld().getGameTime();
        SegmentStatsStore store = SegmentStatsStore.get(server);

        Map<UUID, TrainScheduleCache.Snapshot> snapshots = TrainScheduleCache.all();
        for (var e : snapshots.entrySet()) {
            UUID trainId = e.getKey();
            TrainScheduleCache.Snapshot snap = e.getValue();
            Track t = TRACKS.computeIfAbsent(trainId, k -> new Track());
            int schash = scheduleHash(snap.upcomingGroupIds());

            UUID curGroup = snap.currentGroupId();
            if (curGroup != null && !curGroup.equals(t.lastCurrentGroup)) {
                // 状態遷移: 前駅 → curGroup に到着
                if (t.departedFrom != null && !t.departedFrom.equals(curGroup)) {
                    long elapsed = dayTime - t.departedAtDayTime;
                    if (elapsed > 0 && elapsed < 24000L * 7) {
                        store.record(t.departedFrom, curGroup, (int) elapsed, dayTime, schash);
                        TrainSystemUtilities.LOGGER.debug(
                                "[LegMeasurement] travel train={} {}→{} ticks={}",
                                trainId, t.departedFrom, curGroup, elapsed);
                    }
                }
                t.lastCurrentGroup = curGroup;
                t.lastCurrentEnterDayTime = dayTime;
                t.departedFrom = null;
                t.departedAtDayTime = 0;
            } else if (curGroup == null && t.lastCurrentGroup != null && t.departedFrom == null) {
                // 状態遷移: 駅停車中 → 走行中 (出発)
                long dwell = dayTime - t.lastCurrentEnterDayTime;
                if (dwell > 0 && dwell < 24000L) {
                    UUID nextGroup = snap.nextGroupId();
                    if (nextGroup != null) {
                        store.recordDwell(t.lastCurrentGroup, nextGroup, (int) dwell, dayTime);
                        TrainSystemUtilities.LOGGER.debug(
                                "[LegMeasurement] dwell train={} at={} ticks={}",
                                trainId, t.lastCurrentGroup, dwell);
                    }
                }
                t.departedFrom = t.lastCurrentGroup;
                t.departedAtDayTime = dayTime;
            }
        }
        TRACKS.keySet().retainAll(snapshots.keySet());
    }

    /** スケジュールの順序を 32bit hash 化 (順序を保持)。 */
    private static int scheduleHash(List<UUID> stops) {
        if (stops == null || stops.isEmpty()) return 0;
        int h = 1;
        for (UUID id : stops) {
            if (id == null) continue;
            h = 31 * h + (int) (id.getMostSignificantBits() ^ id.getLeastSignificantBits());
        }
        return h;
    }
}
