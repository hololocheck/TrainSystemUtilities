package com.trainsystemutilities.station;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クライアント側の駅グループキャッシュ。
 *
 * <p>サーバー全体の駅グループ host (network.StationGroupsData、 manta:data) の一覧が届くたびに
 * client.StationsClient が更新する (MANTA_7_CONCEPT C4 まで StationGroupListResponsePayload)。
 * View モードの renderer と管理 GUI の双方が参照する。 一覧の各グループは駅の座標を持たない — 駅数は
 * {@link #stationCount}、 座標は管理画面が選んだグループについて {@link #positions} (本人向けの応答) で読む。
 */
@OnlyIn(Dist.CLIENT)
public final class StationGroupClientCache {

    private static volatile List<StationGroup> groups = List.of();
    private static volatile Map<UUID, Integer> counts = Map.of();
    private static final Map<UUID, List<BlockPos>> positions = new ConcurrentHashMap<>();

    private StationGroupClientCache() {}

    public static List<StationGroup> all() { return groups; }

    public static void replaceAll(List<StationGroup> next, Map<UUID, Integer> nextCounts) {
        // immutable copy で他スレッドの iterate と競合しないように
        groups = Collections.unmodifiableList(new ArrayList<>(next));
        counts = Collections.unmodifiableMap(new HashMap<>(nextCounts));
    }

    /** The group's number of stations (platforms). */
    public static int stationCount(UUID group) {
        return counts.getOrDefault(group, 0);
    }

    /** The group's station positions, once the manage screen asked for them; empty until then. */
    public static List<BlockPos> positions(UUID group) {
        return positions.getOrDefault(group, List.of());
    }

    public static void setPositions(UUID group, List<BlockPos> next) {
        positions.put(group, List.copyOf(next));
    }

    public static void clear() {
        groups = List.of();
        counts = Map.of();
        positions.clear();
    }
}
