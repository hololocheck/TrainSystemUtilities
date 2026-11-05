package com.trainsystemutilities.network;

import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 列車プリセットツールのモードを切り替える (alt+ホイールから送信) — the tools host's {@code preset-tool-mode}
 * action ({@link ToolsData}; TrainPresetToolModePayload until MANTA_7_CONCEPT C4).
 * action: 0=tool_mode 切替、1=range_edit_mode 切替。
 * delta: ホイール方向 (+1 / -1)。 範囲 (action 0..16, delta ±1000) は tools-state.json の宣言。
 */
public final class TrainPresetToolModeAction {

    private TrainPresetToolModeAction() {}

    public static void apply(ServerPlayer player, int action, int delta) {
        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) return;

        int curMode = TrainPresetToolItem.getToolMode(stack);
        if (action == 0) {
            // PLACE モード中: alt+wheel 下回し で 起点→回転→設置 の順に進む。
            // (client は scrollY > 0 を delta=+1 にして送る = ホイール上)。下回しを「forward」に揃える。
            if (curMode == TrainPresetToolItem.TOOL_MODE_PLACE) {
                int sub = TrainPresetToolItem.getPlaceSubMode(stack);
                int next = sub + (delta > 0 ? -1 : 1);
                TrainPresetToolItem.setPlaceSubMode(stack, next);
            } else {
                // GUI/Selection 間トグル (こちらも下回し = forward)
                int next = (curMode + (delta > 0 ? -1 : 1)) & 1;
                TrainPresetToolItem.setToolMode(stack, next);
            }
        } else if (action == 1) {
            // PLACE モード中: 現在の sub-mode に応じて回転値を更新
            if (curMode == TrainPresetToolItem.TOOL_MODE_PLACE) {
                int sub = TrainPresetToolItem.getPlaceSubMode(stack);
                if (sub == TrainPresetToolItem.SUB_ROT_Y) {
                    int cur = TrainPresetToolItem.getPlaceRotY(stack);
                    TrainPresetToolItem.setPlaceRotY(stack, cur + (delta > 0 ? 1 : -1));
                }
                // X/Z 回転は将来対応
            } else {
                // Selection モード: range_edit_mode を 0/1/2 で循環
                int cur = TrainPresetToolItem.getEditMode(stack);
                int next = ((cur + (delta > 0 ? 1 : -1)) % 3 + 3) % 3;
                TrainPresetToolItem.setEditMode(stack, next);
            }
        }
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
