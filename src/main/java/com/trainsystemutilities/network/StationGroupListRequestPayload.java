package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Client → Server: 駅グループ一覧を要求。Server は ListResponse を返送。 */
public record StationGroupListRequestPayload() implements CustomPacketPayload {

    public static final Type<StationGroupListRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "station_group_list_req"));

    public static final StreamCodec<FriendlyByteBuf, StationGroupListRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new StationGroupListRequestPayload());

    public static void handle(StationGroupListRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            List<StationGroup> all = new ArrayList<>(StationGroupSavedData.get(player.server).all());
            PacketDistributor.sendToPlayer(player, new StationGroupListResponsePayload(all));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
