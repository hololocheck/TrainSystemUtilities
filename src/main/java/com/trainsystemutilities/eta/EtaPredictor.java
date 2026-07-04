package com.trainsystemutilities.eta;

import java.util.UUID;

/**
 * 列車 ETA 予測の純ロジック (key 生成 + 巡航速度計算)。
 *
 * <p>B4 継続: {@code RailwayManagementBlockEntity} の ETA subsystem から、Minecraft 非依存の
 * 純粋部分を段階的に切り出していく先。本増分は key builder + cruise-speed 計算式。
 * 実測 map データ + tick 駆動の収集ロジックは runtime 挙動の検証が要るため BlockEntity 側に残す。
 */
public final class EtaPredictor {

    private EtaPredictor() {}

    /** 巡航速度として妥当とみなす下限 / 上限 (block/tick)。 */
    public static final double MIN_CRUISE = 0.05;
    public static final double MAX_CRUISE = 5.0;

    /** 列車別 leg キー: {@code "trainId|from→to"}。 */
    public static String trainLegKey(UUID trainId, String fromStation, String toStation) {
        return trainId.toString() + "|" + fromStation + "→" + toStation;
    }

    /** 駅停車時間の実測キー: {@code "trainId@station"}。 */
    public static String stationWaitKey(UUID trainId, String stationName) {
        return trainId.toString() + "@" + stationName;
    }

    /**
     * 実測距離 / 実測 tick から巡航速度 (block/tick) を計算する。
     * dist/ticks が無効、または速度が ({@link #MIN_CRUISE}, {@link #MAX_CRUISE}) の外なら
     * {@code fallback} を返す。
     */
    public static double computeCruiseSpeed(Double dist, Long ticks, double fallback) {
        if (dist != null && ticks != null && ticks > 0) {
            double cruise = dist / ticks;
            if (cruise > MIN_CRUISE && cruise < MAX_CRUISE) return cruise;
        }
        return fallback;
    }

    // === station-wait 実測 (B4 増分 2: RailwayManagementBlockEntity から relocate、 ロジック不変) ===
    private static final java.util.Map<String, Long> stationWaitTicks = new java.util.HashMap<>();

    /** 列車別駅停車時間の実測値 (測定なしなら null)。 */
    public static Long lookupStationWait(UUID trainId, String stationName) {
        if (trainId == null || stationName == null) return null;
        return stationWaitTicks.get(stationWaitKey(trainId, stationName));
    }

    /** 列車別駅停車時間の実測値を記録。 */
    public static void recordStationWait(UUID trainId, String stationName, long ticks) {
        stationWaitTicks.put(stationWaitKey(trainId, stationName), ticks);
    }

    /** station-wait 実測を全消去 (lifecycle purge 用)。 */
    public static void clearStationWait() {
        stationWaitTicks.clear();
    }

    // === cruise-speed / leg 実測 (増分3: RailwayManagementBlockEntity から relocate) ===
    // tick 駆動の収集ループ / estimateArrival は下の lookup/record accessor 経由でアクセスする (encapsulated)。
    private static final java.util.Map<String, Long> measuredLegTicks = new java.util.HashMap<>();             // 区間集計 (key "from→to")
    private static final java.util.Map<String, Double> measuredLegDistance = new java.util.HashMap<>();         // 区間集計 距離 (block)
    private static final java.util.Map<String, Long> measuredLegTicksByTrain = new java.util.HashMap<>();       // 列車別 (key trainLegKey)
    private static final java.util.Map<String, Double> measuredLegDistanceByTrain = new java.util.HashMap<>();  // 列車別 距離 (block)

    /** フォールバック巡航速度 (実測がない場合の暫定値、 block/tick)。 */
    public static final double DEFAULT_CRUISE_SPEED = 0.7;

    // --- leg 実測 accessor (RailwayManagement.measureTrainTravelTimes / estimateArrival 用) ---
    public static Long lookupLegTicks(String legKey) { return measuredLegTicks.get(legKey); }
    public static void recordLegTicks(String legKey, long ticks) { measuredLegTicks.put(legKey, ticks); }
    public static Double lookupLegDistance(String legKey) { return measuredLegDistance.get(legKey); }
    public static void recordLegDistance(String legKey, double dist) { measuredLegDistance.put(legKey, dist); }
    public static Long lookupLegTicksByTrain(String trainKey) { return measuredLegTicksByTrain.get(trainKey); }
    public static void recordLegTicksByTrain(String trainKey, long ticks) { measuredLegTicksByTrain.put(trainKey, ticks); }
    public static Double lookupLegDistanceByTrain(String trainKey) { return measuredLegDistanceByTrain.get(trainKey); }
    public static void recordLegDistanceByTrain(String trainKey, double dist) { measuredLegDistanceByTrain.put(trainKey, dist); }

    /** leg 実測 (集計 + 列車別) を全消去 (lifecycle purge 用)。 */
    public static void clearLegData() {
        measuredLegTicks.clear();
        measuredLegDistance.clear();
        measuredLegTicksByTrain.clear();
        measuredLegDistanceByTrain.clear();
    }

    /**
     * 区間 (fromStation → toStation) の集計巡航速度 (block/tick) の実測値。
     * 計測が無ければ {@link #DEFAULT_CRUISE_SPEED}。
     */
    public static double getCruiseSpeedStatic(String fromStation, String toStation) {
        if (fromStation == null || toStation == null) return DEFAULT_CRUISE_SPEED;
        String legKey = fromStation + "→" + toStation;
        Double dist = measuredLegDistance.get(legKey);
        Long ticks = measuredLegTicks.get(legKey);
        if (dist == null || ticks == null) {
            String reverseKey = toStation + "→" + fromStation;
            if (dist == null) dist = measuredLegDistance.get(reverseKey);
            if (ticks == null) ticks = measuredLegTicks.get(reverseKey);
        }
        return computeCruiseSpeed(dist, ticks, DEFAULT_CRUISE_SPEED);
    }

    /**
     * 列車別 → 集計 → Create 物理 maxSpeed → 既定 の順で巡航速度を返す。
     */
    public static double getCruiseSpeedStaticForTrain(UUID trainId, String fromStation, String toStation) {
        if (trainId != null && fromStation != null && toStation != null) {
            String key = trainLegKey(trainId, fromStation, toStation);
            Double dist = measuredLegDistanceByTrain.get(key);
            Long ticks = measuredLegTicksByTrain.get(key);
            if (dist == null || ticks == null) {
                String revKey = trainLegKey(trainId, toStation, fromStation);
                if (dist == null) dist = measuredLegDistanceByTrain.get(revKey);
                if (ticks == null) ticks = measuredLegTicksByTrain.get(revKey);
            }
            if (dist != null && ticks != null && ticks > 0) {
                double cruise = dist / ticks;
                if (cruise > MIN_CRUISE && cruise < MAX_CRUISE) return cruise;
            }
        }
        double aggregateCruise = getCruiseSpeedStatic(fromStation, toStation);
        if (aggregateCruise != DEFAULT_CRUISE_SPEED) return aggregateCruise;
        // Create の物理 maxSpeed をフォールバック
        if (trainId != null) {
            try {
                var train = com.simibubi.create.Create.RAILWAYS.trains.get(trainId);
                if (train != null) {
                    double max = train.maxSpeed();
                    if (max > MIN_CRUISE && max < MAX_CRUISE) return max;
                }
            } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Eta] Create maxSpeed read failed", e); }
        }
        return DEFAULT_CRUISE_SPEED;
    }
}
