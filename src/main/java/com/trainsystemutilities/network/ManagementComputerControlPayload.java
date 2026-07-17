package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
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
 * Client → Server: 管理コンピュータからの列車運行 停止 / 再開 制御。
 *
 * <p>B2 (MP desync 修正): 以前は {@code ManagementComputerScreenV2} が client 側で
 * {@code train.runtime.paused} を直接 mutate していたため SP でしか動作せず MP では desync した。
 * 本 payload で server 権威に統一し、{@link ManagementComputerBlockEntity} の既存 server ロジック
 * ({@code startAllTrainsStop} / {@code resumeAllTrains} / {@code emergencyStop} / {@code resumeTrain})
 * を呼ぶ。client 側の obsolete な複製 state machine は削除した。
 *
 * <p>action:
 * <ul>
 *   <li>{@link #ACTION_STOP_ALL} — 全列車を順次停止 (server の 1 台ずつ方式)</li>
 *   <li>{@link #ACTION_RESUME_ALL} — 全列車を再開</li>
 *   <li>{@link #ACTION_TOGGLE_ONE} — {@code trainId} の列車の停止 / 再開をトグル</li>
 * </ul>
 *
 * <p>handler は ServerPlayer + {@link PermissionHelper#isWithinReach proximity} を検証する
 * (= GUI を開かず任意座標へ packet を投げる remote spoof を防ぐ)。
 */
public record ManagementComputerControlPayload(BlockPos computerPos, int action, UUID trainId)
        implements CustomPacketPayload {

    public static final int ACTION_STOP_ALL = 0;
    public static final int ACTION_RESUME_ALL = 1;
    public static final int ACTION_TOGGLE_ONE = 2;

    public static final Type<ManagementComputerControlPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "management_computer_control"));

    public static final StreamCodec<FriendlyByteBuf, ManagementComputerControlPayload> STREAM_CODEC =
            StreamCodec.of(ManagementComputerControlPayload::write, ManagementComputerControlPayload::read);

    private static void write(FriendlyByteBuf buf, ManagementComputerControlPayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeVarInt(p.action);
        buf.writeUUID(p.trainId == null ? new UUID(0, 0) : p.trainId);
    }

    private static ManagementComputerControlPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        // action は STOP_ALL / RESUME_ALL / TOGGLE_ONE の 0..2 のみ
        int action = BoundedStreamCodec.readBoundedVarInt(buf, 2);
        UUID trainId = buf.readUUID();
        return new ManagementComputerControlPayload(pos, action, trainId);
    }

    public static void handle(ManagementComputerControlPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            // proximity gate: 操作者は management computer に手が届く距離にいること
            if (!PermissionHelper.isWithinReach(sp, payload.computerPos)) {
                TrainSystemUtilities.LOGGER.debug(
                        "ManagementComputerControl: {} out of reach of computer {}",
                        sp.getName().getString(), payload.computerPos);
                return;
            }
            BlockEntity be = sp.serverLevel().getBlockEntity(payload.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;

            switch (payload.action) {
                case ACTION_STOP_ALL -> mc.startAllTrainsStop();
                case ACTION_RESUME_ALL -> mc.resumeAllTrains();
                case ACTION_TOGGLE_ONE -> {
                    // SECURITY (TSU-NET-002): 対象列車がこの computer の linked network に属すことを検証。
                    if (!mc.containsLinkedTrain(payload.trainId)) {
                        TrainSystemUtilities.LOGGER.debug(
                                "ManagementComputerControl: train {} not in linked network of computer {} (rejected)",
                                payload.trainId, payload.computerPos);
                        return;
                    }
                    Train train = Create.RAILWAYS.trains.get(payload.trainId);
                    if (train != null && train.runtime != null) {
                        if (train.runtime.paused) {
                            mc.resumeTrain(payload.trainId);
                        } else {
                            mc.emergencyStop(payload.trainId);
                        }
                    }
                }
                default -> { /* action は read() で 0..2 に bound 済 */ }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
