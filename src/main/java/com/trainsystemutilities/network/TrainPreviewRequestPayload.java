package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client → Server: 列車の contraption ブロックデータを要求。
 * Server は {@link com.trainsystemutilities.preview.TrainPreviewSnapshotStore} の
 * snapshot を gzipped NBT として {@link TrainPreviewResponsePayload} で返送する。
 *
 * <p>Snapshot は組立時 (= 列車が Create.RAILWAYS.trains に追加されてから 5秒以内の server tick) で
 * 自動 capture されるため、player と列車の距離に関係なく取得可能。
 *
 * <p>Payload 安全性:
 * <ul>
 *   <li>per-player rate limit (1 train / 200ms) で DoS 防御</li>
 *   <li>応答 payload size cap = 2 MiB</li>
 * </ul>
 */
public record TrainPreviewRequestPayload(UUID trainId) implements CustomPacketPayload {

    public static final Type<TrainPreviewRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "train_preview_req"));

    public static final StreamCodec<FriendlyByteBuf, TrainPreviewRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUUID(p.trainId),
                    buf -> new TrainPreviewRequestPayload(buf.readUUID())
            );

    /**
     * per-player rate limit: 同 player は 50ms に 1 回まで request 受理 (= 20 req/sec)。
     * 100 列車を pre-fetch する場合 ~5秒で全列車キャッシュ完了。
     */
    private static final Map<UUID, Long> lastRequestNanosPerPlayer = new HashMap<>();
    private static final long RATE_LIMIT_NS = 50_000_000L;

    public static void handle(TrainPreviewRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            long now = System.nanoTime();
            Long last = lastRequestNanosPerPlayer.get(player.getUUID());
            if (last != null && now - last < RATE_LIMIT_NS) return;
            lastRequestNanosPerPlayer.put(player.getUUID(), now);
            if (lastRequestNanosPerPlayer.size() > 1024) {
                lastRequestNanosPerPlayer.entrySet().removeIf(e -> now - e.getValue() > 60_000_000_000L);
            }

            // Snapshot store から取得 (= 距離無関係に常に最新の組立内容)。
            // server tick で 5秒ごと自動更新されている。なければ即時 capture を試みる。
            byte[] data = com.trainsystemutilities.preview.TrainPreviewSnapshotStore.get(payload.trainId);
            if (data == null) {
                data = com.trainsystemutilities.preview.TrainPreviewSnapshotStore.captureNow(
                        payload.trainId, player.registryAccess());
            }
            if (data == null) {
                TrainSystemUtilities.LOGGER.debug(
                        "[TrainPreviewRequest] no snapshot for train {} (assembly may be in progress)",
                        payload.trainId);
                return;
            }
            if (data.length > 2 * 1024 * 1024) {
                TrainSystemUtilities.LOGGER.warn(
                        "[TrainPreviewRequest] train {} payload too large ({} bytes), skipping",
                        payload.trainId, data.length);
                return;
            }
            PacketDistributor.sendToPlayer(player, new TrainPreviewResponsePayload(payload.trainId, data));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
