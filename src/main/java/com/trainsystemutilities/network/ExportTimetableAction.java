package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;

import java.util.UUID;

/**
 * 時刻表の書き出し開始 — the management computer host's {@code export-timetable} action ({@link ManagementComputerData};
 * ExportTimetablePayload until MANTA_7_CONCEPT C4).
 */
public final class ExportTimetableAction {

    private ExportTimetableAction() {}

    public static void apply(ManagementComputerBlockEntity mc, UUID trainId) {
        if (!mc.containsLinkedTrain(trainId)) return;
        mc.startExport(trainId);
    }
}
