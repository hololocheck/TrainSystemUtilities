package com.trainsystemutilities.network;

import com.trainsystemutilities.item.StationRangeToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 駅範囲指定ツールのモード切替 (ホイール入力) — the tools host's {@code station-range-mode} action
 * ({@link ToolsData}; StationRangeToolModePayload until MANTA_7_CONCEPT C4).
 * action: 0 = TOOL_MODE 循環 (GUI/SELECTION/VIEW), 1 = RANGE_EDIT_MODE 循環 (0/1/2),
 *         2 = PLATFORM_NUMBERING_DIR 循環 (auto/left/right)。
 * delta: ホイール方向 (+1 / -1)。 範囲 (action 0..16, delta ±1000) は tools-state.json の宣言。
 *
 * <p>列車プリセットツール ({@link TrainPresetToolModeAction}) と同じ規約。
 */
public final class StationRangeToolModeAction {

    private StationRangeToolModeAction() {}

    public static void apply(ServerPlayer player, int action, int delta) {
        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) return;
        int step = delta > 0 ? 1 : -1;
        if (action == 0) {
            int cur = StationRangeToolItem.getToolMode(stack);
            StationRangeToolItem.setToolMode(stack, cur + step);
        } else if (action == 1) {
            int cur = StationRangeToolItem.getEditMode(stack);
            StationRangeToolItem.setEditMode(stack, cur + step);
        } else if (action == 2) {
            int cur = StationRangeToolItem.getNumberingDir(stack);
            StationRangeToolItem.setNumberingDir(stack, cur + step);
        }
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.STATION_RANGE_TOOL.get());
    }
}
