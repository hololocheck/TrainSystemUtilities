package com.trainsystemutilities.schedule;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 列車スケジュール (Create runtime schedule) から駅名・停車時間・路線種別等を
 * 読み取る純ロジック。{@code RailwayManagementBlockEntity} から抽出した。
 *
 * <p>挙動は抽出元と一致。駅依存メソッド (停車秒数・進行方向判定) は元の
 * {@code linkedStationName} 内部フィールド参照を {@code stationName} 引数に置き換えた。
 */
public final class TrainScheduleReader {

    private TrainScheduleReader() {}

    public static boolean isHeadingToStation(Train train, String stationName) {
        try {
            if (train.runtime != null && train.runtime.getSchedule() != null) {
                var schedule = train.runtime.getSchedule();
                int current = train.runtime.currentEntry;
                // Check upcoming schedule entries for our station
                for (int i = 1; i <= schedule.entries.size(); i++) {
                    int idx = (current + i) % schedule.entries.size();
                    CompoundTag data = schedule.entries.get(idx).instruction.getData();
                    if (data.contains("Text") && stationName.equals(data.getString("Text"))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] isHeadingToStation failed", e); /* ignore */ }
        return false;
    }

    /**
     * 列車が自駅に向かって移動中かどうか判定する。
     * 隣の駅を出発して直接こちらに向かっている場合にtrue。
     */
    public static boolean isTrainApproaching(Train train, String stationName) {
        try {
            if (train.getCurrentStation() != null) return false; // まだ停車中
            if (train.runtime == null || train.runtime.getSchedule() == null) return false;
            var schedule = train.runtime.getSchedule();
            int current = train.runtime.currentEntry;
            if (current >= 0 && current < schedule.entries.size()) {
                CompoundTag data = schedule.entries.get(current).instruction.getData();
                if (data.contains("Text") && stationName.equals(data.getString("Text"))) {
                    return true;
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] isTrainApproaching failed", e); /* ignore */ }
        return false;
    }

    /** v3 hybrid: 実挙動 tracker を優先 (= 環状線 2 駅周回の誤 折り返し を解消)、
     *  未観測なら時刻表の確実な判定 (RouteClassifier.classifyDetailed) で即時予測する。
     *  時刻表でも曖昧 (2 駅 / 開いた全 unique 列) なら "" (走って反転/1 周してから確定)。 */
    public static String analyzeRouteType(Train train) {
        String obs = com.trainsystemutilities.route.TrainRouteTracker.routeType(train.id);
        if (!obs.isEmpty()) return obs;
        return com.trainsystemutilities.route.RouteClassifier
                .classifyDetailed(scheduleStations(train)).routeType();
    }

    /** 駅ボード用: SHUTTLE はその駅が実際の折り返し駅 (= 線の端) の時のみ返す。
     *  A-B-C-D 線なら A / D のボードにだけ 折り返し が出て、 途中駅 B / C には出ない。
     *  折り返し駅集合は観測 (反転を見た駅) 優先、 未観測なら時刻表の端駅。 */
    public static String routeTypeForStation(Train train, String stationName) {
        String obs = com.trainsystemutilities.route.TrainRouteTracker.routeType(train.id);
        if (!obs.isEmpty()) {
            if ("SHUTTLE".equals(obs)
                    && !com.trainsystemutilities.route.TrainRouteTracker.isTurnbackStation(train.id, stationName)) {
                return "";
            }
            return obs;
        }
        var r = com.trainsystemutilities.route.RouteClassifier.classifyDetailed(scheduleStations(train));
        if ("SHUTTLE".equals(r.routeType()) && !r.turnbackStations().contains(stationName)) {
            return "";
        }
        return r.routeType();
    }

    /** 列車スケジュールの停車駅名を順序どおり抽出。 */
    private static List<String> scheduleStations(Train train) {
        List<String> out = new ArrayList<>();
        try {
            if (train.runtime != null && train.runtime.getSchedule() != null) {
                for (var entry : train.runtime.getSchedule().entries) {
                    CompoundTag data = entry.instruction.getData();
                    if (data.contains("Text")) {
                        String s = data.getString("Text");
                        if (!s.isEmpty()) out.add(s);
                    }
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] scheduleStations failed", e); }
        return out;
    }

    /**
     * 駅名の順序リストから路線種別を分類する。v2 統一で
     * {@link com.trainsystemutilities.route.RouteClassifier} に一本化し、monitor 表示と
     * railway 管理表示が同一の判定を共有する。i18n コードは client で解決。
     */
    static String routeTypeFromStations(List<String> stationNames) {
        return com.trainsystemutilities.route.RouteClassifier.classify(stationNames);
    }

    /**
     * 同一ネットワーク上の全駅数と停車駅数の比率で列車種別を自動判定。
     * 80%以上→普通、40-80%→快速、40%未満→特急
     */
    public static String detectTrainType(Train train) {
        try {
            if (train.runtime == null || train.runtime.getSchedule() == null) return "";
            var schedule = train.runtime.getSchedule();

            // この列車の停車駅を収集
            java.util.Set<String> trainStops = new java.util.HashSet<>();
            for (var entry : schedule.entries) {
                CompoundTag data = entry.instruction.getData();
                if (data.contains("Text")) {
                    String name = data.getString("Text");
                    if (!name.isEmpty()) trainStops.add(name);
                }
            }
            if (trainStops.isEmpty()) return "";

            // 分母 = 同一グラフ上に実在する駅数 (= 線路ネットワークの駅数)。
            // 旧: 全列車スケジュールの駅の和集合 — 1 列車だけだと分母 = 自分の停車駅数になり、
            // 8 駅ネットワーク 2 駅停車でも 100% = LOCAL (普通) と誤判定していた。
            java.util.Set<String> networkStations = new java.util.HashSet<>();
            try {
                if (train.graph != null) {
                    for (var st : train.graph.getPoints(
                            com.simibubi.create.content.trains.graph.EdgePointType.STATION)) {
                        if (st.name != null && !st.name.isEmpty()) networkStations.add(st.name);
                    }
                }
            } catch (Throwable ignored) { /* Create API 変動時は fallback へ */ }
            if (networkStations.isEmpty()) {
                // fallback: 旧方式 (同一グラフ上の全列車スケジュールの和集合)
                com.simibubi.create.Create.RAILWAYS.trains.forEach((id, otherTrain) -> {
                    if (otherTrain.graph == train.graph && otherTrain.runtime != null) {
                        var sch = otherTrain.runtime.getSchedule();
                        if (sch != null) {
                            for (var entry : sch.entries) {
                                CompoundTag data = entry.instruction.getData();
                                if (data.contains("Text")) {
                                    String name = data.getString("Text");
                                    if (!name.isEmpty()) networkStations.add(name);
                                }
                            }
                        }
                    }
                });
            }

            return trainTypeFromRatio(trainStops.size(), networkStations.size());
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] detectTrainType failed", e); return ""; }
    }

    /**
     * 停車駅比率から列車種別を分類する純ロジック (Minecraft 非依存)。
     * LOCAL &gt;= 0.8、RAPID &gt;= 0.4、EXPRESS &lt; 0.4、"" = network 不明 (&lt;= 0)。
     */
    static String trainTypeFromRatio(int trainStops, int networkStations) {
        if (networkStations <= 0) return "";
        double ratio = (double) trainStops / networkStations;
        if (ratio >= 0.8) return "LOCAL";
        if (ratio >= 0.4) return "RAPID";
        return "EXPRESS";
    }

    /** Get stop seconds at OUR station (not the current destination) */
    public static int getStopSecondsAtStation(Train train, String stationName) {
        try {
            if (train.runtime != null && train.runtime.getSchedule() != null) {
                var schedule = train.runtime.getSchedule();
                for (var entry : schedule.entries) {
                    CompoundTag data = entry.instruction.getData();
                    if (data.contains("Text") && stationName.equals(data.getString("Text"))) {
                        for (var condList : entry.conditions) {
                            for (var cond : condList) {
                                CompoundTag cData = cond.getData();
                                if (cData.contains("Ticks")) return cData.getInt("Ticks") / 20;
                                if (cData.contains("Value")) return cData.getInt("Value");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] getStopSecondsAtOurStation failed", e); /* ignore */ }
        return -1;
    }

    /** Get stop seconds at the train's current schedule entry */
    public static int getScheduledStopSeconds(Train train) {
        try {
            if (train.runtime != null && train.runtime.getSchedule() != null) {
                var schedule = train.runtime.getSchedule();
                int idx = train.runtime.currentEntry;
                if (idx >= 0 && idx < schedule.entries.size()) {
                    var entry = schedule.entries.get(idx);
                    for (var conditionList : entry.conditions) {
                        for (var condition : conditionList) {
                            CompoundTag cData = condition.getData();
                            if (cData.contains("Ticks")) {
                                return cData.getInt("Ticks") / 20;
                            }
                            if (cData.contains("Value")) {
                                return cData.getInt("Value");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] getScheduledStopSeconds failed", e); /* ignore */ }
        return -1;
    }

    public static String getNextDestination(Train train) {
        try {
            if (train.runtime != null && train.runtime.getSchedule() != null) {
                var schedule = train.runtime.getSchedule();
                int currentIndex = train.runtime.currentEntry;
                // Find the next station after the current one
                for (int i = 1; i <= schedule.entries.size(); i++) {
                    int idx = (currentIndex + i) % schedule.entries.size();
                    var entry = schedule.entries.get(idx);
                    CompoundTag data = entry.instruction.getData();
                    if (data.contains("Text")) {
                        return data.getString("Text");
                    }
                }
            }
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] getNextDestination failed", e);
            // Schedule access may fail
        }
        return "";
    }
}
