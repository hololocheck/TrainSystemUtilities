package com.trainsystemutilities.network;

import com.manta.api.util.safe.SafeLog;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPresetStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * プリセット削除 — the player's presets host's {@code delete} action ({@link PresetsData}; TrainPresetDeletePayload until
 * MANTA_7_CONCEPT C4). 自分が author の (authorDir == 自プレイヤー UUID) ファイルのみ削除可能。
 * OP 権限プレイヤーは任意のファイルを削除可能。 The body is the payload's handler; where it sent the new list it
 * refreshes the presets host (a new scan, answered as page 0).
 */
public final class TrainPresetDeleteAction {

    private TrainPresetDeleteAction() {}

    public static void apply(ServerPlayer player, String authorDir, String fileName) {
        var server = player.getServer();
        if (server == null) return;
        // 権限チェック: 自分の author dir or OP
        boolean isOwn = authorDir.equals(player.getUUID().toString());
        boolean isOp = player.hasPermissions(2);
        if (!isOwn && !isOp) {
            player.displayClientMessage(Component.translatable("tsu.preset.delete_others_forbidden")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        // SECURITY: path traversal 防御 — authorDir + fileName を厳格に検証して
        // root 内に収まることを保証してから resolve する。これがないと
        // fileName="../../../level.dat" 等で任意ファイル削除を許す。
        Path file = TrainPresetStorage.safeResolveExisting(server, authorDir, fileName);
        if (file == null) {
            // P0-4 #7: sanitize user-controlled authorDir/fileName before logging
            TrainSystemUtilities.LOGGER.warn(
                    "[security] rejected delete with unsafe path from {}: authorDir={}, fileName={}",
                    player.getName().getString(),
                    SafeLog.sanitize(authorDir),
                    SafeLog.sanitize(fileName));
            player.displayClientMessage(Component.translatable("tsu.preset.delete_failed")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (TrainPresetStorage.delete(file)) {
            player.displayClientMessage(Component.translatable("tsu.preset.delete_success_fmt", fileName)
                    .withStyle(ChatFormatting.YELLOW), true);
            // 一覧を再生成して送り直す
            PresetsData.refresh(player);
        } else {
            player.displayClientMessage(Component.translatable("tsu.preset.delete_failed").withStyle(ChatFormatting.RED), true);
        }
    }
}
