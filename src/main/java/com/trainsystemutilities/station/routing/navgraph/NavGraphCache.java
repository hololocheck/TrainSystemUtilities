package com.trainsystemutilities.station.routing.navgraph;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 駅グループ単位の {@link NavGraph} メモリキャッシュ。
 *
 * <p>Phase 1/2 ではメモリ常駐 (server プロセス内のみ)。Phase 3 で SavedData 経由の永続化を追加予定。
 */
public final class NavGraphCache {

    private static final ConcurrentMap<UUID, NavGraph> CACHE = new ConcurrentHashMap<>();

    private NavGraphCache() {}

    public static void put(NavGraph graph) {
        if (graph == null || graph.stationGroupId() == null) return;
        CACHE.put(graph.stationGroupId(), graph);
    }

    public static NavGraph get(UUID groupId) {
        return groupId == null ? null : CACHE.get(groupId);
    }

    public static void remove(UUID groupId) {
        if (groupId != null) CACHE.remove(groupId);
    }

    public static int size() { return CACHE.size(); }

    public static void clear() { CACHE.clear(); }
}
