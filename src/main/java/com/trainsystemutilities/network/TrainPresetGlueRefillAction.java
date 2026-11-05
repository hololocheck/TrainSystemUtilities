package com.trainsystemutilities.network;

import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 粘着剤タンク補充メニューを開く — the tools host's {@code preset-glue-refill} action ({@link ToolsData};
 * TrainPresetGlueRefillPayload until MANTA_7_CONCEPT C4).
 * 専用 GUI (TrainPresetRefillMenu / Screen) を player.openMenu() で開く。
 */
public final class TrainPresetGlueRefillAction {

    private TrainPresetGlueRefillAction() {}

    public static void apply(ServerPlayer player) {
        ItemStack tool = findHeldTool(player);
        if (tool.isEmpty()) {
            player.displayClientMessage(Component.translatable("tsu.tool.no_tool")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        // 専用 GUI (TrainPresetRefillMenu) を開く
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new com.trainsystemutilities.gui.TrainPresetRefillMenu(id, inv),
                Component.translatable("tsu.refill.title"))); // 粘着剤補充
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
