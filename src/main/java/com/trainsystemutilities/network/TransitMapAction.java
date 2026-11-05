package com.trainsystemutilities.network;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 乗換案内端末 MAP タブの線路網 — the player's transit-map host's {@code map} action ({@link TransitData};
 * TransitMapRequestPayload until MANTA_7_CONCEPT C4). The body is the payload's handler: every track network as
 * 2D segments (curves sampled in 8), at most {@link TransitData#SEGMENT_ROWS} (the payload stopped at 4096; the
 * document's list holds what fits a message).
 */
public final class TransitMapAction {

    // TSU-19: 全 trackNetworks の graph scan + curve サンプリングで重い request。 MAP タブ操作で送られる (低頻度)。
    // abuse client 向けに per-player の最小間隔を強制する。 250ms = legit な tab 操作を壊さず spam を遮断。
    private static final java.util.Map<java.util.UUID, Long> LAST_REQ_NS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_NS = 250_000_000L; // 250ms

    private TransitMapAction() {}

    public static void apply(ServerPlayer player) {
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
                    if (segments.size() >= TransitData.SEGMENT_ROWS) break; // 1 メッセージに収まる上限
                }
            }
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn("[TransitMap] scan failed: {}", t.toString());
        }
        TransitData.answerMap(player, segments);
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
                                if (out.size() >= TransitData.SEGMENT_ROWS) return;
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
}
