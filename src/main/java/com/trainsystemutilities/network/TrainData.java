package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a player's client needs of the trains on manta:data (MANTA_7_CONCEPT C4, TSU slice T6), on one host per player
 * opened at login (Manta closes it at logout), declared by the state-only document {@link #DOCUMENT}:
 * <ul>
 *   <li>the {@code pantograph} action (PantographTogglePayload until C4);</li>
 *   <li>the electrification screen to open (OpenElectrificationScreenPayload): an event, "value + seq";</li>
 *   <li>the in-train monitors' display data (MonitorDisplayInfoPayload), for the carriages the player tracks.</li>
 * </ul>
 * The monitors are not a state list of every tracked carriage: a carriage's data is recomputed every 5 ticks and its
 * first stop's arrival moves with the day time, and a host sends a set list whole, so a state list would resend every
 * tracked carriage on every update. Each tick's updates are a BATCH instead: a carriage's update is queued for every
 * player tracking it (from PlayerEvent.StartTracking / StopTracking, as the payload went to the entity's trackers), and
 * at the end of the server tick each player's queue - the latest data per carriage, oldest first - is drained, at most
 * {@link #MON_ROWS} rows, the rest waiting for the next tick. Traffic is the payloads': the carriages updated that tick.
 * A client that receives two batches between two of its ticks sees only the second (the mirror applies both, the list
 * is the last); the first batch's carriages then update on their next broadcast, at most 5 ticks later.
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class TrainData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/train-state.json";
    static final String DOC = "train";

    public static final String PANTOGRAPH = "pantograph";

    /** train-state.json's list sizes and the maxLen of the monitors' strings (UTF-8 bytes). */
    static final int MON_ROWS = 48;
    static final int MON_STOPS = 96;
    /** The monitor shows the next two stops (MonitorMovementBehaviour builds at most two). */
    static final int STOPS_PER_ROW = 2;
    static final int CODE_BYTES = 64;
    static final int PARTNER_BYTES = 256;
    static final int STATION_BYTES = 256;
    /** A carriage index's declared max, as the payload's decoder took it. */
    public static final int MAX_CAR = 1024;

    /** One stop of a monitor (MonitorDisplayInfoPayload.Stop until C4). */
    public record MonitorStop(String stationName, int stopSec, long estArrivalDayTime, long estDepartureDayTime,
                              boolean atStation) {
    }

    /** One carriage's monitor data. */
    public record MonitorRow(int entityId, UUID trainId, String routeType, String trainType, String couplingStatus,
                             String couplingPartner, List<MonitorStop> stops) {
    }

    /**
     * The monitor rows waiting for each player: the latest row per carriage, in the order each carriage was first
     * queued. Pure bookkeeping - no Minecraft type - so the drain order is tested on its own.
     */
    static final class MonitorBatches {
        private final Map<UUID, LinkedHashMap<Integer, MonitorRow>> queues = new HashMap<>();

        void offer(UUID player, MonitorRow row) {
            queues.computeIfAbsent(player, k -> new LinkedHashMap<>()).put(row.entityId(), row);
        }

        /** At most {@code max} rows, oldest first; they leave the queue. */
        List<MonitorRow> drain(UUID player, int max) {
            LinkedHashMap<Integer, MonitorRow> q = queues.get(player);
            List<MonitorRow> out = new ArrayList<>();
            if (q == null) {
                return out;
            }
            Iterator<MonitorRow> it = q.values().iterator();
            while (it.hasNext() && out.size() < max) {
                out.add(it.next());
                it.remove();
            }
            if (q.isEmpty()) {
                queues.remove(player);
            }
            return out;
        }

        Set<UUID> players() {
            return new HashSet<>(queues.keySet());
        }

        void forget(UUID player) {
            queues.remove(player);
        }

        void clear() {
            queues.clear();
        }
    }

    private static Schema schema;
    /** The open host of each player; server thread only (login, logout, the tick and every caller run there). */
    private static final Map<UUID, Host> HOSTS = new HashMap<>();
    /** Carriage entity id -> the players tracking it; server thread only. */
    private static final Map<Integer, Set<UUID>> TRACKERS = new HashMap<>();
    private static final MonitorBatches BATCHES = new MonitorBatches();

    private TrainData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel(UUID player) {
        return MantaData.channel(TrainSystemUtilities.MOD_ID, DOC, player);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Host host = MantaData.host(player, TrainSystemUtilities.MOD_ID, DOC, schema());
        // Params in train-state.json's declaration order.
        host.on(PANTOGRAPH, (args, p) -> {
            UUID train = ManagementComputerData.parseUuid((String) args.get(0));
            if (train != null) {
                PantographAction.apply(p, train, (Integer) args.get(1), (Integer) args.get(2),
                        new BlockPos((Integer) args.get(3), (Integer) args.get(4), (Integer) args.get(5)));
            }
        });
        HOSTS.put(player.getUUID(), host);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        HOSTS.remove(id);
        BATCHES.forget(id);
        TRACKERS.values().removeIf(players -> players.remove(id) && players.isEmpty());
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof CarriageContraptionEntity && event.getEntity() instanceof ServerPlayer p) {
            TRACKERS.computeIfAbsent(event.getTarget().getId(), k -> new HashSet<>()).add(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void onStopTracking(PlayerEvent.StopTracking event) {
        Set<UUID> players = TRACKERS.get(event.getTarget().getId());
        if (players != null) {
            players.remove(event.getEntity().getUUID());
            if (players.isEmpty()) {
                TRACKERS.remove(event.getTarget().getId());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TRACKERS.clear();
        BATCHES.clear();
    }

    /** The end of each server tick: every player's waiting monitor rows, as one batch each. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (UUID player : BATCHES.players()) {
            Host host = HOSTS.get(player);
            if (host == null) {
                BATCHES.forget(player);
                continue;
            }
            List<MonitorRow> rows = BATCHES.drain(player, MON_ROWS);
            host.setList("mon-rows", monitorRows(rows));
            host.setList("mon-stops", stopRows(rows));
            host.set("mon-seq", ((Integer) host.get("mon-seq") + 1) & 0xFFFF);
        }
    }

    /** A carriage's monitor data for everyone tracking it (MonitorDisplayInfoPayload via sendToPlayersTrackingEntity). */
    public static void queueMonitor(Entity carriage, MonitorRow row) {
        Set<UUID> players = TRACKERS.get(carriage.getId());
        if (players == null) {
            return;
        }
        for (UUID p : players) {
            BATCHES.offer(p, row);
        }
    }

    /** The electrification screen to open for {@code trainId} (OpenElectrificationScreenPayload until C4). */
    public static void openElectrification(ServerPlayer player, UUID trainId) {
        Host host = HOSTS.get(player.getUUID());
        if (host == null) {
            return;
        }
        host.set("open-elec-train", trainId.toString());
        host.set("open-elec-seq", ((Integer) host.get("open-elec-seq") + 1) & 0xFFFF);
    }

    // ===== rows: every value within its declaration =====

    static List<List<Object>> monitorRows(List<MonitorRow> rows) {
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (MonitorRow r : rows) {
            out.add(List.<Object>of(r.entityId(), r.trainId().toString(), TransitData.text(r.routeType(), CODE_BYTES),
                    TransitData.text(r.trainType(), CODE_BYTES), TransitData.text(r.couplingStatus(), CODE_BYTES),
                    TransitData.text(r.couplingPartner(), PARTNER_BYTES)));
        }
        return out;
    }

    /** Each row's first {@link #STOPS_PER_ROW} stops, naming their row. */
    static List<List<Object>> stopRows(List<MonitorRow> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<MonitorStop> stops = rows.get(i).stops();
            for (int s = 0; s < Math.min(stops.size(), STOPS_PER_ROW); s++) {
                MonitorStop st = stops.get(s);
                out.add(List.<Object>of(i, TransitData.text(st.stationName(), STATION_BYTES), st.stopSec(),
                        (int) (st.estArrivalDayTime() >>> 32), (int) st.estArrivalDayTime(),
                        (int) (st.estDepartureDayTime() >>> 32), (int) st.estDepartureDayTime(), st.atStation()));
            }
        }
        return out;
    }

    /** A batch read back; a row whose train id does not parse is left out. */
    public static List<MonitorRow> monitorsOf(List<?> rowList, List<?> stopList) {
        List<List<MonitorStop>> stops = new ArrayList<>();
        for (int i = 0; i < rowList.size(); i++) stops.add(new ArrayList<>());
        for (Object o : stopList) {
            List<?> s = (List<?>) o;
            int row = (Integer) s.get(0);
            if (row < stops.size()) {
                stops.get(row).add(new MonitorStop((String) s.get(1), (Integer) s.get(2),
                        TransitData.dayTime((Integer) s.get(3), (Integer) s.get(4)),
                        TransitData.dayTime((Integer) s.get(5), (Integer) s.get(6)), (Boolean) s.get(7)));
            }
        }
        List<MonitorRow> out = new ArrayList<>(rowList.size());
        for (int i = 0; i < rowList.size(); i++) {
            List<?> r = (List<?>) rowList.get(i);
            UUID train = ManagementComputerData.parseUuid((String) r.get(1));
            if (train != null) {
                out.add(new MonitorRow((Integer) r.get(0), train, (String) r.get(2), (String) r.get(3),
                        (String) r.get(4), (String) r.get(5), stops.get(i)));
            }
        }
        return out;
    }
}
