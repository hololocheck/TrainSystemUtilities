package com.trainsystemutilities.station.routing;

import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.station.TrainScheduleCache;
import com.trainsystemutilities.station.TrainScheduleCache.Snapshot;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * 駅グループ間の経路探索 (双方向 Dijkstra)。
 *
 * <p>グラフ構築: {@link TrainScheduleCache} の各列車の周回スケジュール上の隣接駅 (a → b) を
 * 1 本の有向辺とする。重みは「a で乗車してから b 着までの推定 tick」。
 * 同じ a-b 間を複数列車が通る場合は最短時間を採用する。
 *
 * <p>双方向 Dijkstra: 起点 from と終点 to から同時に探索を進め、訪問済 set が交差した時点で
 * 最短距離を確定する (大規模グラフでの探索範囲半減)。
 *
 * <p>戻り値はグループ ID 列 + 各 leg で乗る train ID。
 */
public final class TrainRouter {

    /** Dijkstra で確定する最大 hop 数 (DoS 防御)。 */
    private static final int MAX_HOPS = 32;

    private TrainRouter() {}

    /**
     * 1 列車で 1 区間移動する leg。
     *
     * @param sampleCount         実走サンプル数 (0 = 推定のみ、≥5 = 確定的)
     * @param stdDevTicks         所要時間の標準偏差
     * @param delayTicks          現在の遅延 (信号待ち等で発生した分)
     */
    public record Leg(
            UUID fromGroupId, UUID toGroupId, UUID trainId,
            int travelTicks,
            int departureTicksFromNow,
            int boardPlatform,
            int alightPlatform,
            String symbolLetters,
            int symbolNumber,
            String symbolColor,
            int sampleCount,
            int stdDevTicks,
            int delayTicks
    ) {}

    public record Route(boolean found, int totalTicks, List<Leg> legs) {
        public static Route notFound() { return new Route(false, Integer.MAX_VALUE, List.of()); }
    }

    /**
     * グラフのエッジ。
     *
     * @param toGroupId             到着グループ
     * @param travelTicks           走行のみの時間 (発 → 着)
     * @param viaTrainId            この edge に乗る列車
     * @param etaFromGroupFromNow   この列車が fromGroup に到達するまでの ticks (現在時刻基準)
     * @param boardStationId        fromGroup でこの列車が停まる Create GlobalStation UUID
     * @param alightStationId       toGroup でこの列車が停まる Create GlobalStation UUID
     */
    private record Edge(UUID toGroupId, int travelTicks, UUID viaTrainId,
                        int etaFromGroupFromNow,
                        UUID boardStationId, UUID alightStationId) {
        /** Dijkstra 重み = 待ち時間 + 走行時間 (現在時刻からの total)。 */
        int weight() { return etaFromGroupFromNow + travelTicks; }
    }

    /** 起点 from から終点 to まで列車だけで到達する最短経路を返す。 */
    public static Route findRoute(MinecraftServer server, UUID fromGroupId, UUID toGroupId) {
        return findRouteImpl(server, fromGroupId, toGroupId, java.util.Set.of());
    }

    /**
     * Yen 風の上位 K 候補列挙。
     * 1 本目を求めた後、その経路上の各エッジを順に「除外」して再探索し、
     * 最短のものを 2 本目として採用、以降同様に K 本まで取る。
     */
    public static java.util.List<Route> findRoutes(MinecraftServer server,
                                                   UUID fromGroupId, UUID toGroupId, int k) {
        java.util.List<Route> result = new java.util.ArrayList<>();
        Route best = findRoute(server, fromGroupId, toGroupId);
        if (!best.found()) return result;
        result.add(best);
        java.util.Set<String> alreadySeen = new java.util.HashSet<>();
        alreadySeen.add(routeKey(best));
        // 累積除外集合: 既に経路に出たエッジを 1 本ずつ除外した候補から選ぶ。
        // 簡易版 Yen: 直前の最良経路の各エッジを 1 本だけ除外して再探索 → 最短を採用。
        for (int i = 1; i < k; i++) {
            Route candidate = null;
            for (Leg leg : result.get(result.size() - 1).legs()) {
                String edgeKey = leg.fromGroupId() + "->" + leg.toGroupId();
                Route r = findRouteImpl(server, fromGroupId, toGroupId, java.util.Set.of(edgeKey));
                if (!r.found()) continue;
                if (alreadySeen.contains(routeKey(r))) continue;
                if (candidate == null || r.totalTicks() < candidate.totalTicks()) {
                    candidate = r;
                }
            }
            if (candidate == null) break;
            alreadySeen.add(routeKey(candidate));
            result.add(candidate);
        }
        return result;
    }

    private static String routeKey(Route r) {
        StringBuilder sb = new StringBuilder();
        for (Leg l : r.legs()) sb.append(l.fromGroupId()).append("-").append(l.toGroupId()).append('|');
        return sb.toString();
    }

    private static Route findRouteImpl(MinecraftServer server, UUID fromGroupId, UUID toGroupId,
                                        java.util.Set<String> excludedEdges) {
        if (server == null || fromGroupId == null || toGroupId == null) return Route.notFound();
        if (fromGroupId.equals(toGroupId)) {
            return new Route(true, 0, List.of());
        }
        Map<UUID, List<Edge>> forward = buildGraph(server);
        Map<UUID, List<Edge>> backward = reverseGraph(forward);
        if (!forward.containsKey(fromGroupId) || !backward.containsKey(toGroupId)) {
            return Route.notFound();
        }

        // 双方向 Dijkstra
        Map<UUID, Integer> distF = new HashMap<>();
        Map<UUID, Integer> distB = new HashMap<>();
        Map<UUID, UUID[]> parentF = new HashMap<>();  // [parentGroup, viaTrain]
        Map<UUID, UUID[]> parentB = new HashMap<>();
        Map<UUID, Integer> edgeWtF = new HashMap<>(); // 各 node 到達時の親エッジ重み (再構築用)
        Map<UUID, Integer> edgeWtB = new HashMap<>();
        distF.put(fromGroupId, 0);
        distB.put(toGroupId, 0);

        PriorityQueue<long[]> pqF = new PriorityQueue<>((a, b) -> Integer.compare((int) a[0], (int) b[0]));
        PriorityQueue<long[]> pqB = new PriorityQueue<>((a, b) -> Integer.compare((int) a[0], (int) b[0]));
        // long[] = [dist, mostSig, leastSig]
        pushPQ(pqF, 0, fromGroupId);
        pushPQ(pqB, 0, toGroupId);

        Set<UUID> settledF = new HashSet<>();
        Set<UUID> settledB = new HashSet<>();
        UUID meet = null;
        int bestTotal = Integer.MAX_VALUE;
        int hopGuard = 0;

        while ((!pqF.isEmpty() || !pqB.isEmpty()) && hopGuard++ < MAX_HOPS * 200) {
            // 交互に進める
            if (!pqF.isEmpty()) {
                long[] top = pqF.poll();
                UUID u = uuidOf(top);
                int d = (int) top[0];
                if (d > distF.getOrDefault(u, Integer.MAX_VALUE)) {
                    // outdated
                } else if (settledF.add(u)) {
                    if (settledB.contains(u)) {
                        int total = d + distB.getOrDefault(u, Integer.MAX_VALUE);
                        if (total < bestTotal) { bestTotal = total; meet = u; }
                    }
                    for (Edge e : forward.getOrDefault(u, List.of())) {
                        if (excludedEdges.contains(u + "->" + e.toGroupId)) continue;
                        // B3: 設計意図の weight (= etaFromGroupFromNow + travelTicks) で探索する。
                        // 以前は travelTicks のみで suboptimal route を選んでいた。
                        int nd = d + e.weight();
                        if (nd < distF.getOrDefault(e.toGroupId, Integer.MAX_VALUE)) {
                            distF.put(e.toGroupId, nd);
                            parentF.put(e.toGroupId, new UUID[]{u, e.viaTrainId});
                            edgeWtF.put(e.toGroupId, e.travelTicks);
                            pushPQ(pqF, nd, e.toGroupId);
                        }
                    }
                }
            }
            if (!pqB.isEmpty()) {
                long[] top = pqB.poll();
                UUID u = uuidOf(top);
                int d = (int) top[0];
                if (d > distB.getOrDefault(u, Integer.MAX_VALUE)) {
                    // outdated
                } else if (settledB.add(u)) {
                    if (settledF.contains(u)) {
                        int total = distF.getOrDefault(u, Integer.MAX_VALUE) + d;
                        if (total < bestTotal) { bestTotal = total; meet = u; }
                    }
                    for (Edge e : backward.getOrDefault(u, List.of())) {
                        if (excludedEdges.contains(e.toGroupId + "->" + u)) continue;
                        // B3: 設計意図の weight (= etaFromGroupFromNow + travelTicks) で探索する。
                        int nd = d + e.weight();
                        if (nd < distB.getOrDefault(e.toGroupId, Integer.MAX_VALUE)) {
                            distB.put(e.toGroupId, nd);
                            parentB.put(e.toGroupId, new UUID[]{u, e.viaTrainId});
                            edgeWtB.put(e.toGroupId, e.travelTicks);
                            pushPQ(pqB, nd, e.toGroupId);
                        }
                    }
                }
            }
            // 終了条件: 両 PQ の top の合計が現在の best 以上なら確定
            int topF = pqF.isEmpty() ? Integer.MAX_VALUE : (int) pqF.peek()[0];
            int topB = pqB.isEmpty() ? Integer.MAX_VALUE : (int) pqB.peek()[0];
            if ((long) topF + topB >= bestTotal && meet != null) break;
        }

        if (meet == null || bestTotal == Integer.MAX_VALUE) return Route.notFound();
        // 経路を再構築 (raw form: from/to/train/travel のみ)。
        List<Leg> rawForward = new ArrayList<>();
        UUID cur = meet;
        while (parentF.containsKey(cur)) {
            UUID[] pp = parentF.get(cur);
            UUID parent = pp[0];
            UUID train = pp[1];
            int wt = edgeWtF.getOrDefault(cur, 0);
            rawForward.add(new Leg(parent, cur, train, wt, 0, 0, 0, "", -1, "", 0, 0, 0));
            cur = parent;
        }
        Collections.reverse(rawForward);

        List<Leg> rawBackward = new ArrayList<>();
        cur = meet;
        while (parentB.containsKey(cur)) {
            UUID[] pp = parentB.get(cur);
            UUID parent = pp[0];
            UUID train = pp[1];
            int wt = edgeWtB.getOrDefault(cur, 0);
            rawBackward.add(new Leg(cur, parent, train, wt, 0, 0, 0, "", -1, "", 0, 0, 0));
            cur = parent;
        }
        List<Leg> rawAll = new ArrayList<>(rawForward);
        rawAll.addAll(rawBackward);

        // 各 leg を発時刻 + 番線で enrich。
        Map<UUID, StationGroup> groupById = new HashMap<>();
        for (StationGroup grp : StationGroupSavedData.get(server).all()) groupById.put(grp.id(), grp);
        List<Leg> enrichedAll = new ArrayList<>(rawAll.size());
        int cumulative = 0;
        UUID prevTrain = null;
        for (Leg raw : rawAll) {
            Edge edge = findEdgeIn(forward, raw.fromGroupId(), raw.toGroupId(), raw.trainId());
            int eta = edge != null ? edge.etaFromGroupFromNow : 0;
            int dep;
            if (prevTrain == null || !prevTrain.equals(raw.trainId())) {
                dep = Math.max(cumulative, eta);
            } else {
                dep = cumulative;
            }
            int boardPlatform = 0, alightPlatform = 0;
            String symLetters = "";
            int symNumber = -1;
            String symColor = "";
            int sampleCount = 0;
            int stdDev = 0;
            if (edge != null) {
                StationGroup fromGrp = groupById.get(raw.fromGroupId());
                StationGroup toGrp = groupById.get(raw.toGroupId());
                if (fromGrp != null) boardPlatform = fromGrp.platformNumberForId(edge.boardStationId);
                if (toGrp != null) alightPlatform = toGrp.platformNumberForId(edge.alightStationId);
                var sym = com.trainsystemutilities.station.LineSymbolResolver.forStationId(edge.boardStationId);
                if (sym != null) {
                    symLetters = sym.getLetters() == null ? "" : sym.getLetters();
                    symNumber = sym.getNumber();
                    symColor = sym.getBorderColor() == null ? "" : sym.getBorderColor();
                }
                // 信頼度: SegmentStatsStore から取得
                try {
                    var stats = com.trainsystemutilities.station.SegmentStatsStore.get(server)
                            .get(raw.fromGroupId(), raw.toGroupId());
                    if (stats != null) {
                        sampleCount = stats.sampleCount;
                        stdDev = (int) Math.round(stats.stdDev());
                    }
                } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrainRouter] segment stats read failed", e); }
            }
            // 遅延: 列車の現在の信号待ち ticks (FIRST leg のみ意味がある)
            int delayTicks = 0;
            if (prevTrain == null && raw.trainId() != null) {
                try {
                    var optTrain = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(raw.trainId());
                    if (optTrain.isPresent()) {
                        var t = optTrain.get();
                        if (t.navigation != null && t.navigation.waitingForSignal != null) {
                            delayTicks = t.navigation.ticksWaitingForSignal;
                        }
                    }
                } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrainRouter] signal-wait read failed", e); }
            }
            enrichedAll.add(new Leg(raw.fromGroupId(), raw.toGroupId(), raw.trainId(),
                    raw.travelTicks(), dep, boardPlatform, alightPlatform,
                    symLetters, symNumber, symColor, sampleCount, stdDev, delayTicks));
            cumulative = dep + raw.travelTicks();
            prevTrain = raw.trainId();
        }

        // 合計 = 最後の leg の到着 (= 累積)
        int totalFromNow = enrichedAll.isEmpty() ? bestTotal : cumulative;
        return new Route(true, totalFromNow, Collections.unmodifiableList(enrichedAll));
    }

    /** forward グラフから (from, to, train) に一致する Edge を検索。 */
    private static Edge findEdgeIn(Map<UUID, List<Edge>> forward, UUID from, UUID to, UUID train) {
        List<Edge> outs = forward.get(from);
        if (outs == null) return null;
        for (Edge e : outs) {
            if (e.toGroupId.equals(to) && (train == null ? e.viaTrainId == null : train.equals(e.viaTrainId))) {
                return e;
            }
        }
        // train mismatch fallback (路線変更時の race condition 対策)
        for (Edge e : outs) {
            if (e.toGroupId.equals(to)) return e;
        }
        return null;
    }

    /** TrainScheduleCache から有向グラフ (group → outgoing edges) を構築。 */
    private static Map<UUID, List<Edge>> buildGraph(MinecraftServer server) {
        Map<UUID, List<Edge>> g = new HashMap<>();
        // (from, to) ごとに最小 weight (待ち + 走行) のエッジを採用。
        Map<UUID, Map<UUID, Edge>> nested = new HashMap<>();

        Map<UUID, Snapshot> snapshots = TrainScheduleCache.all();
        Map<UUID, StationGroup> groupById = new HashMap<>();
        for (StationGroup grp : StationGroupSavedData.get(server).all()) groupById.put(grp.id(), grp);

        for (Snapshot snap : snapshots.values()) {
            List<UUID> stops = snap.upcomingGroupIds();
            List<UUID> stationIds = snap.upcomingStationIds();
            if (stops.size() < 2) continue;

            // この列車が各 stops[i] に到達するまでの累積 tick を事前計算。
            // stops[0] = nextGroupId (または currentGroupId 直後)。
            // 簡易: stops[0] には snap.etaTicksToNext。以降は estimate で加算。
            int[] cumEta = new int[stops.size()];
            cumEta[0] = Math.max(0, snap.etaTicksToNext());
            for (int i = 1; i < stops.size(); i++) {
                int travel = estimateTravelTicks(server, groupById.get(stops.get(i - 1)), groupById.get(stops.get(i)));
                cumEta[i] = cumEta[i - 1] + travel;
            }

            for (int i = 0; i + 1 < stops.size(); i++) {
                UUID a = stops.get(i);
                UUID b = stops.get(i + 1);
                if (a.equals(b)) continue;
                int travel = estimateTravelTicks(server, groupById.get(a), groupById.get(b));
                if (i == 0 && snap.etaTicksToNext() > 0 && b.equals(snap.nextGroupId())) {
                    travel = Math.min(travel, snap.etaTicksToNext());
                }
                // Phase D: 信号占有予測 (i=0 leg のみ意味があるので適用)
                if (i == 0) {
                    int penalty = SignalOccupancyPredictor.predictAddedWaitTicks(snap.trainId(), b);
                    if (penalty > 0) travel += penalty;
                }
                int etaToA = cumEta[i];
                UUID stIdA = i < stationIds.size() ? stationIds.get(i) : null;
                UUID stIdB = (i + 1) < stationIds.size() ? stationIds.get(i + 1) : null;
                Edge candidate = new Edge(b, travel, snap.trainId(), etaToA, stIdA, stIdB);
                Edge cur = nested.computeIfAbsent(a, k -> new HashMap<>()).get(b);
                if (cur == null || candidate.weight() < cur.weight()) {
                    nested.get(a).put(b, candidate);
                }
            }
        }
        for (Map.Entry<UUID, Map<UUID, Edge>> e : nested.entrySet()) {
            g.put(e.getKey(), List.copyOf(e.getValue().values()));
        }
        return g;
    }

    private static Map<UUID, List<Edge>> reverseGraph(Map<UUID, List<Edge>> forward) {
        Map<UUID, Map<UUID, Edge>> nested = new HashMap<>();
        for (Map.Entry<UUID, List<Edge>> e : forward.entrySet()) {
            UUID from = e.getKey();
            for (Edge edge : e.getValue()) {
                nested.computeIfAbsent(edge.toGroupId, k -> new HashMap<>())
                      .put(from, new Edge(from, edge.travelTicks, edge.viaTrainId,
                              edge.etaFromGroupFromNow, edge.boardStationId, edge.alightStationId));
            }
        }
        Map<UUID, List<Edge>> back = new HashMap<>();
        for (Map.Entry<UUID, Map<UUID, Edge>> e : nested.entrySet()) {
            back.put(e.getKey(), List.copyOf(e.getValue().values()));
        }
        return back;
    }

    /**
     * 2 駅グループ間のおおよその所要 tick を推定。
     *
     * <p>優先度:
     * <ol>
     *   <li>{@link com.trainsystemutilities.station.SegmentStatsStore} に過去実測の EMA があればそれを採用</li>
     *   <li>無ければ距離ベースの暫定見積もり (1 blocks/tick の巡航想定)</li>
     * </ol>
     */
    private static int estimateTravelTicks(StationGroup a, StationGroup b) {
        if (a == null || b == null) return 200;
        return estimateTravelTicks(null, a, b);
    }

    /** Server を渡せる版: SegmentStatsStore を時刻帯別 EMA で優先参照。 */
    private static int estimateTravelTicks(net.minecraft.server.MinecraftServer server,
                                           StationGroup a, StationGroup b) {
        if (a == null || b == null) return 200;
        if (server != null) {
            try {
                var stats = com.trainsystemutilities.station.SegmentStatsStore.get(server).get(a.id(), b.id());
                if (stats != null && stats.isTrusted()) {
                    // Phase D: 現在の時刻バケットの EMA を優先 (なければ全期間 EMA)
                    long dayTime = server.overworld().getDayTime();
                    int bucket = com.trainsystemutilities.station.SegmentStatsStore.bucketOf(dayTime);
                    return Math.max(20, (int) Math.round(stats.bucketEmaOrFallback(bucket)));
                }
            } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrainRouter] bucket EMA read failed", ignored); }
        }
        // Phase D: TrackGraph A* で実走距離 (キャッシュ済) → 直線距離フォールバック
        double dist = -1;
        if (a.stationBlockPositions() != null && !a.stationBlockPositions().isEmpty()
                && b.stationBlockPositions() != null && !b.stationBlockPositions().isEmpty()) {
            dist = TrackGraphPathfinder.railDistance(
                    a.stationBlockPositions().get(0), b.stationBlockPositions().get(0));
        }
        if (dist <= 0) {
            double cx1 = (a.minPos().getX() + a.maxPos().getX()) / 2.0;
            double cz1 = (a.minPos().getZ() + a.maxPos().getZ()) / 2.0;
            double cx2 = (b.minPos().getX() + b.maxPos().getX()) / 2.0;
            double cz2 = (b.minPos().getZ() + b.maxPos().getZ()) / 2.0;
            dist = Math.hypot(cx2 - cx1, cz2 - cz1);
        }
        // 1 blocks/tick 巡航想定で換算
        return Math.max(20, (int) Math.ceil(dist));
    }

    private static void pushPQ(PriorityQueue<long[]> pq, int dist, UUID id) {
        pq.offer(new long[]{dist, id.getMostSignificantBits(), id.getLeastSignificantBits()});
    }
    private static UUID uuidOf(long[] entry) {
        return new UUID(entry[1], entry[2]);
    }

    /**
     * 駅グループ間の「運賃計算用」物理線路距離 (= 営業キロ proxy)。
     *
     * <p>Create の TrackGraph 上の実走距離 ({@link TrackGraphPathfinder#railDistance}) を、 各グループの
     * 代表駅 (= {@code stationBlockPositions} の先頭) 間で求める。 <strong>列車スケジュールに依存しない</strong>
     * ので、 列車が走っていなくても運賃判定ができる (= 改札の途中下車判定に使う)。 双方向の線路を Dijkstra で
     * 最短探索するため、 <strong>環状線では自然に「短い方の向き」</strong>が選ばれる (= 大都市近郊区間 /
     * 環状線は最も安くなる経路で計算、 という実ルールに準拠)。
     *
     * @return from→to の物理線路距離 (blocks)。 到達不能は -1、 from==to は 0。
     */
    public static double railFareDistance(MinecraftServer server, UUID fromGroup, UUID toGroup) {
        if (server == null || fromGroup == null || toGroup == null) return -1;
        if (fromGroup.equals(toGroup)) return 0;
        var sgData = StationGroupSavedData.get(server);
        StationGroup a = sgData.get(fromGroup);
        StationGroup b = sgData.get(toGroup);
        if (a == null || b == null) return -1;
        if (a.stationBlockPositions() == null || a.stationBlockPositions().isEmpty()
                || b.stationBlockPositions() == null || b.stationBlockPositions().isEmpty()) return -1;
        return TrackGraphPathfinder.railDistance(
                a.stationBlockPositions().get(0), b.stationBlockPositions().get(0));
    }
}
