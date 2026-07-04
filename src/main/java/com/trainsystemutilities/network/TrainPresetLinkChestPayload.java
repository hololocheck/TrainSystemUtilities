package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: shift+ホイール押し込みでチェストをリンク。
 * バニラ・mod 問わず {@link net.minecraft.world.Container} を実装するブロックエンティティが対象。
 */
public record TrainPresetLinkChestPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<TrainPresetLinkChestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_link_chest"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetLinkChestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.pos),
                    buf -> new TrainPresetLinkChestPayload(buf.readBlockPos()));

    public static void handle(TrainPresetLinkChestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack tool = TrainPresetToolItem.findHeldTool(player);
            if (tool.isEmpty()) return;

            var level = player.serverLevel();
            BlockPos pos = payload.pos;
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
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
