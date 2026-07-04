package com.trainsystemutilities.station;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * クライアント側の駅グループキャッシュ。
 *
 * <p>サーバが {@code StationGroupListResponsePayload} を push したときに更新される。
 * View モードの renderer と管理 GUI の双方が参照する。
 */
@OnlyIn(Dist.CLIENT)
public final class StationGroupClientCache {

    private static volatile List<StationGroup> groups = List.of();

    private StationGroupClientCache() {}

    public static List<StationGroup> all() { return groups; }

    public static void replaceAll(List<StationGroup> next) {
        // immutable copy で他スレッドの iterate と競合しないように
        groups = Collections.unmodifiableList(new ArrayList<>(next));
    }

    public static void clear() { groups = List.of(); }
}
