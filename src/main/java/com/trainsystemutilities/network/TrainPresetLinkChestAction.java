package com.trainsystemutilities.network;

import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * shift+ホイール押し込みでチェストをリンク — the tools host's {@code preset-link-chest} action ({@link ToolsData};
 * TrainPresetLinkChestPayload until MANTA_7_CONCEPT C4).
 * バニラ・mod 問わず {@link net.minecraft.world.Container} を実装するブロックエンティティが対象。
 */
public final class TrainPresetLinkChestAction {

    private TrainPresetLinkChestAction() {}

    public static void apply(ServerPlayer player, BlockPos pos) {
        ItemStack tool = TrainPresetToolItem.findHeldTool(player);
        if (tool.isEmpty()) return;

        var level = player.serverLevel();
        // SECURITY (TSU-02): 遠隔座標の chest を link する spoof を防ぐ近接ゲート
        if (!PermissionHelper.isWithinReach(player, pos)) {
            player.displayClientMessage(
                    Component.translatable("tsu.tool.cant_link_chest")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!TrainPresetSupply.canLinkChest(level, pos)) {
            player.displayClientMessage(
                    Component.translatable("tsu.tool.cant_link_chest")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        BlockState state = level.getBlockState(pos);
        String label = state.getBlock().getName().getString()
                + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
        TrainPresetToolItem.setLinkedChest(tool, pos.immutable(), label);
        TrainPresetToolItem.setMaterialSourceMode(tool, TrainPresetToolItem.SOURCE_CHEST);
        TrainPresetToolItem.clearPlacementStatus(tool);
        player.displayClientMessage(
                Component.translatable("tsu.tool.chest_linked_fmt", label)
                        .withStyle(ChatFormatting.AQUA), true);
    }
}
