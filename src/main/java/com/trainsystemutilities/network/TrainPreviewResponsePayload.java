package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.preview.TrainPreviewCache;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: gzipped 列車 contraption データ。
 * Client は {@link TrainPreviewCache#receive} に渡してデコード + キャッシュ。
 */
public record TrainPreviewResponsePayload(UUID trainId, byte[] gzippedData)
        implements CustomPacketPayload {

    public static final Type<TrainPreviewResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "train_preview_resp"));

    /** 受信側で巨大 payload を弾く防御的上限 (= サーバ送信側 cap と一致)。 */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    public static final StreamCodec<FriendlyByteBuf, TrainPreviewResponsePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.trainId);
                        buf.writeVarInt(p.gzippedData.length);
                        buf.writeBytes(p.gzippedData);
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        // P0-4 #7: gzipped contraption NBT payload → cap matches server send-side limit (2 MiB).
                        // Wire format is identical to the previous writeVarInt(len) + writeBytes(...) pair,
                        // so this stays binary-compatible with the unchanged write side.
                        byte[] data = BoundedStreamCodec.readBoundedByteArray(buf, MAX_BYTES);
                        return new TrainPreviewResponsePayload(id, data);
                    }
            );

    public static void handle(TrainPreviewResponsePayload payload, IPayloadContext context) {
        // client-side のみ。Minecraft thread に再投入してから cache 更新。
        Minecraft.getInstance().execute(() ->
                TrainPreviewCache.receive(payload.trainId, payload.gzippedData));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
