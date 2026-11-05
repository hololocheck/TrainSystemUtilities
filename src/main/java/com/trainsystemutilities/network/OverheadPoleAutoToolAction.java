package com.trainsystemutilities.network;

import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 架線柱自動配置ツールのモード切替 / 値増減 (ホイール入力) — the tools host's {@code pole-tool} action
 * ({@link ToolsData}; OverheadPoleAutoToolPayload until MANTA_7_CONCEPT C4).
 * action: 0 = サブモード循環 (GUIへ戻る / 配置する)、
 *         1 = 高さの増減 (shift+wheel。 高さ / クリアランス / スパンを選ぶ編集対象は 2026-10-07 に廃止)、
 *         2 = tool mode 循環 (GUI/SELECTION)、
 *         3 = 手動回転 (8 方向)。
 * delta: ホイール方向 +1/-1。 範囲 (action 0..16, delta ±1000) は tools-state.json の宣言。
 *
 * <p>{@link StationRangeToolModeAction} と同じパターン。
 */
public final class OverheadPoleAutoToolAction {

    private OverheadPoleAutoToolAction() {}

    public static void apply(ServerPlayer player, int action, int delta) {
        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) return;
        int step = delta > 0 ? 1 : -1;
        switch (action) {
            case 0 -> AutoPlaceConfig.setSubMode(stack,
                    AutoPlaceConfig.getSubMode(stack) + step);
            case 1 -> AutoPlaceConfig.setHeight(stack, AutoPlaceConfig.getHeight(stack) + step);
            case 2 -> AutoPlaceConfig.setToolMode(stack,
                    AutoPlaceConfig.getToolMode(stack) + step);
            case 3 -> AutoPlaceConfig.setManualRotation(stack,
                    AutoPlaceConfig.getManualRotation(stack) + step);
        }
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
    }
}
