package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
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
 * Client → Server: 架線設定画面の「架線を補充」ボタン → 架線スプール装填メニューを開く。
 * 列車プリセットの {@link TrainPresetGlueRefillPayload} と同方式 (= player.openMenu)。
 */
public record WireConnectorRefillOpenPayload() implements CustomPacketPayload {

    public static final Type<WireConnectorRefillOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "wire_refill_open"));

    public static final StreamCodec<FriendlyByteBuf, WireConnectorRefillOpenPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {}, buf -> new WireConnectorRefillOpenPayload());

    public static void handle(WireConnectorRefillOpenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack tool = belugalab.tsu.api.HeldTools.find(player, ModItems.WIRE_CONNECTOR.get());
            if (tool.isEmpty()) {
                player.displayClientMessage(Component.translatable("tsu.tool.no_tool")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new com.trainsystemutilities.gui.WireConnectorMenu(id, inv),
                    Component.translatable("tsu.wire_refill.title")));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
