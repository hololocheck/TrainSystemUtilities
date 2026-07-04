package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.screendoor.ScreenDoorCondition;
import com.trainsystemutilities.screendoor.ScreenDoorConditionEvaluator;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: RailwayManagementBlockEntity のホームドア帯色を更新。
 */
public record ScreenDoorBandColorPayload(BlockPos pos, int argb) implements CustomPacketPayload {

    public static final Type<ScreenDoorBandColorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "screen_door_band_color"));

    public static final StreamCodec<FriendlyByteBuf, ScreenDoorBandColorPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeLong(p.pos.asLong()); buf.writeInt(p.argb); },
                    buf -> new ScreenDoorBandColorPayload(BlockPos.of(buf.readLong()), buf.readInt()));

    public static void handle(ScreenDoorBandColorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (!PermissionHelper.isWithinReach(sp, payload.pos)) return;
            BlockEntity be = sp.level().getBlockEntity(payload.pos);
            if (be instanceof RailwayManagementBlockEntity rmbe && rmbe.canAccess(sp)) {
                rmbe.setScreenDoorBandColorARGB(payload.argb);
                ScreenDoorConditionEvaluator.testApply(rmbe, ScreenDoorCondition.ACTION_COLOR);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
