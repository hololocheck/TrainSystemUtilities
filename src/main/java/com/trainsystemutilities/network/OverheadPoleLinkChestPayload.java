package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
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
 * Client → Server: 架線柱配置ツールでチェストをリンク (= shift+中クリック)。
 * 列車プリセットツールと同じく {@link net.minecraft.world.Container} を実装する BE が対象。
 * リンク先は preset ツールと共用の {@code PLACE_LINKED_CHEST_POS} に保存する。
 */
public record OverheadPoleLinkChestPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<OverheadPoleLinkChestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "opa_link_chest"));

    public static final StreamCodec<FriendlyByteBuf, OverheadPoleLinkChestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.pos),
                    buf -> new OverheadPoleLinkChestPayload(buf.readBlockPos()));

    public static void handle(OverheadPoleLinkChestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack tool = belugalab.tsu.api.HeldTools.find(player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
            if (tool.isEmpty()) return;

            var level = player.serverLevel();
            BlockPos pos = payload.pos;
            // SECURITY (TSU-02): 遠隔座標の chest を link する spoof を防ぐ近接ゲート
            if (!PermissionHelper.isWithinReach(player, pos)) {
                player.displayClientMessage(Component.translatable("tsu.tool.cant_link_chest")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            if (!TrainPresetSupply.canLinkChest(level, pos)) {
                player.displayClientMessage(Component.translatable("tsu.tool.cant_link_chest")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }

            BlockState state = level.getBlockState(pos);
            String label = state.getBlock().getName().getString()
                    + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
            tool.set(ModDataComponents.PLACE_LINKED_CHEST_POS.get(), pos.immutable());
            tool.set(ModDataComponents.PLACE_LINKED_CHEST_LABEL.get(), label);
            player.displayClientMessage(Component.translatable("tsu.tool.chest_linked_fmt", label)
                    .withStyle(ChatFormatting.AQUA), true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
