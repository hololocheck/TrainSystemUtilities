package com.trainsystemutilities.route;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 駅パターンから路線種別 (環状 / 折り返し) を判定する純ロジック。
 *
 * <p>B4 継続 (R2): {@code MonitorMovementBehaviour} から切り出し、unit test 可能化した。
 * Minecraft 非依存。返り値は stable code ({@code "CIRCULAR"} / {@code "SHUTTLE"} / {@code ""})、
 * 表示文字列への変換は呼び出し側 (i18n) が行う。
 *
 * <p>v2 統一: monitor (MonitorMovementBehaviour) と railway 管理
 * (TrainScheduleReader.analyzeRouteType) の両方がこの 1 実装を共有する (= 表示一致)。
 * 旧 monitor 専用ヒューリスティック (= 2 駅を CIRCULAR と判定) は折り返し優先に統一済。
 */
public final class RouteClassifier {

    private RouteClassifier() {}

    /**
     * 駅名の順序リストから路線種別を分類する。
     * <ul>
     *   <li>{@code "SHUTTLE"} (折り返し) = 2 unique 駅、または A→…→A の reverse-palindrome (往復)</li>
     *   <li>{@code "CIRCULAR"} (環状) = 3 駅以上で全 unique、または first==last の明示ループ</li>
     *   <li>{@code ""} = 判定不能 (&lt; 2 駅 / null)</li>
     * </ul>
     */
    public static String classify(List<String> scheduleStations) {
        if (scheduleStations == null || scheduleStations.size() < 2) return "";
        Set<String> unique = new LinkedHashSet<>(scheduleStations);
        if (unique.size() == 2) return "SHUTTLE";
        int half = scheduleStations.size() / 2;
        boolean isRoundTrip = true;
        for (int i = 0; i < half && i < scheduleStations.size() - 1 - i; i++) {
            if (!scheduleStations.get(i).equals(scheduleStations.get(scheduleStations.size() - 1 - i))) {
                isRoundTrip = false;
                break;
            }
        }
        if (isRoundTrip) return "SHUTTLE";
        if (unique.size() >= 3 && unique.size() == scheduleStations.size()) return "CIRCULAR";
        if (scheduleStations.get(0).equals(scheduleStations.get(scheduleStations.size() - 1))) return "CIRCULAR";
        return "";
    }

    /** 時刻表からの判定結果 (種別コード + 折り返し駅集合)。 */
    public record Result(String routeType, Set<String> turnbackStations) {
        public static final Result NONE = new Result("", Set.of());
    }

    /**
     * v3 hybrid 用: 時刻表 (停車駅の cyclic 列) から種別と折り返し駅を判定する純ロジック。
     *
     * <p>{@link #classify} と違い、 時刻表だけで確実に分かるものだけを返し、 曖昧なケースは
     * {@code ""} (= 呼び出し側で実挙動 tracker に委ねる) にする:
     * <ul>
     *   <li>ある駅が 2 回以上出る (= retrace, 往復) → {@code SHUTTLE}。 1 回だけ出る駅 = 線の端
     *       = 折り返し駅 (A-B-C-D-C-B なら A / D)。</li>
     *   <li>明示的に始点へ戻る (first==last、 中間は全 unique) → {@code CIRCULAR}。</li>
     *   <li>2 駅だけ / 全 unique で開いた列 (A-B-C-D) → {@code ""} (環状/折り返しが時刻表では
     *       区別不能。 実挙動で確定させる)。</li>
     * </ul>
     */
    public static Result classifyDetailed(List<String> stations) {
        if (stations == null || stations.size() < 2) return Result.NONE;
        boolean closedLoop = stations.size() > 2
                && stations.get(0).equals(stations.get(stations.size() - 1));
        // cyclic 表記で末尾に始点が重複しているなら 1 つ落として実駅列にする
        List<String> work = closedLoop ? stations.subList(0, stations.size() - 1) : stations;

        Map<String, Integer> occ = new HashMap<>();
        for (String s : work) if (s != null && !s.isEmpty()) occ.merge(s, 1, Integer::sum);
        if (occ.size() < 2) return Result.NONE;

        boolean anyRepeated = occ.values().stream().anyMatch(c -> c >= 2);
        if (anyRepeated) {
            // 往復 (retrace) → 折り返し。 端 (= 1 回だけ出る駅) が折り返し駅
            Set<String> ends = new LinkedHashSet<>();
            for (var e : occ.entrySet()) if (e.getValue() == 1) ends.add(e.getKey());
            if (ends.isEmpty()) { ends.add(work.get(0)); ends.add(work.get(work.size() - 1)); }
            return new Result("SHUTTLE", ends);
        }
        // 以下、 全 unique
        if (closedLoop) return new Result("CIRCULAR", Set.of()); // A-B-C-D-A = 環状
        // 開いた全 unique 列 (A-B-C-D / A-B) は 環状 or 折り返しが時刻表では不明 → 実挙動へ委譲
        return Result.NONE;
    }
}
