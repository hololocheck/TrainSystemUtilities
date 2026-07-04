package com.trainsystemutilities.detection;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列車検知ポイントと、それを通過 / そこで停止する列車のイベント管理。
 *
 * <p>2 種類のイベント:
 * <ul>
 *   <li>{@link DetectionListener#onTrainEnter}: 検知点 ±1 ブロック内に列車のいずれかの
 *       bogey が「外から内へ」入った瞬間に発火 (debounce)</li>
 *   <li>{@link DetectionListener#onTrainStopped}: 検知点内で列車が停止した瞬間に発火
 *       (debounce: 一度動いてから再停止しない限り再発火しない)</li>
 * </ul>
 */
public final class TrainDetectionManager {

    private TrainDetectionManager() {}

    private static final Map<GlobalPos, List<DetectionListener>> listeners = new ConcurrentHashMap<>();

    /** debounce: (trainId, gp) で「現在 inside」と判定済みの set。 */
    private static final Set<TrainPointKey> insideSet = ConcurrentHashMap.newKeySet();
    /** debounce: (trainId, gp) で「現在 inside かつ stopped」と判定済みの set。 */
    private static final Set<TrainPointKey> stoppedSet = ConcurrentHashMap.newKeySet();

    /** 検知半径 (Chebyshev distance, blocks)。 */
    private static final int DETECTION_RADIUS = 1;
    /** 「停止」と見なす speed 閾値 (block/tick)。Create の train speed scale を考慮。 */
    private static final double STOPPED_SPEED_EPS = 0.001;

    public interface DetectionListener {
        default void onTrainEnter(Train train, GlobalPos detectionPoint) {}
        default void onTrainStopped(Train train, GlobalPos detectionPoint) {}
        /** 検知点内で停止していた列車が動き出した瞬間に発火 (= 発車)。 */
        default void onTrainDeparted(Train train, GlobalPos detectionPoint) {}
    }

    private record TrainPointKey(UUID trainId, GlobalPos pos) {}

    public static void register(GlobalPos pos, DetectionListener listener) {
        if (pos == null || listener == null) return;
        listeners.computeIfAbsent(pos, k -> new ArrayList<>()).add(listener);
    }

    public static void unregister(GlobalPos pos, DetectionListener listener) {
        if (pos == null) return;
        var list = listeners.get(pos);
        if (list == null) return;
        list.removeIf(l -> l == listener);
        if (list.isEmpty()) listeners.remove(pos);
    }

    /** TrainMixin から呼ばれる per-tick エントリポイント。 */
    public static void onTrainTick(Train train, ResourceKey<Level> dim) {
        if (listeners.isEmpty()) return;

        UUID trainId = train.id;

        // Step 1: 列車のいずれかの bogey が今 tick で「inside」 な検知点を集合
        Set<GlobalPos> insideNow = new HashSet<>();
        for (Carriage c : train.carriages) {
            collectInsidePoints(c.leadingBogey(), dim, insideNow);
            CarriageBogey trail = c.trailingBogey();
            if (trail != null && trail != c.leadingBogey()) {
                collectInsidePoints(trail, dim, insideNow);
            }
        }

        boolean trainStopped = Math.abs(train.speed) < STOPPED_SPEED_EPS;

        // Step 2: 今 inside の各点について onTrainEnter / onTrainStopped を発火 (debounce)
        for (GlobalPos gp : insideNow) {
            TrainPointKey key = new TrainPointKey(trainId, gp);
            boolean newlyInside = insideSet.add(key);
            List<DetectionListener> list = listeners.get(gp);
            if (list == null) continue;
            if (newlyInside) {
                for (DetectionListener l : list) {
                    try { l.onTrainEnter(train, gp); }
                    catch (Throwable t) {
                        TrainSystemUtilities.LOGGER.warn(
                                "[TrainDetection] onTrainEnter listener failed at {}: {}",
                                gp.pos(), t.toString());
                    }
                }
            }
            if (trainStopped) {
                boolean newlyStopped = stoppedSet.add(key);
                if (newlyStopped) {
                    for (DetectionListener l : list) {
                        try { l.onTrainStopped(train, gp); }
                        catch (Throwable t) {
                            TrainSystemUtilities.LOGGER.warn(
                                    "[TrainDetection] onTrainStopped listener failed at {}: {}",
                                    gp.pos(), t.toString());
                        }
                    }
                }
            } else {
                // 動き出した → stopped flag をクリア (次回停止時に再発火可能)。
                // 直前 tick まで stopped だった (= remove が true) なら DEPART 発火。
                boolean wasStopped = stoppedSet.remove(key);
                if (wasStopped) {
                    for (DetectionListener l : list) {
                        try { l.onTrainDeparted(train, gp); }
                        catch (Throwable t) {
                            TrainSystemUtilities.LOGGER.warn(
                                    "[TrainDetection] onTrainDeparted listener failed at {}: {}",
                                    gp.pos(), t.toString());
                        }
                    }
                }
            }
        }

        // Step 3: 列車が今 outside の点 → key 解除
        insideSet.removeIf(k -> k.trainId.equals(trainId) && !insideNow.contains(k.pos));
        stoppedSet.removeIf(k -> k.trainId.equals(trainId) && !insideNow.contains(k.pos));
    }

    private static void collectInsidePoints(CarriageBogey bogey, ResourceKey<Level> dim,
                                             Set<GlobalPos> out) {
        if (bogey == null) return;
        Vec3 anchor;
        try { anchor = bogey.getAnchorPosition(); } catch (Throwable t) { return; }
        if (anchor == null) return;
        BlockPos bogeyBlock = BlockPos.containing(anchor);
        for (var entry : listeners.entrySet()) {
            GlobalPos gp = entry.getKey();
            if (!gp.dimension().equals(dim)) continue;
            BlockPos dp = gp.pos();
            if (Math.abs(bogeyBlock.getX() - dp.getX()) <= DETECTION_RADIUS
                    && Math.abs(bogeyBlock.getY() - dp.getY()) <= DETECTION_RADIUS
                    && Math.abs(bogeyBlock.getZ() - dp.getZ()) <= DETECTION_RADIUS) {
                out.add(gp);
            }
        }
    }

    public static void clearAll() {
        listeners.clear();
        insideSet.clear();
        stoppedSet.clear();
    }

    public static int registeredPointCount() {
        return listeners.size();
    }
}
