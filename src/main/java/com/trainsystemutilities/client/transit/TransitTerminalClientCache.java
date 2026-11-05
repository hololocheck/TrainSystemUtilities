package com.trainsystemutilities.client.transit;

import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 乗り換え案内端末用のクライアントキャッシュ。
 *
 * <p>Phase 19 では SCHEDULE タブ向けに {@link com.trainsystemutilities.station.TrainScheduleCache.Snapshot}
 * の lite 版 ({@link ScheduleSnapshot}) をサーバから配信して保持する。
 *
 * <p>MAP タブは現状 {@link StationGroupClientCache} の駅グループ一覧をそのまま使うが、
 * 将来的に dimension 横断の track-network 線分情報を持たせるために {@link #allMapGroups()}
 * 抽象を切っておく。
 */
@OnlyIn(Dist.CLIENT)
public final class TransitTerminalClientCache {

    /** What the SCHEDULE tab and the route HUD read of a train (the transit-schedule host's row, TransitData). */
    public record ScheduleSnapshot(
            UUID trainId,
            String trainName,
            UUID nextGroupId,
            int etaTicksToNext
    ) {}

    private static final Map<UUID, ScheduleSnapshot> schedules = new LinkedHashMap<>();
    /** 全 trackNetworks のセグメント (int[]{x1, z1, x2, z2})。MAP 描画で polyline として使う。 */
    private static volatile List<int[]> mapSegments = java.util.Collections.emptyList();

    /**
     * What a wiki stand-in's terminal shows instead of the synced caches: its station groups, trains, track and train
     * positions - installed on this thread for one call into it ({@link #underWiki}), as TransitTerminalState's own is.
     */
    public record WikiData(List<StationGroup> groups, Map<UUID, ScheduleSnapshot> schedules, List<int[]> mapSegments,
                           Map<UUID, com.trainsystemutilities.network.TrainPositionsData.TrainPosition> trainPositions,
                           long trainPositionsDayTime) {
        public WikiData {
            groups = List.copyOf(groups);
            schedules = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(schedules));
            mapSegments = List.copyOf(mapSegments);
            trainPositions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(trainPositions));
        }
    }

    private static final ThreadLocal<WikiData> WIKI = new ThreadLocal<>();

    private TransitTerminalClientCache() {}

    /** Runs {@code body} with {@code data} as this thread's caches, the ones before it back afterwards. */
    public static <T> T underWiki(WikiData data, java.util.function.Supplier<T> body) {
        WikiData outer = WIKI.get();
        WIKI.set(data);
        try {
            return body.get();
        } finally {
            if (outer == null) WIKI.remove();
            else WIKI.set(outer);
        }
    }

    public static synchronized Map<UUID, ScheduleSnapshot> allSchedules() {
        WikiData w = WIKI.get();
        if (w != null) return new LinkedHashMap<>(w.schedules());
        return new LinkedHashMap<>(schedules);
    }

    public static synchronized void replaceSchedules(Map<UUID, ScheduleSnapshot> newSnapshots) {
        schedules.clear();
        schedules.putAll(newSnapshots);
    }

    public static synchronized void clearSchedules() { schedules.clear(); }

    /** HOTFIX N+0.5 #6/#8: server 切替時の全 cache クリア (前 server の state が新 server に流入するのを防ぐ)。 */
    public static synchronized void clearAll() {
        schedules.clear();
        mapSegments = java.util.Collections.emptyList();
        trainPositions = java.util.Collections.emptyMap();
        trainPositionsDayTime = 0;
    }

    public static List<int[]> mapSegments() {
        WikiData w = WIKI.get();
        return w != null ? w.mapSegments() : mapSegments;
    }

    public static void replaceMapSegments(List<int[]> segs) {
        mapSegments = segs == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(segs);
    }

    /** MAP 描画用の駅グループ一覧 (現状は全 dim を含む)。 */
    public static List<StationGroup> allMapGroups() {
        WikiData w = WIKI.get();
        return w != null ? w.groups() : StationGroupClientCache.all();
    }

    // === Train positions (real-time) ===
    private static volatile java.util.Map<UUID, com.trainsystemutilities.network.TrainPositionsData.TrainPosition>
            trainPositions = java.util.Collections.emptyMap();
    private static volatile long trainPositionsDayTime = 0;

    public static java.util.Map<UUID, com.trainsystemutilities.network.TrainPositionsData.TrainPosition> trainPositions() {
        WikiData w = WIKI.get();
        return w != null ? w.trainPositions() : trainPositions;
    }
    public static long trainPositionsDayTime() {
        WikiData w = WIKI.get();
        return w != null ? w.trainPositionsDayTime() : trainPositionsDayTime;
    }

    public static void replaceTrainPositions(
            List<com.trainsystemutilities.network.TrainPositionsData.TrainPosition> list, long dayTime) {
        java.util.Map<UUID, com.trainsystemutilities.network.TrainPositionsData.TrainPosition> map = new java.util.LinkedHashMap<>();
        for (var p : list) map.put(p.trainId(), p);
        trainPositions = java.util.Collections.unmodifiableMap(map);
        trainPositionsDayTime = dayTime;
    }
}
