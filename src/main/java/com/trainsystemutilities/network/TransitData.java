package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.TrainScheduleCache;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import com.trainsystemutilities.station.routing.TrainRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transit terminal and the walking navigation on manta:data (MANTA_7_CONCEPT C4, TSU slice T5): what a route
 * search, a schedule request, a map request and a walking route request sent as payloads of their own is the actions
 * of three hosts per player, opened at login (Manta closes them at logout), each declared by a state-only document; what
 * the payloads carried back is those hosts' fields. Three documents, not one, because the transport carries a snapshot
 * of at most 64 KiB and the schedule and the map each need most of one.
 * <ul>
 *   <li>{@code transit} ({@link #DOCUMENT}): {@code search} answers the top routes and their legs, {@code nav} a walking
 *   path or why there is none. Both are events - "value + seq", and the first snapshot's counter is history.</li>
 *   <li>{@code transit-schedule}: {@code schedule} answers every train's name, next station and ETA - state, which the
 *   client takes whenever the list's revision moves.</li>
 *   <li>{@code transit-map}: {@code map} answers the track network as segments - state, taken the same way. Every
 *   answer is sent whole, as the payload was: a host sends each set field again even when the value is equal
 *   (DataHost.set marks it dirty unconditionally).</li>
 * </ul>
 * The trains' live positions are server-wide ({@link TrainPositionsData}). Every string a row carries is cut to its
 * declaration here: the payloads' writers threw on a group name past 64 chars, a train name past 64 or a legs string
 * past 4096 (about 25 legs), and a thrown encoder disconnected the asker.
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class TransitData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/transit-state.json";
    static final String SCHEDULE_DOCUMENT = "/assets/trainsystemutilities/manta/transit-schedule-state.json";
    static final String MAP_DOCUMENT = "/assets/trainsystemutilities/manta/transit-map-state.json";
    static final String DOC = "transit";
    static final String SCHEDULE_DOC = "transit-schedule";
    static final String MAP_DOC = "transit-map";

    public static final String SEARCH = "search";
    public static final String NAV = "nav";
    public static final String SCHEDULE = "schedule";
    public static final String MAP = "map";

    /** The documents' list sizes. */
    static final int ROUTE_ROWS = 3;
    static final int LEG_ROWS = 128;
    static final int PATH_ROWS = 1024;
    static final int TRAIN_ROWS = 256;
    public static final int SEGMENT_ROWS = 4000;
    /** The documents' maxLen of a shown string (UTF-8 bytes). */
    static final int NAME_BYTES = 128;
    static final int REASON_BYTES = 640;
    static final int ERROR_BYTES = 256;
    static final int LETTERS_BYTES = 72;
    static final int COLOR_BYTES = 32;
    /** transit-state.json's nav platform max; the client clamps to it (a value past a declaration throws in send). */
    public static final int MAX_PLATFORM = 4096;
    /** The declared x / z and y bounds of a path point or a segment end; a value past them is clamped. */
    static final int XZ_MIN = -33554432;
    static final int XZ_MAX = 33554431;
    static final int Y_MIN = -2048;
    static final int Y_MAX = 2047;

    private static Schema schema;
    private static Schema scheduleSchema;
    private static Schema mapSchema;

    /** A player's three open hosts. */
    private record Hosts(Host transit, Host schedule, Host map) {
    }

    /** The open hosts of each player; server thread only (login, logout and every answer run there). */
    private static final Map<UUID, Hosts> HOSTS = new HashMap<>();

    private TransitData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static synchronized Schema scheduleSchema() {
        if (scheduleSchema == null) {
            scheduleSchema = MantaData.schemaResource(TrainSystemUtilities.class, SCHEDULE_DOCUMENT);
        }
        return scheduleSchema;
    }

    public static synchronized Schema mapSchema() {
        if (mapSchema == null) {
            mapSchema = MantaData.schemaResource(TrainSystemUtilities.class, MAP_DOCUMENT);
        }
        return mapSchema;
    }

    public static String channel(UUID player) {
        return MantaData.channel(TrainSystemUtilities.MOD_ID, DOC, player);
    }

    public static String scheduleChannel(UUID player) {
        return MantaData.channel(TrainSystemUtilities.MOD_ID, SCHEDULE_DOC, player);
    }

    public static String mapChannel(UUID player) {
        return MantaData.channel(TrainSystemUtilities.MOD_ID, MAP_DOC, player);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Host transit = MantaData.host(player, TrainSystemUtilities.MOD_ID, DOC, schema());
        // Params in transit-state.json's declaration order.
        transit.on(SEARCH, (args, p) -> {
            UUID from = ManagementComputerData.parseUuid((String) args.get(0));
            UUID to = ManagementComputerData.parseUuid((String) args.get(1));
            if (from != null && to != null) TransitSearchAction.apply(p, from, to);
        });
        transit.on(NAV, (args, p) -> {
            // "" asks for the nearest station, as the payload's absent target did.
            String target = (String) args.get(0);
            UUID id = target.isEmpty() ? null : ManagementComputerData.parseUuid(target);
            if (id != null || target.isEmpty()) NavPathAction.apply(p, id, (Integer) args.get(1));
        });
        Host schedule = MantaData.host(player, TrainSystemUtilities.MOD_ID, SCHEDULE_DOC, scheduleSchema());
        schedule.on(SCHEDULE, (args, p) -> answerSchedule(p, TrainScheduleCache.all()));
        Host map = MantaData.host(player, TrainSystemUtilities.MOD_ID, MAP_DOC, mapSchema());
        map.on(MAP, (args, p) -> TransitMapAction.apply(p));
        HOSTS.put(player.getUUID(), new Hosts(transit, schedule, map));
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        HOSTS.remove(event.getEntity().getUUID());
    }

    /**
     * The asker's hosts - or null when this player object is no longer the one logged in. A walking path is answered
     * from the dispatcher's worker after it finishes (or is cancelled at logout), and a player who logged back in by
     * then has new hosts under the same UUID: the old session's answer must not land there. The payload it replaced
     * went to the old connection and was dropped with it.
     */
    @Nullable
    private static Hosts hostsOf(ServerPlayer player) {
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return null;
        }
        return HOSTS.get(player.getUUID());
    }

    // ===== answers (server thread) =====

    /**
     * What a search found (TransitResultPayload until C4), on the asker's transit host: at most {@link #ROUTE_ROWS}
     * routes whose legs fit {@link #LEG_ROWS} together - a route that does not fit is left out whole, never cut.
     */
    static void answerRoutes(ServerPlayer player, List<ComposedRouteFinder.ComposedRoute> routes, String reason,
                             long dayTime) {
        Hosts h = hostsOf(player);
        if (h == null) {
            return;
        }
        List<ComposedRouteFinder.ComposedRoute> kept = fitting(routes);
        h.transit.set("route-reason", text(reason, REASON_BYTES));
        h.transit.set("route-day-hi", (int) (dayTime >>> 32));
        h.transit.set("route-day-lo", (int) dayTime);
        h.transit.setList("routes", routeRows(kept));
        h.transit.setList("legs", legRows(kept));
        h.transit.set("route-seq", ((Integer) h.transit.get("route-seq") + 1) & 0xFFFF);
    }

    /** A walking path (NavPathPayload until C4), on the asker's transit host; the path is cut to {@link #PATH_ROWS}. */
    static void answerNav(ServerPlayer player, UUID target, String name, List<BlockPos> path, int ticks) {
        nav(player, target.toString(), name, path, ticks, "");
    }

    /** Why there is no walking path (NavPathPayload.empty until C4). */
    static void answerNavError(ServerPlayer player, String error) {
        nav(player, "", "", List.of(), 0, error);
    }

    private static void nav(ServerPlayer player, String target, String name, List<BlockPos> path, int ticks,
                            String error) {
        Hosts h = hostsOf(player);
        if (h == null) {
            return;
        }
        h.transit.set("nav-target", target);
        h.transit.set("nav-name", text(name, NAME_BYTES));
        h.transit.set("nav-ticks", ticks);
        h.transit.set("nav-error", text(error, ERROR_BYTES));
        h.transit.setList("nav-path", pathRows(path));
        h.transit.set("nav-seq", ((Integer) h.transit.get("nav-seq") + 1) & 0xFFFF);
    }

    /** Every train's schedule (TransitSchedulePayload until C4), on the asker's schedule host. */
    static void answerSchedule(ServerPlayer player, Map<UUID, TrainScheduleCache.Snapshot> all) {
        Hosts h = hostsOf(player);
        if (h == null) {
            return;
        }
        if (all.size() > TRAIN_ROWS) {
            TrainSystemUtilities.LOGGER.warn("[Transit] {} trains; the schedule lists the first {}", all.size(),
                    TRAIN_ROWS);
        }
        h.schedule.setList("trains", trainRows(all));
    }

    /** The track network's segments {x1, z1, x2, z2} (TransitMapPayload until C4), on the asker's map host. */
    static void answerMap(ServerPlayer player, List<int[]> segments) {
        Hosts h = hostsOf(player);
        if (h == null) {
            return;
        }
        h.map.setList("segments", segmentRows(segments));
    }

    // ===== rows: every value within its declaration =====

    /** The routes to answer: in order, at most {@link #ROUTE_ROWS}, whose legs fit {@link #LEG_ROWS} together. */
    static List<ComposedRouteFinder.ComposedRoute> fitting(List<ComposedRouteFinder.ComposedRoute> routes) {
        List<ComposedRouteFinder.ComposedRoute> kept = new ArrayList<>(ROUTE_ROWS);
        int legs = 0;
        for (ComposedRouteFinder.ComposedRoute r : routes) {
            if (kept.size() == ROUTE_ROWS) {
                break;
            }
            int n = r.trainLegs().size();
            if (legs + n > LEG_ROWS) {
                TrainSystemUtilities.LOGGER.warn("[Transit] a route of {} legs does not fit the {} the answer carries",
                        n, LEG_ROWS - legs);
                continue;
            }
            kept.add(r);
            legs += n;
        }
        return kept;
    }

    static List<List<Object>> routeRows(List<ComposedRouteFinder.ComposedRoute> routes) {
        List<List<Object>> rows = new ArrayList<>(routes.size());
        for (ComposedRouteFinder.ComposedRoute r : routes) {
            rows.add(List.<Object>of(r.totalTicks(), id(r.fromGroupId()), text(r.fromGroupName(), NAME_BYTES),
                    id(r.toGroupId()), text(r.toGroupName(), NAME_BYTES),
                    r.walkToFrom() == null ? 0 : r.walkToFrom().approxTicks()));
        }
        return rows;
    }

    static List<List<Object>> legRows(List<ComposedRouteFinder.ComposedRoute> routes) {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            for (TrainRouter.Leg l : routes.get(i).trainLegs()) {
                rows.add(List.<Object>of(i, id(l.fromGroupId()), id(l.toGroupId()), id(l.trainId()), l.travelTicks(),
                        l.departureTicksFromNow(), l.boardPlatform(), l.alightPlatform(),
                        text(l.symbolLetters(), LETTERS_BYTES), l.symbolNumber(), text(l.symbolColor(), COLOR_BYTES),
                        l.sampleCount(), l.stdDevTicks(), l.delayTicks()));
            }
        }
        return rows;
    }

    static List<List<Object>> pathRows(List<BlockPos> path) {
        List<List<Object>> rows = new ArrayList<>(Math.min(path.size(), PATH_ROWS));
        for (BlockPos b : path) {
            if (rows.size() == PATH_ROWS) {
                break;
            }
            rows.add(List.<Object>of(xz(b.getX()), clamp(b.getY(), Y_MIN, Y_MAX), xz(b.getZ())));
        }
        return rows;
    }

    static List<List<Object>> trainRows(Map<UUID, TrainScheduleCache.Snapshot> all) {
        List<List<Object>> rows = new ArrayList<>(Math.min(all.size(), TRAIN_ROWS));
        for (Map.Entry<UUID, TrainScheduleCache.Snapshot> e : all.entrySet()) {
            if (rows.size() == TRAIN_ROWS) {
                break;
            }
            TrainScheduleCache.Snapshot s = e.getValue();
            rows.add(List.<Object>of(e.getKey().toString(), text(s.trainName(), NAME_BYTES), id(s.nextGroupId()),
                    s.etaTicksToNext()));
        }
        return rows;
    }

    static List<List<Object>> segmentRows(List<int[]> segments) {
        List<List<Object>> rows = new ArrayList<>(Math.min(segments.size(), SEGMENT_ROWS));
        for (int[] s : segments) {
            if (rows.size() == SEGMENT_ROWS) {
                break;
            }
            rows.add(List.<Object>of(xz(s[0]), xz(s[1]), xz(s[2]), xz(s[3])));
        }
        return rows;
    }

    // ===== reading an answer back (the client, and the tests) =====

    /**
     * The routes of an answer as TransitResultPayload's handler built them: the legs in route order; no route at all is
     * one not-found route carrying the reason. A leg or an id that does not parse is left out, as parseLegs left it.
     */
    public static List<ComposedRouteFinder.ComposedRoute> routesOf(List<?> routeRows, List<?> legRows, String reason) {
        List<ComposedRouteFinder.ComposedRoute> routes = new ArrayList<>(routeRows.size());
        for (int i = 0; i < routeRows.size(); i++) {
            List<?> r = (List<?>) routeRows.get(i);
            List<TrainRouter.Leg> legs = new ArrayList<>();
            for (Object o : legRows) {
                List<?> l = (List<?>) o;
                if ((Integer) l.get(0) != i) {
                    continue;
                }
                TrainRouter.Leg leg = legOf(l);
                if (leg != null) {
                    legs.add(leg);
                }
            }
            int walk = (Integer) r.get(5);
            ComposedRouteFinder.WalkLeg walkLeg = walk > 0 ? new ComposedRouteFinder.WalkLeg(walk, walk, null, null)
                    : null;
            routes.add(new ComposedRouteFinder.ComposedRoute(true, (Integer) r.get(0), walkLeg,
                    ManagementComputerData.parseUuid((String) r.get(1)), (String) r.get(2),
                    ManagementComputerData.parseUuid((String) r.get(3)), (String) r.get(4), legs, "ok"));
        }
        if (routes.isEmpty()) {
            routes.add(new ComposedRouteFinder.ComposedRoute(false, Integer.MAX_VALUE, null, null, "", null, "",
                    List.of(), reason));
        }
        return routes;
    }

    @Nullable
    static TrainRouter.Leg legOf(List<?> l) {
        UUID from = ManagementComputerData.parseUuid((String) l.get(1));
        UUID to = ManagementComputerData.parseUuid((String) l.get(2));
        String trainText = (String) l.get(3);
        UUID train = trainText.isEmpty() ? null : ManagementComputerData.parseUuid(trainText);
        if (from == null || to == null || (train == null && !trainText.isEmpty())) {
            return null;
        }
        return new TrainRouter.Leg(from, to, train, (Integer) l.get(4), (Integer) l.get(5), (Integer) l.get(6),
                (Integer) l.get(7), (String) l.get(8), (Integer) l.get(9), (String) l.get(10), (Integer) l.get(11),
                (Integer) l.get(12), (Integer) l.get(13));
    }

    /** A nav-path list as the path it was made from. */
    public static List<BlockPos> pathOf(List<?> rows) {
        List<BlockPos> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            List<?> r = (List<?>) o;
            out.add(new BlockPos((Integer) r.get(0), (Integer) r.get(1), (Integer) r.get(2)));
        }
        return out;
    }

    /** A segments list as the int[]{x1, z1, x2, z2} the map draws. */
    public static List<int[]> segmentsOf(List<?> rows) {
        List<int[]> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            List<?> r = (List<?>) o;
            out.add(new int[]{(Integer) r.get(0), (Integer) r.get(1), (Integer) r.get(2), (Integer) r.get(3)});
        }
        return out;
    }

    /** A long carried as two ints. */
    public static long dayTime(int hi, int lo) {
        return ((long) hi << 32) | (lo & 0xFFFFFFFFL);
    }

    // ===== values =====

    /** A UUID param, or "" for none. */
    static String id(@Nullable UUID id) {
        return id == null ? "" : id.toString();
    }

    /** A shown string cut to its declaration ({@link RailwayManagementData#fit}); null is "". */
    static String text(@Nullable String s, int maxBytes) {
        return RailwayManagementData.fit(s == null ? "" : s, maxBytes);
    }

    static int xz(int v) {
        return clamp(v, XZ_MIN, XZ_MAX);
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
