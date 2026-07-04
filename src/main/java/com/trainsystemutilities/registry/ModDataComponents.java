package com.trainsystemutilities.registry;

import com.mojang.serialization.Codec;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Supplier;

/**
 * TSU 専用の ItemStack DataComponent 定義。
 * 列車プリセットツール (TrainPresetToolItem) の状態 (範囲選択 pos1/pos2、編集モード、
 * ツールの動作モード、選択中プリセット名) を ItemStack 上に永続化する。
 */
public class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, TrainSystemUtilities.MOD_ID);

    /** 範囲選択 1 点目 (BlockPos)。未設定なら不在。 */
    public static final Supplier<DataComponentType<BlockPos>> RANGE_POS1 =
            DATA_COMPONENTS.register("range_pos1", () ->
                    DataComponentType.<BlockPos>builder()
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
                            .build());

    /** 範囲選択 2 点目 (BlockPos)。未設定なら不在。 */
    public static final Supplier<DataComponentType<BlockPos>> RANGE_POS2 =
            DATA_COMPONENTS.register("range_pos2", () ->
                    DataComponentType.<BlockPos>builder()
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
                            .build());

    /** 範囲選択の編集モード: 0=通常 (Pos1→Pos2 順)、1=Pos1 編集、2=Pos2 編集。 */
    public static final Supplier<DataComponentType<Integer>> RANGE_EDIT_MODE =
            DATA_COMPONENTS.register("range_edit_mode", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** ツール動作モード: 0=GUI (右クリックで GUI を開く)、1=Selection (右クリックで範囲選択)、2=Place (詳細画面で「設置」を押した状態、駅マーカークリック待ち)。 */
    public static final Supplier<DataComponentType<Integer>> TOOL_MODE =
            DATA_COMPONENTS.register("tool_mode", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** 番線連番方向 (駅範囲指定ツール): 0=AUTO (creator pos の近い端を 1 番線)、
     *  1=LEFT (主軸 min 端を 1 番線、いわゆる左側)、2=RIGHT (主軸 max 端を 1 番線)。
     *  ctrl+ホイール押し込みで切替。 */
    public static final Supplier<DataComponentType<Integer>> PLATFORM_NUMBERING_DIR =
            DATA_COMPONENTS.register("platform_numbering_dir", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Place モード時に設置対象として選択中のプリセット ID (ファイル名)。 */
    public static final Supplier<DataComponentType<String>> SELECTED_PRESET =
            DATA_COMPONENTS.register("selected_preset", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    /** Place モードの sub-mode: 0=Rotate Y, 1=Rotate X, 2=Rotate Z, 3=Place。alt+wheel で循環。 */
    public static final Supplier<DataComponentType<Integer>> PLACE_SUB_MODE =
            DATA_COMPONENTS.register("place_sub_mode", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Place モードでの Y 軸回転 (0-3 = 0/90/180/270 度)。 */
    public static final Supplier<DataComponentType<Integer>> PLACE_ROT_Y =
            DATA_COMPONENTS.register("place_rot_y", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    public static final Supplier<DataComponentType<Integer>> PLACE_AUTO_ROT_Y =
            DATA_COMPONENTS.register("place_auto_rot_y", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Place モードで設定した起点 (origin) ブロック位置。Place サブモードでの設置基準。 */
    public static final Supplier<DataComponentType<BlockPos>> PLACE_ORIGIN =
            DATA_COMPONENTS.register("place_origin", () ->
                    DataComponentType.<BlockPos>builder()
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
                            .build());

    public static final Supplier<DataComponentType<Integer>> PLACE_MARKER_DIRECTION =
            DATA_COMPONENTS.register("place_marker_direction", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** 粘着剤タンク残量 (0..GLUE_TANK_MAX)。1 ブロック接着で GLUE_PER_BLOCK 消費。 */
    public static final Supplier<DataComponentType<Integer>> GLUE_TANK =
            DATA_COMPONENTS.register("glue_tank", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    public static final Supplier<DataComponentType<Integer>> PLACE_SOURCE_MODE =
            DATA_COMPONENTS.register("place_source_mode", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    public static final Supplier<DataComponentType<BlockPos>> PLACE_LINKED_CHEST_POS =
            DATA_COMPONENTS.register("place_linked_chest_pos", () ->
                    DataComponentType.<BlockPos>builder()
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
                            .build());

    public static final Supplier<DataComponentType<String>> PLACE_LINKED_CHEST_LABEL =
            DATA_COMPONENTS.register("place_linked_chest_label", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    public static final Supplier<DataComponentType<BlockPos>> PLACE_LINKED_ME_POS =
            DATA_COMPONENTS.register("place_linked_me_pos", () ->
                    DataComponentType.<BlockPos>builder()
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
                            .build());

    public static final Supplier<DataComponentType<String>> PLACE_LINKED_ME_LABEL =
            DATA_COMPONENTS.register("place_linked_me_label", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    /**
     * AE2 無線アクセスポイントのリンク先 (GlobalPos)。
     * AE2 の GridLinkables 経由で WAP の linkable slot に列車プリセットツールを入れたときに設定される。
     * dimension を保持するため GlobalPos を使う (BlockPos だと別 dimension で誤動作する)。
     */
    public static final Supplier<DataComponentType<GlobalPos>> PLACE_LINKED_WAP_POS =
            DATA_COMPONENTS.register("place_linked_wap_pos", () ->
                    DataComponentType.<GlobalPos>builder()
                            .persistent(GlobalPos.CODEC)
                            .networkSynchronized(GlobalPos.STREAM_CODEC)
                            .build());

    public static final Supplier<DataComponentType<String>> PLACE_LINKED_WAP_LABEL =
            DATA_COMPONENTS.register("place_linked_wap_label", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    public static final Supplier<DataComponentType<String>> PLACE_MISSING_ITEMS =
            DATA_COMPONENTS.register("place_missing_items", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    /**
     * 設置に失敗して資材待ちの状態で、サーバ tick が「いま設置可能」と判断したときに true。
     * 真なら HUD に「再開できます」を表示し、ホイール押し込みで再試行できる。
     */
    public static final Supplier<DataComponentType<Boolean>> PLACE_RESUME_READY =
            DATA_COMPONENTS.register("place_resume_ready", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    public static final Supplier<DataComponentType<String>> PLACE_STATUS_MESSAGE =
            DATA_COMPONENTS.register("place_status_message", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    // ===== Overhead Pole Auto Tool =====

    /** Auto-place tool: 1 点目 (= start track BlockPos)。 */
    public static final Supplier<DataComponentType<BlockPos>> AUTO_POLE_POS1 =
            DATA_COMPONENTS.register("auto_pole_pos1", () ->
                    DataComponentType.<BlockPos>builder()
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
                            .build());

    /** Auto-place tool: 2 点目 (= end track BlockPos)。 */
    public static final Supplier<DataComponentType<BlockPos>> AUTO_POLE_POS2 =
            DATA_COMPONENTS.register("auto_pole_pos2", () ->
                    DataComponentType.<BlockPos>builder()
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC)
                            .build());

    /** Auto-place tool: 架線柱の高さ (= block 数)。 default 3。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_HEIGHT =
            DATA_COMPONENTS.register("auto_pole_height", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: 線路外端からのクリアランス (= block 数)。 default 1。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_CLEARANCE =
            DATA_COMPONENTS.register("auto_pole_clearance", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: 隣接架線柱の距離 (= block 数)。 default 4。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_SPAN =
            DATA_COMPONENTS.register("auto_pole_span", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: 編集モード (0=Height, 1=Clearance, 2=Span)。 ctrl+wheel で循環。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_EDIT_MODE =
            DATA_COMPONENTS.register("auto_pole_edit_mode", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: ツール動作モード (0=GUI, 1=Selection)。 alt+wheel で循環。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_TOOL_MODE =
            DATA_COMPONENTS.register("auto_pole_tool_mode", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: 駅範囲 (StationGroup) 内には架線柱を置かない。 default true。 */
    public static final Supplier<DataComponentType<Boolean>> AUTO_POLE_SKIP_STATION =
            DATA_COMPONENTS.register("auto_pole_skip_station", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    /** Auto-place tool: 並走線路検出のスキャン範囲 (= block 数)。 default 8。 (legacy, unused) */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_SCAN_RANGE =
            DATA_COMPONENTS.register("auto_pole_scan_range", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: 複線化数 (= 1=単線、 2=複線、 ...)。 default 1。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_MULTI_TRACK_COUNT =
            DATA_COMPONENTS.register("auto_pole_multi_track_count", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: 設置モードで選択された線路 BlockPos の list (server-side のみ)。 */
    public static final Supplier<DataComponentType<List<BlockPos>>> AUTO_POLE_SELECTED_TRACKS =
            DATA_COMPONENTS.register("auto_pole_selected_tracks", () ->
                    DataComponentType.<List<BlockPos>>builder()
                            .persistent(BlockPos.CODEC.listOf())
                            .build());

    /** Auto-place tool: SELECTED_TRACKS の数 (= client HUD 表示用、 server sync 経由)。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_SELECTED_COUNT =
            DATA_COMPONENTS.register("auto_pole_selected_count", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: SELECTION 内のサブモード (= 0=GUI戻る, 1=選択, 2=配置)。 ctrl+wheel で切替。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_SUBMODE =
            DATA_COMPONENTS.register("auto_pole_submode", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    /** Auto-place tool: 片持ち style (= 片側のみ pole + truss が伸びる)。 default false (= 通常)。 */
    public static final Supplier<DataComponentType<Boolean>> AUTO_POLE_CANTILEVER =
            DATA_COMPONENTS.register("auto_pole_cantilever", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    /** Auto-place tool: 架線柱の頂上を結ぶトラスも配置する。 default true。 */
    public static final Supplier<DataComponentType<Boolean>> AUTO_POLE_PLACE_TRUSS =
            DATA_COMPONENTS.register("auto_pole_place_truss", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    /** Auto-place tool: 線路真上 + トラス直下 に碍子 (insulator) を自動配置する。 default true。 */
    public static final Supplier<DataComponentType<Boolean>> AUTO_POLE_PLACE_INSULATOR =
            DATA_COMPONENTS.register("auto_pole_place_insulator", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    /** Auto-place tool: 手動回転角度 (= 0..7 の ANGLE_8、 45° step)。 default 0。 */
    public static final Supplier<DataComponentType<Integer>> AUTO_POLE_MANUAL_ROTATION =
            DATA_COMPONENTS.register("auto_pole_manual_rotation", () ->
                    DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.VAR_INT)
                            .build());

    /** Auto-place tool: リンク済み倉庫 (chest+ME) の在庫数 (= client HUD 表示用、 "id=count;…" encode)。
     *  OverheadPoleMaterialMonitor が 20 tick ごとに更新し、 自動 sync で client HUD が読む。 */
    public static final Supplier<DataComponentType<String>> AUTO_POLE_STOCK =
            DATA_COMPONENTS.register("auto_pole_stock", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    // ===== 切符 (Ticket) =====

    /** 切符の発駅名 (表示用)。 */
    public static final Supplier<DataComponentType<String>> TICKET_FROM =
            DATA_COMPONENTS.register("ticket_from", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

    /** 切符の着駅名 (表示用)。 */
    public static final Supplier<DataComponentType<String>> TICKET_TO =
            DATA_COMPONENTS.register("ticket_to", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

    /** 切符の着駅 StationGroup UUID (検証/改札用)。 */
    public static final Supplier<DataComponentType<java.util.UUID>> TICKET_TO_ID =
            DATA_COMPONENTS.register("ticket_to_id", () ->
                    DataComponentType.<java.util.UUID>builder()
                            .persistent(net.minecraft.core.UUIDUtil.CODEC)
                            .networkSynchronized(net.minecraft.core.UUIDUtil.STREAM_CODEC).build());

    /** 切符の発駅 StationGroup UUID (改札での経路判定用)。 */
    public static final Supplier<DataComponentType<java.util.UUID>> TICKET_FROM_ID =
            DATA_COMPONENTS.register("ticket_from_id", () ->
                    DataComponentType.<java.util.UUID>builder()
                            .persistent(net.minecraft.core.UUIDUtil.CODEC)
                            .networkSynchronized(net.minecraft.core.UUIDUtil.STREAM_CODEC).build());

    /** 切符の経路概要 (任意、 例 "○○線 経由")。 */
    public static final Supplier<DataComponentType<String>> TICKET_VIA =
            DATA_COMPONENTS.register("ticket_via", () ->
                    DataComponentType.<String>builder()
                            .persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

    /** 切符が入場済みか (= 発駅の改札を通過済み)。 true=出場処理 / false=入場処理。 */
    public static final Supplier<DataComponentType<Boolean>> TICKET_ENTERED =
            DATA_COMPONENTS.register("ticket_entered", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());

    public static void register(IEventBus bus) {
        DATA_COMPONENTS.register(bus);
    }
}
