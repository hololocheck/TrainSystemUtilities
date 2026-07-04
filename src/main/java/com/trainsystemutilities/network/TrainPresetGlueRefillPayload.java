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
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 粘着剤タンク補充メニューを開く。
 * 専用 GUI (TrainPresetRefillMenu / Screen) を player.openMenu() で開く。
 */
public record TrainPresetGlueRefillPayload() implements CustomPacketPayload {

    public static final Type<TrainPresetGlueRefillPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_glue_refill"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetGlueRefillPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {}, buf -> new TrainPresetGlueRefillPayload());

    public static void handle(TrainPresetGlueRefillPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
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
        });
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
