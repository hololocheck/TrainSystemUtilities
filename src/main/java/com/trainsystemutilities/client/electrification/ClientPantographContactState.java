package com.trainsystemutilities.client.electrification;

import com.trainsystemutilities.electrification.contraption.TrainElectrificationView;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S2: 各パンタグラフ BE がレンダリング時に「自分の集電バー Y オフセット」を引くためのクライアントキャッシュ。
 *
 * <p>サーバから {@code TrainElectrificationSyncPayload} で配信されるたびに更新。
 * {@link com.trainsystemutilities.client.electrification.PantographGeoModel#setCustomAnimations}
 * が BE の {@code getBlockPos()} (= contraption ローカル座標) で参照する。
 *
 * <p>v1 制限: ローカル座標のみで keyed しているため、複数 contraption で同じ local pos に
 * パンタを置いた場合は衝突する。実用上稀。
 */
public final class ClientPantographContactState {

    /** ローカル pos → (deployed か、 inContact か、barOffsetY)。 */
    public record Entry(boolean deployed, boolean inContact, float barOffsetY) {
        public static final Entry NONE = new Entry(false, false, 0f);
    }

    private static final Map<BlockPos, Entry> BY_LOCAL_POS = new ConcurrentHashMap<>();

    private ClientPantographContactState() {}

    /** sync payload から全 view を反映。 */
    public static void replaceAll(java.util.List<TrainElectrificationView> views) {
        BY_LOCAL_POS.clear();
        if (views == null) return;
        for (TrainElectrificationView v : views) {
            if (v == null || v.cars == null) continue;
            for (TrainElectrificationView.Car car : v.cars) {
                if (car == null || car.pantographs == null) continue;
                for (TrainElectrificationView.PantoEntry pe : car.pantographs) {
                    if (pe == null || pe.pos == null) continue;
                    BY_LOCAL_POS.put(pe.pos, new Entry(pe.deployed, pe.inContact, pe.barOffsetY));
                }
            }
        }
    }

    /** disconnect 時の lifecycle cleanup (= server 切替で前 server の state を持ち越さない)。 */
    public static void clear() {
        BY_LOCAL_POS.clear();
    }

    public static Entry get(BlockPos localPos) {
        if (localPos == null) return Entry.NONE;
        Entry e = BY_LOCAL_POS.get(localPos);
        return e == null ? Entry.NONE : e;
    }

    /** 未使用: 将来 UUID 別 keyed が必要になった場合に。 */
    public static int size() { return BY_LOCAL_POS.size(); }

    /** S4 用: UUID 別の lookup 用 dummy method (cache が ClientTrainElectrificationCache と並行)。 */
    @SuppressWarnings("unused")
    public static boolean isLikelyOnContraption(UUID trainId) {
        return ClientTrainElectrificationCache.isElectrified(trainId);
    }
}
