package com.trainsystemutilities.client.transit;

import belugalab.experience.controller.TabController;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 乗り換え案内端末 (Phase 19: スマホ風 4 タブ Screen) の client-side state。
 *
 * <p>Phase 19 で旧 HUD-only state machine を Screen ベースに刷新:
 * <ul>
 *   <li>Tab: TOP (検索 + 結果) / SCHEDULE (時刻表) / MAP (路線マップ) / SETTINGS</li>
 *   <li>各タブ独立の state を保持 (タブ切替で消えない)</li>
 *   <li>focused field により「検索ボックスを選択しているとき」だけ typing が反応 (Screen
 *       のフォーカス制御に委ねる)。旧 inputMode T-toggle は廃止。</li>
 * </ul>
 *
 * <p>過去の {@code Screen.SELECT_FROM/TO/RESULTS} 三段モデルは廃止。新 UI では from/to を
 * 同時に持って search ボタンで送信する形になり、結果タイル開閉で詳細表示を切り替える。
 */
@OnlyIn(Dist.CLIENT)
public final class TransitTerminalState {

    public enum Tab { TOP, SCHEDULE, MAP, SETTINGS }

    private static final TabController<Tab> TABS = new TabController<>(
            java.util.List.of(Tab.TOP, Tab.SCHEDULE, Tab.MAP, Tab.SETTINGS), Tab.TOP);

    // --- TOP タブ: 検索 ---
    private static final StringBuilder fromQuery = new StringBuilder();
    private static final StringBuilder toQuery = new StringBuilder();
    private static UUID fromGroupId = null;
    private static UUID toGroupId = null;
    private static volatile ComposedRouteFinder.ComposedRoute lastResult = null;
    /** Phase D: 上位 K 候補のリスト (index 0 = primary)。 */
    private static volatile java.util.List<ComposedRouteFinder.ComposedRoute> lastResults = java.util.List.of();
    /** 表示中の候補 index。 */
    private static int selectedRouteIdx = 0;
    private static long lastResultRequestNanos = 0;
    private static long lastResultBaseDayTime = 0;
    private static int expandedLegIdx = -1;

    // --- SCHEDULE タブ ---
    private static final StringBuilder scheduleQuery = new StringBuilder();
    /** SCHEDULE list の可視行数 (= TransitTerminalScreen の canvas h=256 / row stride 24)。 */
    private static final int SCHEDULE_VISIBLE_ROWS = 256 / 24;
    /** §4.19 標準 ScrollViewport (row-based)。static field のまま (= 画面再オープンで保持)。 */
    private static final belugalab.experience.controller.ScrollViewport scheduleScroll =
            new belugalab.experience.controller.ScrollViewport(
                    TransitTerminalState::scheduleFilteredRowCount, SCHEDULE_VISIBLE_ROWS);

    // --- MAP タブ ---
    // 管理用コンピューターと同じ vector 方式: zoom * (worldCoord + pan) → screen offset。
    // 0 は「未初期化、最初の render で auto-fit する」マーカー。
    private static double mapZoomD = 0.0;
    private static double mapPanXD = 0.0;
    private static double mapPanZD = 0.0;

    // --- SETTINGS タブ ---
    /** 24h ↔ 12h の表示切替。 */
    private static boolean clock24h = true;
    /** 結果に徒歩到達ゲートを適用するか。 */
    private static boolean walkGateEnabled = true;
    /** 検索ソート: 0=早 (時間最短) / 1=楽 (乗換最少) / 2=安 (運賃最安、現状未実装で時間扱い)。 */
    private static int sortMode = 0;

    // --- レイアウト調整 (Phase 23) ---
    /** ON のときは Screen / HUD のドラッグで位置調整できる。 */
    private static boolean layoutAdjustMode = false;
    /** Screen 位置オフセット (右下基準、デフォルト 0)。 */
    private static int screenOffsetX = 0;
    private static int screenOffsetY = 0;
    /** Detail HUD の位置オフセット (左下基準、デフォルト 0)。 */
    private static int hudOffsetX = 0;
    private static int hudOffsetY = 0;
    /** Detail HUD を表示するか。 */
    private static boolean showDetailHud = false;
    /** HUD 表示する route index (selectedRouteIdx と独立)。 */
    private static int hudRouteIdx = 0;

    // --- 検索履歴 (最近検索した駅ペア) ---
    public record SearchHistoryEntry(UUID fromGroupId, String fromName, UUID toGroupId, String toName, long timestamp) {}
    private static final java.util.LinkedList<SearchHistoryEntry> history = new java.util.LinkedList<>();
    private static final int HISTORY_MAX = 8;

    /** お気に入り駅 (UUID set)。 */
    private static final java.util.LinkedHashSet<UUID> favorites = new java.util.LinkedHashSet<>();
    private static final int FAVORITES_MAX = 12;

    private TransitTerminalState() {}

    // --- accessors ---
    public static Tab tab() { return TABS.current(); }
    public static void setTab(Tab t) { TABS.setCurrent(t); }

    public static String fromQuery() { return fromQuery.toString(); }
    public static String toQuery() { return toQuery.toString(); }
    public static UUID fromGroupId() { return fromGroupId; }
    public static UUID toGroupId() { return toGroupId; }
    public static ComposedRouteFinder.ComposedRoute lastResult() { return lastResult; }
    public static int expandedLegIdx() { return expandedLegIdx; }
    public static void setExpandedLegIdx(int idx) { expandedLegIdx = idx; }

    public static boolean clock24h() { return clock24h; }
    public static void setClock24h(boolean v) { clock24h = v; }
    public static boolean walkGateEnabled() { return walkGateEnabled; }
    public static void setWalkGateEnabled(boolean v) { walkGateEnabled = v; }
    public static int sortMode() { return sortMode; }
    public static void setSortMode(int s) { sortMode = ((s % 3) + 3) % 3; }

    public static boolean layoutAdjustMode() { return layoutAdjustMode; }
    public static void setLayoutAdjustMode(boolean v) { layoutAdjustMode = v; }
    public static int screenOffsetX() { return screenOffsetX; }
    public static int screenOffsetY() { return screenOffsetY; }
    public static void setScreenOffset(int x, int y) { screenOffsetX = x; screenOffsetY = y; }
    public static int hudOffsetX() { return hudOffsetX; }
    public static int hudOffsetY() { return hudOffsetY; }
    public static void setHudOffset(int x, int y) { hudOffsetX = x; hudOffsetY = y; }
    public static boolean showDetailHud() { return showDetailHud; }
    public static void setShowDetailHud(boolean v) { showDetailHud = v; }
    public static int hudRouteIdx() { return hudRouteIdx; }
    public static void setHudRouteIdx(int i) { hudRouteIdx = i; }

    public static java.util.List<SearchHistoryEntry> history() { return new java.util.ArrayList<>(history); }
    public static void addHistory(SearchHistoryEntry e) {
        if (e == null || e.fromGroupId == null || e.toGroupId == null) return;
        history.removeIf(h -> h.fromGroupId.equals(e.fromGroupId) && h.toGroupId.equals(e.toGroupId));
        history.addFirst(e);
        while (history.size() > HISTORY_MAX) history.removeLast();
    }
    public static void clearHistory() { history.clear(); }
    public static void removeHistoryAt(int index) {
        if (index >= 0 && index < history.size()) history.remove(index);
    }

    public static java.util.Set<UUID> favoriteIds() { return new java.util.LinkedHashSet<>(favorites); }
    public static boolean isFavorite(UUID id) { return id != null && favorites.contains(id); }
    public static void toggleFavorite(UUID id) {
        if (id == null) return;
        if (!favorites.remove(id)) {
            if (favorites.size() >= FAVORITES_MAX) return;
            favorites.add(id);
        }
    }

    public static String scheduleQuery() { return scheduleQuery.toString(); }
    public static belugalab.experience.controller.ScrollViewport scheduleScroll() { return scheduleScroll; }
    public static int scheduleScrollY() { return scheduleScroll.offset(); }
    public static void setScheduleScrollY(int v) { scheduleScroll.setOffset(v); }

    /** SCHEDULE tab で表示中の (フィルタ後) snapshot 件数 (= scheduleScroll の total)。 */
    public static int scheduleFilteredRowCount() {
        var snapshots = TransitTerminalClientCache.allSchedules();
        if (snapshots.isEmpty()) return 0;
        String key = scheduleQuery.toString().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return snapshots.size();
        int n = 0;
        for (var snap : snapshots.values()) {
            if (snap.trainName().toLowerCase(Locale.ROOT).contains(key)) n++;
        }
        return n;
    }

    public static double mapZoomD() { return mapZoomD; }
    public static double mapPanXD() { return mapPanXD; }
    public static double mapPanZD() { return mapPanZD; }
    public static void setMapZoomD(double z) { mapZoomD = Math.max(0.05, Math.min(8.0, z)); }
    public static void setMapPan(double px, double pz) { mapPanXD = px; mapPanZD = pz; }
    public static void mapPanBy(double dx, double dz) { mapPanXD += dx; mapPanZD += dz; }
    public static void mapResetView() { mapZoomD = 0.0; mapPanXD = 0.0; mapPanZD = 0.0; }

    // --- text input mutators (called from Screen text fields) ---
    public static void setFromQuery(String s) { fromQuery.setLength(0); fromQuery.append(s); fromGroupId = resolveExact(s); }
    public static void setToQuery(String s) { toQuery.setLength(0); toQuery.append(s); toGroupId = resolveExact(s); }
    public static void setScheduleQuery(String s) { scheduleQuery.setLength(0); scheduleQuery.append(s); }

    public static void setLastResult(ComposedRouteFinder.ComposedRoute r) {
        long fallback = 0;
        try {
            var mc = Minecraft.getInstance();
            if (mc.level != null) fallback = mc.level.getDayTime();
        } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TransitTerminal] dayTime read failed", ignored); }
        setLastResult(r, fallback);
    }
    public static void setLastResult(ComposedRouteFinder.ComposedRoute r, long serverDayTime) {
        setLastResults(java.util.List.of(r), serverDayTime);
    }
    /** Phase D: 上位 K 候補 + サーバ時刻で結果を設定。 */
    public static void setLastResults(java.util.List<ComposedRouteFinder.ComposedRoute> routes, long serverDayTime) {
        lastResults = routes;
        lastResult = routes.isEmpty() ? null : routes.get(0);
        selectedRouteIdx = 0;
        expandedLegIdx = -1;
        lastResultBaseDayTime = serverDayTime;
    }
    public static java.util.List<ComposedRouteFinder.ComposedRoute> lastResults() { return lastResults; }
    public static int selectedRouteIdx() { return selectedRouteIdx; }
    /** 検索結果をクリア (履歴画面に戻る)。 */
    public static void clearResults() {
        lastResult = null;
        lastResults = java.util.List.of();
        selectedRouteIdx = 0;
        expandedLegIdx = -1;
        lastResultRequestNanos = 0;
    }
    public static void setSelectedRouteIdx(int i) {
        if (i < 0 || i >= lastResults.size()) return;
        selectedRouteIdx = i;
        lastResult = lastResults.get(i);
        expandedLegIdx = -1;
    }
    public static long lastResultBaseDayTime() { return lastResultBaseDayTime; }

    public static void onSearchSubmit() {
        if (fromGroupId == null) fromGroupId = resolveBest(fromQuery.toString());
        if (toGroupId == null) toGroupId = resolveBest(toQuery.toString());
        if (fromGroupId == null || toGroupId == null) return;
        lastResult = null;
        expandedLegIdx = -1;
        lastResultRequestNanos = System.nanoTime();
        addHistory(new SearchHistoryEntry(fromGroupId, fromQuery.toString(),
                toGroupId, toQuery.toString(), System.currentTimeMillis()));
        com.trainsystemutilities.network.TransitSearchPayload.send(fromGroupId, toGroupId);
    }

    /** 出発駅と到着駅を入れ替える。 */
    public static void swapFromTo() {
        String q = fromQuery.toString();
        UUID id = fromGroupId;
        fromQuery.setLength(0); fromQuery.append(toQuery);
        fromGroupId = toGroupId;
        toQuery.setLength(0); toQuery.append(q);
        toGroupId = id;
    }

    public static long sinceLastResultRequestMs() {
        if (lastResultRequestNanos == 0) return 0;
        return (System.nanoTime() - lastResultRequestNanos) / 1_000_000L;
    }

    /** 完全一致 (case-insensitive) で 1 件あれば UUID を返す。 */
    private static UUID resolveExact(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        for (StationGroup g : StationGroupClientCache.all()) {
            if (g.name().toLowerCase(Locale.ROOT).equals(key)) return g.id();
        }
        return null;
    }

    /** prefix 一致を優先、部分一致を fallback。 */
    private static UUID resolveBest(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        UUID prefixHit = null;
        UUID containsHit = null;
        for (StationGroup g : StationGroupClientCache.all()) {
            String n = g.name().toLowerCase(Locale.ROOT);
            if (n.equals(key)) return g.id();
            if (prefixHit == null && n.startsWith(key)) prefixHit = g.id();
            if (containsHit == null && n.contains(key)) containsHit = g.id();
        }
        return prefixHit != null ? prefixHit : containsHit;
    }

    /** プレイヤー位置からの距離順で全駅グループを返す (current dim)。 */
    public static List<StationGroup> sortedGroups() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return List.of();
        String dim = mc.player.level().dimension().location().toString();
        var pos = mc.player.blockPosition();
        List<StationGroup> list = new ArrayList<>();
        for (var g : StationGroupClientCache.all()) {
            if (g.dimensionId().equals(dim)) list.add(g);
        }
        list.sort(Comparator.comparingDouble(g -> {
            double cx = (g.minPos().getX() + g.maxPos().getX()) / 2.0;
            double cz = (g.minPos().getZ() + g.maxPos().getZ()) / 2.0;
            double dx = cx - pos.getX(), dz = cz - pos.getZ();
            return dx * dx + dz * dz;
        }));
        return list;
    }

    /** 検索クエリでフィルタした駅候補 (autocomplete 用)。 */
    public static List<StationGroup> autocomplete(String query, int maxItems) {
        var all = sortedGroups();
        if (query == null || query.isBlank()) return all.subList(0, Math.min(maxItems, all.size()));
        String key = query.toLowerCase(Locale.ROOT);
        List<StationGroup> out = new ArrayList<>();
        for (var g : all) {
            if (g.name().toLowerCase(Locale.ROOT).contains(key)) {
                out.add(g);
                if (out.size() >= maxItems) break;
            }
        }
        return out;
    }

    /** リセット (アイテムを離したとき等)。 */
    public static void resetAll() {
        TABS.setCurrent(Tab.TOP);
        fromQuery.setLength(0); toQuery.setLength(0);
        fromGroupId = null; toGroupId = null;
        lastResult = null;
        expandedLegIdx = -1;
        scheduleQuery.setLength(0);
        scheduleScroll.setOffset(0);
        mapResetView();
    }
}
