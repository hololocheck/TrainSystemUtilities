package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.schedule.TrainTypes;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → Server: 列車種別 (普通 / 快速 / 特急 …) の設定。
 *
 * <p>種別は 2026-07-18 まで停車駅比率からの自動判定だったが、 分母が路線ではなく連結グラフ全体
 * だったため実質すべて特急になっていた。 判定は廃止し、 管理用コンピューターの電子式時刻表タブで
 * 列車ごとに手動設定する。 source of truth は server-global な
 * {@link com.trainsystemutilities.schedule.TrainTypeState}。
 *
 * <p>種別は表示ラベルであってスケジュール変更ではないため、 運転士 / 時刻表のゲートは課さない。
 * proximity + canAccess + linked network の 3 点のみ検証する。
 */
public record SetTrainTypePayload(BlockPos computerPos, UUID trainId, int typeIndex)
        implements CustomPacketPayload {

    public static final Type<SetTrainTypePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "set_train_type"));

    public static final StreamCodec<FriendlyByteBuf, SetTrainTypePayload> STREAM_CODEC =
            StreamCodec.of(SetTrainTypePayload::write, SetTrainTypePayload::read);

    private static void write(FriendlyByteBuf buf, SetTrainTypePayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeUUID(p.trainId == null ? new UUID(0, 0) : p.trainId);
        buf.writeVarInt(p.typeIndex);
    }

    private static SetTrainTypePayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        UUID trainId = buf.readUUID();
        int idx = buf.readVarInt();
        // R3.6.1: 範囲は decode 時に検証する。 範囲外は未設定 (0) に落とす。
        if (idx < 0 || idx >= TrainTypes.count()) idx = 0;
        return new SetTrainTypePayload(pos, trainId, idx);
    }

    public static void handle(SetTrainTypePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (!PermissionHelper.isWithinReach(sp, payload.computerPos)) return;
            BlockEntity be = sp.serverLevel().getBlockEntity(payload.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;
            // SECURITY (TSU-NET-002 と同じ形): 対象列車がこの computer の linked network に属すこと
            if (!mc.containsLinkedTrain(payload.trainId)) return;

            mc.setTrainType(payload.trainId, TrainTypes.byIndex(payload.typeIndex));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
