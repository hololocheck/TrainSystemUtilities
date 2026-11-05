package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The station groups on manta:data (MANTA_7_CONCEPT C4): what StationGroupListResponsePayload carried to every player -
 * at login, on request, and to all after each change - is one server-wide host's list field, declared by the
 * state-only document {@link #DOCUMENT}. Every client subscribes at login (client.StationsClient), so the list the
 * screens, the HUD and the station range renderer read stays current as the broadcast kept it.
 *
 * <p>A group's station positions do not ride the list (256 groups of them would not fit a message): the list carries
 * their count, and a screen asks for one group's positions on the asker's stations host ({@link StationsData#answerDetail}).
 * The payload's decoder took at most 256 groups - past that a client was disconnected - and so does the list.</p>
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class StationGroupsData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/station-groups-state.json";
    static final String CHANNEL = TrainSystemUtilities.MOD_ID + ":station-groups@server";

    /** station-groups-state.json's maxLength and the maxLen of a row's name and dimension (UTF-8 bytes). */
    static final int ROWS = 256;
    static final int NAME_BYTES = 128;
    static final int DIM_BYTES = 48;
    static final int MAX_STATIONS = 65535;

    private static Schema schema;
    /** The open host; server thread only (start, stop and every publisher run there). */
    @Nullable
    private static Host host;

    private StationGroupsData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel() {
        return CHANNEL;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        host = MantaData.host(event.getServer(), CHANNEL, schema(), player -> true);
        publish(event.getServer());
    }

    /** Manta closes every host with the server; this one is forgotten so the next server opens its own. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        host = null;
    }

    /** The whole list as it is now - after a group is created, renamed or deleted. */
    public static void publish(MinecraftServer server) {
        Host h = host;
        if (h == null) {
            return;
        }
        List<StationGroup> all = new ArrayList<>(StationGroupSavedData.get(server).all());
        if (all.size() > ROWS) {
            TrainSystemUtilities.LOGGER.warn("[StationGroup] {} groups; the list carries the first {}", all.size(), ROWS);
        }
        h.setList("groups", rows(all));
    }

    /** The groups as list rows, each value within its declaration. */
    static List<List<Object>> rows(Collection<StationGroup> groups) {
        List<List<Object>> out = new ArrayList<>(Math.min(groups.size(), ROWS));
        for (StationGroup g : groups) {
            if (out.size() == ROWS) {
                break;
            }
            BlockPos min = g.minPos();
            BlockPos max = g.maxPos();
            String dim = RailwayManagementData.fit(g.dimensionId(), DIM_BYTES);
            if (!dim.equals(g.dimensionId())) {
                TrainSystemUtilities.LOGGER.warn("[StationGroup] dimension id '{}' is past {} bytes; group {} is listed cut",
                        g.dimensionId(), DIM_BYTES, g.id());
            }
            out.add(List.<Object>of(g.id().toString(), RailwayManagementData.fit(g.name(), NAME_BYTES), dim,
                    min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(),
                    Math.min(g.stationBlockPositions().size(), MAX_STATIONS)));
        }
        return out;
    }

    /**
     * A list row read back as the client's StationGroup: no station ids or positions (see the class note). Null for a
     * row whose id does not parse - the caller leaves it out, as the list decoder before C4 left such a group out.
     */
    @Nullable
    public static StationGroup groupOf(List<?> row) {
        java.util.UUID id = ManagementComputerData.parseUuid((String) row.get(0));
        if (id == null) {
            return null;
        }
        return new StationGroup(id, (String) row.get(1),
                (String) row.get(2),
                new BlockPos((Integer) row.get(3), (Integer) row.get(4), (Integer) row.get(5)),
                new BlockPos((Integer) row.get(6), (Integer) row.get(7), (Integer) row.get(8)),
                List.of(), List.of(), "", null);
    }

    /** A row's station count. */
    public static int stationsOf(List<?> row) {
        return (Integer) row.get(9);
    }
}
