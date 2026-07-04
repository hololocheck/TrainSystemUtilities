package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: プリセット完全データ (palette + blocks + entities) NBT。
 * クライアントは TrainPresetClientCache に保存して 3D プレビュー描画に使う。
 */
public record TrainPresetDataResponsePayload(String authorDir, String fileName, CompoundTag presetNbt)
        implements CustomPacketPayload {

    public static final Type<TrainPresetDataResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_data_resp"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetDataResponsePayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeUtf(p.authorDir, 64);
                buf.writeUtf(p.fileName, 128);
                buf.writeNbt(p.presetNbt);
            }, buf -> new TrainPresetDataResponsePayload(
                    // P0-4 #7: authorDir is a UUID-string author dir (generic name), bound to 128 bytes
                    BoundedStreamCodec.readBoundedUtf(buf, 128),
                    // P0-4 #7: fileName is a file name on disk, bound to SafePath.MAX_FILENAME_BYTES (=255)
                    BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES),
                    buf.readNbt()));

    public static void handle(TrainPresetDataResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (payload.presetNbt == null) return;
                TrainPreset preset = TrainPresetCodec.fromNbt(payload.presetNbt);
                String key = payload.authorDir + "/" + payload.fileName;
                Class<?> cache = Class.forName(
                        "com.trainsystemutilities.client.gui.TrainPresetClientCache");
                cache.getMethod("setPresetData", String.class, TrainPreset.class)
                        .invoke(null, key, preset);
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("Preset data cache update failed: {}", t.getMessage());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
