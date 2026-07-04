package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.TrainScheduleCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client → Server: 全列車の運行スケジュール一覧を要求 (乗り換え案内端末の SCHEDULE タブ用)。
 *
 * <p>サーバ側は {@link TrainScheduleCache#all()} を {@link TransitSchedulePayload} で返す。
 * クライアントは Screen 表示中に定期的 (例: 2 秒ごと) または タブを開いた瞬間に送る想定。
 */
public record TransitScheduleRequestPayload() implements CustomPacketPayload {

    public static final Type<TransitScheduleRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "transit_schedule_req"));

    public static final StreamCodec<FriendlyByteBuf, TransitScheduleRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new TransitScheduleRequestPayload());

    public static void send() {
        PacketDistributor.sendToServer(new TransitScheduleRequestPayload());
    }

    public static void handle(TransitScheduleRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Map<UUID, TrainScheduleCache.Snapshot> all = TrainScheduleCache.all();
            // serialize に渡す lite な copy
            Map<UUID, TransitSchedulePayload.SnapshotLite> lite = new LinkedHashMap<>();
            for (var e : all.entrySet()) {
                var s = e.getValue();
                lite.put(e.getKey(), new TransitSchedulePayload.SnapshotLite(
                        s.trainId(), s.trainName(),
                        s.currentGroupId(), s.nextGroupId(),
                        s.etaTicksToNext(),
                        s.upcomingGroupIds(),
                        s.upcomingStationNames()
                ));
            }
            PacketDistributor.sendToPlayer(player, new TransitSchedulePayload(lite));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
