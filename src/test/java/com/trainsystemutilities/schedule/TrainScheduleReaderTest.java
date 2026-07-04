package com.trainsystemutilities.schedule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * god-class deep #2: {@link TrainScheduleReader} から切り出した純判定ロジックを検証
 * (Minecraft / Create 非依存)。Create を読む wrapper (analyzeRouteType / detectTrainType)
 * は挙動不変で、本テストは抽出した純関数のみを対象とする。
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

    // ===== trainTypeFromRatio =====

    @Test
    void trainType_ratioAtLeast80IsLocal() {
        assertEquals("LOCAL", TrainScheduleReader.trainTypeFromRatio(8, 10));
        assertEquals("LOCAL", TrainScheduleReader.trainTypeFromRatio(10, 10));
    }

    @Test
    void trainType_ratioBetween40And80IsRapid() {
        assertEquals("RAPID", TrainScheduleReader.trainTypeFromRatio(4, 10));
        assertEquals("RAPID", TrainScheduleReader.trainTypeFromRatio(7, 10));
    }

    @Test
    void trainType_ratioBelow40IsExpress() {
        assertEquals("EXPRESS", TrainScheduleReader.trainTypeFromRatio(3, 10));
        assertEquals("EXPRESS", TrainScheduleReader.trainTypeFromRatio(1, 10));
    }

    @Test
    void trainType_emptyWhenNoNetwork() {
        assertEquals("", TrainScheduleReader.trainTypeFromRatio(5, 0));
    }
}
