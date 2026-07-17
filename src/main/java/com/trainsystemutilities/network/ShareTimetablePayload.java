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
 * Client → Server: source 列車の電子式時刻表を target 列車へ共有 ON/OFF (反転)。
 * 共有 popup でトグルを押した瞬間に client が送る。 server 側で検証して反転する。
 */
public record ShareTimetablePayload(BlockPos computerPos, UUID sourceTrainId, UUID targetTrainId)
        implements CustomPacketPayload {

    public static final Type<ShareTimetablePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "share_timetable"));

    public static final StreamCodec<FriendlyByteBuf, ShareTimetablePayload> STREAM_CODEC =
            StreamCodec.of(ShareTimetablePayload::write, ShareTimetablePayload::read);

    private static void write(FriendlyByteBuf buf, ShareTimetablePayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeUUID(p.sourceTrainId == null ? new UUID(0, 0) : p.sourceTrainId);
        buf.writeUUID(p.targetTrainId == null ? new UUID(0, 0) : p.targetTrainId);
    }

    private static ShareTimetablePayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        UUID source = buf.readUUID();
        UUID target = buf.readUUID();
        return new ShareTimetablePayload(pos, source, target);
    }

    public static void handle(ShareTimetablePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (!PermissionHelper.isWithinReach(sp, payload.computerPos)) return;
            BlockEntity be = sp.serverLevel().getBlockEntity(payload.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;
            // SECURITY (TSU-NET-002): source/target 双方がこの computer の linked network に属すことを検証。
            if (!mc.containsLinkedTrain(payload.sourceTrainId)
                    || !mc.containsLinkedTrain(payload.targetTrainId)) return;
            mc.toggleTimetableShare(payload.sourceTrainId, payload.targetTrainId);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
