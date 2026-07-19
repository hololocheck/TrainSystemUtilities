package com.trainsystemutilities.schedule;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 種別レジストリの純ロジック検証 (Minecraft 非依存)。
 *
 * <p>自動判定廃止 (2026-07-18) にともない {@code TrainScheduleReaderTest} の
 * {@code trainTypeFromRatio} 系 4 件を置き換えたもの。
 */
class TrainTypesTest {

    // ===== cycle (R4.13.0.8 の ((idx+dir)%n+n)%n) =====

    @Test
    void cycle_forwardAdvancesOneStep() {
        assertEquals("LOCAL", TrainTypes.cycle(TrainTypes.NONE, 1));
        assertEquals("RAPID", TrainTypes.cycle("LOCAL", 1));
    }

    @Test
    void cycle_backwardFromUnsetWrapsToLast() {
        assertEquals("LIMITED_EXPRESS", TrainTypes.cycle(TrainTypes.NONE, -1));
    }

    @Test
    void cycle_forwardFromLastWrapsToUnset() {
        assertEquals(TrainTypes.NONE, TrainTypes.cycle("LIMITED_EXPRESS", 1));
    }

    @Test
    void cycle_fullLapReturnsToStart() {
        String code = TrainTypes.NONE;
        for (int i = 0; i < TrainTypes.count(); i++) code = TrainTypes.cycle(code, 1);
        assertEquals(TrainTypes.NONE, code);
    }

    @Test
    void cycle_unknownCodeIsTreatedAsUnset() {
        assertEquals("LOCAL", TrainTypes.cycle("NOT_A_REAL_TYPE", 1));
    }

    // ===== index =====

    @Test
    void indexOf_unknownIsZero() {
        assertEquals(0, TrainTypes.indexOf("NOT_A_REAL_TYPE"));
        assertEquals(0, TrainTypes.indexOf(null));
        assertEquals(0, TrainTypes.indexOf(TrainTypes.NONE));
    }

    @Test
    void byIndex_outOfRangeIsUnset() {
        assertEquals(TrainTypes.NONE, TrainTypes.byIndex(-1));
        assertEquals(TrainTypes.NONE, TrainTypes.byIndex(TrainTypes.count()));
    }

    @Test
    void byIndex_roundTripsWithIndexOf() {
        for (int i = 0; i < TrainTypes.count(); i++) {
            assertEquals(i, TrainTypes.indexOf(TrainTypes.byIndex(i)));
        }
    }

    // ===== isSet =====

    @Test
    void isSet_onlyFalseForUnset() {
        assertFalse(TrainTypes.isSet(null));
        assertFalse(TrainTypes.isSet(TrainTypes.NONE));
        for (int i = 1; i < TrainTypes.count(); i++) {
            assertTrue(TrainTypes.isSet(TrainTypes.byIndex(i)), "index " + i + " should be set");
        }
    }

    // ===== lang key =====

    /**
     * 自動判定時代は EXPRESS が「特急」だった。 8 種別化で EXPRESS = 急行 /
     * LIMITED_EXPRESS = 特急 に切り分けたので、 取り違えると全モニターの表示が狂う。
     */
    @Test
    void langKey_expressAndLimitedExpressAreDistinct() {
        assertEquals("tsu.monitor.train_type_express", TrainTypes.langKey("EXPRESS"));
        assertEquals("tsu.monitor.train_type_limited_express", TrainTypes.langKey("LIMITED_EXPRESS"));
        assertNotEquals(TrainTypes.langKey("EXPRESS"), TrainTypes.langKey("LIMITED_EXPRESS"));
    }

    /** switch のコピペで 2 種別が同じ key を指すと表示が入れ替わるので、 全種別ぶん一意性を確かめる。 */
    @Test
    void langKey_isUniquePerType() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < TrainTypes.count(); i++) {
            String code = TrainTypes.byIndex(i);
            assertTrue(keys.add(TrainTypes.langKey(code)),
                    "duplicate lang key for " + (code.isEmpty() ? "<unset>" : code));
        }
    }

    @Test
    void langKey_unknownFallsBackToUnsetLabel() {
        assertEquals(TrainTypes.langKey(TrainTypes.NONE), TrainTypes.langKey("NOT_A_REAL_TYPE"));
    }

    // ===== color =====

    @Test
    void colorArgb_everySetTypeIsFullyOpaque() {
        for (int i = 1; i < TrainTypes.count(); i++) {
            int argb = TrainTypes.colorArgb(TrainTypes.byIndex(i));
            assertEquals(0xFF, (argb >>> 24) & 0xFF, "alpha for " + TrainTypes.byIndex(i));
        }
    }
}
