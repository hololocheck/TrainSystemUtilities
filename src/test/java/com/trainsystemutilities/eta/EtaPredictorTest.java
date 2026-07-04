package com.trainsystemutilities.eta;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** B4 継続: RailwayManagementBlockEntity から切り出した {@link EtaPredictor} の純ロジックを検証。 */
class EtaPredictorTest {

    @Test
    void cruiseSpeed_validWithinBounds() {
        // 100 block / 200 tick = 0.5 (in (0.05, 5.0)) → 採用
        assertEquals(0.5, EtaPredictor.computeCruiseSpeed(100.0, 200L, 0.7), 1e-9);
    }

    @Test
    void cruiseSpeed_fallbackWhenNullOrZeroTicks() {
        assertEquals(0.7, EtaPredictor.computeCruiseSpeed(null, 200L, 0.7), 1e-9);
        assertEquals(0.7, EtaPredictor.computeCruiseSpeed(100.0, null, 0.7), 1e-9);
        assertEquals(0.7, EtaPredictor.computeCruiseSpeed(100.0, 0L, 0.7), 1e-9);
    }

    @Test
    void cruiseSpeed_fallbackWhenOutOfBounds() {
        assertEquals(0.7, EtaPredictor.computeCruiseSpeed(100.0, 10L, 0.7), 1e-9);  // 10.0 > MAX
        assertEquals(0.7, EtaPredictor.computeCruiseSpeed(1.0, 100L, 0.7), 1e-9);   // 0.01 < MIN
    }

    @Test
    void keys_format() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(id + "|A→B", EtaPredictor.trainLegKey(id, "A", "B"));
        assertEquals(id + "@StationX", EtaPredictor.stationWaitKey(id, "StationX"));
    }

    @Test
    void cruiseSpeedStatic_measuredAndReverseFallback() {
        EtaPredictor.clearLegData();
        // 計測なし → DEFAULT
        assertEquals(EtaPredictor.DEFAULT_CRUISE_SPEED,
                EtaPredictor.getCruiseSpeedStatic("A", "B"), 1e-9);
        // 100 block / 200 tick = 0.5
        EtaPredictor.recordLegDistance("A→B", 100.0);
        EtaPredictor.recordLegTicks("A→B", 200L);
        assertEquals(0.5, EtaPredictor.getCruiseSpeedStatic("A", "B"), 1e-9);
        // 逆キー fallback: B→A の計測が無ければ A→B を流用
        assertEquals(0.5, EtaPredictor.getCruiseSpeedStatic("B", "A"), 1e-9);
        EtaPredictor.clearLegData();
    }
}
