package com.trainsystemutilities.client.transit;

import com.trainsystemutilities.network.TrainPositionsData;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import com.trainsystemutilities.station.routing.TrainRouter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wiki's demo terminal (改善1, 2026-10-07: the user asked the page to search a demo departure and arrival, show the
 * guidance, and operate the timetable and the map): two lines - a loop and a line across it - their stations as station
 * groups, a dozen trains on them, their track, and a router that answers a search on them as the server's would (each
 * line ridden directly, or with a change at a station both serve). Nothing here is the player's world; the names are the
 * management computer's demo trains' (ManagementComputerScreenV2.wikiCreate).
 */
@OnlyIn(Dist.CLIENT)
final class TransitTerminalWikiDemo {

    private TransitTerminalWikiDemo() {}

    private record Stop(String name, int x, int z) {}

    private record Line(String letters, String color, String train, boolean loop, Stop... stops) {}

    /** A loop (山手線) and a line across it (中央線快速), sharing 東京 and 新宿. Block coordinates of the demo's own map. */
    private static final Line LOOP = new Line("JY", "#80C241", "山手線 E235系", true,
            new Stop("東京", 300, 0), new Stop("有楽町", 290, 50), new Stop("新橋", 265, 100), new Stop("品川", 210, 190),
            new Stop("渋谷", 20, 160), new Stop("新宿", -60, 10), new Stop("池袋", 0, -150), new Stop("上野", 230, -140));
    private static final Line CHUO = new Line("JC", "#F15A22", "中央線快速 E233系", false,
            new Stop("東京", 300, 0), new Stop("神田", 290, -40), new Stop("御茶ノ水", 200, -50), new Stop("四ツ谷", 60, -10),
            new Stop("新宿", -60, 10));
    /** A ride between two neighbouring stations, and the wait for the next train, in ticks. */
    private static final int TICKS_PER_STOP = 400;
    private static final int WAIT_TICKS = 300;

    private static UUID id(String what) {
        return UUID.nameUUIDFromBytes(("tsu-wiki-transit-" + what).getBytes(StandardCharsets.UTF_8));
    }

    /** The stations, one group per name, in the dimension the player stands in (the terminal lists that one's). */
    static List<StationGroup> groups() {
        Minecraft mc = Minecraft.getInstance();
        String dim = mc != null && mc.player != null
                ? mc.player.level().dimension().location().toString() : "minecraft:overworld";
        Map<String, StationGroup> out = new LinkedHashMap<>();
        for (Line line : new Line[] {LOOP, CHUO}) {
            for (Stop s : line.stops()) {
                out.computeIfAbsent(s.name(), n -> new StationGroup(id("station-" + n), n, dim,
                        new BlockPos(s.x() - 8, 64, s.z() - 5), new BlockPos(s.x() + 8, 70, s.z() + 5),
                        List.of(), List.of(), StationGroup.NAMING_TEMPLATE_JP, null));
            }
        }
        return List.copyOf(out.values());
    }

    /** The terminal's state for a stand-in: the demo's stations, searched by {@link #routes}. */
    static TransitTerminalState.Data state() {
        return new TransitTerminalState.Data(groups(), TransitTerminalWikiDemo::routes);
    }

    /** What the stand-in shows instead of the synced caches: the trains, the track and where the trains are. */
    static TransitTerminalClientCache.WikiData cache() {
        Map<UUID, TransitTerminalClientCache.ScheduleSnapshot> schedules = new LinkedHashMap<>();
        Map<UUID, TrainPositionsData.TrainPosition> positions = new LinkedHashMap<>();
        // Each line: trains spread along it, each between two stops, heading for the next.
        addTrains(LOOP, "(外回り)", 0, 4, schedules, positions);
        addTrains(LOOP, "(内回り)", 1, 3, schedules, positions);
        addTrains(CHUO, "(上り)", 2, 3, schedules, positions);
        addTrains(CHUO, "(下り)", 3, 2, schedules, positions);
        List<int[]> segments = new ArrayList<>();
        for (Line line : new Line[] {LOOP, CHUO}) {
            Stop[] s = line.stops();
            for (int i = 0; i + 1 < s.length; i++) segments.add(new int[] {s[i].x(), s[i].z(), s[i + 1].x(), s[i + 1].z()});
            if (line.loop()) segments.add(new int[] {s[s.length - 1].x(), s[s.length - 1].z(), s[0].x(), s[0].z()});
        }
        Minecraft mc = Minecraft.getInstance();
        return new TransitTerminalClientCache.WikiData(groups(), schedules, segments, positions,
                mc != null && mc.level != null ? mc.level.getDayTime() : 0L);
    }

    private static void addTrains(Line line, String direction, int salt, int count,
                                  Map<UUID, TransitTerminalClientCache.ScheduleSnapshot> schedules,
                                  Map<UUID, TrainPositionsData.TrainPosition> positions) {
        Stop[] s = line.stops();
        for (int k = 0; k < count; k++) {
            int from = (k * s.length / count + salt) % s.length;
            int to = from + 1 < s.length ? from + 1 : (line.loop() ? 0 : from - 1);
            UUID train = trainId(line, direction, k);
            int eta = 120 + ((k * 97 + salt * 53) % 5) * 70;
            schedules.put(train, new TransitTerminalClientCache.ScheduleSnapshot(train,
                    line.train() + " " + direction, id("station-" + s[to].name()), eta));
            float t = 0.35f + 0.1f * k;
            positions.put(train, new TrainPositionsData.TrainPosition(train,
                    s[from].x() + (s[to].x() - s[from].x()) * t, s[from].z() + (s[to].z() - s[from].z()) * t,
                    0.8f, eta));
        }
    }

    private static UUID trainId(Line line, String direction, int k) {
        return id("train-" + line.letters() + direction + k);
    }

    /** Where {@code name} stands on {@code line}, or -1. */
    private static int indexOn(Line line, String name) {
        Stop[] s = line.stops();
        for (int i = 0; i < s.length; i++) if (s[i].name().equals(name)) return i;
        return -1;
    }

    private static String nameOf(UUID group) {
        for (StationGroup g : groups()) if (g.id().equals(group)) return g.name();
        return null;
    }

    /** The router: each line ridden directly where both stations are on it, else a change at a station both lines serve. */
    static List<ComposedRouteFinder.ComposedRoute> routes(UUID from, UUID to) {
        String a = nameOf(from);
        String b = nameOf(to);
        List<ComposedRouteFinder.ComposedRoute> out = new ArrayList<>();
        if (a == null || b == null || a.equals(b)) {
            out.add(ComposedRouteFinder.ComposedRoute.notFound("no route"));
            return out;
        }
        for (Line line : new Line[] {LOOP, CHUO}) {
            List<TrainRouter.Leg> leg = ride(line, a, b, WAIT_TICKS);
            if (leg != null) out.add(route(from, a, to, b, leg));
        }
        // A change: one line to a station both serve, the other from there.
        for (String via : new String[] {"東京", "新宿"}) {
            for (Line first : new Line[] {LOOP, CHUO}) {
                Line second = first == LOOP ? CHUO : LOOP;
                List<TrainRouter.Leg> one = ride(first, a, via, WAIT_TICKS);
                if (one == null) continue;
                int arrive = WAIT_TICKS + one.get(0).travelTicks();
                List<TrainRouter.Leg> two = ride(second, via, b, arrive + 160);
                if (two == null) continue;
                List<TrainRouter.Leg> legs = new ArrayList<>(one);
                legs.addAll(two);
                out.add(route(from, a, to, b, legs));
            }
        }
        out.sort(java.util.Comparator.comparingInt(ComposedRouteFinder.ComposedRoute::totalTicks));
        return out.size() > 3 ? List.copyOf(out.subList(0, 3)) : out;
    }

    /** One train on {@code line} from {@code a} to {@code b} (the shorter way round a loop), leaving in {@code wait}. */
    private static List<TrainRouter.Leg> ride(Line line, String a, String b, int wait) {
        int i = indexOn(line, a);
        int j = indexOn(line, b);
        if (i < 0 || j < 0 || i == j) return null;
        int n = line.stops().length;
        int stops = line.loop() ? Math.min(Math.floorMod(j - i, n), Math.floorMod(i - j, n)) : Math.abs(j - i);
        boolean outer = !line.loop() || Math.floorMod(j - i, n) <= Math.floorMod(i - j, n);
        String direction = line.loop() ? (outer ? "(外回り)" : "(内回り)") : (j < i ? "(上り)" : "(下り)");
        return List.of(new TrainRouter.Leg(id("station-" + a), id("station-" + b), trainId(line, direction, 0),
                stops * TICKS_PER_STOP, wait, 1 + (stops % 2), 2 - (stops % 2), line.letters(), i + 1, line.color(),
                12, 40, 0));
    }

    private static ComposedRouteFinder.ComposedRoute route(UUID from, String a, UUID to, String b,
                                                           List<TrainRouter.Leg> legs) {
        int total = 0;
        for (TrainRouter.Leg leg : legs) total = Math.max(total, leg.departureTicksFromNow() + leg.travelTicks());
        Minecraft mc = Minecraft.getInstance();
        BlockPos at = mc != null && mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
        return new ComposedRouteFinder.ComposedRoute(true, total,
                new ComposedRouteFinder.WalkLeg(200, 24, at, at.offset(18, 0, 16)), from, a, to, b, List.copyOf(legs), "");
    }
}
