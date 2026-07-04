package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.storage.ImageStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → Server: 画像データのダウンロード要求。
 * クライアントがモニター描画時にキャッシュにない画像を要求する。
 */
public record ImageDownloadRequestPayload(UUID imageId) implements CustomPacketPayload {

    public static final Type<ImageDownloadRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "image_download_request"));

    public static final StreamCodec<FriendlyByteBuf, ImageDownloadRequestPayload> STREAM_CODEC =
            StreamCodec.of(ImageDownloadRequestPayload::write, ImageDownloadRequestPayload::read);

    private static void write(FriendlyByteBuf buf, ImageDownloadRequestPayload p) {
        buf.writeUUID(p.imageId);
    }

    private static ImageDownloadRequestPayload read(FriendlyByteBuf buf) {
        return new ImageDownloadRequestPayload(buf.readUUID());
    }

    public static void handle(ImageDownloadRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            var server = serverPlayer.getServer();
            if (server == null) return;

            byte[] data = ImageStorage.load(server, payload.imageId);
            if (data == null) {
                TrainSystemUtilities.LOGGER.warn("[Poster] download request {}: no stored image file (orphaned id?)", payload.imageId);
                return;
            }

            // チャンク分割してクライアントに送信
            int chunkSize = ClientImageDataPayload.CHUNK_SIZE;
            int chunkCount = (data.length + chunkSize - 1) / chunkSize;

            for (int i = 0; i < chunkCount; i++) {
                int offset = i * chunkSize;
                int len = Math.min(chunkSize, data.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(data, offset, chunk, 0, len);
                PacketDistributor.sendToPlayer(serverPlayer,
                        new ClientImageDataPayload(payload.imageId, i, chunkCount, chunk));
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
