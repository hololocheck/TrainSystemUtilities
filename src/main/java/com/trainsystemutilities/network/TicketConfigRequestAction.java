package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.station.TicketConfigSavedData;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 券売機タブを開いたときの販売可設定の取得 — the management computer host's {@code ticket-request} action
 * ({@link ManagementComputerData}; TicketConfigRequestPayload until MANTA_7_CONCEPT C4). The answer
 * (TicketConfigSyncPayload until then, to the asker alone) is the host's two list fields, for every screen open on
 * this computer.
 */
public final class TicketConfigRequestAction {

    private TicketConfigRequestAction() {}

    public static void apply(ServerPlayer player, ManagementComputerBlockEntity be, Host host) {
        var level = player.level();
        var server = player.server;
        var sellable = new ArrayList<>(TicketConfigSavedData.get(server).sellable());
        List<UUID> networkGroups = new ArrayList<>();
        String dim = level.dimension().location().toString();
        var sgData = StationGroupSavedData.get(server);
        Set<UUID> seen = new HashSet<>();
        for (var s : be.getCachedStations()) {
            var g = sgData.findContaining(dim, s.position());
            if (g != null && seen.add(g.id())) networkGroups.add(g.id());
        }
        ManagementComputerData.publishTicketConfig(host, sellable, networkGroups);
    }
}
