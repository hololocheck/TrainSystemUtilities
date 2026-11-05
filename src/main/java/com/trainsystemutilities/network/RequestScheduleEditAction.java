package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 時刻表エディタを開く要求 — the management computer host's {@code schedule-request} action
 * ({@link ManagementComputerData}; RequestScheduleEditPayload until MANTA_7_CONCEPT C4). The answer
 * (ScheduleEditDataPayload until then) is the asker's alone: it goes to the asker's tools host
 * ({@link ToolsData#answerScheduleEdit}).
 */
public final class RequestScheduleEditAction {

    private RequestScheduleEditAction() {}

    public static void apply(ServerPlayer sp, ManagementComputerBlockEntity mc, UUID trainId) {
        if (!mc.containsLinkedTrain(trainId)) return;
        CompoundTag out = new CompoundTag();
        boolean hasData = false;
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train != null && train.runtime != null) {
            Schedule sched = train.runtime.getSchedule();
            if (sched != null) {
                out = sched.write(sp.serverLevel().registryAccess());
                hasData = !sched.entries.isEmpty();
            }
        }
        ToolsData.answerScheduleEdit(sp, trainId, hasData, out);
    }
}
