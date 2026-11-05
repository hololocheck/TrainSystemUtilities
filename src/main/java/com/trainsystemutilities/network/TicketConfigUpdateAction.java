package com.trainsystemutilities.network;

import com.trainsystemutilities.station.TicketConfigSavedData;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 駅グループの販売可トグル — the management computer host's {@code ticket-update} action
 * ({@link ManagementComputerData}; TicketConfigUpdatePayload until MANTA_7_CONCEPT C4).
 */
public final class TicketConfigUpdateAction {

    private TicketConfigUpdateAction() {}

    public static void apply(ServerPlayer player, UUID stationId, boolean sellable) {
        TicketConfigSavedData.get(player.server).setSellable(stationId, sellable);
    }
}
