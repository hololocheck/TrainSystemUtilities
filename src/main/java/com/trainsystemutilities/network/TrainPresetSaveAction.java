package com.trainsystemutilities.network;

import com.manta.api.util.safe.SafeLog;
import com.manta.api.util.safe.SafeName;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetCapture;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 列車プリセット保存リクエスト — the tools host's {@code preset-save} action ({@link ToolsData};
 * TrainPresetSavePayload until MANTA_7_CONCEPT C4).
 * クライアントの GUI 「保存」ボタンから送られる。サーバ側で範囲スキャンを行いファイル化する。
 *
 * pos1/pos2 はサーバー側の ItemStack DataComponent から取得するため、action に含めない
 * (改ざん防止)。
 */
public final class TrainPresetSaveAction {

    /**
     * The save screen's name input holds this many code points. tools-state.json declares the name 12 bytes per
     * code point: the codec counts UTF-8 bytes after NFC, which grows a code point to at most three four-byte ones.
     */
    public static final int MAX_NAME_CODE_POINTS = 64;

    private TrainPresetSaveAction() {}

    public static void apply(ServerPlayer player, String presetName) {
        // P0-4 #7: validate preset display name (user-controlled). Fall back to default if rejected
        // so the handler can still proceed instead of silently failing.
        String safePresetName;
        try {
            SafeName.validate(presetName);
            safePresetName = presetName;
        } catch (Exception ex) {
            TrainSystemUtilities.LOGGER.warn(
                    "[security] rejected preset_save name from {}: {}",
                    player.getName().getString(), SafeLog.sanitize(presetName));
            safePresetName = "preset";
        }
        final String effectivePresetName = safePresetName;

        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) {
            com.manta.api.net.Notify.error(player, effectivePresetName,
                    net.minecraft.network.chat.Component.translatable("tsu.tool.tool_not_held").getString());
            return;
        }
        BlockPos p1 = TrainPresetToolItem.getPos1(stack);
        BlockPos p2 = TrainPresetToolItem.getPos2(stack);
        if (p1 == null || p2 == null) {
            com.manta.api.net.Notify.error(player, effectivePresetName,
                    net.minecraft.network.chat.Component.translatable("tsu.tool.pos_unset").getString());
            return;
        }

        TrainPreset preset = TrainPresetCapture.capture(
                player.serverLevel(), p1, p2,
                effectivePresetName, player.getName().getString(), player.getUUID());
        if (preset == null || preset.isEmpty()) {
            com.manta.api.net.Notify.error(player, effectivePresetName,
                    net.minecraft.network.chat.Component.translatable("tsu.tool.range_too_large").getString());
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
            com.manta.api.net.Notify.success(player, effectivePresetName, detail);
            TrainSystemUtilities.LOGGER.info("Preset saved: {}", path.getFileName());
        } catch (Exception ex) {
            TrainSystemUtilities.LOGGER.error("Preset save failed", ex);
            com.manta.api.net.Notify.error(player, effectivePresetName,
                    ex.getMessage() == null
                            ? net.minecraft.network.chat.Component.translatable("tsu.tool.file_write_error").getString()
                            : ex.getMessage());
        }
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
