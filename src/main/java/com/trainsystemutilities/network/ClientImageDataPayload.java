package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.renderer.PosterTextureManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server → Client: 画像データのチャンク送信。
 */
public record ClientImageDataPayload(UUID imageId, int chunkIndex, int chunkCount, byte[] data)
        implements CustomPacketPayload {

    public static final int CHUNK_SIZE = 500 * 1024;
    /** P0-4 #10: chunk 数の上限。 256 × 500KB = 約 128MB / 画像。
     *  旧コードは無制限 (= 悪意ある server から {@code chunkCount=4000+} で 2GB+ allocate
     *  → client OOM crash)。 ポスター用途として 128MB は十分大きい。 */
    public static final int MAX_CHUNKS = 256;

    public static final Type<ClientImageDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "client_image_data"));

    public static final StreamCodec<FriendlyByteBuf, ClientImageDataPayload> STREAM_CODEC =
            StreamCodec.of(ClientImageDataPayload::write, ClientImageDataPayload::read);

    private static void write(FriendlyByteBuf buf, ClientImageDataPayload p) {
        buf.writeUUID(p.imageId);
        buf.writeInt(p.chunkIndex);
        buf.writeInt(p.chunkCount);
        buf.writeByteArray(p.data);
    }

    private static ClientImageDataPayload read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        // P0-4 #10: BoundedStreamCodec 経由で全数値 / バイト数を bounded 化。
        int chunkIndex = BoundedStreamCodec.readBoundedInt(buf, 0, MAX_CHUNKS - 1);
        int chunkCount = BoundedStreamCodec.readBoundedInt(buf, 1, MAX_CHUNKS);
        byte[] data = BoundedStreamCodec.readBoundedByteArray(buf, CHUNK_SIZE);
        return new ClientImageDataPayload(id, chunkIndex, chunkCount, data);
    }

    // クライアント側のチャンク組み立て
    private static final ConcurrentHashMap<UUID, DownloadSession> downloadSessions = new ConcurrentHashMap<>();

    public static void handle(ClientImageDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            DownloadSession session = downloadSessions.computeIfAbsent(payload.imageId,
                    id -> new DownloadSession(payload.chunkCount));

            int idx = payload.chunkIndex;
            // B3: per-index dedup。 chunkIndex は read() で bounded 済だが session 単位で再検証し、
            // 既受信 index は無視 (= out-of-order / 重複到着でも receivedCount を正しく数える)。
            if (idx < 0 || idx >= session.chunkCount || session.received[idx]) return;

            int offset = idx * CHUNK_SIZE;
            int len = Math.min(payload.data.length, session.buffer.length - offset);
            if (len > 0) System.arraycopy(payload.data, 0, session.buffer, offset, len);
            session.received[idx] = true;
            session.receivedCount++;
            // B3: 最終 index chunk の実長を記録 (= out-of-order でも totalSize を正しく出す。
            // 旧コードは last-arrived chunk の data.length を最終 chunk 長と誤用していた)。
            if (idx == session.chunkCount - 1) {
                session.finalChunkLen = payload.data.length;
            }

            if (session.receivedCount >= session.chunkCount) {
                downloadSessions.remove(payload.imageId);
                int finalLen = session.finalChunkLen >= 0 ? session.finalChunkLen : CHUNK_SIZE;
                int totalSize = (session.chunkCount - 1) * CHUNK_SIZE + finalLen;
                byte[] finalData = new byte[totalSize];
                System.arraycopy(session.buffer, 0, finalData, 0,
                        Math.min(totalSize, session.buffer.length));
                PosterTextureManager.onImageReceived(payload.imageId, finalData);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static class DownloadSession {
        final byte[] buffer;
        final int chunkCount;
        final boolean[] received;     // B3: per-index 受信フラグ (重複 / out-of-order に強い)
        int receivedCount;
        int finalChunkLen = -1;       // B3: 最終 index chunk の実長 (未受信なら -1)

        DownloadSession(int chunkCount) {
            // P0-4 #10: defense in depth — read() で MAX_CHUNKS をチェック済だが、
            // computeIfAbsent 経由でも到達するため再検証。
            if (chunkCount < 1 || chunkCount > MAX_CHUNKS) {
                throw new IllegalArgumentException("chunkCount " + chunkCount
                        + " out of range [1, " + MAX_CHUNKS + "]");
            }
            this.buffer = new byte[chunkCount * CHUNK_SIZE];
            this.chunkCount = chunkCount;
            this.received = new boolean[chunkCount];
            this.receivedCount = 0;
        }
    }
}
