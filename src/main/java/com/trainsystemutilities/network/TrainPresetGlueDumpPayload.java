package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 粘着剤タンクを 0 にリセット (dump)。
 */
public record TrainPresetGlueDumpPayload() implements CustomPacketPayload {

    public static final Type<TrainPresetGlueDumpPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_glue_dump"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetGlueDumpPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {}, buf -> new TrainPresetGlueDumpPayload());

    public static void handle(TrainPresetGlueDumpPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack tool = findHeldTool(player);
            if (tool.isEmpty()) return;
            int prev = TrainPresetToolItem.getGlueTank(tool);
            TrainPresetToolItem.setGlueTank(tool, 0);
            player.getInventory().setChanged();
            if (player.containerMenu != null) player.containerMenu.broadcastChanges();
            player.displayClientMessage(Component.translatable(
                    "tsu.tool.dump_fmt", prev)
                    .withStyle(ChatFormatting.YELLOW), true); // タンクダンプ: N gauge を破棄
        });
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
