package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.screendoor.ScreenDoorCondition;
import com.trainsystemutilities.screendoor.ScreenDoorConditionEvaluator;

/**
 * RailwayManagementBlockEntity のホームドア帯色を更新 — the railway management host's {@code door-band-color}
 * action ({@link RailwayManagementData}; ScreenDoorBandColorPayload until MANTA_7_CONCEPT C4).
 */
public final class ScreenDoorBandColorAction {

    private ScreenDoorBandColorAction() {}

    public static void apply(RailwayManagementBlockEntity rmbe, int argb) {
        rmbe.setScreenDoorBandColorARGB(argb);
        ScreenDoorConditionEvaluator.testApply(rmbe, ScreenDoorCondition.ACTION_COLOR);
    }
}
