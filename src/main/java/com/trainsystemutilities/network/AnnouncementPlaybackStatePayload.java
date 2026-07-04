package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.gui.RailwayAnnouncementClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> Client: currently playing announcement entry for one railway
 * management block. entryIdx is -1 when nothing is actively playing.
 */
public record AnnouncementPlaybackStatePayload(BlockPos pos, int entryIdx, boolean playing)
        implements CustomPacketPayload {

    public static final Type<AnnouncementPlaybackStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "announcement_playback_state"));

    public static final StreamCodec<FriendlyByteBuf, AnnouncementPlaybackStatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeInt(p.entryIdx);
                        buf.writeBoolean(p.playing);
                    },
                    buf -> new AnnouncementPlaybackStatePayload(
                            buf.readBlockPos(),
                            // P0-4 #7: entryIdx is an announcement list index; -1 means "not playing".
                            // Bound to [-1, 1_000_000] to reject malicious oversized values.
                            BoundedStreamCodec.readBoundedInt(buf, -1, 1_000_000),
                            buf.readBoolean())
            );

    public static void handle(AnnouncementPlaybackStatePayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() ->
                RailwayAnnouncementClientState.setPlayingEntry(
                        payload.pos, payload.playing ? payload.entryIdx : -1));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
