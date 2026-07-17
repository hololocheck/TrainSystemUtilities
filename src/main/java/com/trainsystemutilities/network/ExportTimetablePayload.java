package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
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
 * Client → Server: 電子式時刻表を物理「スケジュール」アイテムへ書き出し開始。
 * 入力スロットに空スケジュールが入った瞬間に client が送る。 server 側で検証して進捗を開始する。
 */
public record ExportTimetablePayload(BlockPos computerPos, UUID trainId) implements CustomPacketPayload {

    public static final Type<ExportTimetablePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "export_timetable"));

    public static final StreamCodec<FriendlyByteBuf, ExportTimetablePayload> STREAM_CODEC =
            StreamCodec.of(ExportTimetablePayload::write, ExportTimetablePayload::read);

    private static void write(FriendlyByteBuf buf, ExportTimetablePayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeUUID(p.trainId == null ? new UUID(0, 0) : p.trainId);
    }

    private static ExportTimetablePayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        UUID trainId = buf.readUUID();
        return new ExportTimetablePayload(pos, trainId);
    }

    public static void handle(ExportTimetablePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (!PermissionHelper.isWithinReach(sp, payload.computerPos)) return;
            BlockEntity be = sp.serverLevel().getBlockEntity(payload.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;
            // SECURITY (TSU-NET-002): 対象列車がこの computer の linked network に属すことを検証。
            if (!mc.containsLinkedTrain(payload.trainId)) return;
            mc.startExport(payload.trainId);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
