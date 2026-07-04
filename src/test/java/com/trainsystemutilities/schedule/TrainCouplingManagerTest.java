package com.trainsystemutilities.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v2🟠: {@link TrainCouplingManager} から切り出した純式を検証 (Minecraft / Create 非依存)。
 * Create を読む calculateGapSpacing は挙動不変で、本テストは抽出した gapSpacing のみを対象とする。
 */
class TrainCouplingManagerTest {

    @Test
    void gapSpacing_subtractsHalfBogeysAndRounds() {
        // entityDist 10, bogeys 4 + 4 → adjustment 4 → round(10-4) = 6。
        assertEquals(6, TrainCouplingManager.gapSpacing(10.0, 4.0, 4.0));
    }

    @Test
    void gapSpacing_roundsToNearest() {
        // adjustment 0 → round(10.6) = 11。
        assertEquals(11, TrainCouplingManager.gapSpacing(10.6, 0.0, 0.0));
    }

    @Test
    void gapSpacing_clampedToMinimumOne() {
        // entityDist 1, bogeys 4 + 4 → 1-4 = -3 → max(-3, 1) = 1。
        assertEquals(1, TrainCouplingManager.gapSpacing(1.0, 4.0, 4.0));
    }

    @Test
    void gapSpacing_asymmetricBogeys() {
        // adjustment = 6/2 + 2/2 = 4 → round(20-4) = 16。
        assertEquals(16, TrainCouplingManager.gapSpacing(20.0, 6.0, 2.0));
    }
}
