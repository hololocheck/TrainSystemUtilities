package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: プリセット一覧 (header メタのみ)。
 * 詳細 (palette + blocks) は TrainPresetDataRequestPayload で個別に取得する。
 */
public record TrainPresetListResponsePayload(List<Item> items) implements CustomPacketPayload {

    public static final Type<TrainPresetListResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_list_resp"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetListResponsePayload> STREAM_CODEC =
            StreamCodec.of(TrainPresetListResponsePayload::write, TrainPresetListResponsePayload::read);

    public record Item(String name, String author,
                        int sizeX, int sizeY, int sizeZ,
                        int blockCount, int entityCount,
                        long createdMs,
                        String authorDir, String fileName,
                        /** プリセットプレイス由来 = アップロード抑止フラグ。 */
                        boolean imported) {}

    private static void write(FriendlyByteBuf buf, TrainPresetListResponsePayload p) {
        buf.writeVarInt(p.items.size());
        for (Item it : p.items) {
            buf.writeUtf(it.name, 64);
            buf.writeUtf(it.author, 64);
            buf.writeVarInt(it.sizeX);
            buf.writeVarInt(it.sizeY);
            buf.writeVarInt(it.sizeZ);
            buf.writeVarInt(it.blockCount);
            buf.writeVarInt(it.entityCount);
            buf.writeVarLong(it.createdMs);
            buf.writeUtf(it.authorDir, 64);
            buf.writeUtf(it.fileName, 128);
            buf.writeBoolean(it.imported);
        }
    }

    private static TrainPresetListResponsePayload read(FriendlyByteBuf buf) {
        // P0-4 #7: preset list length — cap at 4096 (large list of presets)
        int n = BoundedStreamCodec.readBoundedListLength(buf, 4096);
        List<Item> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Item(
                    // P0-4 #7: preset name — generic name, 128 bytes
                    BoundedStreamCodec.readBoundedUtf(buf, 128),
                    // P0-4 #7: author name — generic name, 128 bytes
                    BoundedStreamCodec.readBoundedUtf(buf, 128),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(),
                    buf.readVarLong(),
                    // P0-4 #7: authorDir — file name component, MAX_FILENAME_BYTES (255)
                    BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES),
                    // P0-4 #7: fileName — file name, MAX_FILENAME_BYTES (255)
                    BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES),
                    buf.readBoolean()));
        }
        return new TrainPresetListResponsePayload(list);
    }

    public static void handle(TrainPresetListResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // クライアント側のキャッシュへ反映
            try {
                Class<?> cache = Class.forName("com.trainsystemutilities.client.gui.TrainPresetClientCache");
                cache.getMethod("setList", List.class).invoke(null, payload.items);
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("Preset list cache update failed: {}", t.getMessage());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
