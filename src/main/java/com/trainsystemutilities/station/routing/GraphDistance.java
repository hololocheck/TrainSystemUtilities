package com.trainsystemutilities.station.routing;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * 汎用 Dijkstra 最短経路 (Minecraft / Create 非依存)。{@code TrackGraphPathfinder} の
 * TrackGraph 専用 dijkstra から抽出した純アルゴリズム — node 種別と近傍重みは呼び元が
 * {@code neighbors} で供給する。到達不能 / safety 超過は -1。
 */
public final class GraphDistance {

    private GraphDistance() {}

    private record NodeDist<N>(N node, double dist) {}

    /**
     * {@code start} から {@code end} までの最短累積距離。到達不能 or safety 超過は -1。
     * {@code neighbors} は node → (近傍 node → 辺重み &gt; 0) を返す純関数 (null は近傍なし扱い)。
     */
    public static <N> double shortestPath(N start, N end,
                                          Function<N, Map<N, Double>> neighbors, int safetyLimit) {
        if (start.equals(end)) return 0;
        Map<N, Double> dist = new HashMap<>();
        PriorityQueue<NodeDist<N>> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.dist()));
        dist.put(start, 0.0);
        pq.offer(new NodeDist<>(start, 0.0));
        int safety = safetyLimit;
        while (!pq.isEmpty() && safety-- > 0) {
            NodeDist<N> top = pq.poll();
            if (top.node().equals(end)) return top.dist();
            if (top.dist() > dist.getOrDefault(top.node(), Double.MAX_VALUE)) continue;
            Map<N, Double> conns = neighbors.apply(top.node());
            if (conns == null) continue;
            for (Map.Entry<N, Double> entry : conns.entrySet()) {
                N next = entry.getKey();
                double nd = top.dist() + entry.getValue();
                if (nd < dist.getOrDefault(next, Double.MAX_VALUE)) {
                    dist.put(next, nd);
                    pq.offer(new NodeDist<>(next, nd));
                }
            }
        }
        return -1;
    }

    /** 2D (X-Z 平面) Euclidean 距離 (Minecraft 非依存)。 */
    public static double euclidean2D(double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
