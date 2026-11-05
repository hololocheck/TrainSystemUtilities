package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.schedule.TrainTypes;

import java.util.UUID;

/**
 * 列車種別の設定 — the management computer host's {@code set-train-type} action ({@link ManagementComputerData};
 * SetTrainTypePayload until MANTA_7_CONCEPT C4). The payload's decoder read an index outside the types as the first
 * one rather than refusing it, so the declaration leaves it unbounded and this keeps that reading.
 */
public final class SetTrainTypeAction {

    private SetTrainTypeAction() {}

    public static void apply(ManagementComputerBlockEntity mc, UUID trainId, int typeIndex) {
        int idx = typeIndex < 0 || typeIndex >= TrainTypes.count() ? 0 : typeIndex;
        if (!mc.containsLinkedTrain(trainId)) return;
        mc.setTrainType(trainId, TrainTypes.byIndex(idx));
    }
}
