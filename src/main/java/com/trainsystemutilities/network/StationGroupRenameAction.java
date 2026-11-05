package com.trainsystemutilities.network;

import com.manta.api.util.safe.SafeLog;
import com.manta.api.util.safe.SafeName;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 駅グループの改名 — the per-player stations host's {@code rename} action ({@link StationsData};
 * StationGroupRenamePayload until MANTA_7_CONCEPT C4). The new list reaches every client through the global station
 * groups host ({@link StationGroupsData#publish}, broadcastListToAll until then).
 */
public final class StationGroupRenameAction {

    private StationGroupRenameAction() {}

    public static void apply(ServerPlayer player, UUID groupId, String rawName) {
        String newName = rawName == null ? "" : rawName.trim();
        if (newName.isEmpty() || newName.length() > 64) return;
        try {
            SafeName.validate(newName);
        } catch (IllegalArgumentException ex) {
            TrainSystemUtilities.LOGGER.warn("[StationGroup] reject rename — name failed SafeName validation: {}",
                    SafeLog.sanitize(ex.getMessage()));
            return;
        }
        var data = StationGroupSavedData.get(player.server);
        StationGroup old = data.get(groupId);
        if (old == null) return;
        if (!PermissionHelper.canManageOwned(player, old.ownerUUID())) return;
        StationGroup updated = new StationGroup(
                old.id(), newName, old.dimensionId(), old.minPos(), old.maxPos(),
                old.stationBlockIds(), old.stationBlockPositions(), old.namingTemplate(), old.ownerUUID());
        data.put(updated);
        var level = player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(old.dimensionId())));
        if (level != null) {
            for (int i = 0; i < updated.stationBlockPositions().size(); i++) {
                BlockPos p = updated.stationBlockPositions().get(i);
                int platformNum = i + 1;
                String formatted = updated.formatStationName(platformNum);
                var be = level.getBlockEntity(p);
                if (be instanceof com.simibubi.create.content.trains.station.StationBlockEntity sbe) {
                    sbe.updateName(formatted);
                }
            }
        }
        StationGroupsData.publish(player.server);
        TrainSystemUtilities.LOGGER.info(
                "[StationGroup] renamed {} '{}' → '{}' by {}",
                groupId, SafeLog.sanitize(old.name()), SafeLog.sanitize(newName), player.getName().getString());
    }
}
