package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
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
 * Client → Server: ホームドア手動開閉テスト (= 列車イベント抜きで即時発火)。
 */
public record ScreenDoorTestActionPayload(BlockPos pos, int actionType) implements CustomPacketPayload {

    public static final Type<ScreenDoorTestActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "screen_door_test_action"));

    public static final StreamCodec<FriendlyByteBuf, ScreenDoorTestActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeLong(p.pos.asLong()); buf.writeVarInt(p.actionType); },
                    buf -> new ScreenDoorTestActionPayload(BlockPos.of(buf.readLong()), buf.readVarInt()));

    public static void handle(ScreenDoorTestActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (!PermissionHelper.isWithinReach(sp, payload.pos)) return;
            BlockEntity be = sp.level().getBlockEntity(payload.pos);
            if (be instanceof RailwayManagementBlockEntity rmbe && rmbe.canAccess(sp)) {
                ScreenDoorConditionEvaluator.testApply(rmbe, payload.actionType);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
