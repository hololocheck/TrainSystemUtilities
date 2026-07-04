package com.trainsystemutilities.blockentity;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Create 駅名 → BlockPos の TTL キャッシュ (距離ベースフォールバック ETA 用)。
 *
 * <p>B (god-class 本体縮小): {@code RailwayManagementBlockEntity} から駅位置キャッシュ機構を分離。
 * 全 BE 共有の static cache で、 server tick から {@link #refresh(Level)} される。
 */
public final class StationPosCache {

    private StationPosCache() {}

    private static final long TTL_TICKS = 200;
    private static final Map<String, BlockPos> cache = new HashMap<>();
    private static long cacheTime = 0;

    /** TTL を超えていれば Create trackNetworks を走査して駅位置を再構築。 */
    public static void refresh(Level level) {
        if (level == null) return;
        long gameTime = level.getGameTime();
        if (gameTime - cacheTime > TTL_TICKS) {
            cacheTime = gameTime;
            cache.clear();
            try {
                for (var graph : com.simibubi.create.Create.RAILWAYS.trackNetworks.values()) {
                    for (var station : graph.getPoints(
                            com.simibubi.create.content.trains.graph.EdgePointType.STATION)) {
                        BlockPos pos = station.getBlockEntityPos();
                        if (pos != null) cache.put(station.name, pos);
                    }
                }
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.debug("[Railway] station pos cache refresh failed", e);
            }
        }
    }

    /** キャッシュ済の駅位置 (未キャッシュなら null)。 */
    public static BlockPos get(String stationName) {
        return cache.get(stationName);
    }

    /** キャッシュを全消去 (lifecycle purge 用)。 */
    public static void clear() {
        cache.clear();
        cacheTime = 0;
    }
}
