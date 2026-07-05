package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → Server: 電子式時刻表の編集を開くために、対象列車の現在の schedule を要求する。
 *
 * <p>編集画面はこれまで client 側の {@code Train.runtime.getSchedule()} を読んでいたが、
 * Create は schedule を全 client に確実には同期しないため、運行停止直後などに null/空になり
 * 「編集ボタンで全エントリが空欄」バグの原因になっていた。schedule は server 権威なので、
 * server で読み取り {@link ScheduleEditDataPayload} で送り返し、client で editor を開く。
 */
public record RequestScheduleEditPayload(BlockPos computerPos, UUID trainId)
        implements CustomPacketPayload {

    public static final Type<RequestScheduleEditPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "request_schedule_edit"));

    public static final StreamCodec<FriendlyByteBuf, RequestScheduleEditPayload> STREAM_CODEC =
            StreamCodec.of(RequestScheduleEditPayload::write, RequestScheduleEditPayload::read);

    private static void write(FriendlyByteBuf buf, RequestScheduleEditPayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeUUID(p.trainId == null ? new UUID(0, 0) : p.trainId);
    }

    private static RequestScheduleEditPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        UUID trainId = buf.readUUID();
        return new RequestScheduleEditPayload(pos, trainId);
    }

    public static void handle(RequestScheduleEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            // proximity gate: 操作者は management computer に手が届く距離にいること
            if (!PermissionHelper.isWithinReach(sp, payload.computerPos)) return;
            BlockEntity be = sp.serverLevel().getBlockEntity(payload.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;

            CompoundTag out = new CompoundTag();
            boolean hasData = false;
            Train train = Create.RAILWAYS.trains.get(payload.trainId);
            if (train != null && train.runtime != null) {
                Schedule sched = train.runtime.getSchedule();
                if (sched != null) {
                    out = sched.write(sp.serverLevel().registryAccess());
                    hasData = !sched.entries.isEmpty();
                }
            }
            PacketDistributor.sendToPlayer(sp,
                    new ScheduleEditDataPayload(payload.trainId, hasData, out));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
