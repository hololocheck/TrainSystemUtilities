package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ImageUploadStartPayload(BlockPos pos, String fileName, int totalSize, int chunkCount)
        implements CustomPacketPayload {

    public static final Type<ImageUploadStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "image_upload_start"));

    public static final StreamCodec<FriendlyByteBuf, ImageUploadStartPayload> STREAM_CODEC =
            StreamCodec.of(ImageUploadStartPayload::write, ImageUploadStartPayload::read);

    private static void write(FriendlyByteBuf buf, ImageUploadStartPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeUtf(p.fileName);
        buf.writeInt(p.totalSize);
        buf.writeInt(p.chunkCount);
    }

    private static ImageUploadStartPayload read(FriendlyByteBuf buf) {
        // P0-4 #9: BoundedStreamCodec で fileName を MAX_FILENAME_BYTES (= 255) に bound、
        // totalSize / chunkCount は非負・上限内に限定。 handle() でさらに SafePath で
        // path traversal / Windows reserved を検証する 2-layer 防御。
        BlockPos pos = buf.readBlockPos();
        String fileName = BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES);
        int totalSize = BoundedStreamCodec.readBoundedInt(buf, 1, Integer.MAX_VALUE);
        int chunkCount = BoundedStreamCodec.readBoundedInt(buf, 1, ClientImageDataPayload.MAX_CHUNKS);
        return new ImageUploadStartPayload(pos, fileName, totalSize, chunkCount);
    }

    public static void handle(ImageUploadStartPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            // P0-4 #9: SafePath で path traversal / null byte / Windows reserved を検証。
            try {
                SafePath.validateFileName(payload.fileName);
            } catch (IllegalArgumentException ex) {
                TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected image upload (unsafe fileName) from {}: {}",
                        player.getName().getString(), SafeLog.sanitize(ex.getMessage()));
                return;
            }
            // SECURITY: 値域検証 — 負数 / 0 / 想定不整合の早期拒否 (NegativeArraySizeException 防止)
            int max = com.trainsystemutilities.storage.ImageStorage.MAX_IMAGE_SIZE;
            int chunkSize = ImageUploadChunkPayload.CHUNK_SIZE;
            if (payload.totalSize <= 0 || payload.totalSize > max) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "tsu.upload.image_too_large_detail_fmt", Math.max(0, payload.totalSize) / 1024 / 1024)
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
            // chunkCount は totalSize から自動算出される値と一致するべき。許容を chunkSize 単位で計算。
            int expectedChunks = (payload.totalSize + chunkSize - 1) / chunkSize;
            if (payload.chunkCount <= 0 || payload.chunkCount != expectedChunks) {
                TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected image upload start with bad chunkCount from {}: "
                                + "totalSize={}, chunkCount={}, expected={}",
                        player.getName().getString(), payload.totalSize,
                        payload.chunkCount, expectedChunks);
                return;
            }
            // SECURITY: BE への近接 + 開放中チェック
            BlockEntity be = player.level().getBlockEntity(payload.pos);
            if (!(be instanceof com.trainsystemutilities.blockentity.PosterManagementBlockEntity posterBE)) {
                return;
            }
            // 近接距離 (vanilla container と同じ 8 ブロック相当)
            double distSq = player.distanceToSqr(
                    payload.pos.getX() + 0.5, payload.pos.getY() + 0.5, payload.pos.getZ() + 0.5);
            if (distSq > 8.0 * 8.0) {
                TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected image upload start (too far) from {}: pos={}, distSq={}",
                        player.getName().getString(), payload.pos, distSq);
                return;
            }
            // SECURITY: private mode の poster は owner 以外の upload を拒否 (UI open と同じゲート)
            if (!posterBE.canAccess(player)) return;
            ImageUploadChunkPayload.startUpload(player.getUUID(), payload.pos,
                    payload.fileName, payload.totalSize, payload.chunkCount);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
