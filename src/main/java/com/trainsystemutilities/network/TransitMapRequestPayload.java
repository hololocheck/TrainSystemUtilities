package com.trainsystemutilities.network;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client → Server: 全 trackNetworks のポリラインを要求 (MAP タブ用)。
 *
 * <p>Server は Create の {@link com.simibubi.create.Create#RAILWAYS}.trackNetworks 配下の
 * 全グラフをスキャンし、各エッジを 2D ポリライン (x, z 座標列) に変換して
 * {@link TransitMapPayload} で返す。
 *
 * <p>負荷を抑えるため、polyline を 1 セグメント (始点+終点 = 2 点) のみで送る簡易版。
 * 直線近似だが、将来的にカーブをサンプリングして高密度送信に切り替え可。
 */
public record TransitMapRequestPayload() implements CustomPacketPayload {

    public static final Type<TransitMapRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "transit_map_req"));

    public static final StreamCodec<FriendlyByteBuf, TransitMapRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new TransitMapRequestPayload());

    public static void send() {
        PacketDistributor.sendToServer(new TransitMapRequestPayload());
    }

    // TSU-19: 全 trackNetworks の graph scan + curve サンプリングで重い request。 MAP タブ操作で送られる (低頻度)。
    // abuse client 向けに per-player の最小間隔を強制する。 250ms = legit な tab 操作を壊さず spam を遮断。
    private static final java.util.Map<java.util.UUID, Long> LAST_REQ_NS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_NS = 250_000_000L; // 250ms

    public static void handle(TransitMapRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            // TSU-19: per-player rate limit (atomic compute で TOCTOU 回避)。 超過は silently drop。
            long now = System.nanoTime();
            Long updated = LAST_REQ_NS.compute(player.getUUID(), (k, prev) ->
                    (prev != null && now - prev < MIN_INTERVAL_NS) ? prev : now);
            if (updated != now) return;
            List<int[]> segments = new ArrayList<>();
            try {
                if (com.simibubi.create.Create.RAILWAYS != null
                        && com.simibubi.create.Create.RAILWAYS.trackNetworks != null) {
                    for (TrackGraph graph : com.simibubi.create.Create.RAILWAYS.trackNetworks.values()) {
                        collectGraphSegments(graph, segments);
                        if (segments.size() >= 4096) break; // packet サイズ上限保護
                    }
                }
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("[TransitMap] scan failed: {}", t.toString());
            }
            PacketDistributor.sendToPlayer(player, new TransitMapPayload(segments));
        });
    }

    /**
     * 1 グラフから (x1, z1, x2, z2) 配列を集める。
     * fromIdx < toIdx で重複防止。カーブは {@link TrackEdge#getPosition(TrackGraph, double)} で
     * 8 段階サンプリングして polyline 化。
     */
    private static void collectGraphSegments(TrackGraph graph, List<int[]> out) {
        try {
            Map<TrackNode, Integer> idx = new HashMap<>();
            int i = 0;
            for (var nodeLoc : graph.getNodes()) {
                TrackNode node = graph.locateNode(nodeLoc);
                if (node == null) continue;
                idx.put(node, i++);
            }
            for (var nodeLoc : graph.getNodes()) {
                TrackNode from = graph.locateNode(nodeLoc);
                if (from == null) continue;
                Integer fromIdx = idx.get(from);
                if (fromIdx == null) continue;
                var conns = graph.getConnectionsFrom(from);
                if (conns == null) continue;
                for (var entry : conns.entrySet()) {
                    TrackNode to = entry.getKey();
                    Integer toIdx = idx.get(to);
                    if (toIdx == null || toIdx <= fromIdx) continue;
                    TrackEdge edge = entry.getValue();
                    // カーブを 8 セグメントでサンプル
                    int steps = 8;
                    double prevX = Double.NaN, prevZ = Double.NaN;
                    for (int s = 0; s <= steps; s++) {
                        double t = (double) s / steps;
                        try {
                            var p = edge.getPosition(graph, t);
                            if (p == null) continue;
                            if (!Double.isNaN(prevX)) {
                                out.add(new int[]{(int) prevX, (int) prevZ, (int) p.x, (int) p.z});
                                if (out.size() >= 4096) return;
                            }
                            prevX = p.x; prevZ = p.z;
                        } catch (Throwable ignored) {
                            // フォールバック: ノード直線
                            var fv = nodeLoc.getLocation();
                            var tv = to.getLocation().getLocation();
                            out.add(new int[]{(int) fv.x, (int) fv.z, (int) tv.x, (int) tv.z});
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { TrainSystemUtilities.LOGGER.debug("[TransitMap] graph segment collect failed", ignored); }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
