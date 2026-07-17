package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.PosterManagementBlockEntity;
import com.trainsystemutilities.storage.ImageStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record ImageUploadChunkPayload(int chunkIndex, byte[] data)
        implements CustomPacketPayload {

    public static final int CHUNK_SIZE = 500 * 1024;

    public static final Type<ImageUploadChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "image_upload_chunk"));

    public static final StreamCodec<FriendlyByteBuf, ImageUploadChunkPayload> STREAM_CODEC =
            StreamCodec.of(ImageUploadChunkPayload::write, ImageUploadChunkPayload::read);

    private static void write(FriendlyByteBuf buf, ImageUploadChunkPayload p) {
        buf.writeInt(p.chunkIndex);
        buf.writeByteArray(p.data);
    }

    private static ImageUploadChunkPayload read(FriendlyByteBuf buf) {
        // P0-4 #7: chunkIndex は index なので 0..1_000_000 に制限 (負値や巨大値で OOM / バッファ外書込を防ぐ)
        int chunkIndex = BoundedStreamCodec.readBoundedInt(buf, 0, 1_000_000);
        // P0-4 #7: chunk data は CHUNK_SIZE + 1024 を上限とする (既存の wire 上限を維持)
        byte[] data = BoundedStreamCodec.readBoundedByteArray(buf, CHUNK_SIZE + 1024);
        return new ImageUploadChunkPayload(chunkIndex, data);
    }

    private static final ConcurrentHashMap<UUID, UploadSession> activeSessions = new ConcurrentHashMap<>();
    private static final long SESSION_TIMEOUT_MS = 30_000;

    /** SECURITY (TSU-06): logout 時に未完了 upload session を破棄 (server memory leak 防止)。 */
    public static void clearForPlayer(UUID playerId) {
        activeSessions.remove(playerId);
    }

    static void startUpload(UUID playerId, BlockPos pos, String fileName, int totalSize, int chunkCount) {
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> now - e.getValue().createdAt > SESSION_TIMEOUT_MS);
        activeSessions.put(playerId, new UploadSession(pos, fileName, totalSize, chunkCount));
    }

    public static void handle(ImageUploadChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            UploadSession session = activeSessions.get(player.getUUID());
            if (session == null) return;

            // SECURITY: chunkIndex \u3092\u53b3\u683c\u30c1\u30a7\u30c3\u30af (\u8ca0\u6570 / \u7bc4\u56f2\u5916\u3067\u30bb\u30c3\u30b7\u30e7\u30f3 buffer \u5916\u66f8\u8fbc\u307f\u9632\u6b62)
            if (payload.chunkIndex < 0 || payload.chunkIndex >= session.chunkCount) {
                return;
            }
            int offset = payload.chunkIndex * CHUNK_SIZE;
            if (offset < 0 || offset >= session.buffer.length) return;
            // SECURITY: chunk data \u3082\u4e0a\u9650\u30c1\u30a7\u30c3\u30af (CHUNK_SIZE \u8d85\u306f\u8aad\u307f\u53d6\u308a\u6bb5\u3067 reject \u6e08\u307f\u3060\u304c\u5ff5\u306e\u305f\u3081)
            if (payload.data == null || payload.data.length > CHUNK_SIZE) return;
            int len = Math.min(payload.data.length, session.buffer.length - offset);
            if (len > 0) System.arraycopy(payload.data, 0, session.buffer, offset, len);
            // SECURITY (TSU-05): 同一 chunkIndex の再送を別 chunk として数えない。
            // 重複加算は未受信領域を zero のまま completion に到達させ、破損画像を保存しうる。
            if (session.received[payload.chunkIndex]) return;
            session.received[payload.chunkIndex] = true;
            session.receivedChunks++;

            if (session.receivedChunks >= session.chunkCount) {
                activeSessions.remove(player.getUUID());

                String sizeError = ImageStorage.validateSize(session.buffer);
                if (sizeError != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a7c" + sizeError));
                    return;
                }

                var server = player.getServer();
                if (server == null) return;

                // SECURITY: \u5b8c\u4e86\u6642\u306b\u3082\u8fd1\u63a5 + BE \u5b58\u5728\u3092\u518d\u78ba\u8a8d (start \u6642\u304b\u3089\u72b6\u6cc1\u304c\u5909\u308f\u308b\u53ef\u80fd\u6027)
                BlockEntity entity = player.level().getBlockEntity(session.pos);
                if (!(entity instanceof PosterManagementBlockEntity posterBE)) return;
                if (!posterBE.canAccess(player)) return;
                double distSq = player.distanceToSqr(
                        session.pos.getX() + 0.5, session.pos.getY() + 0.5, session.pos.getZ() + 0.5);
                if (distSq > 8.0 * 8.0) {
                    TrainSystemUtilities.LOGGER.warn(
                            "[security] rejected image upload finalize (too far) from {}: pos={}",
                            player.getName().getString(), session.pos);
                    return;
                }

                // SECURITY (TSU-STORAGE-001): magic + decode \u5f8c dimension \u3092\u691c\u8a3c (client \u5074 decode \u306e
                // native allocation \u7206\u767a\u3092\u9632\u3050)\u3002\u672a\u8a8d\u8b58/\u5de8\u5927\u5bf8\u6cd5\u306f\u4fdd\u5b58\u524d\u306b\u62d2\u5426\u3002
                String contentError = ImageStorage.validateImageContent(session.buffer);
                if (contentError != null) {
                    TrainSystemUtilities.LOGGER.warn("[security] rejected image upload ({}) from {}",
                            contentError, player.getName().getString());
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "tsu.upload.image_invalid").withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
                // SECURITY (TSU-STORAGE-001): per-poster \u679a\u6570\u4e0a\u9650\u3002
                if (posterBE.getImageIds().size() >= ImageStorage.MAX_POSTER_IMAGES) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "tsu.upload.poster_full").withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
                // SECURITY (TSU-STORAGE-001): world \u5168\u4f53\u306e disk quota\u3002
                if (ImageStorage.totalBytes(server) + session.buffer.length > ImageStorage.MAX_WORLD_IMAGE_BYTES) {
                    TrainSystemUtilities.LOGGER.warn(
                            "[security] rejected image upload (world image quota exceeded) from {}",
                            player.getName().getString());
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "tsu.upload.world_quota").withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }

                UUID imageId = ImageStorage.save(server, session.buffer, session.fileName);
                if (imageId == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("tsu.upload.image_save_fail").withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
                posterBE.addImageId(imageId, session.fileName);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static class UploadSession {
        final BlockPos pos;
        final String fileName;
        final byte[] buffer;
        final int chunkCount;
        final boolean[] received;
        int receivedChunks;
        final long createdAt;

        UploadSession(BlockPos pos, String fileName, int totalSize, int chunkCount) {
            this.pos = pos;
            this.fileName = fileName;
            this.buffer = new byte[totalSize];
            this.chunkCount = chunkCount;
            this.received = new boolean[Math.max(0, chunkCount)];
            this.receivedChunks = 0;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
