package com.trainsystemutilities.network;

import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 粘着剤タンクを 0 にリセット (dump) — the tools host's {@code preset-glue-dump} action ({@link ToolsData};
 * TrainPresetGlueDumpPayload until MANTA_7_CONCEPT C4).
 */
public final class TrainPresetGlueDumpAction {

    private TrainPresetGlueDumpAction() {}

    public static void apply(ServerPlayer player) {
        ItemStack tool = findHeldTool(player);
        if (tool.isEmpty()) return;
        int prev = TrainPresetToolItem.getGlueTank(tool);
        TrainPresetToolItem.setGlueTank(tool, 0);
        player.getInventory().setChanged();
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();
        player.displayClientMessage(Component.translatable(
                "tsu.tool.dump_fmt", prev)
                .withStyle(ChatFormatting.YELLOW), true); // タンクダンプ: N gauge を破棄
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
