package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.LineSymbol;
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
 * Client → Server: 管理コンピュータの路線記号 (LineSymbol) の作成 / 編集 / 削除 / 駅割当。
 *
 * <p>MP desync 修正: 以前は {@code ManagementComputerScreenV2} が dedicated server で
 * client 側 BE を直接 mutate していた (SP 分岐の else)。 server BE に届かず、次の
 * {@code sendBlockUpdated} 同期で client の変更が上書きされて「記号を作っても消える」状態だった。
 * 本 payload で server 権威化し、{@link ManagementComputerBlockEntity} の既存 mutator
 * ({@code saveLineSymbol} / {@code removeLineSymbol} / {@code assignSymbolToStation}) を呼ぶ。
 * これら mutator は {@code onLineSymbolsChanged()} 等で {@code setChanged} + {@code sendBlockUpdated}
 * するため server → client 同期は自動。 ({@link ManagementComputerControlPayload} と同じ B2 方式)
 *
 * <p>handler は ServerPlayer + {@link PermissionHelper#isWithinReach proximity} + {@code canAccess}
 * を検証する (= GUI を開かず任意座標へ packet を投げる remote spoof / 非所有者の改竄を防ぐ)。
 */
public record ManagementSymbolPayload(
        BlockPos computerPos, int action, int index,
        String letters, int number, String borderColor, String name, int borderRadius,
        String stationName, BlockPos stationPos, UUID symbolId)
        implements CustomPacketPayload {

    public static final int ACTION_SAVE = 0;    // index < 0 = 新規追加, index >= 0 = 既存編集
    public static final int ACTION_DELETE = 1;  // index の記号を削除
    public static final int ACTION_ASSIGN = 2;  // 駅 ↔ 記号 割当 (symbolId == 0,0 で解除)

    public static final Type<ManagementSymbolPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "management_symbol"));

    public static final StreamCodec<FriendlyByteBuf, ManagementSymbolPayload> STREAM_CODEC =
            StreamCodec.of(ManagementSymbolPayload::write, ManagementSymbolPayload::read);

    private static void write(FriendlyByteBuf buf, ManagementSymbolPayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeVarInt(p.action);
        buf.writeInt(p.index);
        buf.writeUtf(p.letters == null ? "" : p.letters, 8);
        buf.writeVarInt(Math.max(0, p.number));
        buf.writeUtf(p.borderColor == null ? "" : p.borderColor, 16);
        buf.writeUtf(p.name == null ? "" : p.name, 64);
        buf.writeVarInt(Math.max(0, p.borderRadius));
        buf.writeUtf(p.stationName == null ? "" : p.stationName, 256);
        buf.writeLong(p.stationPos == null ? 0 : p.stationPos.asLong());
        buf.writeUUID(p.symbolId == null ? new UUID(0, 0) : p.symbolId);
    }

    private static ManagementSymbolPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int action = BoundedStreamCodec.readBoundedVarInt(buf, 2);   // 0..2
        int index = buf.readInt();
        String letters = buf.readUtf(8);
        int number = buf.readVarInt();
        String borderColor = buf.readUtf(16);
        String name = buf.readUtf(64);
        int borderRadius = buf.readVarInt();
        String stationName = buf.readUtf(256);
        BlockPos stationPos = BlockPos.of(buf.readLong());
        UUID symbolId = buf.readUUID();
        return new ManagementSymbolPayload(pos, action, index, letters, number,
                borderColor, name, borderRadius, stationName, stationPos, symbolId);
    }

    public static void handle(ManagementSymbolPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            // proximity gate: 操作者は management computer に手が届く距離にいること
            if (!PermissionHelper.isWithinReach(sp, p.computerPos)) {
                TrainSystemUtilities.LOGGER.debug(
                        "ManagementSymbol: {} out of reach of computer {}",
                        sp.getName().getString(), p.computerPos);
                return;
            }
            BlockEntity be = sp.serverLevel().getBlockEntity(p.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;

            switch (p.action) {
                // LineSymbol ctor が letters 大文字化 / number 0-99 / borderRadius 5-25 を clamp する。
                case ACTION_SAVE -> mc.saveLineSymbol(p.index,
                        new LineSymbol(p.letters, p.number, p.borderColor, p.name, p.borderRadius));
                case ACTION_DELETE -> mc.removeLineSymbol(p.index);
                case ACTION_ASSIGN -> mc.assignSymbolToStation(
                        p.stationName, p.stationPos,
                        p.symbolId.equals(new UUID(0, 0)) ? null : p.symbolId);
                default -> { /* action は read() で 0..2 に bound 済 */ }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
