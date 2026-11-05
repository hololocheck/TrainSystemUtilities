package com.trainsystemutilities.network;

import com.trainsystemutilities.announce.AnnouncementCondition;
import com.trainsystemutilities.announce.AnnouncementEntry;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.compat.sas.SasIntegration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * アナウンス設定への CRUD コマンド (1 entry につき 1 condition モデル) — the railway management host's
 * {@code announcement} action ({@link RailwayManagementData}; AnnouncementCommandPayload until MANTA_7_CONCEPT C4).
 * 範囲 (op 0..10, a1..a3 ±1,000,000) は railway-management-state.json の宣言。 変更後の設定は host の field に載る
 * ({@link RailwayManagementBlockEntity#setAnnouncementConfig})。
 *
 * <p>Op:
 * <ul>
 *   <li>{@link #OP_ADD_ENTRY}: 新規エントリ追加 (引数なし)</li>
 *   <li>{@link #OP_REMOVE_ENTRY}: a1=entryIndex のエントリを削除</li>
 *   <li>{@link #OP_SET_ENTRY_CONDITION}: a1=entryIndex の condition type を a2=Type ordinal に設定</li>
 *   <li>{@link #OP_ADJUST_ENTRY_DELAY}: a1=entryIndex の delaySeconds に a2 を加算 (ホイール用)</li>
 *   <li>{@link #OP_TEST_PLAY}: a1=entryIndex を即時再生</li>
 *   <li>{@link #OP_SYNC_REQUEST}: mutate なし (設定は host の購読で届くので、画面はもう送らない)</li>
 *   <li>{@link #OP_TOGGLE_ENABLED}: master toggle を反転</li>
 *   <li>{@link #OP_REORDER_ENTRY}: a1=from, a2=to (-1=上へ, +1=下へ の意味で a2 を ±1 と運用も可)</li>
 * </ul>
 */
public final class AnnouncementCommandAction {

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

    private AnnouncementCommandAction() {}

    public static void apply(ServerPlayer player, RailwayManagementBlockEntity rmbe, int op, int a1, int a2, int a3) {
        var cfg = rmbe.getAnnouncementConfig();
        switch (op) {
            case OP_ADD_ENTRY -> cfg.addEntry();
            case OP_REMOVE_ENTRY -> {
                // 削除エントリの記録媒体をプレイヤーへ返却 (スロットに置き去りにしない)。
                // entry.remove と同じく媒体もシフトして entry↔媒体 の整列を保つ。
                var media = rmbe.removeAnnouncementMedia(a1);
                if (!media.isEmpty() && !player.getInventory().add(media)) {
                    player.drop(media, false);
                }
                cfg.removeEntry(a1);
            }
            case OP_SET_ENTRY_CONDITION -> {
                AnnouncementEntry e = cfg.get(a1);
                if (e != null && a2 >= 0
                        && a2 < AnnouncementCondition.Type.values().length) {
                    AnnouncementCondition.Type t = AnnouncementCondition.Type.values()[a2];
                    e.setCondition(e.condition().withType(t));
                }
            }
            case OP_ADJUST_ENTRY_DELAY -> {
                AnnouncementEntry e = cfg.get(a1);
                if (e != null) {
                    int newDelay = e.condition().delaySeconds + a2;
                    e.setCondition(e.condition().withDelay(newDelay));
                }
            }
            case OP_TEST_PLAY -> {
                if (player.level() instanceof ServerLevel sl && SasIntegration.isLoaded()) {
                    com.trainsystemutilities.announce.AnnouncementScheduler.testPlay(
                            sl, rmbe.getBlockPos(), a1);
                }
            }
            case OP_TOGGLE_ENABLED -> cfg.setEnabled(!cfg.isEnabled());
            case OP_REORDER_ENTRY -> {
                if (cfg.moveEntry(a1, a2)) {
                    // メタデータ移動と同時に BE の媒体スロットも入れ替える必要がある
                    // (entry index と BE slot が 1:1 で紐付いているため、metadata だけ動かすと
                    //  音声と条件がずれる)
                    rmbe.swapAnnouncementMedia(a1, a2);
                }
            }
            case OP_STOP_PLAYBACK -> {
                if (player.level() instanceof ServerLevel sl) {
                    SasIntegration.stopAudio(sl, rmbe.getBlockPos());
                    com.trainsystemutilities.announce.AnnouncementScheduler.clearPlaybackState(
                            sl, rmbe.getBlockPos());
                }
            }
            case OP_ADJUST_ENTRY_PLAYCOUNT -> {
                AnnouncementEntry e = cfg.get(a1);
                if (e != null) e.setPlayCount(e.playCount() + a2);
            }
            case OP_TOGGLE_ATTENUATION -> cfg.setAttenuationMode(!cfg.isAttenuationMode());
            case OP_SYNC_REQUEST -> { /* no-op */ }
            default -> { return; }
        }
        if (op != OP_SYNC_REQUEST && op != OP_TEST_PLAY) {
            rmbe.setAnnouncementConfig(cfg);
        }
    }
}
