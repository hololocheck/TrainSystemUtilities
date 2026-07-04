package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Client → Server: プリセット一覧要求。
 * サーバはディスクから一覧をスキャンし、TrainPresetListResponsePayload で返す。
 */
public record TrainPresetListRequestPayload() implements CustomPacketPayload {

    public static final Type<TrainPresetListRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_list_req"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetListRequestPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {}, buf -> new TrainPresetListRequestPayload());

    public static void handle(TrainPresetListRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var server = player.getServer();
            if (server == null) return;
            var entries = TrainPresetStorage.listAll(server);
            List<TrainPresetListResponsePayload.Item> items = new ArrayList<>();
            for (var e : entries) {
                try {
                    TrainPreset header = TrainPresetStorage.loadHeader(e.file());
                    items.add(new TrainPresetListResponsePayload.Item(
                            header.name == null || header.name.isEmpty() ? e.name() : header.name,
                            header.author == null ? "" : header.author,
                            header.sizeX, header.sizeY, header.sizeZ,
                            header.blocks.size(),
                            header.entities.size(),
                            header.createdEpochMs,
                            e.authorDir(),
                            e.file().getFileName().toString(),
                            header.importedFromPresetId != null
                                    && !header.importedFromPresetId.isEmpty()));
                } catch (IOException ex) {
                    TrainSystemUtilities.LOGGER.warn("Failed to read preset header {}: {}",
                            e.file(), ex.getMessage());
                }
            }
            PacketDistributor.sendToPlayer(player, new TrainPresetListResponsePayload(items));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
