package com.trainsystemutilities.network;

import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 架線設定画面の「架線を補充」ボタン → 架線スプール装填メニューを開く — the tools host's {@code wire-refill-open}
 * action ({@link ToolsData}; WireConnectorRefillOpenPayload until MANTA_7_CONCEPT C4).
 * 列車プリセットの {@link TrainPresetGlueRefillAction} と同方式 (= player.openMenu)。
 */
public final class WireConnectorRefillOpenAction {

    private WireConnectorRefillOpenAction() {}

    public static void apply(ServerPlayer player) {
        ItemStack tool = com.manta.api.hud.HeldTools.find(player, ModItems.WIRE_CONNECTOR.get());
        if (tool.isEmpty()) {
            player.displayClientMessage(Component.translatable("tsu.tool.no_tool")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new com.trainsystemutilities.gui.WireConnectorMenu(id, inv),
                Component.translatable("tsu.wire_refill.title")));
    }
}
