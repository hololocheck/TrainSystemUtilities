package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: プリセット保存結果通知 (チャットではなく専用 HUD で表示)。
 */
public record TrainPresetSaveResultPayload(boolean success, String name, String detail)
        implements CustomPacketPayload {

    public static final Type<TrainPresetSaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_save_result"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetSaveResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeBoolean(p.success); buf.writeUtf(p.name); buf.writeUtf(p.detail); },
                    buf -> new TrainPresetSaveResultPayload(
                            buf.readBoolean(),
                            // P0-4 #7: preset name shown on HUD → 128 bytes (generic label cap)
                            BoundedStreamCodec.readBoundedUtf(buf, 128),
                            // P0-4 #7: detail message shown on HUD → 1024 bytes (long text cap)
                            BoundedStreamCodec.readBoundedUtf(buf, 1024)));

    public static void handle(TrainPresetSaveResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.trainsystemutilities.client.renderer.SaveResultHudRenderer
                    .show(payload.success, payload.name, payload.detail);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
