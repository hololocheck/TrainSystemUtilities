package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafeName;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetCapture;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 列車プリセット保存リクエスト。
 * クライアントの GUI 「保存」ボタンから送られる。サーバ側で範囲スキャンを行いファイル化する。
 *
 * pos1/pos2 はサーバー側の ItemStack DataComponent から取得するため、ペイロードに含めない
 * (改ざん防止)。
 */
public record TrainPresetSavePayload(String presetName) implements CustomPacketPayload {

    public static final Type<TrainPresetSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "train_preset_save"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetSavePayload> STREAM_CODEC =
            StreamCodec.of(TrainPresetSavePayload::write, TrainPresetSavePayload::read);

    private static void write(FriendlyByteBuf buf, TrainPresetSavePayload p) {
        buf.writeUtf(p.presetName, 64);
    }

    private static TrainPresetSavePayload read(FriendlyByteBuf buf) {
        // P0-4 #7: preset display name → 128 bytes (matches generic label cap; write side caps at 64 chars)
        return new TrainPresetSavePayload(BoundedStreamCodec.readBoundedUtf(buf, 128));
    }

    public static void handle(TrainPresetSavePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            // P0-4 #7: validate preset display name (user-controlled). Fall back to default if rejected
            // so the handler can still proceed instead of silently failing.
            String safePresetName;
            try {
                SafeName.validate(payload.presetName);
                safePresetName = payload.presetName;
            } catch (Exception ex) {
                TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected preset_save name from {}: {}",
                        player.getName().getString(), SafeLog.sanitize(payload.presetName));
                safePresetName = "preset";
            }
            final String effectivePresetName = safePresetName;

            ItemStack stack = findHeldTool(player);
            if (stack.isEmpty()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new TrainPresetSaveResultPayload(false, effectivePresetName,
                                net.minecraft.network.chat.Component.translatable("tsu.tool.tool_not_held").getString()));
                return;
            }
            BlockPos p1 = TrainPresetToolItem.getPos1(stack);
            BlockPos p2 = TrainPresetToolItem.getPos2(stack);
            if (p1 == null || p2 == null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new TrainPresetSaveResultPayload(false, effectivePresetName,
                                net.minecraft.network.chat.Component.translatable("tsu.tool.pos_unset").getString()));
                return;
            }

            TrainPreset preset = TrainPresetCapture.capture(
                    player.serverLevel(), p1, p2,
                    effectivePresetName, player.getName().getString(), player.getUUID());
            if (preset == null || preset.isEmpty()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new TrainPresetSaveResultPayload(false, effectivePresetName,
                                net.minecraft.network.chat.Component.translatable("tsu.tool.range_too_large").getString()));
                return;
            }

            try {
                var path = TrainPresetStorage.save(player.getServer(), preset);
                // 範囲選択 (Pos1/Pos2) を解除 → 同じ範囲を再保存しようとする事故防止
                stack.remove(com.trainsystemutilities.registry.ModDataComponents.RANGE_POS1.get());
                stack.remove(com.trainsystemutilities.registry.ModDataComponents.RANGE_POS2.get());
                stack.remove(com.trainsystemutilities.registry.ModDataComponents.RANGE_EDIT_MODE.get());
                String detail = preset.sizeX + "x" + preset.sizeY + "x" + preset.sizeZ
                        + " / " + preset.blocks.size() + "B";
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new TrainPresetSaveResultPayload(true, effectivePresetName, detail));
                TrainSystemUtilities.LOGGER.info("Preset saved: {}", path.getFileName());
            } catch (Exception ex) {
                TrainSystemUtilities.LOGGER.error("Preset save failed", ex);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new TrainPresetSaveResultPayload(false, effectivePresetName,
                                ex.getMessage() == null ? net.minecraft.network.chat.Component.translatable("tsu.tool.file_write_error").getString() : ex.getMessage()));
            }
        });
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
