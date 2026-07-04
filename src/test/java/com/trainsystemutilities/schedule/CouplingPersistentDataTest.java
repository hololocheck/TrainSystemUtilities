package com.trainsystemutilities.schedule;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TSU-22: 同時に複数の merge があっても front/rear が混ざらないことを検証する。
 *
 * <p>旧実装は {@code getFrontSchedule()} / {@code getRearSchedule()} が global first-match で引き、
 * merge ごとに {@code clear()} していたため、 2 組目を merge すると 1 組目の schedule が消え、
 * 1 組目の split が誤った carriage 数 / schedule を使っていた。 mergedTrainId scope でこれを防ぐ。
 *
 * <p>getter は isFront / mergedTrainId しか参照しないため scheduleNbt は null で良く、 Minecraft bootstrap 不要。
 */
class CouplingPersistentDataTest {

    private static CouplingPersistentData.SavedScheduleData data(UUID original, UUID merged,
                                                                  boolean isFront, int carriages) {
        return new CouplingPersistentData.SavedScheduleData(
                original, merged, null, 0, carriages, isFront, "t", 5);
    }

    @Test
    void scopedGetters_doNotCrossMerges() {
        CouplingPersistentData pd = new CouplingPersistentData();
        UUID mergeA = UUID.randomUUID();
        UUID mergeB = UUID.randomUUID();
        UUID aFront = UUID.randomUUID(), aRear = UUID.randomUUID();
        UUID bFront = UUID.randomUUID(), bRear = UUID.randomUUID();

        pd.put(aFront, data(aFront, mergeA, true, 2));
        pd.put(aRear, data(aRear, mergeA, false, 3));
        // 2 組目を merge (旧実装ならここで 1 組目が壊れた)。
        pd.put(bFront, data(bFront, mergeB, true, 4));
        pd.put(bRear, data(bRear, mergeB, false, 5));

        // mergeA は依然として自分の front/rear を返す。
        assertEquals(aFront, pd.getFrontSchedule(mergeA).originalTrainId());
        assertEquals(aRear, pd.getRearSchedule(mergeA).originalTrainId());
        assertEquals(2, pd.getFrontSchedule(mergeA).carriageCount());
        // mergeB も自分のペアを返す (取り違えない)。
        assertEquals(bFront, pd.getFrontSchedule(mergeB).originalTrainId());
        assertEquals(bRear, pd.getRearSchedule(mergeB).originalTrainId());
        assertEquals(4, pd.getFrontSchedule(mergeB).carriageCount());
    }

    @Test
    void scopedGetters_returnNullForUnknownMerge() {
        CouplingPersistentData pd = new CouplingPersistentData();
        pd.put(UUID.randomUUID(), data(UUID.randomUUID(), UUID.randomUUID(), true, 2));
        assertNull(pd.getFrontSchedule(UUID.randomUUID()));
        assertNull(pd.getRearSchedule(UUID.randomUUID()));
    }
}
