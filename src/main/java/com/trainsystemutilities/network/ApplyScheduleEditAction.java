package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 時刻表エディタの適用 — the management computer host's {@code schedule-apply} action ({@link ManagementComputerData};
 * ApplyScheduleEditPayload until MANTA_7_CONCEPT C4). The schedule travels as SNBT.
 */
public final class ApplyScheduleEditAction {

    private ApplyScheduleEditAction() {}

    public static void apply(ManagementComputerBlockEntity mc, UUID trainId, CompoundTag scheduleNbt) {
        if (!mc.containsLinkedTrain(trainId)) return;
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null || train.runtime == null) return;
        boolean hasSchedule = train.runtime.getSchedule() != null
                && !train.runtime.getSchedule().entries.isEmpty();
        boolean electronic = mc.isElectronicTimetable(trainId);
        if (hasSchedule && !electronic) return;
        if (!ManagementComputerBlockEntity.hasConductor(train)) return;
        mc.setPendingSchedule(trainId, scheduleNbt);
        mc.applyPendingSchedule();
    }
}
