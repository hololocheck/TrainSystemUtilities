package com.trainsystemutilities.network;

import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 架線柱自動配置ツールの詳細設定 (= GUI screen) からの設定値書き戻し — the tools host's {@code pole-settings}
 * action ({@link ToolsData}; OverheadPoleAutoSettingsPayload until MANTA_7_CONCEPT C4).
 *
 * <p>field: 0=Height, 1=Clearance, 5=Cantilever, 6=PlaceTruss, 7=PlaceInsulator (boolean → 0/1), 8=MultiTrackCount,
 * 100=APPLY。 2=Span / 3=ScanRange / 4=SkipStation は legacy で無視する。
 * value: clamp は server 側で実施。 範囲 (field 0..127 = APPLY の 100 を含む, value ±1024) は tools-state.json の宣言。
 */
public final class OverheadPoleAutoSettingsAction {

    public static final int FIELD_HEIGHT = 0;
    public static final int FIELD_CLEARANCE = 1;
    public static final int FIELD_SPAN = 2;           // legacy, unused: the span is retired (2026-10-07)
    public static final int FIELD_SCAN_RANGE = 3;     // legacy, unused
    public static final int FIELD_SKIP_STATION = 4;   // legacy, 常時 ON
    public static final int FIELD_CANTILEVER = 5;
    public static final int FIELD_PLACE_TRUSS = 6;
    public static final int FIELD_PLACE_INSULATOR = 7;
    public static final int FIELD_MULTI_TRACK_COUNT = 8;
    /** action: 「この設定にする」 ボタン → tool mode を SELECTION に切り替え、 SELECTED_TRACKS clear。 */
    public static final int FIELD_APPLY_AND_ENTER_PLACEMENT = 100;

    private OverheadPoleAutoSettingsAction() {}

    public static void apply(ServerPlayer player, int field, int value) {
        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) return;
        switch (field) {
            case FIELD_HEIGHT             -> AutoPlaceConfig.setHeight(stack, value);
            case FIELD_CLEARANCE          -> AutoPlaceConfig.setClearance(stack, value);
            case FIELD_CANTILEVER         -> AutoPlaceConfig.setCantilever(stack, value != 0);
            case FIELD_PLACE_TRUSS        -> AutoPlaceConfig.setPlaceTruss(stack, value != 0);
            case FIELD_PLACE_INSULATOR    -> AutoPlaceConfig.setPlaceInsulator(stack, value != 0);
            case FIELD_MULTI_TRACK_COUNT  -> AutoPlaceConfig.setMultiTrackCount(stack, value);
            case FIELD_APPLY_AND_ENTER_PLACEMENT -> {
                // GUI 閉じ + 設置モード移行 (= TOOL_MODE_SELECTION の「配置する」) + 選択 track 列をリセット。
                // The sub-mode is set too: a tool switched to GUI from 「GUIへ戻る」 by alt+wheel kept that
                // sub-mode, so 「設置モードへ」 landed where a right-click returns to the GUI.
                AutoPlaceConfig.setToolMode(stack, AutoPlaceConfig.TOOL_MODE_SELECTION);
                AutoPlaceConfig.setSubMode(stack, AutoPlaceConfig.SUB_MODE_PLACE);
                stack.set(com.trainsystemutilities.registry.ModDataComponents.AUTO_POLE_SELECTED_TRACKS.get(),
                        java.util.List.of());
                stack.set(com.trainsystemutilities.registry.ModDataComponents.AUTO_POLE_SELECTED_COUNT.get(), 0);
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("tsu.opas.placement_entered_fmt",
                                        AutoPlaceConfig.getMultiTrackCount(stack))
                                .withStyle(net.minecraft.ChatFormatting.AQUA), false);
            }
        }
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
    }
}
