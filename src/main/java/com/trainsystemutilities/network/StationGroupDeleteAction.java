package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 駅グループの削除 — the per-player stations host's {@code delete} action ({@link StationsData};
 * StationGroupDeletePayload until MANTA_7_CONCEPT C4).
 */
public final class StationGroupDeleteAction {

    private StationGroupDeleteAction() {}

    public static void apply(ServerPlayer player, UUID groupId) {
        var data = StationGroupSavedData.get(player.server);
        StationGroup g = data.get(groupId);
        if (g == null) return;
        if (!PermissionHelper.canManageOwned(player, g.ownerUUID())) return;
        boolean removed = data.remove(groupId);
        if (removed) {
            com.trainsystemutilities.station.routing.navgraph.NavGraphCache
                    .remove(groupId);
            com.trainsystemutilities.station.routing.navfield.NavFieldCache
                    .removeAll(player.server, groupId);
            com.trainsystemutilities.station.routing.navfield.NavFieldBuildScheduler
                    .cancelFor(groupId);
            TrainSystemUtilities.LOGGER.info("[StationGroup] deleted {} by {}",
                    groupId, player.getName().getString());
            StationGroupsData.publish(player.server);
        }
    }
}
