package com.trainsystemutilities.schedule;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.TrackNetworkScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * 連結・切り離し時の信号制御を管理するコントローラー。
 *
 * 連結時:
 * - 連結指示を受けている列車が駅で待機中、ニキシー管が黄色に変化
 * - 相手列車が徐行して駅に侵入可能
 * - 侵入後、信号は通常通り赤に戻る
 *
 * 切り離し時:
 * - 切り離し駅に到着すると信号がオレンジに変化
 * - 切り離し完了後、進行方向前方の車両が先に発車
 * - 前方車両発車→信号青確認→後方車両が時刻表通りに動作
 * - 切り離し時は駅ブロックを踏まなくても停止位置から次の動作を開始可能
 */
public class CouplingSignalController {

    // 連結待機中の駅マップ（駅名 → 待機列車情報）
    private static final Map<String, CouplingWaitInfo> couplingWaitStations = new HashMap<>();

    // 切り離し中の駅マップ
    private static final Map<String, DecouplingInfo> decouplingStations = new HashMap<>();

    public record CouplingWaitInfo(
            UUID waitingTrainId,
            UUID incomingTrainId,
            String stationName,
            CouplingState state
    ) {}

    public record DecouplingInfo(
            UUID frontTrainId,
            UUID rearTrainId,
            String stationName,
            DecouplingState state
    ) {}

    public enum CouplingState {
        WAITING_FOR_PARTNER,     // 相手列車の到着を待機中
        PARTNER_APPROACHING,      // 相手列車が徐行侵入中
        COUPLING_IN_PROGRESS,     // 連結実行中
        COUPLING_COMPLETE         // 連結完了
    }

    public enum DecouplingState {
        DECOUPLING_IN_PROGRESS,   // 切り離し中
        FRONT_DEPARTING,          // 前方車両発車中
        WAITING_FOR_SIGNAL,       // 信号青待ち
        REAR_READY                // 後方車両発車準備完了
    }

    /**
     * 連結待機を登録する。
     */
    public static void registerCouplingWait(String stationName, UUID waitingTrainId, UUID incomingTrainId) {
        couplingWaitStations.put(stationName, new CouplingWaitInfo(
                waitingTrainId, incomingTrainId, stationName, CouplingState.WAITING_FOR_PARTNER
        ));
        TrainSystemUtilities.LOGGER.info("Coupling wait registered at station: {} for trains {} <- {}",
                stationName, waitingTrainId, incomingTrainId);
    }

    /**
     * 切り離しを登録する。
     */
    public static void registerDecoupling(String stationName, UUID frontTrainId, UUID rearTrainId) {
        decouplingStations.put(stationName, new DecouplingInfo(
                frontTrainId, rearTrainId, stationName, DecouplingState.DECOUPLING_IN_PROGRESS
        ));
        TrainSystemUtilities.LOGGER.info("Decoupling registered at station: {} for trains {} | {}",
                stationName, frontTrainId, rearTrainId);
    }

    /**
     * 指定された駅が連結待機中かどうか判定する。
     * 信号のニキシー管を黄色にする判断に使用。
     */
    public static boolean isStationWaitingForCoupling(String stationName) {
        return couplingWaitStations.containsKey(stationName);
    }

    /**
     * 指定された駅が切り離し中かどうか判定する。
     * 信号のニキシー管をオレンジにする判断に使用。
     */
    public static boolean isStationDecoupling(String stationName) {
        return decouplingStations.containsKey(stationName);
    }

    /**
     * 指定された列車が連結のために信号・駅制限を無視できるか判定する。
     */
    public static boolean canTrainBypassSignal(UUID trainId, String stationName) {
        CouplingWaitInfo info = couplingWaitStations.get(stationName);
        if (info == null) return false;

        // 連結する相手列車のみが信号を無視できる
        return info.incomingTrainId().equals(trainId)
                && (info.state() == CouplingState.WAITING_FOR_PARTNER
                    || info.state() == CouplingState.PARTNER_APPROACHING);
    }

    /**
     * 切り離し後、列車が駅ブロックを踏まずに動作を開始できるか判定する。
     */
    public static boolean canTrainStartWithoutStationBlock(UUID trainId) {
        return decouplingStations.values().stream()
                .anyMatch(info -> info.rearTrainId().equals(trainId)
                        && info.state() == DecouplingState.REAR_READY);
    }

    /**
     * 連結プロセスを更新する。tickごとに呼ばれる。
     */
    public static void tick(Level level) {
        Iterator<Map.Entry<String, CouplingWaitInfo>> couplingIter = couplingWaitStations.entrySet().iterator();
        while (couplingIter.hasNext()) {
            Map.Entry<String, CouplingWaitInfo> entry = couplingIter.next();
            CouplingWaitInfo info = entry.getValue();

            switch (info.state()) {
                case WAITING_FOR_PARTNER -> {
                    // Check if incoming train has arrived at the station area
                    Optional<Train> incoming = TrackNetworkScanner.getTrainById(info.incomingTrainId());
                    if (incoming.isPresent() && isTrainNearStation(incoming.get(), info.stationName())) {
                        entry.setValue(new CouplingWaitInfo(
                                info.waitingTrainId(), info.incomingTrainId(),
                                info.stationName(), CouplingState.PARTNER_APPROACHING
                        ));
                    }
                }
                case PARTNER_APPROACHING -> {
                    // Check if coupling is complete
                    Optional<Train> incoming = TrackNetworkScanner.getTrainById(info.incomingTrainId());
                    if (incoming.isPresent() && incoming.get().getCurrentStation() != null
                            && incoming.get().getCurrentStation().name.equals(info.stationName())) {
                        entry.setValue(new CouplingWaitInfo(
                                info.waitingTrainId(), info.incomingTrainId(),
                                info.stationName(), CouplingState.COUPLING_IN_PROGRESS
                        ));
                    }
                }
                case COUPLING_COMPLETE -> couplingIter.remove();
                default -> {}
            }
        }

        // Process decoupling
        Iterator<Map.Entry<String, DecouplingInfo>> decouplIter = decouplingStations.entrySet().iterator();
        while (decouplIter.hasNext()) {
            Map.Entry<String, DecouplingInfo> entry = decouplIter.next();
            DecouplingInfo info = entry.getValue();

            switch (info.state()) {
                case FRONT_DEPARTING -> {
                    Optional<Train> frontTrain = TrackNetworkScanner.getTrainById(info.frontTrainId());
                    if (frontTrain.isPresent() && frontTrain.get().getCurrentStation() == null) {
                        // Front train has departed, signal should be green now
                        entry.setValue(new DecouplingInfo(
                                info.frontTrainId(), info.rearTrainId(),
                                info.stationName(), DecouplingState.WAITING_FOR_SIGNAL
                        ));
                    }
                }
                case WAITING_FOR_SIGNAL -> {
                    // Signal is green, rear train can start
                    entry.setValue(new DecouplingInfo(
                            info.frontTrainId(), info.rearTrainId(),
                            info.stationName(), DecouplingState.REAR_READY
                    ));
                }
                case REAR_READY -> {
                    // Rear train has started, clean up
                    Optional<Train> rearTrain = TrackNetworkScanner.getTrainById(info.rearTrainId());
                    if (rearTrain.isPresent() && rearTrain.get().speed != 0) {
                        decouplIter.remove();
                    }
                }
                default -> {}
            }
        }
    }

    private static boolean isTrainNearStation(Train train, String stationName) {
        // Simplified check - in full implementation, would check proximity to station block
        return train.speed < 0.5; // Train is slowing down near station
    }

    public static CouplingWaitInfo getCouplingInfo(String stationName) {
        return couplingWaitStations.get(stationName);
    }

    public static DecouplingInfo getDecouplingInfo(String stationName) {
        return decouplingStations.get(stationName);
    }

    public static void clearStation(String stationName) {
        couplingWaitStations.remove(stationName);
        decouplingStations.remove(stationName);
    }

    /** TSU-23: server stop 時に全 static state を clear する。 これらの static map は同一 JVM で
     *  integrated server を開き直しても残り、 前 world の override / cache が漏れるため lifecycle で明示 clear。 */
    public static void clearAll() {
        couplingWaitStations.clear();
        decouplingStations.clear();
        signalOverrides.clear();
        stationPositionCache.clear();
        clientSignalOverrides.clear();
    }

    // --- 信号オーバーライド機能 ---

    public enum SignalOverrideState {
        NONE,
        RED_BLUE_BLINK,          // 連結時: 赤白交互点滅
        RED_WHITE_SIMULTANEOUS   // 切り離し時: 赤白同時点滅（両方ON→両方OFF）
    }

    // 信号位置 → オーバーライド状態
    // TSU-23: key を (dimension, BlockPos) で一意化。 BlockPos のみだと別 dimension の同座標 signal が override を共有する。
    private static final Map<GlobalPos, SignalOverrideState> signalOverrides = new HashMap<>();

    // 駅位置 → 駅名 キャッシュ (信号から最寄りの駅を探すため)。 同上 dimension-aware key。
    private static final Map<GlobalPos, String> stationPositionCache = new HashMap<>();

    private static GlobalPos keyOf(Level level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos.immutable());
    }

    /**
     * 信号のオーバーライド状態を設定する。
     */
    public static void setSignalOverride(Level level, BlockPos signalPos, SignalOverrideState state) {
        signalOverrides.put(keyOf(level, signalPos), state);
    }

    /**
     * 信号のオーバーライドをクリアする。
     */
    public static void clearSignalOverride(Level level, BlockPos signalPos) {
        signalOverrides.remove(keyOf(level, signalPos));
    }

    /**
     * 信号のオーバーライド状態を取得する。
     */
    public static SignalOverrideState getSignalOverride(Level level, BlockPos signalPos) {
        return signalOverrides.getOrDefault(keyOf(level, signalPos), SignalOverrideState.NONE);
    }

    // === #8 MP fix: client へ override を同期 ===
    // server の signalOverrides (上記, GlobalPos) は権威だが MP の client JVM には届かない。
    // server は変化時 + active 中 (5tick 毎) に override を chunk-tracker へ broadcast し、 client は
    // 本 map に (state, 最終受信ms) で格納。 描画 mixin は getClientSignalOverride で読む。
    // re-assert が TTL (= broadcast 周期 5tick の約 4 倍) 来なければ stale = NONE 扱い → coupling 完了で
    // server が re-assert を止めれば client は自然に点滅をやめる (clear broadcast 取りこぼしの保険)。
    private static final long CLIENT_OVERRIDE_TTL_MS = 1000L;
    private record ClientOverride(SignalOverrideState state, long updatedAtMs) {}
    private static final Map<Long, ClientOverride> clientSignalOverrides =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** server: override を、 当該 chunk を tracking 中の client へ配信。 SP は loopback で local client に届く。 */
    public static void broadcastOverride(Level level, BlockPos pos, SignalOverrideState state) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(
                sl, new net.minecraft.world.level.ChunkPos(pos),
                new com.trainsystemutilities.network.CouplingSignalOverridePayload(pos, state.ordinal()));
    }

    /** client: payload 受信で override を格納 (NONE は削除)。 */
    public static void putClientSignalOverride(BlockPos pos, int stateOrdinal, long nowMs) {
        SignalOverrideState state = switch (stateOrdinal) {
            case 1 -> SignalOverrideState.RED_BLUE_BLINK;
            case 2 -> SignalOverrideState.RED_WHITE_SIMULTANEOUS;
            default -> SignalOverrideState.NONE;
        };
        if (state == SignalOverrideState.NONE) clientSignalOverrides.remove(pos.asLong());
        else clientSignalOverrides.put(pos.asLong(), new ClientOverride(state, nowMs));
    }

    /** client: 描画 mixin が読む。 TTL 超過 entry は stale = NONE (= 自然消灯)。 */
    public static SignalOverrideState getClientSignalOverride(BlockPos pos, long nowMs) {
        ClientOverride o = clientSignalOverrides.get(pos.asLong());
        if (o == null) return SignalOverrideState.NONE;
        if (nowMs - o.updatedAtMs() > CLIENT_OVERRIDE_TTL_MS) {
            clientSignalOverrides.remove(pos.asLong());
            return SignalOverrideState.NONE;
        }
        return o.state();
    }

    /**
     * 駅位置をキャッシュに登録する。
     */
    public static void registerStationPosition(Level level, String stationName, BlockPos pos) {
        stationPositionCache.put(keyOf(level, pos), stationName);
    }

    /**
     * 信号位置から最も近い連結/切り離し中の駅を検索する。
     * 半径32ブロック以内の駅をチェック。
     */
    public static String findNearestCouplingStation(Level level, BlockPos signalPos) {
        double searchRadius = 32.0;
        String nearest = null;
        double nearestDist = Double.MAX_VALUE;

        // キャッシュされた駅位置から検索
        for (Map.Entry<GlobalPos, String> entry : stationPositionCache.entrySet()) {
            if (!entry.getKey().dimension().equals(level.dimension())) continue;
            double dist = signalPos.distSqr(entry.getKey().pos());
            if (dist < searchRadius * searchRadius && dist < nearestDist) {
                String stationName = entry.getValue();
                if (isStationWaitingForCoupling(stationName) || isStationDecoupling(stationName)) {
                    nearest = stationName;
                    nearestDist = dist;
                }
            }
        }

        // キャッシュになければネットワークスキャンの結果から検索
        if (nearest == null) {
            TrackNetworkScanner.NetworkData data = TrackNetworkScanner.scanFromPosition(level, signalPos);
            if (data != null) {
                for (TrackNetworkScanner.StationInfo station : data.stations()) {
                    double dist = signalPos.distSqr(station.position());
                    if (dist < searchRadius * searchRadius && dist < nearestDist) {
                        if (isStationWaitingForCoupling(station.name()) || isStationDecoupling(station.name())) {
                            nearest = station.name();
                            nearestDist = dist;
                            stationPositionCache.put(keyOf(level, station.position()), station.name());
                        }
                    }
                }
            }
        }

        return nearest;
    }
}
