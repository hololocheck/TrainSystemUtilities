package com.trainsystemutilities.network;

import com.manta.api.util.safe.SafeLog;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * ブラウズ GUI の「設置」ボタンから送信 — the tools host's {@code preset-select} action ({@link ToolsData};
 * TrainPresetSelectPayload until MANTA_7_CONCEPT C4).
 * tool_mode を Place に切替、selected_preset に対象プリセットのファイル名を保存。
 * authorDir は file path 解決用。
 */
public final class TrainPresetSelectAction {

    /**
     * The browse screen sends an entry of the preset list, which the presets host ({@link PresetsData}) lists only
     * when its names have at most these many UTF-16 chars. The author directory (a UUID, ASCII) is declared 9 bytes per char: at
     * most 3 UTF-8 bytes per char, which NFC (the codec counts after it) grows at most threefold. The file name must
     * find the file byte for byte, so its param is declared exact ({@link ExactText}) - at most 3 UTF-8 bytes per char.
     */
    public static final int LISTED_AUTHOR_DIR_CHARS = 64;
    public static final int LISTED_FILE_NAME_CHARS = 128;

    private TrainPresetSelectAction() {}

    public static void apply(ServerPlayer player, String authorDir, String fileName) {
        var server = player.getServer();
        // SECURITY: 任意の authorDir/fileName が tool に書き込まれないよう厳格検証。
        // ここで通すと TrainPresetToolItem 経由で path traversal 由来の任意 NBT
        // 読込・設置誘導を許す。
        if (server == null
                || com.trainsystemutilities.preset.TrainPresetStorage.safeResolveExisting(
                        server, authorDir, fileName) == null) {
            // P0-4 #7: sanitize user-controlled path components before logging (prevent log injection).
            TrainSystemUtilities.LOGGER.warn(
                    "[security] rejected preset_select with unsafe path from {}: authorDir={}, fileName={}",
                    player.getName().getString(),
                    SafeLog.sanitize(authorDir),
                    SafeLog.sanitize(fileName));
            player.displayClientMessage(Component.translatable("tsu.preset.delete_failed")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) {
            player.displayClientMessage(Component.translatable("tsu.preset.no_tool_held").withStyle(ChatFormatting.RED), true);
            return;
        }
        // selected_preset に "authorDir/fileName" 形式で保存
        String key = authorDir + "/" + fileName;
        stack.set(ModDataComponents.SELECTED_PRESET.get(), key);
        // 前プリセットの bogey 配置から算出された自動回転値を破棄。
        // 残したままだと新プリセットに古い回転が適用され、180° 反転して設置される。
        stack.remove(ModDataComponents.PLACE_AUTO_ROT_Y.get());
        stack.remove(ModDataComponents.PLACE_ORIGIN.get());
        stack.remove(ModDataComponents.PLACE_MARKER_DIRECTION.get());
        TrainPresetToolItem.setPlaceRotY(stack, 0);
        TrainPresetToolItem.setPlaceSubMode(stack, TrainPresetToolItem.SUB_ORIGIN);
        TrainPresetToolItem.setToolMode(stack, TrainPresetToolItem.TOOL_MODE_PLACE);
        player.displayClientMessage(
                Component.translatable("tsu.preset.selected_for_place_fmt", fileName)
                        .withStyle(ChatFormatting.AQUA), true);
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return com.manta.api.hud.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
