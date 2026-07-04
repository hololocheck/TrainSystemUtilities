package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.MonitorLayoutPanel;
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

/**
 * Client → Server: 管理コンピュータのモニター設定 (レイアウト / 色 / 有効化)。
 *
 * <p>MP desync 修正 ({@link ManagementSymbolPayload} と同型の sister): 以前は
 * {@code ManagementComputerScreenV2} が {@code serverBE()} を直接 mutate していた。
 * SP は integrated server の BE に届くが、 dedicated server では client BE にしか書けず、
 * 次の server NBT 同期で空に上書きされて「レイアウトを保存しても次に開くと 0 パネル」
 * になっていた (モニター描画は MonitorClientCache 優先参照のため見かけ上残る)。
 * 本 payload で server 権威化する。
 *
 * <p>handler は ServerPlayer + {@link PermissionHelper#isWithinReach proximity} +
 * {@code canAccess} を検証する (= remote spoof / 非所有者の改竄を防ぐ)。
 */
public record MonitorLayoutPayload(BlockPos computerPos, int action, CompoundTag data)
        implements CustomPacketPayload {

    public static final int ACTION_SAVE_LAYOUT = 0; // data.L = MonitorLayoutPanel ListTag
    public static final int ACTION_SET_COLOR = 1;   // data.K / data.V (V 空文字 = リセット)
    public static final int ACTION_SET_ENABLED = 2; // data.E = boolean

    /** レイアウトパネル数の上限 (エディタ実用上限。 packet 肥大防止)。 */
    public static final int MAX_PANELS = 32;

    public static final Type<MonitorLayoutPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "monitor_layout"));

    public static final StreamCodec<FriendlyByteBuf, MonitorLayoutPayload> STREAM_CODEC =
            StreamCodec.of(MonitorLayoutPayload::write, MonitorLayoutPayload::read);

    private static void write(FriendlyByteBuf buf, MonitorLayoutPayload p) {
        buf.writeLong(p.computerPos == null ? 0 : p.computerPos.asLong());
        buf.writeVarInt(p.action);
        buf.writeNbt(p.data == null ? new CompoundTag() : p.data);
    }

    private static MonitorLayoutPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int action = BoundedStreamCodec.readBoundedVarInt(buf, 2);   // 0..2
        CompoundTag data = (CompoundTag) buf.readNbt();
        return new MonitorLayoutPayload(pos, action, data == null ? new CompoundTag() : data);
    }

    public static void handle(MonitorLayoutPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            // proximity gate: 操作者は management computer に手が届く距離にいること
            if (!PermissionHelper.isWithinReach(sp, p.computerPos)) return;
            BlockEntity be = sp.serverLevel().getBlockEntity(p.computerPos);
            if (!(be instanceof ManagementComputerBlockEntity mc)) return;
            if (!mc.canAccess(sp)) return;

            switch (p.action) {
                case ACTION_SAVE_LAYOUT -> {
                    var list = MonitorLayoutPanel.loadList(
                            p.data.getList("L", net.minecraft.nbt.Tag.TAG_COMPOUND));
                    if (list.size() > MAX_PANELS) return;
                    // NBT 直 load は無検証のため、 setter 経由で server 側でも clamp する
                    for (var panel : list) {
                        panel.setWidth(panel.getWidth());
                        panel.setHeight(panel.getHeight());
                        panel.setX(panel.getX());
                        panel.setY(panel.getY());
                        panel.setFontSize(panel.getFontSize());
                        panel.setTrainIconSize(panel.getTrainIconSize());
                        panel.setStationIconSize(panel.getStationIconSize());
                        panel.setSignalIconSize(panel.getSignalIconSize());
                        panel.setMapTextSize(panel.getMapTextSize());
                    }
                    mc.getMonitorLayout().clear();
                    mc.getMonitorLayout().addAll(list);
                }
                case ACTION_SET_COLOR -> {
                    String k = p.data.getString("K");
                    String v = p.data.getString("V");
                    if (k.length() > 32 || v.length() > 16) return;
                    mc.setColor(k, v);
                }
                case ACTION_SET_ENABLED -> mc.setMonitorEnabled(p.data.getBoolean("E"));
                default -> { /* action は read() で 0..2 に bound 済 */ }
            }
            mc.setChanged();
            sp.serverLevel().sendBlockUpdated(
                    p.computerPos, mc.getBlockState(), mc.getBlockState(), 3);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
