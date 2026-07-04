package com.trainsystemutilities.route;

import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 列車の実挙動 (速度符号の反転 = 物理的な折り返し) から路線種別を判定する server-side tracker。
 *
 * <p>スケジュール駅名パターンのみの {@link RouteClassifier} は「2 unique 駅 = SHUTTLE」等の
 * ヒューリスティックのため、 環状線を 2 駅停車で周回する列車も折り返しと誤判定していた。
 * スケジュールだけでは環状 (反転なし周回) と折り返し (末端で反転) を原理的に区別できないため、
 * 1 Hz で {@code train.speed} の符号を観測して判定する:
 * <ul>
 *   <li>符号反転を観測 → SHUTTLE。 反転直前に停車していた駅 = 折り返し駅として記録
 *       (= A-B-C-D 線なら A / D のみ。 途中駅 B / C のボードには出さない)</li>
 *   <li>反転なしで最初の停車駅に戻ってきた (= 1 周) → CIRCULAR</li>
 *   <li>どちらも未観測 (走り始め直後等) → "" (バッジ非表示)</li>
 * </ul>
 * schedule が変わったら学習をリセットする。 手動運転 (schedule 無し) は追跡しない。
 * 呼び出しは全て server thread (tick / BE tick / contraption tick) のため同期不要。
 */
public final class TrainRouteTracker {

    private TrainRouteTracker() {}

    private static final class State {
        int scheduleHash;
        int lastMovingSign = 0;
        String lastStopStation = null;
        boolean reversalObserved = false;
        final Set<String> turnbackStations = new HashSet<>();
        String firstStopStation = null;
        final Set<String> visitedSinceFirst = new HashSet<>();
        boolean circularConfirmed = false;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    /** server tick (1 Hz) から呼ぶ。 全列車の速度符号と停車駅を観測する。 */
    public static void sample(MinecraftServer server) {
        if (server == null) return;
        Set<UUID> alive = new HashSet<>();
        try {
            for (Train train : com.trainsystemutilities.compat.CreateBridge.snapshotTrains()) {
                if (train == null || train.id == null) continue;
                if (train.runtime == null || train.runtime.getSchedule() == null) continue; // 手動運転は対象外
                alive.add(train.id);
                int schedHash = scheduleHash(train);
                State st = STATES.computeIfAbsent(train.id, k -> new State());
                if (st.scheduleHash == 0) st.scheduleHash = schedHash;
                if (st.scheduleHash != schedHash) {
                    State fresh = new State();
                    fresh.scheduleHash = schedHash;
                    STATES.put(train.id, fresh);
                    st = fresh;
                }

                var cur = train.getCurrentStation();
                if (cur != null && cur.name != null && !cur.name.isEmpty()) {
                    String s = cur.name;
                    st.lastStopStation = s;
                    if (st.firstStopStation == null) {
                        st.firstStopStation = s;
                    } else if (!s.equals(st.firstStopStation)) {
                        st.visitedSinceFirst.add(s);
                    } else if (!st.reversalObserved && !st.visitedSinceFirst.isEmpty()) {
                        // 反転なしで最初の駅へ戻ってきた = 1 周 → 環状確定
                        st.circularConfirmed = true;
                    }
                    continue; // 停車中は速度符号を見ない (approach 揺らぎ回避)
                }

                double sp = train.speed;
                if (Math.abs(sp) < 0.01) continue;
                int sign = sp > 0 ? 1 : -1;
                if (st.lastMovingSign != 0 && sign != st.lastMovingSign) {
                    // 物理的な折り返しを観測
                    st.reversalObserved = true;
                    st.circularConfirmed = false;
                    if (st.lastStopStation != null) st.turnbackStations.add(st.lastStopStation);
                }
                st.lastMovingSign = sign;
            }
        } catch (Throwable t) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[RouteTracker] sample failed", t);
        }
        STATES.keySet().retainAll(alive);
    }

    private static int scheduleHash(Train train) {
        int h = 1;
        try {
            for (var entry : train.runtime.getSchedule().entries) {
                var data = entry.instruction.getData();
                if (data.contains("Text")) h = 31 * h + data.getString("Text").hashCode();
            }
        } catch (Throwable ignored) {}
        return h == 0 ? 1 : h;
    }

    /** {@code "SHUTTLE"} (反転観測済) / {@code "CIRCULAR"} (反転なし 1 周確認) / {@code ""} (未確定)。 */
    public static String routeType(UUID trainId) {
        State st = trainId == null ? null : STATES.get(trainId);
        if (st == null) return "";
        if (st.reversalObserved) return "SHUTTLE";
        if (st.circularConfirmed) return "CIRCULAR";
        return "";
    }

    /** stationName がこの列車の実際の折り返し駅 (= 反転を観測した駅) か。 */
    public static boolean isTurnbackStation(UUID trainId, String stationName) {
        State st = trainId == null ? null : STATES.get(trainId);
        return st != null && stationName != null && st.turnbackStations.contains(stationName);
    }
}
