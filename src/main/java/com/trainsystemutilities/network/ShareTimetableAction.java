package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;

import java.util.UUID;

/**
 * 時刻表の共有トグル — the management computer host's {@code share-timetable} action ({@link ManagementComputerData};
 * ShareTimetablePayload until MANTA_7_CONCEPT C4).
 */
public final class ShareTimetableAction {

    private ShareTimetableAction() {}

    public static void apply(ManagementComputerBlockEntity mc, UUID sourceTrainId, UUID targetTrainId) {
        if (!mc.containsLinkedTrain(sourceTrainId)
                || !mc.containsLinkedTrain(targetTrainId)) return;
        mc.toggleTimetableShare(sourceTrainId, targetTrainId);
    }
}
