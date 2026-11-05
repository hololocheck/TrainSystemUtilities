package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.station.TrainScheduleCache;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * 全 server player に列車のリアルタイム位置を 2Hz でブロードキャストする。
 *
 * <p>呼び出し元は {@code StationGroupServerEvents.onServerTick} (1 Hz tick)。
 * 内部で半分の頻度に間引きする (= 結果的に 1 Hz)。実装の単純さを優先し 2 Hz 化は
 * 将来 phase で。
 *
 * <p>配る先はサーバー全体の host ({@link TrainPositionsData}、 MANTA_7_CONCEPT C4 で TrainPositionPayload から移行)。
 * 全 client がログイン時に購読するので、 届く範囲は旧 broadcast と同じ。
 */
public final class TrainPositionBroadcaster {

    private TrainPositionBroadcaster() {}

    public static void broadcast(MinecraftServer server) {
        if (server == null) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;

        List<TrainPositionsData.TrainPosition> positions = new ArrayList<>();
        try {
            if (Create.RAILWAYS == null) return;
            // P0-1 #9: snapshot iteration で 2 Hz broadcast 中の Create CME を排除
            for (Train train : com.trainsystemutilities.compat.CreateBridge.snapshotTrains()) {
                if (train == null || train.id == null) continue;
                if (train.carriages.isEmpty()) continue;
                var lead = train.carriages.get(0).leadingBogey();
                if (lead == null || lead.getAnchorPosition() == null) continue;
                var p = lead.getAnchorPosition();
                int eta = 0;
                TrainScheduleCache.Snapshot snap = TrainScheduleCache.all().get(train.id);
                if (snap != null) eta = snap.etaTicksToNext();
                positions.add(new TrainPositionsData.TrainPosition(
                        train.id, (float) p.x, (float) p.z,
                        (float) train.speed, eta));
                if (positions.size() >= TrainPositionsData.ROWS) break; // 1 メッセージに収まる上限
            }
        } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrainPos] position broadcast scan failed", ignored); }

        if (positions.isEmpty()) return;
        TrainPositionsData.publish(positions, server.overworld().getDayTime());
    }
}
