package com.trainsystemutilities.station.routing;

import com.trainsystemutilities.station.StationGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * (groupId, platform) → 候補プラットフォーム位置リスト のキャッシュ。
 *
 * <p>{@link StationWalkTargetSelector} の `collectAroundStation` は範囲走査が重いので、
 * 一度計算した結果を駅単位で保持。駅範囲やワールドが変わったら invalidate 必要。
 *
 * <p>cache key は `(groupId, platform, dimension, lastUpdateGameTime)` の混成。
 * 駅範囲が更新されると `StationGroupSavedData` 経由で setDirty が呼ばれるが、
 * 簡易実装として 5 分間 (= 6000 ticks) 経過で expire させる。
 */
public final class PlatformCache {

    public record Entry(List<BlockPos> candidates, long createdGameTime) {}

    /** キャッシュ schema バージョン。アルゴリズム変更時に bump → 過去キャッシュ無効化。 */
    private static final int SCHEMA_VERSION = 2;
    private static final long EXPIRE_TICKS = 6000L; // 5 分

    private static final ConcurrentMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    private PlatformCache() {}

    private static String key(UUID groupId, int platform, String dim) {
        return SCHEMA_VERSION + ":" + groupId + "|" + platform + "|" + dim;
    }

    public static Entry get(StationGroup group, int platform, ServerLevel level) {
        Entry e = CACHE.get(key(group.id(), platform, group.dimensionId()));
        if (e == null) return null;
        long age = level.getGameTime() - e.createdGameTime;
        if (age > EXPIRE_TICKS || age < 0) {
            CACHE.remove(key(group.id(), platform, group.dimensionId()));
            return null;
        }
        return e;
    }

    public static void put(StationGroup group, int platform, List<BlockPos> candidates, ServerLevel level) {
        CACHE.put(key(group.id(), platform, group.dimensionId()),
                new Entry(List.copyOf(candidates), level.getGameTime()));
    }

    /** 駅範囲が更新された時に呼ぶ。 */
    public static void invalidate(UUID groupId) {
        // キーは "<SCHEMA_VERSION>:<groupId>|..." 形式 (= key() と一致させる)。
        // 旧実装は startsWith(groupId) だったが全キーが "2:" 始まりのため常に no-op だった。
        String prefix = SCHEMA_VERSION + ":" + groupId + "|";
        CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public static void invalidateAll() {
        CACHE.clear();
    }
}
