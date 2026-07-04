package com.trainsystemutilities.client.electrification;

import com.trainsystemutilities.electrification.contraption.TrainElectrificationView;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クライアント側で保持する「電化列車の状態」キャッシュ。
 *
 * <p>サーバから周期的に {@code TrainElectrificationSyncPayload} で配信される最新スナップショット。
 * 管理コンピューターの列車タブ / 電化詳細タブから参照される。
 *
 * <p>NavPath や StationGroup のクライアントキャッシュと同じパターン。
 */
public final class ClientTrainElectrificationCache {

    private static final Map<UUID, TrainElectrificationView> CACHE = new ConcurrentHashMap<>();
    private static long lastUpdateMs = 0;

    private ClientTrainElectrificationCache() {}

    public static void put(TrainElectrificationView view) {
        if (view == null || view.trainId == null) return;
        CACHE.put(view.trainId, view);
        lastUpdateMs = System.currentTimeMillis();
    }

    public static TrainElectrificationView get(UUID trainId) {
        return trainId == null ? null : CACHE.get(trainId);
    }

    public static boolean isElectrified(UUID trainId) {
        TrainElectrificationView v = get(trainId);
        return v != null && v.hasAnyInverter();
    }

    public static void replaceAll(java.util.List<TrainElectrificationView> views) {
        CACHE.clear();
        for (TrainElectrificationView v : views) put(v);
    }

    /** disconnect 時の lifecycle cleanup (= server 切替で前 server の state を持ち越さない)。 */
    public static void clear() {
        CACHE.clear();
        lastUpdateMs = 0;
    }

    public static int size() { return CACHE.size(); }
    public static long lastUpdateMs() { return lastUpdateMs; }
}
