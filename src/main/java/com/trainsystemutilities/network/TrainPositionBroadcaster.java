package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.station.TrainScheduleCache;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全 server player に列車のリアルタイム位置を 2Hz でブロードキャストする。
 *
 * <p>呼び出し元は {@code StationGroupServerEvents.onServerTick} (1 Hz tick)。
 * 内部で半分の頻度に間引きする (= 結果的に 1 Hz)。実装の単純さを優先し 2 Hz 化は
 * 将来 phase で。
 */
public final class TrainPositionBroadcaster {

    private TrainPositionBroadcaster() {}

    public static void broadcast(MinecraftServer server) {
        if (server == null) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;

        List<TrainPositionPayload.Position> positions = new ArrayList<>();
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
                positions.add(new TrainPositionPayload.Position(
                        train.id, (float) p.x, (float) p.z,
                        (float) train.speed, eta));
                if (positions.size() >= 256) break; // packet サイズガード
            }
        } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrainPos] position broadcast scan failed", ignored); }

        if (positions.isEmpty()) return;
        long dayTime = server.overworld().getDayTime();
        TrainPositionPayload payload = new TrainPositionPayload(positions, dayTime);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
