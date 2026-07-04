package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeName;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 共有先 station の toggle (ON/OFF 反転)。
 * shareType 0=検知カード, 1=範囲指定ボード。
 * Server は対応する toggle メソッドを呼び、最新 sync を返送。
 */
public record AnnouncementShareTogglePayload(BlockPos pos, String stationName, byte shareType)
        implements CustomPacketPayload {

    public static final byte TYPE_DETECTION = 0;
    public static final byte TYPE_RANGE = 1;

    public static final Type<AnnouncementShareTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "announcement_share_toggle"));

    public static final StreamCodec<FriendlyByteBuf, AnnouncementShareTogglePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeUtf(p.stationName, 256);
                        buf.writeByte(p.shareType);
                    },
                    buf -> new AnnouncementShareTogglePayload(
                            buf.readBlockPos(),
                            // P0-4 #7: station name is a generic display label; 128 bytes is the
                            // standard generic-name cap and matches SafeName.DEFAULT_MAX_BYTES.
                            BoundedStreamCodec.readBoundedUtf(buf, 128),
                            buf.readByte())
            );

    public static void handle(AnnouncementShareTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.level().getBlockEntity(payload.pos)
                    instanceof RailwayManagementBlockEntity rmbe)) return;
            if (player.distanceToSqr(payload.pos.getX() + 0.5, payload.pos.getY() + 0.5,
                    payload.pos.getZ() + 0.5) > 64) return;
            if (!rmbe.canAccess(player)) return;
            if (payload.stationName == null || payload.stationName.isEmpty()) return;
            // P0-4 #7: station name is a user-supplied display name used as a config key.
            // Reject control chars / BiDi / oversized strings; silently drop the toggle on invalid input.
            try {
                SafeName.validate(payload.stationName);
            } catch (IllegalArgumentException e) {
                return;
            }

            var cfg = rmbe.getAnnouncementConfig();
            if (payload.shareType == TYPE_RANGE) {
                cfg.toggleRangeShared(payload.stationName);
            } else {
                cfg.toggleDetectionShared(payload.stationName);
            }
            rmbe.setAnnouncementConfig(cfg);
            PacketDistributor.sendToPlayer(player, new AnnouncementSyncPayload(
                    payload.pos, cfg.save()));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
