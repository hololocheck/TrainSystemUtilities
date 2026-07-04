package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModDataComponents;
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
 * Client → Server: PLACE モードをキャンセル (shift+中クリックで送信)。
 * tool_mode を GUI へ戻し、PLACE_ORIGIN・SELECTED_PRESET を消去。
 */
public record TrainPresetCancelPlacePayload() implements CustomPacketPayload {

    public static final Type<TrainPresetCancelPlacePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_cancel_place"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetCancelPlacePayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {}, buf -> new TrainPresetCancelPlacePayload());

    public static void handle(TrainPresetCancelPlacePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack stack = findHeldTool(player);
            if (stack.isEmpty()) return;
            if (TrainPresetToolItem.getToolMode(stack) != TrainPresetToolItem.TOOL_MODE_PLACE) return;

            TrainPresetToolItem.setToolMode(stack, TrainPresetToolItem.TOOL_MODE_GUI);
            stack.remove(ModDataComponents.PLACE_ORIGIN.get());
            stack.remove(ModDataComponents.PLACE_MARKER_DIRECTION.get());
            stack.remove(ModDataComponents.PLACE_AUTO_ROT_Y.get());
            stack.remove(ModDataComponents.SELECTED_PRESET.get());
            stack.remove(ModDataComponents.PLACE_SUB_MODE.get());
            stack.remove(ModDataComponents.PLACE_ROT_Y.get());
            // 不足アイテム HUD / 再開可能フラグも消す
            TrainPresetToolItem.clearPlacementStatus(stack);
            stack.remove(ModDataComponents.PLACE_RESUME_READY.get());
            player.displayClientMessage(Component.translatable("tsu.tool.cancel_place")
                    .withStyle(ChatFormatting.GRAY), true); // 設置をキャンセルしました
        });
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
