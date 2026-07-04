package com.trainsystemutilities.station.routing;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;

import java.util.*;

/**
 * Create の TrackGraph 上で A* / Dijkstra による「実走距離」を計算するヘルパ。
 *
 * <p>用途: 駅間の物理的な軌道沿い距離を求めて、キネマティクス推定の入力にする。
 * 直線 (Euclidean) 距離だと過小評価になるため、カーブを含む実距離が欲しい。
 *
 * <p>結果は {@link com.trainsystemutilities.station.SegmentStatsStore} の EMA より優先順位は低い
 * (実測 EMA があれば常にそちらを使う)。EMA 未学習区間のみ使用。
 *
 * <p>キャッシュ: 同じノードペアで再計算を避けるため軽く LRU キャッシュ。
 */
public final class TrackGraphPathfinder {

    private TrackGraphPathfinder() {}

    private static final int CACHE_MAX = 256;
    private static final Map<String, Double> CACHE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Double> eldest) {
            return size() > CACHE_MAX;
        }
    };

    /**
     * 2 BlockPos 間の軌道沿い距離 (blocks) を Dijkstra で計算。経路がなければ -1。
     */
    public static synchronized double railDistance(net.minecraft.core.BlockPos fromPos, net.minecraft.core.BlockPos toPos) {
        if (fromPos == null || toPos == null) return -1;
        if (fromPos.equals(toPos)) return 0;
        String k = fromPos.asLong() + ":" + toPos.asLong();
        Double cached = CACHE.get(k);
        if (cached != null) return cached;
        double result = compute(fromPos, toPos);
        CACHE.put(k, result);
        return result;
    }

    private static double compute(net.minecraft.core.BlockPos fromPos, net.minecraft.core.BlockPos toPos) {
        if (Create.RAILWAYS == null || Create.RAILWAYS.trackNetworks == null) return -1;
        try {
            // 双方の位置に最も近いノードを持つ TrackGraph を共通で探す
            TrackGraph graph = null;
            TrackNode startNode = null;
            TrackNode endNode = null;
            double bestStart = Double.MAX_VALUE, bestEnd = Double.MAX_VALUE;
            for (TrackGraph g : Create.RAILWAYS.trackNetworks.values()) {
                TrackNode s = nearestNode(g, fromPos);
                TrackNode e = nearestNode(g, toPos);
                if (s == null || e == null) continue;
                double ds = squaredDistTo(s, fromPos);
                double de = squaredDistTo(e, toPos);
                if (ds + de < bestStart + bestEnd) {
                    graph = g; startNode = s; endNode = e;
                    bestStart = ds; bestEnd = de;
                }
            }
            if (graph == null) return -1;
            return dijkstra(graph, startNode, endNode);
        } catch (Throwable t) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackGraph] graph distance compute failed", t);
            return -1;
        }
    }

    private static TrackNode nearestNode(TrackGraph g, net.minecraft.core.BlockPos pos) {
        TrackNode best = null;
        double bestDist = 32 * 32;
        try {
            for (TrackNodeLocation loc : g.getNodes()) {
                var v = loc.getLocation();
                double dx = v.x - pos.getX();
                double dy = v.y - pos.getY();
                double dz = v.z - pos.getZ();
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestDist) {
                    TrackNode n = g.locateNode(loc);
                    if (n != null) {
                        best = n;
                        bestDist = d;
                    }
                }
            }
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackGraph] nearest-node scan failed", e); }
        return best;
    }

    private static double squaredDistTo(TrackNode node, net.minecraft.core.BlockPos pos) {
        try {
            var v = node.getLocation().getLocation();
            double dx = v.x - pos.getX();
            double dy = v.y - pos.getY();
            double dz = v.z - pos.getZ();
            return dx * dx + dy * dy + dz * dz;
        } catch (Throwable t) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackGraph] squared-dist failed", t); return Double.MAX_VALUE; }
    }

    private static double dijkstra(TrackGraph graph, TrackNode start, TrackNode end) {
        return GraphDistance.shortestPath(start, end, node -> {
            var conns = graph.getConnectionsFrom(node);
            if (conns == null) return null;
            Map<TrackNode, Double> out = new HashMap<>();
            for (var entry : conns.entrySet()) {
                TrackNode next = entry.getKey();
                TrackEdge edge = entry.getValue();
                double w;
                try { w = edge.getLength(); }
                catch (Throwable t) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackGraph] edge length failed, using euclidean", t); w = euclidean(node, next); }
                if (w <= 0) w = euclidean(node, next);
                out.put(next, w);
            }
            return out;
        }, 4096);
    }

    private static double euclidean(TrackNode a, TrackNode b) {
        try {
            var va = a.getLocation().getLocation();
            var vb = b.getLocation().getLocation();
            return GraphDistance.euclidean2D(va.x, va.z, vb.x, vb.z);
        } catch (Throwable t) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackGraph] euclidean failed", t); return 1; }
    }

}
