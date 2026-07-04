package com.trainsystemutilities.station;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クライアント側の「販売可な着駅」キャッシュ (= {@link TicketConfigSavedData} の鏡)。
 *
 * <p>管理用コンピューターの券売機タブが表示 / トグルに使う。
 * サーバが {@code TicketConfigSyncPayload} を push したときに {@link #replaceAll} で更新。
 * トグル操作時は {@link #setLocal} で即時反映してから C2S を送る (§4.9)。
 */
@OnlyIn(Dist.CLIENT)
public final class TicketConfigClientCache {

    private static final Set<UUID> sellable = ConcurrentHashMap.newKeySet();
    /** この管理用コンピューターが管理するネットワークの駅グループ ID (= 切符タブに表示する駅)。 空 = ネットワーク未確立。 */
    private static final Set<UUID> networkGroups = ConcurrentHashMap.newKeySet();

    private TicketConfigClientCache() {}

    public static boolean isSellable(UUID id) { return id != null && sellable.contains(id); }

    /** トグル操作の即時反映 (server 応答を待たずに表示を更新)。 */
    public static void setLocal(UUID id, boolean on) {
        if (id == null) return;
        if (on) sellable.add(id); else sellable.remove(id);
    }

    public static void replaceAll(Collection<UUID> nextSellable, Collection<UUID> nextNetwork) {
        sellable.clear();
        sellable.addAll(nextSellable);
        networkGroups.clear();
        networkGroups.addAll(nextNetwork);
    }

    /** 切符タブに表示すべき自ネットワークの駅グループ ID 集合 (= server が解決して送ってくる)。 */
    public static Set<UUID> networkGroups() { return networkGroups; }

    public static void clear() { sellable.clear(); networkGroups.clear(); }
}
