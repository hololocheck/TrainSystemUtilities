package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TrainPresetPlaceResultPayload(String title, String detail) implements CustomPacketPayload {

    public static final Type<TrainPresetPlaceResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_place_result"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetPlaceResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUtf(payload.title);
                        buf.writeUtf(payload.detail);
                    },
                    buf -> new TrainPresetPlaceResultPayload(
                            // P0-4 #7: title is a short label shown on HUD → 128 bytes
                            BoundedStreamCodec.readBoundedUtf(buf, 128),
                            // P0-4 #7: detail is a longer descriptive line for the HUD → 1024 bytes
                            BoundedStreamCodec.readBoundedUtf(buf, 1024)));

    public static void handle(TrainPresetPlaceResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.trainsystemutilities.client.renderer.TrainPresetPlaceResultHudRenderer.show(
                        payload.title, payload.detail));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
