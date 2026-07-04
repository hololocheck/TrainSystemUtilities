package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: PLACE モード再試行 (資材搬入後にホイール押し込みで送信)。
 * tool に保存されている origin / preset / rotation で doPlaceAt を再実行。
 */
public record TrainPresetRetryPayload() implements CustomPacketPayload {

    public static final Type<TrainPresetRetryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_retry_place"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetRetryPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {}, buf -> new TrainPresetRetryPayload());

    public static void handle(TrainPresetRetryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack tool = TrainPresetToolItem.findHeldTool(player);
            if (tool.isEmpty()) return;
            if (TrainPresetToolItem.getToolMode(tool) != TrainPresetToolItem.TOOL_MODE_PLACE) return;
            if (!TrainPresetToolItem.isPlaceResumeReady(tool)) return;

            TrainPresetToolItem.retryPlacement(player.serverLevel(), tool, player);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
