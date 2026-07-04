package com.trainsystemutilities.station.routing;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v2🟠: {@link GraphDistance} の純 Dijkstra を検証 (Minecraft / Create 非依存)。
 * TrackGraphPathfinder.dijkstra から抽出したアルゴリズム本体の routing 正当性を担保する。
 */
class GraphDistanceTest {

    /** 重み付き有向グラフ (String node)。A→B 1, A→C 4, B→C 1, B→D 5, C→D 1。 */
    private static Map<String, Double> neighbors(String n) {
        return switch (n) {
            case "A" -> Map.of("B", 1.0, "C", 4.0);
            case "B" -> Map.of("C", 1.0, "D", 5.0);
            case "C" -> Map.of("D", 1.0);
            default -> Map.of();
        };
    }

    @Test
    void startEqualsEnd_isZero() {
        assertEquals(0.0, GraphDistance.shortestPath("A", "A", GraphDistanceTest::neighbors, 100));
    }

    @Test
    void directEdge() {
        assertEquals(1.0, GraphDistance.shortestPath("A", "B", GraphDistanceTest::neighbors, 100));
    }

    @Test
    void prefersCheaperMultiHopOverDirectEdge() {
        // A→C direct = 4, but A→B→C = 1+1 = 2 is cheaper → Dijkstra must pick 2.
        assertEquals(2.0, GraphDistance.shortestPath("A", "C", GraphDistanceTest::neighbors, 100));
    }

    @Test
    void multiHopShortest() {
        // A→B→C→D = 3 beats A→B→D = 6 and A→C→D = 5.
        assertEquals(3.0, GraphDistance.shortestPath("A", "D", GraphDistanceTest::neighbors, 100));
    }

    @Test
    void unreachableIsMinusOne() {
        assertEquals(-1.0, GraphDistance.shortestPath("D", "A", GraphDistanceTest::neighbors, 100));
        assertEquals(-1.0, GraphDistance.shortestPath("A", "Z", GraphDistanceTest::neighbors, 100));
    }

    @Test
    void nullNeighborsTreatedAsNoEdges() {
        assertEquals(-1.0, GraphDistance.shortestPath("A", "B", n -> null, 100));
    }

    @Test
    void euclidean2D_pythagorean() {
        assertEquals(5.0, GraphDistance.euclidean2D(0, 0, 3, 4));
        assertEquals(0.0, GraphDistance.euclidean2D(2, 2, 2, 2));
    }
}
