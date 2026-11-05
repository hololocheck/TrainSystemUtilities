package com.trainsystemutilities.client;

import com.manta.api.data.Mirror;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.ManagementComputerData;
import com.trainsystemutilities.network.StationGroupsData;
import com.trainsystemutilities.network.StationsData;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The client end of the station groups (MANTA_7_CONCEPT C4), open while the client is in a world: the server-wide
 * list ({@link StationGroupsData}) into {@link StationGroupClientCache} - which the screens, the HUD and the station
 * range renderer read - and the player's own host ({@link StationsData}): a group's station positions the manage screen
 * asked for, and a vending machine to open. One method per action, its params in stations-state.json's order.
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class StationsClient {

    private static Mirror groups;
    private static Mirror mine;
    private static int groupsRevision = -1;
    private static int mineRevision = -1;
    /** The answer counters last seen; -1 until the first snapshot, whose answers are history, not news. */
    private static int seenDetail = -1;
    private static int seenVending = -1;

    private StationsClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        close();
        groups = Mirror.open(StationGroupsData.channel(), StationGroupsData.schema());
        mine = Mirror.open(StationsData.channel(event.getPlayer().getUUID()), StationsData.schema());
        groupsRevision = -1;
        mineRevision = -1;
        seenDetail = -1;
        seenVending = -1;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }

    private static void close() {
        if (groups != null) {
            groups.close();
            groups = null;
        }
        if (mine != null) {
            mine.close();
            mine = null;
        }
    }

    /** Each tick: what the two hosts sent since the last one. No page draws these mirrors, so the tick polls them. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Mirror g = groups;
        if (g != null) {
            g.poll();
            int revision = g.revision();
            if (revision >= 0 && revision != groupsRevision) {
                groupsRevision = revision;
                List<StationGroup> list = new ArrayList<>();
                Map<UUID, Integer> counts = new HashMap<>();
                for (Object o : (List<?>) g.get("groups")) {
                    List<?> row = (List<?>) o;
                    StationGroup sg = StationGroupsData.groupOf(row);
                    if (sg == null) {
                        continue;
                    }
                    list.add(sg);
                    counts.put(sg.id(), StationGroupsData.stationsOf(row));
                }
                StationGroupClientCache.replaceAll(list, counts);
            }
        }
        Mirror m = mine;
        if (m != null) {
            m.poll();
            int revision = m.revision();
            if (revision >= 0 && revision != mineRevision) {
                mineRevision = revision;
                int detail = (Integer) m.get("detail-seq");
                if (seenDetail >= 0 && detail != seenDetail) {
                    UUID id = parse((String) m.get("detail-group"));
                    if (id != null) {
                        StationGroupClientCache.setPositions(id,
                                StationsData.positionsOf((List<?>) m.get("detail-positions")));
                    }
                }
                seenDetail = detail;
                int vending = (Integer) m.get("vending-seq");
                if (seenVending >= 0 && vending != seenVending) {
                    com.trainsystemutilities.client.gui.TicketVendingMachineScreen.open(
                            new BlockPos((Integer) m.get("vending-x"), (Integer) m.get("vending-y"),
                                    (Integer) m.get("vending-z")),
                            (String) m.get("vending-origin"),
                            StationsData.destsOf((List<?>) m.get("vending-dests")));
                }
                seenVending = vending;
            }
        }
    }

    public static void create(String name) {
        send(StationsData.CREATE, name);
    }

    public static void rename(UUID group, String name) {
        send(StationsData.RENAME, ManagementComputerData.uuidParam(group), name);
    }

    public static void delete(UUID group) {
        send(StationsData.DELETE, ManagementComputerData.uuidParam(group));
    }

    /** One group's station positions, answered into StationGroupClientCache.positions. */
    public static void detail(UUID group) {
        send(StationsData.DETAIL, ManagementComputerData.uuidParam(group));
    }

    public static void buy(BlockPos machine, UUID dest) {
        send(StationsData.BUY, machine.getX(), machine.getY(), machine.getZ(), ManagementComputerData.uuidParam(dest));
    }

    private static void send(String action, Object... args) {
        if (mine != null) {
            mine.send(action, args);
        }
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
