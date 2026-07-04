package com.trainsystemutilities.electrification.contraption;

import com.simibubi.create.content.trains.entity.Train;

/**
 * 電化列車のロック判定。
 *
 * <p>電化列車 (FE インバータ + パンタ搭載) の FE が 0 になったら、 Create の {@code Train.tick}
 * HEAD inject から呼ばれて {@code speed = targetSpeed = 0} に強制される (= 走行停止)。
 *
 * <p>battery 走行 (E2/E3): パンタを折り畳んでも FE が残っている限り走行し (蓄電分で走る)、
 * 停止条件は **FE=0** に一本化した。 従来の「パンタ全折畳 → 即停止」は撤廃。
 * 非電化列車 (= FE インバータなし) は素通し。
 */
public final class ContraptionElectrificationLock {

    private ContraptionElectrificationLock() {}

    /**
     * この列車をロックすべきか? = 電化列車であり、かつ列車全体の FE が 0。
     */
    public static boolean shouldLock(Train train) {
        var status = ContraptionElectrificationTicker.trainElectricStatus(train);
        return status.electrified() && status.totalStoredEnergy() <= 0;
    }
}
