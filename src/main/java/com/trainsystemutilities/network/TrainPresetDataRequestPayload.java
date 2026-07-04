package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetCodec;
import com.trainsystemutilities.preset.TrainPresetStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Client → Server: 個別プリセットの完全データ (palette + blocks + entities) を要求。
 * Server は読み込んだ NBT を CompoundTag として返す。
 */
public record TrainPresetDataRequestPayload(String authorDir, String fileName) implements CustomPacketPayload {

    public static final Type<TrainPresetDataRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_data_req"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetDataRequestPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeUtf(p.authorDir, 64);
                buf.writeUtf(p.fileName, 128);
            }, buf -> new TrainPresetDataRequestPayload(
                    // P0-4 #7: authorDir is a UUID-string author dir (generic name), bound to 128 bytes
                    BoundedStreamCodec.readBoundedUtf(buf, 128),
                    // P0-4 #7: fileName is a file name on disk, bound to SafePath.MAX_FILENAME_BYTES (=255)
                    BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES)));

    public static void handle(TrainPresetDataRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var server = player.getServer();
            if (server == null) return;
            // SECURITY: path traversal 防御 — 任意 .nbt ファイル読込・繰り返しDoS 抑止
            Path file = TrainPresetStorage.safeResolveExisting(server, payload.authorDir, payload.fileName);
            if (file == null) {
                // P0-4 #7: sanitize user-controlled authorDir/fileName before logging
                TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected preset_data_req with unsafe path from {}: authorDir={}, fileName={}",
                        player.getName().getString(),
                        SafeLog.sanitize(payload.authorDir),
                        SafeLog.sanitize(payload.fileName));
                return;
            }
            try {
                TrainPreset preset = TrainPresetStorage.load(file);
                var nbt = TrainPresetCodec.toNbt(preset);
                PacketDistributor.sendToPlayer(player, new TrainPresetDataResponsePayload(
                        payload.authorDir, payload.fileName, nbt));
            } catch (IOException ex) {
                // P0-4 #7: sanitize user-controlled authorDir/fileName before logging
                TrainSystemUtilities.LOGGER.warn("Preset data load failed for {}/{}: {}",
                        SafeLog.sanitize(payload.authorDir),
                        SafeLog.sanitize(payload.fileName),
                        ex.getMessage());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
