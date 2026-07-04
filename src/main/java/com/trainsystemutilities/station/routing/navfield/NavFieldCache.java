package com.trainsystemutilities.station.routing.navfield;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * NavField への 2 層アクセス層:
 * <ol>
 *   <li>L1: in-memory ConcurrentMap (= ms 単位 lookup)</li>
 *   <li>L2: NavFieldSavedData (= 永続化、世界をまたいで保持)</li>
 * </ol>
 *
 * <p>サーバ参照 (= ServerLevel / MinecraftServer) があれば L2 を経由、無ければ L1 のみ。
 * worker thread からは ServerLevel を渡してアクセス可能。
 */
public final class NavFieldCache {

    private record Key(UUID groupId, int platform) {}

    /** L1 in-memory cache。SavedData から lazy load された結果も含む。 */
    private static final ConcurrentMap<Key, NavField> CACHE = new ConcurrentHashMap<>();
    /** L2 (= SavedData) との同期初期化フラグ。サーバ起動後の最初のアクセスで読み込む。 */
    private static volatile boolean initialized = false;

    private NavFieldCache() {}

    /**
     * 初回アクセス時に SavedData から全 field を L1 にプリロード。
     * server tick thread から呼ぶこと。
     */
    public static synchronized void ensureInitialized(MinecraftServer server) {
        if (initialized || server == null) return;
        try {
            NavFieldSavedData data = NavFieldSavedData.get(server);
            // SavedData の internal map をそのまま L1 へ反映。
            // 既存の field instance を共有する形で OK (= 不変オブジェクト)。
            // ただし NavFieldSavedData の private map に直接アクセスできないので、
            // get() ベースで個別 lookup するスキームではなく、SavedData が保持してる map を
            // 「同じ key で問い合わせるたびに dynamic lookup」 する。
            // → 簡易: initialized を立てるだけ、L1 cache miss 時に L2 へフォールバック。
            initialized = true;
        } catch (Throwable ignored) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[NavFieldCache] SavedData preload failed", ignored);
            initialized = true;
        }
    }

    public static NavField get(ServerLevel level, UUID groupId, int platform) {
        if (groupId == null || platform <= 0) return null;
        Key k = new Key(groupId, platform);
        NavField cached = CACHE.get(k);
        if (cached != null) return cached;
        // L1 miss → SavedData (= L2) を lookup
        if (level == null) return null;
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        ensureInitialized(server);
        NavFieldSavedData data = NavFieldSavedData.get(server);
        NavField field = data.get(groupId, platform);
        if (field != null) {
            CACHE.put(k, field); // L1 にプロモート
        }
        return field;
    }

    /** 旧 API: server なしバージョン (= L1 のみ)。後方互換用。 */
    public static NavField get(UUID groupId, int platform) {
        if (groupId == null || platform <= 0) return null;
        return CACHE.get(new Key(groupId, platform));
    }

    public static void put(ServerLevel level, NavField field) {
        if (field == null || field.groupId() == null) return;
        Key k = new Key(field.groupId(), field.platform());
        CACHE.put(k, field);
        // L2 にも書き込む (= 永続化)
        if (level != null) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                // SavedData アクセスは main thread で実行
                server.execute(() -> NavFieldSavedData.get(server).put(field));
            }
        }
    }

    /** 旧 API: L1 のみ書込 (= 永続化なし)。 */
    public static void put(NavField field) {
        if (field == null || field.groupId() == null) return;
        CACHE.put(new Key(field.groupId(), field.platform()), field);
    }

    public static void removeAll(MinecraftServer server, UUID groupId) {
        if (groupId == null) return;
        CACHE.keySet().removeIf(k -> k.groupId.equals(groupId));
        if (server != null) {
            server.execute(() -> NavFieldSavedData.get(server).removeAll(groupId));
        }
    }

    /** 旧 API: L1 のみクリア。 */
    public static void removeAll(UUID groupId) {
        if (groupId == null) return;
        CACHE.keySet().removeIf(k -> k.groupId.equals(groupId));
    }

    public static int size() { return CACHE.size(); }
    public static void clear() { CACHE.clear(); }
}
