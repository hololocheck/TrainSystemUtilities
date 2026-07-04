package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client → Server: 乗換案内端末から経路検索リクエスト。 */
public record TransitSearchPayload(UUID fromGroupId, UUID toGroupId) implements CustomPacketPayload {

    public static final Type<TransitSearchPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "transit_search"));

    public static final StreamCodec<FriendlyByteBuf, TransitSearchPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeUUID(p.fromGroupId); buf.writeUUID(p.toGroupId); },
                    buf -> new TransitSearchPayload(buf.readUUID(), buf.readUUID()));

    /** P0-4 #13: 経路探索 DoS 防御。 player UUID → 直近 search 開始時刻 (nano)。
     *  ComposedRouteFinder は重い処理 (= 全駅 BFS + 時刻表 join) で連射されると server tick lag。 */
    private static final ConcurrentHashMap<UUID, Long> LAST_SEARCH_NS = new ConcurrentHashMap<>();
    /** 同一プレイヤーは 5 秒に 1 回まで。 */
    private static final long SEARCH_INTERVAL_NS = 5L * 1_000_000_000L;

    public static void send(UUID from, UUID to) {
        if (from == null || to == null) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new TransitSearchPayload(from, to));
    }

    public static void handle(TransitSearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // P0-4 #13: atomic check-then-set で per-player 5 秒 rate limit (DoS 防御)。
            long now = System.nanoTime();
            Long updated = LAST_SEARCH_NS.compute(player.getUUID(), (k, prev) -> {
                if (prev != null && now - prev < SEARCH_INTERVAL_NS) return prev;
                return now;
            });
            if (updated != now) {
                TrainSystemUtilities.LOGGER.info(
                        "[TransitSearch] rate-limited from {}", player.getName().getString());
                return;
            }
            long dayTime = player.serverLevel().getDayTime();
            // 上位 3 候補を取得
            var multi = ComposedRouteFinder.findMultiple(
                    player.server, player.serverLevel(), player.blockPosition(),
                    payload.fromGroupId, payload.toGroupId, 3);
            String reason = "";
            java.util.List<TransitResultPayload.RouteData> rds = new java.util.ArrayList<>();
            if (multi.isEmpty()) {
                // フォールバック: 単一探索でメッセージ取得
                var single = ComposedRouteFinder.find(player.server, player.serverLevel(),
                        player.blockPosition(), payload.fromGroupId, payload.toGroupId);
                reason = single.reason() == null ? "" : single.reason();
                if (single.found()) multi = java.util.List.of(single);
            }
            for (var route : multi) {
                rds.add(new TransitResultPayload.RouteData(
                        route.totalTicks(),
                        route.fromGroupId(),
                        route.fromGroupName() == null ? "" : route.fromGroupName(),
                        route.toGroupId(),
                        route.toGroupName() == null ? "" : route.toGroupName(),
                        route.walkToFrom() == null ? 0 : route.walkToFrom().approxTicks(),
                        serializeLegs(route.trainLegs())));
            }
            PacketDistributor.sendToPlayer(player, new TransitResultPayload(rds, reason, dayTime));
        });
    }

    private static String serializeLegs(java.util.List<com.trainsystemutilities.station.routing.TrainRouter.Leg> legs) {
        // フォーマット: from|to|train|travel|dep|board|alight|symL|symN|symC|samples|stdDev;...
        StringBuilder sb = new StringBuilder();
        for (var l : legs) {
            if (sb.length() > 0) sb.append(';');
            sb.append(l.fromGroupId()).append('|').append(l.toGroupId()).append('|')
              .append(l.trainId() == null ? "" : l.trainId()).append('|')
              .append(l.travelTicks()).append('|')
              .append(l.departureTicksFromNow()).append('|')
              .append(l.boardPlatform()).append('|')
              .append(l.alightPlatform()).append('|')
              .append(l.symbolLetters() == null ? "" : l.symbolLetters()).append('|')
              .append(l.symbolNumber()).append('|')
              .append(l.symbolColor() == null ? "" : l.symbolColor()).append('|')
              .append(l.sampleCount()).append('|')
              .append(l.stdDevTicks()).append('|')
              .append(l.delayTicks());
        }
        return sb.toString();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
