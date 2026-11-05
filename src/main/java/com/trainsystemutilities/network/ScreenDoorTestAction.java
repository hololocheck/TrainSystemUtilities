package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.screendoor.ScreenDoorConditionEvaluator;

/**
 * ホームドア手動開閉テスト (= 列車イベント抜きで即時発火) — the railway management host's {@code door-test} action
 * ({@link RailwayManagementData}; ScreenDoorTestActionPayload until MANTA_7_CONCEPT C4).
 */
public final class ScreenDoorTestAction {

    private ScreenDoorTestAction() {}

    public static void apply(RailwayManagementBlockEntity rmbe, int actionType) {
        ScreenDoorConditionEvaluator.testApply(rmbe, actionType);
    }
}
