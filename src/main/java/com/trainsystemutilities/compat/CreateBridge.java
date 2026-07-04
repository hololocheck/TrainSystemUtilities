package com.trainsystemutilities.compat;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Create 連携の薄い bridge layer。 P0-1 #9 で snapshot helper を提供。
 *
 * <p>{@code Create.RAILWAYS.trains} は server thread + tick handler + 他 mod から
 * 並行アクセスされ得る。 直接 iterate すると ConcurrentModificationException や
 * 半分更新された state を観測する。 本 helper の {@link #snapshotTrains()} は
 * 一度だけ {@link ArrayList} に複製して返すので iterate 中に Create が train を
 * spawn / despawn しても安全。
 *
 * <p>P0-3 で本 class を {@code McssBridge} / {@code Ae2Bridge} と統合した
 * sealed cross-mod bridge facade に発展させる予定。 現状は最小 helper。
 */
public final class CreateBridge {

    public static final String MOD_ID = "create";

    private static final AtomicBoolean selfCheckDone = new AtomicBoolean(false);
    private static volatile boolean available = false;

    private CreateBridge() {}

    /**
     * P0-3: Create が ModList 経由でロード済か。 初回呼出時に self-check + log。
     * TSU は Create を {@code required} dep 宣言しているので通常 true。 異常な mod
     * 構成 (= Create 抜き) で TSU が起動した場合の graceful degrade ガード。
     */
    public static boolean isAvailable() {
        if (selfCheckDone.compareAndSet(false, true)) {
            try {
                available = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
                if (available) {
                    TrainSystemUtilities.LOGGER.info("[CreateBridge] {} detected: train SPI enabled", MOD_ID);
                } else {
                    TrainSystemUtilities.LOGGER.warn(
                            "[CreateBridge] {} not loaded: TSU train features are no-op", MOD_ID);
                }
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn(
                        "[CreateBridge] self-check failed: {} (treating as unavailable)", t.toString());
                available = false;
            }
        }
        return available;
    }

    /**
     * {@code Create.RAILWAYS.trains.values()} の immutable snapshot を返す。
     *
     * <p>caller は snapshot を free に iterate / stream できる。 snapshot 取得後の
     * Create 側の変更は反映されない。 通常 1 tick 内では Create の train set は
     * stable なので、 hot path (= ticker / broadcaster) の安全な迭代パターン。
     *
     * <p>{@code Create.RAILWAYS == null} (= Create dimension 未 init 等) の場合は
     * 空 list を返す。
     */
    public static List<Train> snapshotTrains() {
        try {
            Map<UUID, Train> trains = Create.RAILWAYS.trains;
            if (trains == null || trains.isEmpty()) return List.of();
            return new ArrayList<>(trains.values());
        } catch (Throwable t) {
            // Create が absent / partially-init の極端なケースに備えた fail-safe。
            return List.of();
        }
    }

    /**
     * 同 snapshot で {@code (id, train)} pair を返す。 forEach pattern 用。
     */
    public static List<Map.Entry<UUID, Train>> snapshotEntries() {
        try {
            Map<UUID, Train> trains = Create.RAILWAYS.trains;
            if (trains == null || trains.isEmpty()) return List.of();
            return new ArrayList<>(trains.entrySet());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * {@code trainId} に対応する {@link Train} を返す。 直接 {@code get} と等価だが、
     * Create 状態 race に対する fail-safe 込み。
     */
    public static Train getTrain(UUID trainId) {
        if (trainId == null) return null;
        try {
            Map<UUID, Train> trains = Create.RAILWAYS.trains;
            return trains == null ? null : trains.get(trainId);
        } catch (Throwable t) {
            return null;
        }
    }
}
