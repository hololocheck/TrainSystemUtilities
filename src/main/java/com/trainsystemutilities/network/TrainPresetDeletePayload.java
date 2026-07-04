package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPresetStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.file.Path;

/**
 * Client → Server: プリセット削除リクエスト。
 * 自分が author の (authorDir == 自プレイヤー UUID) ファイルのみ削除可能。
 * OP 権限プレイヤーは任意のファイルを削除可能。
 */
public record TrainPresetDeletePayload(String authorDir, String fileName) implements CustomPacketPayload {

    public static final Type<TrainPresetDeletePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_delete"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetDeletePayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeUtf(p.authorDir, 64);
                buf.writeUtf(p.fileName, 128);
            }, buf -> new TrainPresetDeletePayload(
                    // P0-4 #7: authorDir is a UUID-string author dir (generic name), bound to 128 bytes
                    BoundedStreamCodec.readBoundedUtf(buf, 128),
                    // P0-4 #7: fileName is a file name on disk, bound to SafePath.MAX_FILENAME_BYTES (=255)
                    BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES)));

    public static void handle(TrainPresetDeletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var server = player.getServer();
            if (server == null) return;

            // 権限チェック: 自分の author dir or OP
            boolean isOwn = payload.authorDir.equals(player.getUUID().toString());
            boolean isOp = player.hasPermissions(2);
            if (!isOwn && !isOp) {
                player.displayClientMessage(Component.translatable("tsu.preset.delete_others_forbidden")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }

            // SECURITY: path traversal 防御 — authorDir + fileName を厳格に検証して
            // root 内に収まることを保証してから resolve する。これがないと
            // fileName="../../../level.dat" 等で任意ファイル削除を許す。
            Path file = TrainPresetStorage.safeResolveExisting(server, payload.authorDir, payload.fileName);
            if (file == null) {
                // P0-4 #7: sanitize user-controlled authorDir/fileName before logging
                TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected delete with unsafe path from {}: authorDir={}, fileName={}",
                        player.getName().getString(),
                        SafeLog.sanitize(payload.authorDir),
                        SafeLog.sanitize(payload.fileName));
                player.displayClientMessage(Component.translatable("tsu.preset.delete_failed")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            if (TrainPresetStorage.delete(file)) {
                player.displayClientMessage(Component.translatable("tsu.preset.delete_success_fmt", payload.fileName)
                        .withStyle(ChatFormatting.YELLOW), true);
                // 一覧を再生成して送り直す
                var entries = TrainPresetStorage.listAll(server);
                java.util.List<TrainPresetListResponsePayload.Item> items = new java.util.ArrayList<>();
                for (var e : entries) {
                    try {
                        var header = TrainPresetStorage.loadHeader(e.file());
                        items.add(new TrainPresetListResponsePayload.Item(
                                header.name == null || header.name.isEmpty() ? e.name() : header.name,
                                header.author == null ? "" : header.author,
                                header.sizeX, header.sizeY, header.sizeZ,
                                header.blocks.size(), header.entities.size(),
                                header.createdEpochMs,
                                e.authorDir(), e.file().getFileName().toString(),
                                header.importedFromPresetId != null
                                        && !header.importedFromPresetId.isEmpty()));
                    } catch (Exception ignored) { TrainSystemUtilities.LOGGER.debug("[Preset] list header load failed during delete", ignored); }
                }
                PacketDistributor.sendToPlayer(player, new TrainPresetListResponsePayload(items));
            } else {
                player.displayClientMessage(Component.translatable("tsu.preset.delete_failed").withStyle(ChatFormatting.RED), true);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
