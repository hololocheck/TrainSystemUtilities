package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.announce.AnnouncementCondition;
import com.trainsystemutilities.announce.AnnouncementEntry;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.compat.sas.SasIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: アナウンス設定への CRUD コマンド (1 entry につき 1 condition モデル)。
 *
 * <p>Op:
 * <ul>
 *   <li>{@link #OP_ADD_ENTRY}: 新規エントリ追加 (引数なし)</li>
 *   <li>{@link #OP_REMOVE_ENTRY}: a1=entryIndex のエントリを削除</li>
 *   <li>{@link #OP_SET_ENTRY_CONDITION}: a1=entryIndex の condition type を a2=Type ordinal に設定</li>
 *   <li>{@link #OP_ADJUST_ENTRY_DELAY}: a1=entryIndex の delaySeconds に a2 を加算 (ホイール用)</li>
 *   <li>{@link #OP_TEST_PLAY}: a1=entryIndex を即時再生</li>
 *   <li>{@link #OP_SYNC_REQUEST}: 現状の AnnouncementConfig を返送 (mutate なし)</li>
 *   <li>{@link #OP_TOGGLE_ENABLED}: master toggle を反転</li>
 *   <li>{@link #OP_REORDER_ENTRY}: a1=from, a2=to (-1=上へ, +1=下へ の意味で a2 を ±1 と運用も可)</li>
 * </ul>
 */
public record AnnouncementCommandPayload(BlockPos pos, byte op, int a1, int a2, int a3)
        implements CustomPacketPayload {

    public static final byte OP_ADD_ENTRY = 0;
    public static final byte OP_REMOVE_ENTRY = 1;
    public static final byte OP_SET_ENTRY_CONDITION = 2;
    public static final byte OP_ADJUST_ENTRY_DELAY = 3;
    public static final byte OP_TEST_PLAY = 4;
    public static final byte OP_SYNC_REQUEST = 5;
    public static final byte OP_TOGGLE_ENABLED = 6;
    public static final byte OP_REORDER_ENTRY = 7;
    /** Per-entry pause: 該当 BE での再生を停止 (一時停止扱い、再開は未対応)。a1=entryIdx (現状未使用)。 */
    public static final byte OP_STOP_PLAYBACK = 8;
    /** a1=entryIndex の playCount を a2 (delta) で増減 (1..MAX_PLAY_COUNT で clamp)。 */
    public static final byte OP_ADJUST_ENTRY_PLAYCOUNT = 9;
    /** 減衰モード (cfg.attenuationMode) を反転。 */
    public static final byte OP_TOGGLE_ATTENUATION = 10;

    /** P0-4 #11: 有効 op の最大 byte (= OP_TOGGLE_ATTENUATION)。 read で範囲 check。 */
    private static final byte MAX_OP = OP_TOGGLE_ATTENUATION;
    /** entry index / a1-a3 の妥当な abs 上限 (= 異常値 silent reject 用)。 */
    private static final int A_RANGE = 1_000_000;

    public static final Type<AnnouncementCommandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "announcement_cmd"));

    public static final StreamCodec<FriendlyByteBuf, AnnouncementCommandPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeByte(p.op);
                        buf.writeInt(p.a1); buf.writeInt(p.a2); buf.writeInt(p.a3);
                    },
                    buf -> {
                        // P0-4 #11: defense-in-depth — op byte 範囲 + a1/a2/a3 ±1M に bound。
                        // 既存 handle() 内の switch default は不正 op を return するが、 read 段階で
                        // 不正値を弾いて network thread の処理量を抑える。
                        BlockPos pos = buf.readBlockPos();
                        byte op = buf.readByte();
                        if (op < 0 || op > MAX_OP) {
                            throw new IllegalArgumentException("invalid op byte " + op);
                        }
                        int a1 = BoundedStreamCodec.readBoundedInt(buf, -A_RANGE, A_RANGE);
                        int a2 = BoundedStreamCodec.readBoundedInt(buf, -A_RANGE, A_RANGE);
                        int a3 = BoundedStreamCodec.readBoundedInt(buf, -A_RANGE, A_RANGE);
                        return new AnnouncementCommandPayload(pos, op, a1, a2, a3);
                    }
            );

    public static void handle(AnnouncementCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.level().getBlockEntity(payload.pos)
                    instanceof RailwayManagementBlockEntity rmbe)) return;
            if (player.distanceToSqr(payload.pos.getX() + 0.5, payload.pos.getY() + 0.5,
                    payload.pos.getZ() + 0.5) > 64) return;
            if (!rmbe.canAccess(player)) return;

            var cfg = rmbe.getAnnouncementConfig();
            switch (payload.op) {
                case OP_ADD_ENTRY -> cfg.addEntry();
                case OP_REMOVE_ENTRY -> {
                    // 削除エントリの記録媒体をプレイヤーへ返却 (スロットに置き去りにしない)。
                    // entry.remove と同じく媒体もシフトして entry↔媒体 の整列を保つ。
                    var media = rmbe.removeAnnouncementMedia(payload.a1);
                    if (!media.isEmpty() && !player.getInventory().add(media)) {
                        player.drop(media, false);
                    }
                    cfg.removeEntry(payload.a1);
                }
                case OP_SET_ENTRY_CONDITION -> {
                    AnnouncementEntry e = cfg.get(payload.a1);
                    if (e != null && payload.a2 >= 0
                            && payload.a2 < AnnouncementCondition.Type.values().length) {
                        AnnouncementCondition.Type t = AnnouncementCondition.Type.values()[payload.a2];
                        e.setCondition(e.condition().withType(t));
                    }
                }
                case OP_ADJUST_ENTRY_DELAY -> {
                    AnnouncementEntry e = cfg.get(payload.a1);
                    if (e != null) {
                        int newDelay = e.condition().delaySeconds + payload.a2;
                        e.setCondition(e.condition().withDelay(newDelay));
                    }
                }
                case OP_TEST_PLAY -> {
                    if (player.level() instanceof ServerLevel sl && SasIntegration.isLoaded()) {
                        com.trainsystemutilities.announce.AnnouncementScheduler.testPlay(
                                sl, payload.pos, payload.a1);
                    }
                }
                case OP_TOGGLE_ENABLED -> cfg.setEnabled(!cfg.isEnabled());
                case OP_REORDER_ENTRY -> {
                    if (cfg.moveEntry(payload.a1, payload.a2)) {
                        // メタデータ移動と同時に BE の媒体スロットも入れ替える必要がある
                        // (entry index と BE slot が 1:1 で紐付いているため、metadata だけ動かすと
                        //  音声と条件がずれる)
                        rmbe.swapAnnouncementMedia(payload.a1, payload.a2);
                    }
                }
                case OP_STOP_PLAYBACK -> {
                    if (player.level() instanceof ServerLevel sl) {
                        SasIntegration.stopAudio(sl, payload.pos);
                        com.trainsystemutilities.announce.AnnouncementScheduler.clearPlaybackState(
                                sl, payload.pos);
                    }
                }
                case OP_ADJUST_ENTRY_PLAYCOUNT -> {
                    AnnouncementEntry e = cfg.get(payload.a1);
                    if (e != null) e.setPlayCount(e.playCount() + payload.a2);
                }
                case OP_TOGGLE_ATTENUATION -> cfg.setAttenuationMode(!cfg.isAttenuationMode());
                case OP_SYNC_REQUEST -> { /* no-op; sync を送信 */ }
                default -> { return; }
            }
            if (payload.op != OP_SYNC_REQUEST && payload.op != OP_TEST_PLAY) {
                rmbe.setAnnouncementConfig(cfg);
            }
            PacketDistributor.sendToPlayer(player, new AnnouncementSyncPayload(
                    payload.pos, cfg.save()));
            com.trainsystemutilities.announce.AnnouncementScheduler.syncCurrentPlaybackTo(
                    player, payload.pos);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
