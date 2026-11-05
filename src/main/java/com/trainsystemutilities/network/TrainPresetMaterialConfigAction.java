package com.trainsystemutilities.network;

import com.trainsystemutilities.item.TrainPresetToolItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The train preset tool's material source - the tools host's {@code preset-material-config} action
 * ({@link ToolsData}; TrainPresetMaterialConfigPayload until MANTA_7_CONCEPT C4). 範囲 (action 0..16,
 * value ±1000) は tools-state.json の宣言。
 */
public final class TrainPresetMaterialConfigAction {

    public static final int ACTION_SET_SOURCE_MODE = 0;
    public static final int ACTION_CLEAR_CHEST = 1;
    public static final int ACTION_CLEAR_ME = 2;
    /** ホイール式切替: value > 0 で次へ、value < 0 で前へ。 */
    public static final int ACTION_CYCLE_SOURCE_MODE = 3;

    private TrainPresetMaterialConfigAction() {}

    public static void apply(ServerPlayer player, int action, int value) {
        ItemStack tool = TrainPresetToolItem.findHeldTool(player);
        if (tool.isEmpty()) {
            return;
        }

        switch (action) {
            case ACTION_SET_SOURCE_MODE -> {
                TrainPresetToolItem.setMaterialSourceMode(tool, value);
                TrainPresetToolItem.clearPlacementStatus(tool);
            }
            case ACTION_CLEAR_CHEST -> {
                TrainPresetToolItem.clearLinkedChest(tool);
                TrainPresetToolItem.clearPlacementStatus(tool);
            }
            case ACTION_CLEAR_ME -> {
                TrainPresetToolItem.clearLinkedMe(tool);
                TrainPresetToolItem.clearPlacementStatus(tool);
            }
            case ACTION_CYCLE_SOURCE_MODE -> {
                TrainPresetToolItem.cycleMaterialSourceMode(tool, value);
                TrainPresetToolItem.clearPlacementStatus(tool);
            }
            default -> {
                return;
            }
        }

        player.displayClientMessage(
                Component.translatable("tsu.preset.material_config_updated").withStyle(ChatFormatting.AQUA),
                true);
    }
}
