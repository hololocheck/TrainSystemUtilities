package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.station.TicketConfigSavedData;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → Server: 管理用コンピューター券売機タブで 1 駅の販売可フラグを更新。
 * Server は {@link TicketConfigSavedData} を更新する (= 全券売機が server 側で即参照)。
 */
public record TicketConfigUpdatePayload(BlockPos computerPos, UUID stationId, boolean sellable) implements CustomPacketPayload {

    public static final Type<TicketConfigUpdatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "ticket_config_update"));

    public static final StreamCodec<FriendlyByteBuf, TicketConfigUpdatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
                                  buf.writeUUID(p.stationId); buf.writeBoolean(p.sellable); },
                    buf -> new TicketConfigUpdatePayload(
                            BlockPos.of(buf.readLong()), buf.readUUID(), buf.readBoolean()));

    public static void handle(TicketConfigUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // SECURITY (TSU-04): management computer 近接 + private mode access を検証
            if (!PermissionHelper.isWithinReach(player, payload.computerPos)) return;
            if (!(player.serverLevel().getBlockEntity(payload.computerPos)
                    instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(player)) return;
            TicketConfigSavedData.get(player.server).setSellable(payload.stationId, payload.sellable);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
