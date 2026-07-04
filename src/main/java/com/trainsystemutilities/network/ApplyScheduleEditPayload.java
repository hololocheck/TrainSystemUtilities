package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → Server: 電子式時刻表の編集適用。
 *
 * <p>旧実装は {@code ManagementComputerScreenV2} が {@code serverBE()} 経由で直接 apply していたため
 * SP 専用 (MP では server BE に届かない) だった。本 payload で server 権威に統一し、ゲート
 * (運転士あり + 通常時刻表でない = 電子式 / なし) を server 側で検証してから適用する。
 */
public record ApplyScheduleEditPayload(BlockPos computerPos, UUID trainId, CompoundTag scheduleNbt)
        implements CustomPacketPayload {

    public static final Type<ApplyScheduleEditPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "apply_schedule_edit"));

    public static final StreamCodec<FriendlyByteBuf, ApplyScheduleEditPayload> STREAM_CODEC =
            StreamCodec.of(ApplyScheduleEditPayload::write, ApplyScheduleEditPayload::read);

    private static void write(FriendlyByteBuf buf, ApplyScheduleEditPayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeUUID(p.trainId == null ? new UUID(0, 0) : p.trainId);
        buf.writeNbt(p.scheduleNbt == null ? new CompoundTag() : p.scheduleNbt);
    }

    private static ApplyScheduleEditPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        UUID trainId = buf.readUUID();
        CompoundTag nbt = buf.readNbt();
        if (nbt == null) nbt = new CompoundTag();
        return new ApplyScheduleEditPayload(pos, trainId, nbt);
    }

    public static void handle(ApplyScheduleEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            // proximity gate: 操作者は management computer に手が届く距離にいること
            if (!PermissionHelper.isWithinReach(sp, payload.computerPos)) return;
            BlockEntity be = sp.serverLevel().getBlockEntity(payload.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;

            Train train = Create.RAILWAYS.trains.get(payload.trainId);
            if (train == null || train.runtime == null) return;
            // server 権威ゲート: 通常時刻表 (= schedule あり かつ 非電子式) は編集不可
            boolean hasSchedule = train.runtime.getSchedule() != null
                    && !train.runtime.getSchedule().entries.isEmpty();
            boolean electronic = mc.isElectronicTimetable(payload.trainId);
            if (hasSchedule && !electronic) return;
            // 運転士 (モブ / ブレイズバーナー) 必須
            if (!ManagementComputerBlockEntity.hasConductor(train)) return;

            mc.setPendingSchedule(payload.trainId, payload.scheduleNbt);
            mc.applyPendingSchedule();
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
