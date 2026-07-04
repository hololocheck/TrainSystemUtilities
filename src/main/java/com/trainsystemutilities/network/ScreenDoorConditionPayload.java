package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.screendoor.ScreenDoorCondition;
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
 * Client → Server: ホームドア発火条件の add / remove / update。
 *
 * <p>op:
 * <ul>
 *   <li>{@link #OP_ADD} — デフォルト entry を末尾に追加 (= idx / fields は無視)</li>
 *   <li>{@link #OP_REMOVE} — idx の entry を削除</li>
 *   <li>{@link #OP_UPDATE} — idx の entry を fields で上書き</li>
 * </ul>
 */
public record ScreenDoorConditionPayload(
        BlockPos pos, int op, int idx,
        int trackNumber, int eventType, int actionType) implements CustomPacketPayload {

    public static final int OP_ADD = 0;
    public static final int OP_REMOVE = 1;
    public static final int OP_UPDATE = 2;

    public static final Type<ScreenDoorConditionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "screen_door_condition"));

    public static final StreamCodec<FriendlyByteBuf, ScreenDoorConditionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeLong(p.pos.asLong());
                        buf.writeVarInt(p.op);
                        buf.writeVarInt(p.idx);
                        buf.writeVarInt(p.trackNumber);
                        buf.writeVarInt(p.eventType);
                        buf.writeVarInt(p.actionType);
                    },
                    buf -> new ScreenDoorConditionPayload(
                            BlockPos.of(buf.readLong()),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()));

    public static void handle(ScreenDoorConditionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (!PermissionHelper.isWithinReach(sp, payload.pos)) return;
            BlockEntity be = sp.level().getBlockEntity(payload.pos);
            if (!(be instanceof RailwayManagementBlockEntity rmbe)) return;
            if (!rmbe.canAccess(sp)) return;
            switch (payload.op) {
                case OP_ADD -> rmbe.addScreenDoorCondition(ScreenDoorCondition.defaultEntry());
                case OP_REMOVE -> rmbe.removeScreenDoorCondition(payload.idx);
                case OP_UPDATE -> rmbe.updateScreenDoorCondition(payload.idx,
                        new ScreenDoorCondition(payload.trackNumber, payload.eventType, payload.actionType));
                default -> {}
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
