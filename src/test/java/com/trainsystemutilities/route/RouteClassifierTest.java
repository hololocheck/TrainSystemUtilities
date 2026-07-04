package com.trainsystemutilities.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R2: 切り出した {@link RouteClassifier} の純ロジックを検証 (Minecraft 非依存)。 */
class RouteClassifierTest {

    @Test
    void allUniqueStations_isCircular() {
        assertEquals("CIRCULAR", RouteClassifier.classify(List.of("A", "B", "C", "D")));
    }

    @Test
    void repeatedStations_isShuttle() {
        assertEquals("SHUTTLE", RouteClassifier.classify(List.of("A", "B", "C", "B", "A")));
    }

    @Test
    void twoUniqueStations_isShuttle() {
        // v2 統一: 2 駅 (A↔B) は折り返し。旧 monitor ヒューリスティック (CIRCULAR) は廃止。
        assertEquals("SHUTTLE", RouteClassifier.classify(List.of("A", "B")));
        assertEquals("SHUTTLE", RouteClassifier.classify(List.of("A", "B", "A")));
    }

    @Test
    void explicitFirstEqualsLast_isCircular() {
        // A B C A: all-unique でも round-trip でもないが first==last → 環状。
        assertEquals("CIRCULAR", RouteClassifier.classify(List.of("A", "B", "C", "A")));
    }

    @Test
    void emptyOrSingleOrNull_isEmpty() {
        assertEquals("", RouteClassifier.classify(List.of()));
        assertEquals("", RouteClassifier.classify(List.of("A")));
        assertEquals("", RouteClassifier.classify(null));
    }

    // ===== v3 classifyDetailed (hybrid schedule-based) =====

    @Test
    void detailed_retrace_isShuttle_withEndTurnbacks() {
        // A-B-C-D-C-B (Create cyclic shuttle) → 折り返し、 端 A / D が折り返し駅
        var r = RouteClassifier.classifyDetailed(List.of("A", "B", "C", "D", "C", "B"));
        assertEquals("SHUTTLE", r.routeType());
        assertEquals(java.util.Set.of("A", "D"), r.turnbackStations());
    }

    @Test
    void detailed_shortRetrace_isShuttle() {
        var r = RouteClassifier.classifyDetailed(List.of("A", "B", "C", "B"));
        assertEquals("SHUTTLE", r.routeType());
        assertEquals(java.util.Set.of("A", "C"), r.turnbackStations());
    }

    @Test
    void detailed_explicitLoop_isCircular_noTurnbacks() {
        var r = RouteClassifier.classifyDetailed(List.of("A", "B", "C", "D", "A"));
        assertEquals("CIRCULAR", r.routeType());
        assertEquals(java.util.Set.of(), r.turnbackStations());
    }

    @Test
    void detailed_twoStation_isAmbiguous_deferToObservation() {
        // A↔B も 8 駅環状の 2 駅停車も時刻表は [A,B] で区別不能 → "" (実挙動で確定)
        assertEquals("", RouteClassifier.classifyDetailed(List.of("A", "B")).routeType());
    }

    @Test
    void detailed_openDistinct_isAmbiguous_deferToObservation() {
        // 開いた全 unique 列は 環状/折り返しが時刻表では不明 → "" (実挙動で確定)
        assertEquals("", RouteClassifier.classifyDetailed(List.of("A", "B", "C", "D")).routeType());
    }

    @Test
    void detailed_bounceRepeat_isShuttle() {
        var r = RouteClassifier.classifyDetailed(List.of("A", "B", "A", "B"));
        assertEquals("SHUTTLE", r.routeType());
        assertEquals(java.util.Set.of("A", "B"), r.turnbackStations());
    }
}
