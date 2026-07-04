package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.gui.TrainPresetClientCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: 「このプリセットの不足アイテム」を返す。encoded は TrainPresetMaterials.encode 形式。
 */
public record TrainPresetMaterialCheckResponsePayload(String authorDir, String fileName, String missingEncoded)
        implements CustomPacketPayload {

    public static final Type<TrainPresetMaterialCheckResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_material_check_resp"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetMaterialCheckResponsePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeUtf(p.authorDir); buf.writeUtf(p.fileName); buf.writeUtf(p.missingEncoded); },
                    buf -> new TrainPresetMaterialCheckResponsePayload(
                            // P0-4 #7: authorDir — file name component, MAX_FILENAME_BYTES (255)
                            BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES),
                            // P0-4 #7: fileName — file name, MAX_FILENAME_BYTES (255)
                            BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES),
                            // P0-4 #7: missingEncoded — serialized config (Item id -> count), 4096 bytes
                            BoundedStreamCodec.readBoundedUtf(buf, 4096)));

    public static void handle(TrainPresetMaterialCheckResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            String key = payload.authorDir + "/" + payload.fileName;
            TrainPresetClientCache.setMissingForPreset(key, payload.missingEncoded);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
