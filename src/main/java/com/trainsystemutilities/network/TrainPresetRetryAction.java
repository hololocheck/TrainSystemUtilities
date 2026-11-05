package com.trainsystemutilities.network;

import com.trainsystemutilities.item.TrainPresetToolItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * PLACE モード再試行 (資材搬入後にホイール押し込みで送信) — the tools host's {@code preset-retry} action
 * ({@link ToolsData}; TrainPresetRetryPayload until MANTA_7_CONCEPT C4).
 * tool に保存されている origin / preset / rotation で doPlaceAt を再実行。
 */
public final class TrainPresetRetryAction {

    private TrainPresetRetryAction() {}

    public static void apply(ServerPlayer player) {
        ItemStack tool = TrainPresetToolItem.findHeldTool(player);
        if (tool.isEmpty()) return;
        if (TrainPresetToolItem.getToolMode(tool) != TrainPresetToolItem.TOOL_MODE_PLACE) return;
        if (!TrainPresetToolItem.isPlaceResumeReady(tool)) return;

        TrainPresetToolItem.retryPlacement(player.serverLevel(), tool, player);
    }
}
