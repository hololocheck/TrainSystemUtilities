package com.trainsystemutilities.client;

import com.manta.api.data.Mirror;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.transit.TransitNavClientState;
import com.trainsystemutilities.client.transit.TransitTerminalClientCache;
import com.trainsystemutilities.client.transit.TransitTerminalState;
import com.trainsystemutilities.network.TrainPositionsData;
import com.trainsystemutilities.network.TransitData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The client end of the transit terminal, the walking navigation and the train positions (MANTA_7_CONCEPT C4, TSU
 * slice T5), open while the client is in a world: the player's three transit hosts ({@link TransitData}) and the
 * server-wide positions ({@link TrainPositionsData}), polled each tick into what the payloads' handlers wrote -
 * {@link TransitTerminalState}'s results, {@link TransitTerminalClientCache} and {@link TransitNavClientState}. The
 * walking navigation renders in the world with no screen open, so these mirrors cannot live on a page. One method per
 * action, its params in the documents' order.
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class TransitClient {

    private static Mirror transit;
    private static Mirror schedule;
    private static Mirror map;
    private static Mirror positions;
    private static int transitRevision = -1;
    private static int scheduleRevision = -1;
    private static int mapRevision = -1;
    private static int positionsRevision = -1;
    /** The answer counters last seen; -1 until the first snapshot, whose answers are history, not news. */
    private static int seenRoute = -1;
    private static int seenNav = -1;

    private TransitClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        close();
        UUID me = event.getPlayer().getUUID();
        transit = Mirror.open(TransitData.channel(me), TransitData.schema());
        schedule = Mirror.open(TransitData.scheduleChannel(me), TransitData.scheduleSchema());
        map = Mirror.open(TransitData.mapChannel(me), TransitData.mapSchema());
        positions = Mirror.open(TrainPositionsData.channel(), TrainPositionsData.schema());
        transitRevision = -1;
        scheduleRevision = -1;
        mapRevision = -1;
        positionsRevision = -1;
        seenRoute = -1;
        seenNav = -1;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }

    private static void close() {
        for (Mirror m : new Mirror[]{transit, schedule, map, positions}) {
            if (m != null) {
                m.close();
            }
        }
        transit = null;
        schedule = null;
        map = null;
        positions = null;
    }

    /** Each tick: what the four hosts sent since the last one. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Mirror t = transit;
        if (t != null) {
            t.poll();
            int revision = t.revision();
            if (revision >= 0 && revision != transitRevision) {
                transitRevision = revision;
                transitAnswers(t);
            }
        }
        Mirror s = schedule;
        if (s != null) {
            s.poll();
            int revision = s.revision();
            if (revision >= 0 && revision != scheduleRevision) {
                scheduleRevision = revision;
                Map<UUID, TransitTerminalClientCache.ScheduleSnapshot> trains = new LinkedHashMap<>();
                for (Object o : (List<?>) s.get("trains")) {
                    List<?> r = (List<?>) o;
                    UUID id = parse((String) r.get(0));
                    if (id == null) {
                        continue;
                    }
                    String next = (String) r.get(2);
                    trains.put(id, new TransitTerminalClientCache.ScheduleSnapshot(id, (String) r.get(1),
                            next.isEmpty() ? null : parse(next), (Integer) r.get(3)));
                }
                TransitTerminalClientCache.replaceSchedules(trains);
            }
        }
        Mirror m = map;
        if (m != null) {
            m.poll();
            int revision = m.revision();
            if (revision >= 0 && revision != mapRevision) {
                mapRevision = revision;
                TransitTerminalClientCache.replaceMapSegments(TransitData.segmentsOf((List<?>) m.get("segments")));
            }
        }
        Mirror p = positions;
        if (p != null) {
            p.poll();
            int revision = p.revision();
            if (revision >= 0 && revision != positionsRevision) {
                positionsRevision = revision;
                TransitTerminalClientCache.replaceTrainPositions(
                        TrainPositionsData.positionsOf((List<?>) p.get("positions")),
                        TransitData.dayTime((Integer) p.get("pos-day-hi"), (Integer) p.get("pos-day-lo")));
            }
        }
    }

    /** A search's routes and a walking path are events: each is taken when its counter moves. */
    private static void transitAnswers(Mirror t) {
        int route = (Integer) t.get("route-seq");
        if (seenRoute >= 0 && route != seenRoute) {
            TransitTerminalState.setLastResults(
                    TransitData.routesOf((List<?>) t.get("routes"), (List<?>) t.get("legs"),
                            (String) t.get("route-reason")),
                    TransitData.dayTime((Integer) t.get("route-day-hi"), (Integer) t.get("route-day-lo")));
        }
        seenRoute = route;
        int nav = (Integer) t.get("nav-seq");
        if (seenNav >= 0 && nav != seenNav) {
            String error = (String) t.get("nav-error");
            List<?> path = (List<?>) t.get("nav-path");
            if (!error.isEmpty() || path.isEmpty()) {
                TransitNavClientState.setError(error);
            } else {
                String target = (String) t.get("nav-target");
                TransitNavClientState.setActivePath(parse(target), (String) t.get("nav-name"),
                        TransitData.pathOf(path), (Integer) t.get("nav-ticks"));
            }
        }
        seenNav = nav;
    }

    /** A route search between two station groups (TransitSearchPayload.send until C4). */
    public static void search(@Nullable UUID from, @Nullable UUID to) {
        if (from == null || to == null) return;
        send(transit, TransitData.SEARCH, from.toString(), to.toString());
    }

    /** Every train's schedule, answered into TransitTerminalClientCache. */
    public static void schedule() {
        send(schedule, TransitData.SCHEDULE);
    }

    /** The track network's segments, answered into TransitTerminalClientCache. */
    public static void map() {
        send(map, TransitData.MAP);
    }

    /** A walking path to a station group's platform, or to the nearest station when the target is null. */
    public static void nav(@Nullable UUID target, int platform) {
        send(transit, TransitData.NAV, target == null ? "" : target.toString(),
                Math.max(0, Math.min(TransitData.MAX_PLATFORM, platform)));
    }

    private static void send(@Nullable Mirror mirror, String action, Object... args) {
        if (mirror != null) {
            mirror.send(action, args);
        }
    }

    @Nullable
    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
