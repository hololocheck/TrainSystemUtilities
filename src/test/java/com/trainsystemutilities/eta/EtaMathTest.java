package com.trainsystemutilities.eta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TSU 初の unit test (B4)。 god-class から切り出した純数値計算 {@link WelfordStats} /
 * {@link EtaMath} を Minecraft 非依存で検証する。
 */
class EtaMathTest {

    // ===== WelfordStats (online 平均 / 分散) =====

    @Test
    void welford_meanAndStddev_matchKnownTextbookValues() {
        // 標準偏差の教科書例 {2,4,4,4,5,5,7,9}: mean=5, 標本分散=32/7。
        WelfordStats s = new WelfordStats();
        for (double v : new double[]{2, 4, 4, 4, 5, 5, 7, 9}) s.update(v);
        assertEquals(8, s.count);
        assertEquals(5.0, s.mean, 1e-9);
        assertEquals(Math.sqrt(32.0 / 7.0), s.stddev(), 1e-9);
    }

    @Test
    void welford_singleSample_stddevIsZero() {
        WelfordStats s = new WelfordStats();
        s.update(42);
        assertEquals(1, s.count);
        assertEquals(42.0, s.mean, 1e-9);
        assertEquals(0.0, s.stddev(), 1e-9);  // count<=1 → 0
    }

    // ===== EtaMath: 異常値拒絶付き EWMA (long) =====

    @Test
    void ewmaLong_bootstrap_acceptsSampleWhenNoExisting() {
        var rNull = EtaMath.applyEwmaLong(null, 100, 0.2, 2.0);
        assertTrue(rNull.accepted());
        assertEquals(100, rNull.value());

        var rZero = EtaMath.applyEwmaLong(0L, 100, 0.2, 2.0);
        assertTrue(rZero.accepted());
        assertEquals(100, rZero.value());
    }

    @Test
    void ewmaLong_blendsWhenWithinRatio() {
        // existing=100, sample=200, alpha=0.2 → round(100*0.8 + 200*0.2) = 120。 ratio 2.0 == maxRatio は許容。
        var r = EtaMath.applyEwmaLong(100L, 200, 0.2, 2.0);
        assertTrue(r.accepted());
        assertEquals(120, r.value());
    }

    @Test
    void ewmaLong_rejectsOutlierAboveRatio() {
        // 250/100 = 2.5 > 2.0 → 拒絶、 既存値。
        var r = EtaMath.applyEwmaLong(100L, 250, 0.2, 2.0);
        assertFalse(r.accepted());
        assertEquals(100, r.value());
    }

    @Test
    void ewmaLong_rejectsOutlierBelowRatio() {
        // 40/100 = 0.4 < 1/2.0 = 0.5 → 拒絶。
        var r = EtaMath.applyEwmaLong(100L, 40, 0.2, 2.0);
        assertFalse(r.accepted());
        assertEquals(100, r.value());
    }

    // ===== EtaMath: 異常値拒絶付き EWMA (double) =====

    @Test
    void ewmaDouble_blendAndReject() {
        var blend = EtaMath.applyEwmaDouble(10.0, 20.0, 0.2, 2.5);  // 20/10=2.0 < 2.5 → 採用
        assertTrue(blend.accepted());
        assertEquals(10.0 * 0.8 + 20.0 * 0.2, blend.value(), 1e-9);  // 12.0

        var reject = EtaMath.applyEwmaDouble(10.0, 30.0, 0.2, 2.5);  // 30/10=3.0 > 2.5 → 拒絶
        assertFalse(reject.accepted());
        assertEquals(10.0, reject.value(), 1e-9);
    }
}
