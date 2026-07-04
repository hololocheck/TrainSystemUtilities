package com.trainsystemutilities.eta;

import com.trainsystemutilities.blockentity.StationPosCache;
import com.trainsystemutilities.schedule.CreateScheduleIds;

import static com.trainsystemutilities.eta.EtaPredictor.trainLegKey;
import static com.trainsystemutilities.eta.EtaPredictor.getCruiseSpeedStaticForTrain;
import static com.trainsystemutilities.eta.EtaPredictor.DEFAULT_CRUISE_SPEED;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;

/**
 * 列車 ETA 計算 subsystem (god-class 分割: {@code RailwayManagementBlockEntity} から verbatim 移設)。
 *
 * <p>実測ベースの到着時刻予測システム。区間 (= 駅 A → 駅 B) の実測 game tick 数と物理距離 (block)
 * を蓄積し、走行中列車は残距離と実測平均速度の組合せで動的に ETA を出す。
 *
 * <p>移設方針: アルゴリズム / ロジック / コメントを一切変えず、 BE インスタンス依存
 * ({@code this.level} / {@code this.linkedStationName} / {@code this.arrivalEstimateCache})
 * のみメソッド引数化した。それ以外の static map / 定数 / {@code EtaPredictor} 等の参照は不変。
 */
public final class TrainArrivalCalculator {

    private TrainArrivalCalculator() {}

    // ===== 実測ベースの到着時刻予測システム =====
    // 区間 (= 駅 A → 駅 B) の実測 game tick 数と物理距離 (block) を蓄積。
    // 走行中列車は `train.navigation.distanceToDestination` (= 残距離) と
    // 実測平均速度 (= legDistance / legTicks) の組合せで動的に ETA を出すため、
    // 信号停止 / 前詰まり等のイレギュラーが発生してもリアルタイムに反映される。

    // 区間実測データ (集計 measuredLegTicks/Distance + 列車別 *ByTrain) は
    // com.trainsystemutilities.eta.EtaPredictor に relocate (増分3)。 measureTrainTravelTimes /
    // estimateArrival は static import 経由で参照する。

    // === 案L: 駅停車時間の実測 ===
    // キー: "trainIdString@stationName" → 実測停車 tick (EWMA)
    // 貨物・redstone・threshold 等の Timer 以外条件で実測値が無いと不正確だが、
    // station-wait 実測 map は com.trainsystemutilities.eta.EtaPredictor に relocate (B4 増分 2)。

    // === 案N: per-(train, leg) 走行時間 variance 追跡 (Welford = com.trainsystemutilities.eta.WelfordStats に B4 で切出し) ===
    private static final java.util.Map<String, WelfordStats> legStats = new java.util.HashMap<>();

    // === 案M: 位置-時間トレース実測 ===
    /**
     * 区間走行中に一定間隔で {@code (elapsed, traveled)} を記録した波形。
     * {@code elapsed[i] < elapsed[i+1]} かつ {@code traveled[i] < traveled[i+1]} の単調増加で保持。
     * 列車が停止/逆行した時点はサンプル追加をスキップするため、binary search で安全に補間できる。
     */
    public static final class LegTrace {
        public final java.util.List<Long> sampleTicks = new java.util.ArrayList<>(64);
        public final java.util.List<Double> sampleDistances = new java.util.ArrayList<>(64);
        public long totalTicks;
        public double totalDistance;
        public long createdAt;
    }
    // 完了したトレース (per-train per-leg)。キーは trainLegKey と同じ。
    private static final java.util.Map<String, LegTrace> measuredLegTraces = new java.util.HashMap<>();
    // 走行中の列車のアクティブトレース (per-train)。出発で生成、到着で finalize。
    private static final java.util.Map<UUID, LegTrace> activeTraces = new java.util.HashMap<>();

    // === 案E3: 異常値拒絶閾値 ===
    // ratio = sample / existing が [1/MAX, MAX] 範囲外なら EWMA 更新をスキップ。
    private static final double OUTLIER_RATIO_AGGREGATE = 2.0;  // 集計値は厳しめ
    private static final double OUTLIER_RATIO_PER_TRAIN = 2.5;  // 列車別は緩め (個体差を吸収)

    // 列車の出発記録: trainId → 出発gameTick
    private static final java.util.Map<UUID, Long> trainDepartureTick = new java.util.HashMap<>();
    // 列車の出発駅: trainId → 出発駅名
    private static final java.util.Map<UUID, String> trainDepartureStation = new java.util.HashMap<>();
    // 列車出発時の Navigation.distanceStartedAt (= 直後の区間距離)。
    private static final java.util.Map<UUID, Double> trainDepartureDistance = new java.util.HashMap<>();
    // 前回の列車停車駅: trainId → 駅名（発車検知用）
    private static final java.util.Map<UUID, String> prevTrainStation = new java.util.HashMap<>();
    // 列車の現駅到着 gameTick: trainId → arrivalTick
    // 中間駅停車中の ETA を「arrival + wait + 後続」で固定するために使用 (毎tickのスライドを防止)
    private static final java.util.Map<UUID, Long> currentStationArrivalTick = new java.util.HashMap<>();

    // 走行中とみなす最小速度 (block/tick)。これ未満は実質停止扱い。
    private static final double MOVING_SPEED_THRESHOLD = 0.05;
    // DEFAULT_CRUISE_SPEED は EtaPredictor に relocate (増分3、static import で参照)。
    // EWMA 重み: 新サンプル 0.2、既存 0.8 (ゆっくり追従)
    private static final double LEG_EWMA_ALPHA = 0.2;
    // 再アンカー閾値: |dynamic - anchored| が anchorRemaining * この比率を超えたとき再アンカー
    private static final double ANCHOR_DRIFT_RATIO = 0.15;
    // 再アンカーの絶対下限 (近距離では比率より絶対値で判定)
    private static final long ANCHOR_DRIFT_MIN_TICKS = 20;

    /**
     * 列車到着予測のアンカー (案D: 発車時固定 + 停止時1:1スライド + 大ズレ時のみ再計算)。
     * 全モニターで同じ予測値を共有し、表示の揺れを防ぐ。
     *
     * <p>有効範囲: 列車が現在走行中の単一区間 (fromStation → toStation)。
     * 列車が toStation に到着、または別の区間に切り替わった時点で破棄される。
     */
    public static final class TrainArrivalAnchor {
        public final String fromStation;
        public final String toStation;
        public final long departureGameTick;
        public long anchoredArrivalGameTick;
        public long lastQueryGameTick;

        public TrainArrivalAnchor(String fromStation, String toStation,
                                  long departureGameTick, long anchoredArrivalGameTick) {
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.departureGameTick = departureGameTick;
            this.anchoredArrivalGameTick = anchoredArrivalGameTick;
            this.lastQueryGameTick = 0;
        }
    }

    private static final java.util.Map<UUID, TrainArrivalAnchor> currentLegAnchor = new java.util.HashMap<>();

    /**
     * HOTFIX N+0.5 #2/#7: world unload 時に全 static 計測 map を purge する。
     *
     * <p>これらの map はすべて {@code String} (= "from→to" / "trainId|from→to" 等) や
     * {@code UUID} を key として持ち、 dimension 情報を含まない。
     * single-player でワールドを切り替えると前 world の measurement が新 world に流入し
     * cross-world contamination が発生する (WF-G blockentity-lifecycle-22)。
     *
     * <p>本 hotfix は最小修正として「server overworld unload で全 clear」 を実装。
     * 本格 dim-key 化 (= key を {@code ResourceKey<Level>} 複合化) は P0-5 で実施。
     */
    public static synchronized void purgeAllMeasurements() {
        EtaPredictor.clearLegData();
        EtaPredictor.clearStationWait();
        legStats.clear();
        measuredLegTraces.clear();
        activeTraces.clear();
        trainDepartureTick.clear();
        trainDepartureStation.clear();
        trainDepartureDistance.clear();
        prevTrainStation.clear();
        currentStationArrivalTick.clear();
        currentLegAnchor.clear();
        StationPosCache.clear();
    }

    // P0-1 #11: StaticCacheRegistry に自身の purge callback を登録。
    // server 側 LevelEvent.Unload で {@link StaticCacheRegistry#purgeAll} 経由で発火される。
    static {
        belugalab.mcss3.util.concurrent.StaticCacheRegistry.register(
                TrainArrivalCalculator::purgeAllMeasurements,
                "TrainArrivalCalculator");
    }

    /** 実測集計値のみ (測定なしなら null) */
    private static Long lookupMeasuredLegTicks(String fromStation, String toStation) {
        if (fromStation == null || toStation == null) return null;
        Long agg = EtaPredictor.lookupLegTicks(fromStation + "→" + toStation);
        if (agg != null) return agg;
        Long aggRev = EtaPredictor.lookupLegTicks(toStation + "→" + fromStation);
        return aggRev;
    }

    /** 列車別 → 集計の順で実測値を取得 (測定なしなら null) */
    private static Long lookupMeasuredLegTicksForTrain(UUID trainId, String fromStation, String toStation) {
        if (trainId != null && fromStation != null && toStation != null) {
            Long perTrain = EtaPredictor.lookupLegTicksByTrain(trainLegKey(trainId, fromStation, toStation));
            if (perTrain != null) return perTrain;
            Long perTrainRev = EtaPredictor.lookupLegTicksByTrain(trainLegKey(trainId, toStation, fromStation));
            if (perTrainRev != null) return perTrainRev;
        }
        return lookupMeasuredLegTicks(fromStation, toStation);
    }

    /** 列車別 → 集計 → 物理 → デフォルトの順で leg ticks を返す (集計値のみ版、後方互換) */
    public static long getPublicLegTravelTicks(String fromStation, String toStation) {
        Long measured = lookupMeasuredLegTicks(fromStation, toStation);
        if (measured != null) return measured;
        return 200; // 列車不明なら物理計算できないのでデフォルト
    }

    /**
     * 列車別 → 集計 → 物理理論値 → デフォルトの順で leg ticks を返す。
     * <ul>
     *   <li>案E2: 列車別の実測値を最優先 (個別の癖を反映)</li>
     *   <li>案G: 実測値が無い区間は Create 物理パラメータ (maxSpeed/acceleration) と
     *       既知の leg 距離から理論値を算出 (新規開通直後でも初回から精度高い)</li>
     * </ul>
     */
    public static long getPublicLegTravelTicksForTrain(UUID trainId, String fromStation, String toStation) {
        Long measured = lookupMeasuredLegTicksForTrain(trainId, fromStation, toStation);
        if (measured != null) return measured;
        // 案G: 物理理論値
        long theoretical = computeTheoreticalLegTicksForTrain(trainId, fromStation, toStation);
        if (theoretical > 0) return theoretical;
        return 200;
    }

    /**
     * 案G: Create 物理パラメータから理論 legTicks を算出する。
     * <pre>
     *   d_acc = v² / a   (加速 + 減速で消費する距離の合計)
     *   ・台形プロファイル (d ≥ d_acc): t = v/a + d/v
     *   ・三角形プロファイル (d &lt; d_acc): t = 2 × √(d/a)  (最高速度に到達しない)
     * </pre>
     */
    private static long computeTheoreticalLegTicksForTrain(UUID trainId, String fromStation, String toStation) {
        if (trainId == null) return 0;
        com.simibubi.create.content.trains.entity.Train train;
        try {
            train = com.simibubi.create.Create.RAILWAYS.trains.get(trainId);
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] train lookup failed for legTicks", e);
            return 0;
        }
        if (train == null) return 0;
        Double dist = lookupLegDistance(trainId, fromStation, toStation);
        if (dist == null && train.navigation != null && train.navigation.destination != null
                && toStation != null && toStation.equals(train.navigation.destination.name)
                && train.navigation.distanceStartedAt > 0) {
            dist = train.navigation.distanceStartedAt;
        }
        if (dist == null || dist <= 0) return 0;
        return computeTheoreticalLegTicks(dist, train.maxSpeed(), train.acceleration());
    }

    /** 距離 d / 最高速度 v / 加速度 a から理論移動時間を計算。 */
    private static long computeTheoreticalLegTicks(double distance, float maxSpeed, float acceleration) {
        if (distance <= 0 || maxSpeed <= 0 || acceleration <= 0) return 0;
        double v = maxSpeed;
        double a = acceleration;
        double accelDistTotal = v * v / a; // 加速 + 減速 の合計距離
        if (distance >= accelDistTotal) {
            // 台形プロファイル: 加速 + 巡航 + 減速
            double cruiseDist = distance - accelDistTotal;
            double accelTicks = 2.0 * v / a;
            double cruiseTicks = cruiseDist / v;
            return Math.max(20, (long) Math.ceil(accelTicks + cruiseTicks));
        } else {
            // 三角形プロファイル: 巡航速度に到達せず加速→即減速
            return Math.max(20, (long) Math.ceil(2.0 * Math.sqrt(distance / a)));
        }
    }

    /**
     * 案I: スケジュール待機条件1つあたりの予測 wait ticks。
     * <ul>
     *   <li>TimedWaitCondition (ScheduledDelay/IdleCargoCondition): {@code totalWaitTicks()} で正確</li>
     *   <li>cargo/fluid/item threshold: ローディング待ち、デフォルト 60秒</li>
     *   <li>redstone_link / station_powered / station_unloaded: 状態待ち、デフォルト 10秒</li>
     *   <li>player_passenger / time_of_day / その他: デフォルト 30秒</li>
     * </ul>
     */
    private static long estimateWaitTicksForCondition(
            com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition cond) {
        if (cond == null) return 0;
        if (cond instanceof com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition tw) {
            return Math.max(0, tw.totalWaitTicks());
        }
        String id = cond.getId() != null ? cond.getId().toString() : "";
        return switch (id) {
            case CreateScheduleIds.CARGO_THRESHOLD,
                 CreateScheduleIds.FLUID_THRESHOLD,
                 CreateScheduleIds.ITEM_THRESHOLD -> 1200; // 60s 積み込み待ち
            case CreateScheduleIds.REDSTONE_LINK,
                 CreateScheduleIds.STATION_POWERED,
                 CreateScheduleIds.STATION_UNLOADED -> 200;  // 10s 状態待ち
            case CreateScheduleIds.PLAYER_PASSENGER -> 200; // 10s
            case CreateScheduleIds.TIME_OF_DAY -> 600;       // 30s (正確には日時計算要)
            default -> 600;
        };
    }

    /**
     * 案I: ScheduleEntry の待機条件を解析して合計 wait ticks を算出する。
     *
     * <p>conditions は {@code List<List<>>} 構造で「OR of ANDs」:
     * 内側の AND グループは全条件を満たす必要があるので最大値、
     * 外側の OR は最も早く満たされるグループが採用されるので最小値。
     */
    public static long computeWaitTicksForEntry(
            com.simibubi.create.content.trains.schedule.ScheduleEntry entry) {
        if (entry == null || entry.conditions == null || entry.conditions.isEmpty()) return 0;
        long minGroupWait = Long.MAX_VALUE;
        boolean anyGroup = false;
        for (var condGroup : entry.conditions) {
            if (condGroup == null || condGroup.isEmpty()) continue;
            long groupMax = 0;
            for (var cond : condGroup) {
                long thisCondWait = estimateWaitTicksForCondition(cond);
                if (thisCondWait > groupMax) groupMax = thisCondWait;
            }
            if (groupMax < minGroupWait) minGroupWait = groupMax;
            anyGroup = true;
        }
        return anyGroup ? minGroupWait : 0;
    }

    /**
     * 案L: 列車別駅停車時間の実測値を返す (測定なしなら null)。
     */
    private static Long lookupMeasuredStationWait(UUID trainId, String stationName) {
        return EtaPredictor.lookupStationWait(trainId, stationName);
    }

    /**
     * 案L: 案I よりも正確な「列車別実測値優先」の wait ticks 算出。
     * 実測値があればそれを使い、無ければ条件型ベースの推定 (案I) にフォールバック。
     *
     * <p>例: 貨物列車が同じ駅に毎回 45 秒停車していれば、`computeWaitTicksForEntry` の
     * デフォルト 60 秒 (cargo_threshold) ではなく実測 900tick が返る。
     */
    public static long computeStationWaitTicks(UUID trainId, String stationName,
            com.simibubi.create.content.trains.schedule.ScheduleEntry entry) {
        Long measured = lookupMeasuredStationWait(trainId, stationName);
        if (measured != null && measured > 0) return measured;
        return computeWaitTicksForEntry(entry);
    }

    /** 列車別 → 集計の順で leg 距離を取得 (測定なしなら null) */
    private static Double lookupLegDistance(UUID trainId, String fromStation, String toStation) {
        if (fromStation == null || toStation == null) return null;
        if (trainId != null) {
            Double perTrain = EtaPredictor.lookupLegDistanceByTrain(trainLegKey(trainId, fromStation, toStation));
            if (perTrain != null) return perTrain;
            Double perTrainRev = EtaPredictor.lookupLegDistanceByTrain(trainLegKey(trainId, toStation, fromStation));
            if (perTrainRev != null) return perTrainRev;
        }
        Double agg = EtaPredictor.lookupLegDistance(fromStation + "→" + toStation);
        if (agg != null) return agg;
        Double aggRev = EtaPredictor.lookupLegDistance(toStation + "→" + fromStation);
        return aggRev;
    }

    // getCruiseSpeedStaticForTrain は EtaPredictor に relocate (増分3、static import で参照)。

    // 案E3 (異常値拒絶付き EWMA) は com.trainsystemutilities.eta.EtaMath に B4 で切出し (unit test 可能化)。

    public static Long getPublicTrainDepartureTick(UUID trainId) {
        return trainDepartureTick.get(trainId);
    }

    public static String getPublicTrainDepartureStation(UUID trainId) {
        return trainDepartureStation.get(trainId);
    }

    /** 列車が現在停車中の駅への到着 gameTick (停車中でない場合は null) */
    public static Long getPublicStationArrivalTick(UUID trainId) {
        return currentStationArrivalTick.get(trainId);
    }

    // getCruiseSpeedStatic は EtaPredictor に relocate (増分3、EtaPredictor 内部で利用)。

    /**
     * 案D: アンカー方式による安定した到着 gameTick を返す。
     *
     * <p>列車が fromStation → toStation を走行中であることを前提とする。
     * アンカーが未生成なら作成 (departure tick + legTicks)。
     * 既存アンカーは:
     * <ul>
     *   <li>列車停止中 (|speed| &lt; 0.05): 経過 game tick だけ ETA を未来へ 1:1 スライド</li>
     *   <li>フェーズ分解動的予測値が anchorRemaining × 15% (最低20tick) 以上ズレた時のみ再アンカー</li>
     * </ul>
     */
    public static long getAnchoredArrivalGameTick(
            Level level,
            com.simibubi.create.content.trains.entity.Train train,
            UUID trainId,
            String fromStation,
            String toStation) {
        if (level == null || trainId == null || fromStation == null || toStation == null) return 0;

        long currentGameTick = level.getGameTime();

        TrainArrivalAnchor anchor = currentLegAnchor.get(trainId);

        // 区間が変わっていれば古いアンカーを破棄
        if (anchor != null && (!anchor.toStation.equals(toStation)
                || !anchor.fromStation.equals(fromStation))) {
            currentLegAnchor.remove(trainId);
            anchor = null;
        }

        // アンカーが無ければ生成 (案E2: 列車別 legTicks を優先)
        if (anchor == null) {
            long legTicks = getPublicLegTravelTicksForTrain(trainId, fromStation, toStation);
            Long departureTick = trainDepartureTick.get(trainId);
            long anchorDeparture;
            long anchoredArrival;
            if (departureTick != null) {
                // 出発を観測済み: departureTick + 平均区間時間
                anchorDeparture = departureTick;
                anchoredArrival = departureTick + legTicks;
            } else if (train != null && train.getCurrentStation() == null) {
                // 走行中だが出発時刻が未記録 (サーバ起動直後など): トレース実測+物理で推定
                Long dynamicRemaining = estimateRemainingHybrid(train, trainId, fromStation, toStation);
                anchorDeparture = currentGameTick;
                anchoredArrival = currentGameTick
                        + (dynamicRemaining != null ? dynamicRemaining : legTicks / 2);
            } else {
                // 列車が出発駅に停車中 (発車直前): 出発を待たず暫定アンカー
                anchorDeparture = currentGameTick;
                anchoredArrival = currentGameTick + legTicks;
            }
            anchor = new TrainArrivalAnchor(fromStation, toStation, anchorDeparture, anchoredArrival);
            currentLegAnchor.put(trainId, anchor);
        }

        // 停止検知スライド: 走行中に train.speed ≈ 0 なら ETA を経過分だけ未来へずらす
        boolean inTransit = train != null && train.getCurrentStation() == null;
        if (inTransit && anchor.lastQueryGameTick > 0) {
            double absSpeed = Math.abs(train.speed);
            if (absSpeed < MOVING_SPEED_THRESHOLD) {
                long elapsed = currentGameTick - anchor.lastQueryGameTick;
                if (elapsed > 0 && elapsed < 200) { // sanity: 10秒超のジャンプは無視
                    anchor.anchoredArrivalGameTick += elapsed;
                }
            }
        }
        anchor.lastQueryGameTick = currentGameTick;

        // 大ズレ検知: 案M トレース実測 → 案F 物理 のハイブリッド予測と比較。
        // トレースがある区間では実測補間 (信号/カーブ含む) で正確、無ければ物理予測。
        // 案N: 区間別 variance に応じた適応的閾値で再アンカー判定。
        if (inTransit) {
            Long dynamicRemaining = estimateRemainingHybrid(train, trainId, fromStation, toStation);
            if (dynamicRemaining != null) {
                long dynamicArrivalGameTick = currentGameTick + dynamicRemaining;
                long anchorRemaining = Math.max(1, anchor.anchoredArrivalGameTick - currentGameTick);
                long drift = Math.abs(dynamicArrivalGameTick - anchor.anchoredArrivalGameTick);

                // 閾値決定: variance データが十分 (>=3 サンプル) なら 2σ、無ければ 15% 比率
                long threshold;
                WelfordStats stats = legStats.get(trainLegKey(trainId, fromStation, toStation));
                if (stats != null && stats.count >= 3) {
                    long twoSigma = (long) (2.0 * stats.stddev());
                    threshold = Math.max(ANCHOR_DRIFT_MIN_TICKS,
                            Math.min(anchorRemaining / 2, twoSigma));
                } else {
                    threshold = Math.max(ANCHOR_DRIFT_MIN_TICKS,
                            (long) (anchorRemaining * ANCHOR_DRIFT_RATIO));
                }
                if (drift > threshold) {
                    anchor.anchoredArrivalGameTick = dynamicArrivalGameTick;
                }
            }
        }

        return anchor.anchoredArrivalGameTick;
    }

    /**
     * gameTick 版アンカークエリの dayTime ラッパー。
     * 表示用 (旧来の API 互換)。
     */
    public static long getAnchoredArrivalDayTime(
            Level level,
            com.simibubi.create.content.trains.entity.Train train,
            UUID trainId,
            String fromStation,
            String toStation) {
        if (level == null) return 0;
        long anchoredGameTick = getAnchoredArrivalGameTick(level, train, trainId, fromStation, toStation);
        if (anchoredGameTick <= 0) return 0;
        long currentGameTick = level.getGameTime();
        long currentDayTime = level.getDayTime();
        long etaDayTime = currentDayTime + (anchoredGameTick - currentGameTick);
        return Math.max(currentDayTime, etaDayTime);
    }

    /**
     * 案M: 位置-時間トレース実測からの残時間補間予測。
     * 過去走行のトレースで現在距離に達した時点の elapsed を二分探索で線形補間し、
     * 残時間 = totalTicks - expectedElapsedAtThisDistance を返す。
     *
     * <p>これは実走行データの直接補間であり、信号/カーブ/分岐等の「同じ場所で毎回起こる
     * 減速・停止パターン」を完全に反映する。物理予測 (案F) より正確。
     *
     * @return 残 tick 数。トレース無しなら null。
     */
    private static Long estimateRemainingFromTrace(UUID trainId, String fromStation,
                                                    String toStation, double currentTraveled) {
        if (trainId == null || fromStation == null || toStation == null) return null;
        LegTrace trace = measuredLegTraces.get(trainLegKey(trainId, fromStation, toStation));
        if (trace == null || trace.sampleTicks.isEmpty()) {
            // 逆方向のトレース (= 同じ track、向き違い) を試す
            trace = measuredLegTraces.get(trainLegKey(trainId, toStation, fromStation));
            if (trace == null || trace.sampleTicks.isEmpty()) return null;
        }
        if (trace.totalTicks <= 0) return null;

        if (currentTraveled <= 0) return trace.totalTicks;
        if (currentTraveled >= trace.totalDistance) return 0L;

        int n = trace.sampleDistances.size();
        // 二分探索: sampleDistances[lo] >= currentTraveled となる最小 lo
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (trace.sampleDistances.get(mid) < currentTraveled) lo = mid + 1;
            else hi = mid;
        }

        long expectedElapsed;
        double dAtLo = trace.sampleDistances.get(lo);
        long tAtLo = trace.sampleTicks.get(lo);
        if (lo == 0) {
            // (0, 0) と (dAtLo, tAtLo) で線形補間
            if (dAtLo <= 0.001) {
                expectedElapsed = tAtLo;
            } else {
                double frac = currentTraveled / dAtLo;
                expectedElapsed = Math.round(tAtLo * frac);
            }
        } else if (Math.abs(dAtLo - currentTraveled) < 0.5) {
            expectedElapsed = tAtLo;
        } else {
            double dPrev = trace.sampleDistances.get(lo - 1);
            long tPrev = trace.sampleTicks.get(lo - 1);
            double dDelta = dAtLo - dPrev;
            if (dDelta < 0.001) {
                expectedElapsed = tAtLo;
            } else {
                double frac = (currentTraveled - dPrev) / dDelta;
                expectedElapsed = tPrev + Math.round((tAtLo - tPrev) * frac);
            }
        }

        long remaining = trace.totalTicks - expectedElapsed;
        return Math.max(0, remaining);
    }

    /**
     * 案M優先 → 案F+G フォールバックの残時間予測。
     * トレースが蓄積された区間では実測ベース、未蓄積なら物理ベース。
     */
    private static Long estimateRemainingHybrid(
            com.simibubi.create.content.trains.entity.Train train,
            UUID trainId, String fromStation, String toStation) {
        // 案M: トレース実測値を最優先
        if (train != null && train.navigation != null && train.navigation.distanceStartedAt > 0) {
            double total = train.navigation.distanceStartedAt;
            double remaining = train.navigation.distanceToDestination;
            if (remaining < 0) remaining = 0;
            double traveled = total - remaining;
            Long traceRemaining = estimateRemainingFromTrace(trainId, fromStation, toStation, traveled);
            if (traceRemaining != null) return traceRemaining;
        }
        // 案F+G: 物理ベースのフォールバック
        return estimateRemainingTicksByCruise(train, fromStation, toStation);
    }

    /**
     * 案F+G: フェーズ分解予測。Create 物理パラメータ (maxSpeed/acceleration) で
     * 加速・巡航・減速のフェーズ境界を算出し、列車の現在位置から残時間を
     * 物理的に正確に推定する。さらに (measured/theoretical) スケール係数で
     * 実走行の overhead (curve, signal 等) を補正する。
     *
     * <p>これにより加減速フェーズでも誤差なく残時間が出るため、案E1 の
     * inAccelOrDecelPhase guard は不要になる。
     */
    private static Long estimateRemainingTicksByCruise(
            com.simibubi.create.content.trains.entity.Train train,
            String fromStation, String toStation) {
        if (train == null || train.navigation == null) return null;
        if (train.navigation.destination == null) return null;
        String navDest = train.navigation.destination.name;
        if (navDest == null || !navDest.equals(toStation)) return null;
        double total = train.navigation.distanceStartedAt;
        double remaining = train.navigation.distanceToDestination;
        if (remaining <= 0) return 0L;
        if (total <= 0) {
            // 区間総距離不明 → 巡航速度フォールバック
            double cruise = getCruiseSpeedStaticForTrain(train.id, fromStation, toStation);
            if (cruise <= 0.001) cruise = DEFAULT_CRUISE_SPEED;
            return (long) Math.ceil(remaining / cruise);
        }

        float vmax = train.maxSpeed();
        float a = train.acceleration();
        if (vmax <= 0 || a <= 0) {
            // 物理パラメータ取得失敗 → 巡航速度フォールバック
            double cruise = getCruiseSpeedStaticForTrain(train.id, fromStation, toStation);
            if (cruise <= 0.001) cruise = DEFAULT_CRUISE_SPEED;
            return (long) Math.ceil(remaining / cruise);
        }

        double traveled = total - remaining;
        double idealRemaining = computePhaseAwareRemaining(traveled, total, vmax, a);
        if (idealRemaining < 0) return null;

        // スケール補正: 実走行 / 物理理論 の比で curve/signal の overhead を反映
        long idealLegTicks = computeTheoreticalLegTicks(total, vmax, a);
        Long measuredTicks = lookupMeasuredLegTicksForTrain(train.id, fromStation, toStation);
        double scale = 1.0;
        if (measuredTicks != null && idealLegTicks > 0) {
            scale = (double) measuredTicks / idealLegTicks;
            if (scale < 0.7) scale = 0.7; // sanity 下限
            if (scale > 3.0) scale = 3.0; // sanity 上限
        }
        return Math.max(0, (long) Math.ceil(idealRemaining * scale));
    }

    /**
     * 案F: 物理計算による残時間 (gameTick)。
     * <ul>
     *   <li>台形プロファイル (total ≥ 2×d_acc): accel + cruise + decel の3フェーズ</li>
     *   <li>三角形プロファイル (total &lt; 2×d_acc): 巡航速度未到達、accel + decel のみ</li>
     * </ul>
     * 各フェーズの残時間を運動方程式 (v² = u² + 2as, v = u + at) から算出。
     */
    private static double computePhaseAwareRemaining(double traveled, double total,
                                                     double vmax, double a) {
        if (traveled < 0) traveled = 0;
        if (traveled >= total) return 0;
        double dAcc = vmax * vmax / (2.0 * a);

        if (total >= 2.0 * dAcc) {
            // 台形プロファイル
            double dCruiseEnd = total - dAcc;
            double cruiseTotal = total - 2.0 * dAcc;
            double cruiseTime = cruiseTotal / vmax;
            double accelTime = vmax / a;

            if (traveled < dAcc) {
                // 加速フェーズ中: v_current² = 2a × traveled
                double vCurrent = Math.sqrt(2.0 * a * traveled);
                double remainAccelTime = (vmax - vCurrent) / a;
                return remainAccelTime + cruiseTime + accelTime;
            } else if (traveled < dCruiseEnd) {
                // 巡航フェーズ中
                double remainCruiseDist = dCruiseEnd - traveled;
                return remainCruiseDist / vmax + accelTime;
            } else {
                // 減速フェーズ中: v_current² = vmax² - 2a × (traveled - dCruiseEnd)
                double decelDist = traveled - dCruiseEnd;
                double vCurrentSq = Math.max(0, vmax * vmax - 2.0 * a * decelDist);
                return Math.sqrt(vCurrentSq) / a;
            }
        } else {
            // 三角形プロファイル: 巡航速度に到達せず加速→即減速
            double half = total / 2.0;
            double vPeak = Math.sqrt(a * total);
            if (traveled < half) {
                // 加速フェーズ中
                double vCurrent = Math.sqrt(2.0 * a * traveled);
                double remainAccelTime = (vPeak - vCurrent) / a;
                double decelTime = vPeak / a;
                return remainAccelTime + decelTime;
            } else {
                // 減速フェーズ中
                double decelDist = traveled - half;
                double vCurrentSq = Math.max(0, vPeak * vPeak - 2.0 * a * decelDist);
                return Math.sqrt(vCurrentSq) / a;
            }
        }
    }

    /**
     * グローバル測定の最終実行 gameTick (= 同一 tick 内に多重実行しないためのガード)。
     * 多数の RailwayManagementBlockEntity がほぼ同時に updateTrainInfo() を呼び出しても、
     * 実体の重い処理 (全列車 forEach) は 1 game tick 内で 1 回しか走らない。
     * Tier 1 改善: per-BE × 全列車 = O(N×M) → グローバル 1回 = O(M) に削減。
     */
    private static long lastGlobalMeasureGameTick = -100;

    /**
     * 全列車の駅間移動を監視して実測データを収集する。
     * updateTrainInfoから毎秒呼ばれるが、複数 BE が同 tick で呼んでも本体は 1 回だけ実行。
     */
    public static void measureTrainTravelTimes(Level level) {
        long profStart = com.trainsystemutilities.profiler.TsuProfiler.start();
        try {
            if (level == null) return;
            long gameTick = level.getGameTime();
            // グローバル抑制: 同 game tick 内では 1 回だけ実行
            if (gameTick == lastGlobalMeasureGameTick) return;
            lastGlobalMeasureGameTick = gameTick;
            measureTrainTravelTimesImpl(level);
        } finally {
            com.trainsystemutilities.profiler.TsuProfiler.end(
                    com.trainsystemutilities.profiler.TsuProfiler.Phase.TSU_MEASURE, profStart);
        }
    }

    private static void measureTrainTravelTimesImpl(Level level) {
        if (level == null) return;
        long gameTick = level.getGameTime();

        try {
            com.simibubi.create.Create.RAILWAYS.trains.forEach((id, train) -> {
                String currentStation = train.getCurrentStation() != null ? train.getCurrentStation().name : null;
                String prevStation = prevTrainStation.get(id);

                if (prevStation != null && currentStation == null) {
                    // 列車が駅を出発した（停車中→移動中に変化）
                    // 案L: 実停車時間を測定して EWMA 更新
                    Long arrivalTick = currentStationArrivalTick.get(id);
                    if (arrivalTick != null) {
                        long actualWaitTicks = gameTick - arrivalTick;
                        // sanity: 1秒〜10分範囲のみ採用
                        if (actualWaitTicks >= 20 && actualWaitTicks <= 12000) {
                            Long existingWait = EtaPredictor.lookupStationWait(id, prevStation);
                            var waitEwma = EtaMath.applyEwmaLong(existingWait, actualWaitTicks,
                                    LEG_EWMA_ALPHA, OUTLIER_RATIO_PER_TRAIN);
                            if (waitEwma.accepted()) {
                                EtaPredictor.recordStationWait(id, prevStation, waitEwma.value());
                            }
                        }
                    }
                    trainDepartureStation.put(id, prevStation);
                    trainDepartureTick.put(id, gameTick);
                    currentStationArrivalTick.remove(id); // もう停車中ではない
                    // 案M: 新しい leg のアクティブトレース開始 (旧トレースは破棄)
                    activeTraces.put(id, new LegTrace());
                    // Navigation が活きていれば区間開始時の距離もキャプチャ
                    if (train.navigation != null && train.navigation.distanceStartedAt > 0) {
                        trainDepartureDistance.put(id, train.navigation.distanceStartedAt);
                    }
                }

                // 案M: 走行中サンプリング (currentStation == null かつ出発記録あり)
                if (currentStation == null && trainDepartureTick.containsKey(id)) {
                    LegTrace activeTrace = activeTraces.get(id);
                    if (activeTrace != null && train.navigation != null
                            && train.navigation.distanceStartedAt > 0) {
                        long elapsed = gameTick - trainDepartureTick.get(id);
                        double total = train.navigation.distanceStartedAt;
                        double remaining = train.navigation.distanceToDestination;
                        double traveled = total - remaining;
                        if (traveled >= 0 && traveled <= total + 1.0 && elapsed >= 0) {
                            int last = activeTrace.sampleTicks.size() - 1;
                            if (last < 0) {
                                activeTrace.sampleTicks.add(elapsed);
                                activeTrace.sampleDistances.add(traveled);
                            } else {
                                long prevElapsed = activeTrace.sampleTicks.get(last);
                                double prevDist = activeTrace.sampleDistances.get(last);
                                // 単調増加のみ採用 (停止/逆行時はスキップ → first-time-at-distance を保つ)
                                if (elapsed > prevElapsed && traveled > prevDist + 0.5) {
                                    activeTrace.sampleTicks.add(elapsed);
                                    activeTrace.sampleDistances.add(traveled);
                                }
                            }
                        }
                    }
                }
                if (currentStation != null && !currentStation.equals(prevStation)) {
                    // 列車が新たに駅に到着 (= prevStation と異なる駅、または初観測)
                    currentStationArrivalTick.put(id, gameTick);
                }

                if (currentStation != null && trainDepartureStation.containsKey(id)) {
                    // 列車が駅に到着した
                    String fromStation = trainDepartureStation.get(id);
                    Long departureTick = trainDepartureTick.get(id);
                    if (departureTick != null && !currentStation.equals(fromStation)) {
                        long travelTicks = gameTick - departureTick;
                        String legKey = fromStation + "→" + currentStation;
                        String trainKey = trainLegKey(id, fromStation, currentStation);
                        Double depDist = trainDepartureDistance.get(id);

                        // === 案E3 + E2: 集計 / 列車別 EWMA 更新 (両方とも異常値拒絶付き) ===
                        Long existing = EtaPredictor.lookupLegTicks(legKey);
                        var agResult = EtaMath.applyEwmaLong(existing, travelTicks,
                                LEG_EWMA_ALPHA, OUTLIER_RATIO_AGGREGATE);
                        if (agResult.accepted()) {
                            EtaPredictor.recordLegTicks(legKey, agResult.value());
                        }

                        Long perTrainExisting = EtaPredictor.lookupLegTicksByTrain(trainKey);
                        var perTrainResult = EtaMath.applyEwmaLong(perTrainExisting, travelTicks,
                                LEG_EWMA_ALPHA, OUTLIER_RATIO_PER_TRAIN);
                        if (perTrainResult.accepted()) {
                            EtaPredictor.recordLegTicksByTrain(trainKey, perTrainResult.value());
                            // 案N: variance も更新 (拒絶された外れ値はカウントしない)
                            legStats.computeIfAbsent(trainKey, k -> new WelfordStats())
                                    .update(travelTicks);
                        }

                        // 区間距離も同様に集計/列車別を更新
                        if (depDist != null && depDist > 0) {
                            var agDist = EtaMath.applyEwmaDouble(EtaPredictor.lookupLegDistance(legKey),
                                    depDist, LEG_EWMA_ALPHA, OUTLIER_RATIO_AGGREGATE);
                            if (agDist.accepted()) {
                                EtaPredictor.recordLegDistance(legKey, agDist.value());
                            }
                            var perTrainDist = EtaMath.applyEwmaDouble(
                                    EtaPredictor.lookupLegDistanceByTrain(trainKey),
                                    depDist, LEG_EWMA_ALPHA, OUTLIER_RATIO_PER_TRAIN);
                            if (perTrainDist.accepted()) {
                                EtaPredictor.recordLegDistanceByTrain(trainKey, perTrainDist.value());
                            }
                        }

                        // 実測ログ (Logger 経由で I/O ブロックを回避、debug レベルなのでデフォルト無効)
                        if (com.trainsystemutilities.TrainSystemUtilities.LOGGER.isDebugEnabled()) {
                            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                                    "[TrainMeasure] leg={} raw={}t ag={} perTrain={} dist={} train={} gameTick={}",
                                    legKey, travelTicks,
                                    agResult.accepted() ? agResult.value() + "t" : "REJECTED",
                                    perTrainResult.accepted() ? perTrainResult.value() + "t" : "REJECTED",
                                    depDist != null ? String.format("%.1f", depDist) + "b" : "n/a",
                                    train.name.getString(), gameTick);
                        }

                        // 案M: アクティブトレースを finalize して保存 (per-train EWMA accepted のときのみ)
                        LegTrace activeTrace = activeTraces.remove(id);
                        if (activeTrace != null && !activeTrace.sampleTicks.isEmpty()
                                && perTrainResult.accepted()) {
                            activeTrace.totalTicks = travelTicks;
                            activeTrace.totalDistance = depDist != null && depDist > 0 ? depDist
                                    : activeTrace.sampleDistances.get(activeTrace.sampleDistances.size() - 1);
                            activeTrace.createdAt = gameTick;
                            measuredLegTraces.put(trainKey, activeTrace);
                        }
                    } else {
                        // 同じ駅から同じ駅 (or 出発記録不整合) → トレース捨てる
                        activeTraces.remove(id);
                    }

                    trainDepartureStation.remove(id);
                    trainDepartureTick.remove(id);
                    trainDepartureDistance.remove(id);
                    // 列車到着でアンカー破棄 (次区間で改めて生成)
                    currentLegAnchor.remove(id);
                }

                prevTrainStation.put(id, currentStation);
            });
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] travel-time trace update failed", e); /* ignore */ }
    }

    /**
     * 区間の移動時間を取得する。実測値優先、なければ距離ベース推定。
     */
    private static long getLegTravelTicks(Level level, String fromStation, String toStation) {
        if (fromStation == null || toStation == null) return 200;

        // 実測値を確認
        String legKey = fromStation + "→" + toStation;
        Long measured = EtaPredictor.lookupLegTicks(legKey);
        if (measured != null) return measured;

        // 逆方向の実測値も参考にする
        String reverseKey = toStation + "→" + fromStation;
        Long reverseMeasured = EtaPredictor.lookupLegTicks(reverseKey);
        if (reverseMeasured != null) return reverseMeasured;

        // フォールバック: 距離ベースの推定
        return estimateLegByDistance(level, fromStation, toStation);
    }

    /**
     * 距離ベースのフォールバック推定（実測データがない場合のみ使用）
     */
    private static long estimateLegByDistance(Level level, String fromStation, String toStation) {
        StationPosCache.refresh(level);
        BlockPos fromPos = StationPosCache.get(fromStation);
        BlockPos toPos = StationPosCache.get(toStation);
        if (fromPos == null || toPos == null) return 200;

        double dx = toPos.getX() - fromPos.getX();
        double dy = toPos.getY() - fromPos.getY();
        double dz = toPos.getZ() - fromPos.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        long travelTicks = (long) Math.ceil(distance * 1.15 / 1.3) + 50;
        return Math.max(20, travelTicks);
    }

    /**
     * 区間 (fromStation→toStation) の巡航速度 (block/tick) を推定する。
     * 実測 leg distance / leg ticks があれば使用、なければ DEFAULT_CRUISE_SPEED。
     */
    private static double getCruiseSpeed(String fromStation, String toStation) {
        if (fromStation == null || toStation == null) return DEFAULT_CRUISE_SPEED;
        String legKey = fromStation + "→" + toStation;
        Double dist = EtaPredictor.lookupLegDistance(legKey);
        Long ticks = EtaPredictor.lookupLegTicks(legKey);
        if (dist == null) {
            dist = EtaPredictor.lookupLegDistance(toStation + "→" + fromStation);
            if (ticks == null) ticks = EtaPredictor.lookupLegTicks(toStation + "→" + fromStation);
        }
        if (dist != null && ticks != null && ticks > 0) {
            double cruise = dist / ticks;
            if (cruise > 0.05 && cruise < 5.0) return cruise; // sanity check
        }
        return DEFAULT_CRUISE_SPEED;
    }

    /**
     * 走行中列車の現区間における残 tick 数を、Navigation 残距離と巡航速度から推定する。
     *
     * <p>案D 移行に伴い:
     * <ul>
     *   <li>{@code train.speed} を使う effective 速度ロジックを廃止 (= 加減速時の振動を防止)</li>
     *   <li>{@code ticksWaitingForSignal/2} 線形ペナルティを削除 (= 案D の停止検知スライドで代替)</li>
     * </ul>
     *
     * <p>本メソッドは「アンカーが存在しない初期生成時のフォールバック」用。通常時は
     * {@link #getAnchoredArrivalDayTime} を使う。
     *
     * @return 残 tick 数 / Navigation データが取れない場合は null
     */
    private static Long estimateRemainingTicksDynamic(
            com.simibubi.create.content.trains.entity.Train train,
            String fromStation, String toStation) {
        // 巡航速度ベースの static 版に委譲 (instance/static の両系統で同じ実装を使う)
        return estimateRemainingTicksByCruise(train, fromStation, toStation);
    }

    // refreshStationPosCache は StationPosCache.refresh(level) に移設 (B: god-class 縮小)。

    public static long estimateArrivalDayTime(Level level, String linkedStationName,
            Map<String, Long> arrivalEstimateCache,
            com.simibubi.create.content.trains.entity.Train train) {
        long profStart = com.trainsystemutilities.profiler.TsuProfiler.start();
        try {
            return estimateArrivalDayTimeImpl(level, linkedStationName, arrivalEstimateCache, train);
        } finally {
            com.trainsystemutilities.profiler.TsuProfiler.end(
                    com.trainsystemutilities.profiler.TsuProfiler.Phase.TSU_ESTIMATE, profStart);
        }
    }

    private static long estimateArrivalDayTimeImpl(Level level, String linkedStationName,
            Map<String, Long> arrivalEstimateCache,
            com.simibubi.create.content.trains.entity.Train train) {
        if (level == null) return 0;
        try {
            if (train.runtime != null && train.runtime.getSchedule() != null) {
                var schedule = train.runtime.getSchedule();
                int current = train.runtime.currentEntry;

                // currentEntry はスケジュール変更直後 (連結/切り離しで entries が書き換わる
                // 瞬間など) に [0, size) を外れることがある。下の entries.get(current) で
                // IndexOutOfBounds になる前にガード (cf. TrainScheduleReader#isTrainApproaching)。
                if (schedule.entries.isEmpty()
                        || current < 0 || current >= schedule.entries.size()) {
                    return 0;
                }

                // キャッシュ方針:
                // - 駅停車中 (isMoving=false): currentEntry が同じ間は変動なし → キャッシュ可
                // - 移動中 (isMoving=true): Navigation.distanceToDestination や train.speed が
                //   毎 tick 変わるため動的に再計算したい → キャッシュしない
                boolean isMoving = train.getCurrentStation() == null;
                String cacheKey = train.id.toString() + ":" + current + ":" + (isMoving ? "m" : "s");
                if (!isMoving) {
                    Long cached = arrivalEstimateCache.get(cacheKey);
                    if (cached != null) return cached;
                }

                String trainPrefix = train.id.toString() + ":";
                arrivalEstimateCache.keySet().removeIf(k -> k.startsWith(trainPrefix));

                long currentGameTick = level.getGameTime();
                long currentDayTime = level.getDayTime();

                CompoundTag currentData = schedule.entries.get(current).instruction.getData();
                String currentStationName = currentData.contains("Text") ? currentData.getString("Text") : "";

                // === フルチェーン アンカー: すべて gameTick 空間で計算 ===
                // 列車が currentStation に到着する gameTick を確定 (停車中なら実到着、走行中ならアンカー予測)。
                long firstArrivalGameTick;
                if (train.getCurrentStation() != null) {
                    Long arrivalTick = currentStationArrivalTick.get(train.id);
                    firstArrivalGameTick = arrivalTick != null ? arrivalTick : currentGameTick;
                } else {
                    int prevIdx = (current - 1 + schedule.entries.size()) % schedule.entries.size();
                    CompoundTag prevData = schedule.entries.get(prevIdx).instruction.getData();
                    String fromStation = prevData.contains("Text") ? prevData.getString("Text") : null;
                    if (fromStation != null && !fromStation.isEmpty()) {
                        long anchored = getAnchoredArrivalGameTick(
                                level, train, train.id, fromStation, currentStationName);
                        firstArrivalGameTick = anchored > 0 ? anchored
                                : currentGameTick + getLegTravelTicks(level, null, currentStationName) / 2;
                    } else {
                        firstArrivalGameTick = currentGameTick
                                + getLegTravelTicks(level, null, currentStationName) / 2;
                    }
                }

                // 結果 gameTick: chain で linkedStation までの全ての leg+wait を加算
                long resultGameTick = -1;
                if (linkedStationName.equals(currentStationName)) {
                    // 列車が直接こちらに到着する → arrival がそのまま結果
                    resultGameTick = firstArrivalGameTick;
                } else {
                    // チェーン: arrival + waitAtCurrent + Σ(legTicks + waitTicks) で linkedStation まで
                    // 案L: 列車別実測 wait ticks を優先 (cargo/threshold 等もリアルな値に)
                    long basisGameTick = firstArrivalGameTick
                            + computeStationWaitTicks(train.id, currentStationName,
                                    schedule.entries.get(current));
                    String prevStationName = currentStationName;
                    long sumLegs = 0;
                    long sumWaits = 0;
                    for (int i = 1; i < schedule.entries.size(); i++) {
                        int idx = (current + i) % schedule.entries.size();
                        var entry = schedule.entries.get(idx);
                        CompoundTag data = entry.instruction.getData();
                        if (!data.contains("Text")) continue;
                        String stationName = data.getString("Text");

                        sumLegs += getPublicLegTravelTicksForTrain(
                                train.id, prevStationName, stationName);

                        if (linkedStationName.equals(stationName)) {
                            // linkedStation 到着時点の gameTick (waitTime は加算しない)
                            resultGameTick = basisGameTick + sumLegs + sumWaits;
                            break;
                        }
                        sumWaits += computeStationWaitTicks(train.id, stationName, entry);
                        prevStationName = stationName;
                    }
                }

                if (resultGameTick < 0) {
                    // linkedStation がスケジュールに無い → 予測不能
                    return 0;
                }

                // 案Q: 単一の gameTick → dayTime 変換 (clamp は撤廃: 列車到着済みのときは過去時刻が正しい)
                long result = currentDayTime + (resultGameTick - currentGameTick);

                // 移動中は毎フレーム再計算したいのでキャッシュしない (上の早期 return も停車中限定)
                if (!isMoving) {
                    arrivalEstimateCache.put(cacheKey, result);
                }

                // 予測ログ (debug 有効時のみ、毎秒数十回起きるため通常は無効化)
                if (com.trainsystemutilities.TrainSystemUtilities.LOGGER.isDebugEnabled()) {
                    String navInfo = "n/a";
                    if (isMoving && train.navigation != null && train.navigation.destination != null) {
                        boolean stalled = train.navigation.waitingForSignal != null;
                        navInfo = String.format("dist=%.1fb speed=%.3fb/t stalled=%s waitTicks=%d",
                                train.navigation.distanceToDestination,
                                Math.abs(train.speed),
                                stalled ? "Y" : "N",
                                train.navigation.ticksWaitingForSignal);
                    }
                    com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                            "[TrainEstimate] train={} target={} currentEntry={} moving={} firstArrivalGT={} resultGT={} currentGT={} currentDayTime={} estArrivalDayTime={} nav={}",
                            train.name.getString(), linkedStationName, currentStationName, isMoving,
                            firstArrivalGameTick, resultGameTick, currentGameTick,
                            currentDayTime, result, navInfo);
                }

                return result;
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] arrival estimate failed", e); /* ignore */ }
        return 0;
    }
}
