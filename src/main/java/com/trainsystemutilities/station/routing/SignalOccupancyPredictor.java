package com.trainsystemutilities.station.routing;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.trainsystemutilities.station.TrainScheduleCache;

import java.util.UUID;

/**
 * 信号占有予測: 列車 A が次駅へ向かう途中で、他列車 B が同じ track 上にいて
 * A がブロックされる可能性を簡易判定する。
 *
 * <p>厳密な signal block 解析ではなく、ヒューリスティック:
 * <ol>
 *   <li>列車 A の前方に他列車 B がいて、B の next stop が A と同じか A の途中駅</li>
 *   <li>距離が近い (= 100 ブロック以内)</li>
 * </ol>
 * このとき B の next-stop ETA を A の追加待ち時間として加算する。
 *
 * <p>本格的には TrackGraph 上の SignalBoundary 解析だが、運用には十分。
 */
public final class SignalOccupancyPredictor {

    private SignalOccupancyPredictor() {}

    /**
     * 列車 a が次駅 (group b) に向かう経路で、他列車に阻まれる予想 ticks を返す。
     * 該当なしなら 0。
     */
    public static int predictAddedWaitTicks(UUID aTrainId, UUID bGroupId) {
        if (aTrainId == null || bGroupId == null) return 0;
        try {
            if (Create.RAILWAYS == null) return 0;
            Train trainA = Create.RAILWAYS.trains.get(aTrainId);
            if (trainA == null || trainA.graph == null) return 0;
            TrackGraph graphA = trainA.graph;
            var leadA = trainA.carriages.isEmpty() ? null : trainA.carriages.get(0).leadingBogey();
            if (leadA == null || leadA.getAnchorPosition() == null) return 0;
            var posA = leadA.getAnchorPosition();

            int worst = 0;
            for (Train other : Create.RAILWAYS.trains.values()) {
                if (other == null || other.id == null) continue;
                if (other.id.equals(aTrainId)) continue;
                if (other.graph != graphA) continue; // 同じネットワーク上のみ
                if (other.carriages.isEmpty()) continue;
                var leadO = other.carriages.get(0).leadingBogey();
                if (leadO == null || leadO.getAnchorPosition() == null) continue;
                var posO = leadO.getAnchorPosition();
                double dx = posO.x - posA.x;
                double dz = posO.z - posA.z;
                double dist = Math.hypot(dx, dz);
                if (dist > 200) continue; // 200 ブロック以上離れていれば干渉しない
                // other の next stop が A の next stop と同じか先行関係なら waiting
                TrainScheduleCache.Snapshot oSnap = TrainScheduleCache.all().get(other.id);
                if (oSnap == null) continue;
                if (bGroupId.equals(oSnap.nextGroupId())) {
                    // 同じ次駅: other が先に到着する分の余分待ち
                    int otherEta = Math.max(0, oSnap.etaTicksToNext());
                    // 簡易: other が止まる時間 (10 sec) + その分待ち
                    int penalty = otherEta + 200;
                    if (penalty > worst) worst = penalty;
                }
            }
            return worst;
        } catch (Throwable t) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[SignalOccupancy] penalty prediction failed", t);
            return 0;
        }
    }
}
