package com.trainsystemutilities.schedule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * god-class deep #2: {@link TrainScheduleReader} から切り出した純判定ロジックを検証
 * (Minecraft / Create 非依存)。Create を読む wrapper (analyzeRouteType) は挙動不変で、
 * 本テストは抽出した純関数のみを対象とする。
 */
class TrainScheduleReaderTest {

    // ===== routeTypeFromStations =====

    @Test
    void routeType_emptyWhenFewerThanTwo() {
        assertEquals("", TrainScheduleReader.routeTypeFromStations(List.of()));
        assertEquals("", TrainScheduleReader.routeTypeFromStations(List.of("A")));
    }

    @Test
    void routeType_twoUniqueIsShuttle() {
        assertEquals("SHUTTLE", TrainScheduleReader.routeTypeFromStations(List.of("A", "B")));
        assertEquals("SHUTTLE", TrainScheduleReader.routeTypeFromStations(List.of("A", "B", "A")));
    }

    @Test
    void routeType_reversePalindromeIsShuttle() {
        assertEquals("SHUTTLE", TrainScheduleReader.routeTypeFromStations(List.of("A", "B", "C", "B", "A")));
    }

    @Test
    void routeType_threePlusAllUniqueIsCircular() {
        assertEquals("CIRCULAR", TrainScheduleReader.routeTypeFromStations(List.of("A", "B", "C")));
        assertEquals("CIRCULAR", TrainScheduleReader.routeTypeFromStations(List.of("A", "B", "C", "D")));
    }

    @Test
    void routeType_explicitFirstEqualsLastIsCircular() {
        // A B C A: unique {A,B,C}=3 だが size=4 で all-unique でない。round-trip でもない (B != C)。
        // first==last → CIRCULAR。
        assertEquals("CIRCULAR", TrainScheduleReader.routeTypeFromStations(List.of("A", "B", "C", "A")));
    }

    @Test
    void routeType_convergesWithRouteClassifierOnTwoUnique() {
        // v2 統一: RouteClassifier も [A,B] を SHUTTLE と判定するようになり発散は解消。
        // 両者が同一ロジックを共有することを保証 (再発散防止)。
        assertEquals("SHUTTLE", TrainScheduleReader.routeTypeFromStations(List.of("A", "B")));
        assertEquals(com.trainsystemutilities.route.RouteClassifier.classify(List.of("A", "B")),
                TrainScheduleReader.routeTypeFromStations(List.of("A", "B")));
    }

    // trainTypeFromRatio のテストは、 判定アルゴリズム本体の廃止 (2026-07-18) にあわせて削除した。
    // 種別の振る舞いは TrainTypesTest が担保する。
}
