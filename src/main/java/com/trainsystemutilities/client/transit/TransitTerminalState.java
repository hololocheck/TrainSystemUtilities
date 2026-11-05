package com.trainsystemutilities.client.transit;

import com.manta.api.controller.TabController;
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
 *
 * <p>The state is one {@link Data}: the player's terminal's, which every static accessor reads - except on a thread a wiki
 * stand-in has installed its own on ({@link #underWiki}), for the length of one call into it. The wiki's page plays the
 * terminal (改善1, 2026-10-07: a search typed, a route shown, the timetable and the map operated), and its presses must
 * not reach the player's own terminal - its queries, results, tab, history, map view or settings. A thread-local rather
 * than a swap of the fields: a network handler that writes the player's results on its own thread in the middle of the
 * wiki's call still writes the player's.
 */
@OnlyIn(Dist.CLIENT)
public final class TransitTerminalState {

    public enum Tab { TOP, SCHEDULE, MAP, SETTINGS }

    // --- 検索履歴 (最近検索した駅ペア) ---
    public record SearchHistoryEntry(UUID fromGroupId, String fromName, UUID toGroupId, String toName, long timestamp) {}
    private static final int HISTORY_MAX = 8;
    private static final int FAVORITES_MAX = 12;
    /** SCHEDULE list の可視行数 (= TransitTerminalScreen の canvas h=256 / row stride 24)。 */
    private static final int SCHEDULE_VISIBLE_ROWS = 256 / 24;

    /**
     * One terminal's state. The player's lives as long as the client; a wiki stand-in's is its screen's, with the station
     * groups it searches among and the router that answers its searches (the player's: the synced groups and the server).
     */
    public static final class Data {
        private final TabController<Tab> tabs = new TabController<>(
                java.util.List.of(Tab.TOP, Tab.SCHEDULE, Tab.MAP, Tab.SETTINGS), Tab.TOP);

        // --- TOP タブ: 検索 ---
        private final StringBuilder fromQuery = new StringBuilder();
        private final StringBuilder toQuery = new StringBuilder();
        private UUID fromGroupId = null;
        private UUID toGroupId = null;
        private volatile ComposedRouteFinder.ComposedRoute lastResult = null;
        /** Phase D: 上位 K 候補のリスト (index 0 = primary)。 */
        private volatile java.util.List<ComposedRouteFinder.ComposedRoute> lastResults = java.util.List.of();
        /** 表示中の候補 index。 */
        private int selectedRouteIdx = 0;
        private long lastResultRequestNanos = 0;
        private long lastResultBaseDayTime = 0;
        private int expandedLegIdx = -1;

        // --- SCHEDULE タブ ---
        private final StringBuilder scheduleQuery = new StringBuilder();
        /** §4.19 標準 ScrollViewport (row-based)。 保持は Data の寿命 (= 画面再オープンで保持)。 */
        private final com.manta.api.controller.ScrollViewport scheduleScroll =
                new com.manta.api.controller.ScrollViewport(
                        TransitTerminalState::scheduleFilteredRowCount, SCHEDULE_VISIBLE_ROWS);

        // --- MAP タブ ---
        // 管理用コンピューターと同じ vector 方式: zoom * (worldCoord + pan) → screen offset。
        // 0 は「未初期化、最初の render で auto-fit する」マーカー。
        private double mapZoomD = 0.0;
        private double mapPanXD = 0.0;
        private double mapPanZD = 0.0;

        // --- SETTINGS タブ ---
        /** 24h ↔ 12h の表示切替。 */
        private boolean clock24h = true;
        /** 結果に徒歩到達ゲートを適用するか。 */
        private boolean walkGateEnabled = true;
        /** 検索ソート: 0=早 (時間最短) / 1=楽 (乗換最少) / 2=安 (運賃最安、現状未実装で時間扱い)。 */
        private int sortMode = 0;

        // --- レイアウト調整 (Phase 23) ---
        /** ON のときは Screen / HUD のドラッグで位置調整できる。 */
        private boolean layoutAdjustMode = false;
        /** Screen 位置オフセット (右下基準、デフォルト 0)。 */
        private int screenOffsetX = 0;
        private int screenOffsetY = 0;
        /** Detail HUD の位置オフセット (左下基準、デフォルト 0)。 */
        private int hudOffsetX = 0;
        private int hudOffsetY = 0;
        /** Detail HUD を表示するか。 */
        private boolean showDetailHud = false;
        /** HUD 表示する route index (selectedRouteIdx と独立)。 */
        private int hudRouteIdx = 0;

        private final java.util.LinkedList<SearchHistoryEntry> history = new java.util.LinkedList<>();
        /** お気に入り駅 (UUID set)。 */
        private final java.util.LinkedHashSet<UUID> favorites = new java.util.LinkedHashSet<>();

        /** The stand-in's station groups and router; null for the player's terminal. */
        private final List<StationGroup> groups;
        private final java.util.function.BiFunction<UUID, UUID, List<ComposedRouteFinder.ComposedRoute>> router;

        /** The player's terminal. */
        private Data() {
            this(null, null);
        }

        /** A wiki stand-in's terminal: it searches among {@code groups}, and {@code router} answers its searches. */
        public Data(List<StationGroup> groups,
                    java.util.function.BiFunction<UUID, UUID, List<ComposedRouteFinder.ComposedRoute>> router) {
            this.groups = groups == null ? null : List.copyOf(groups);
            this.router = router;
        }
    }

    private static final Data PLAYER = new Data();
    private static final ThreadLocal<Data> WIKI = new ThreadLocal<>();

    private TransitTerminalState() {}

    /** The state every accessor reads: the stand-in's installed on this thread, else the player's. */
    private static Data d() {
        Data w = WIKI.get();
        return w != null ? w : PLAYER;
    }

    /** Runs {@code body} with {@code data} as this thread's terminal, the one before it back afterwards. */
    public static <T> T underWiki(Data data, java.util.function.Supplier<T> body) {
        Data outer = WIKI.get();
        WIKI.set(data);
        try {
            return body.get();
        } finally {
            if (outer == null) WIKI.remove();
            else WIKI.set(outer);
        }
    }

    /** Whether this thread is in a wiki stand-in's call: nothing it does may reach the server or the player's navigation. */
    public static boolean wiki() {
        return WIKI.get() != null;
    }

    // --- accessors ---
    public static Tab tab() { return d().tabs.current(); }
    public static void setTab(Tab t) { d().tabs.setCurrent(t); }

    public static String fromQuery() { return d().fromQuery.toString(); }
    public static String toQuery() { return d().toQuery.toString(); }
    public static UUID fromGroupId() { return d().fromGroupId; }
    public static UUID toGroupId() { return d().toGroupId; }
    public static ComposedRouteFinder.ComposedRoute lastResult() { return d().lastResult; }
    public static int expandedLegIdx() { return d().expandedLegIdx; }
    public static void setExpandedLegIdx(int idx) { d().expandedLegIdx = idx; }

    public static boolean clock24h() { return d().clock24h; }
    public static void setClock24h(boolean v) { d().clock24h = v; }
    public static boolean walkGateEnabled() { return d().walkGateEnabled; }
    public static void setWalkGateEnabled(boolean v) { d().walkGateEnabled = v; }
    public static int sortMode() { return d().sortMode; }
    public static void setSortMode(int s) { d().sortMode = ((s % 3) + 3) % 3; }

    public static boolean layoutAdjustMode() { return d().layoutAdjustMode; }
    public static void setLayoutAdjustMode(boolean v) { d().layoutAdjustMode = v; }
    public static int screenOffsetX() { return d().screenOffsetX; }
    public static int screenOffsetY() { return d().screenOffsetY; }
    public static void setScreenOffset(int x, int y) { Data s = d(); s.screenOffsetX = x; s.screenOffsetY = y; }
    public static int hudOffsetX() { return d().hudOffsetX; }
    public static int hudOffsetY() { return d().hudOffsetY; }
    public static void setHudOffset(int x, int y) { Data s = d(); s.hudOffsetX = x; s.hudOffsetY = y; }
    public static boolean showDetailHud() { return d().showDetailHud; }
    public static void setShowDetailHud(boolean v) { d().showDetailHud = v; }
    public static int hudRouteIdx() { return d().hudRouteIdx; }
    public static void setHudRouteIdx(int i) { d().hudRouteIdx = i; }

    public static java.util.List<SearchHistoryEntry> history() { return new java.util.ArrayList<>(d().history); }
    public static void addHistory(SearchHistoryEntry e) {
        if (e == null || e.fromGroupId == null || e.toGroupId == null) return;
        var history = d().history;
        history.removeIf(h -> h.fromGroupId.equals(e.fromGroupId) && h.toGroupId.equals(e.toGroupId));
        history.addFirst(e);
        while (history.size() > HISTORY_MAX) history.removeLast();
    }
    public static void clearHistory() { d().history.clear(); }
    public static void removeHistoryAt(int index) {
        var history = d().history;
        if (index >= 0 && index < history.size()) history.remove(index);
    }

    public static java.util.Set<UUID> favoriteIds() { return new java.util.LinkedHashSet<>(d().favorites); }
    public static boolean isFavorite(UUID id) { return id != null && d().favorites.contains(id); }
    public static void toggleFavorite(UUID id) {
        if (id == null) return;
        var favorites = d().favorites;
        if (!favorites.remove(id)) {
            if (favorites.size() >= FAVORITES_MAX) return;
            favorites.add(id);
        }
    }

    public static String scheduleQuery() { return d().scheduleQuery.toString(); }
    public static com.manta.api.controller.ScrollViewport scheduleScroll() { return d().scheduleScroll; }
    public static int scheduleScrollY() { return d().scheduleScroll.offset(); }
    public static void setScheduleScrollY(int v) { d().scheduleScroll.setOffset(v); }

    /** SCHEDULE tab で表示中の (フィルタ後) snapshot 件数 (= scheduleScroll の total)。 */
    public static int scheduleFilteredRowCount() {
        var snapshots = TransitTerminalClientCache.allSchedules();
        if (snapshots.isEmpty()) return 0;
        String key = d().scheduleQuery.toString().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return snapshots.size();
        int n = 0;
        for (var snap : snapshots.values()) {
            if (snap.trainName().toLowerCase(Locale.ROOT).contains(key)) n++;
        }
        return n;
    }

    public static double mapZoomD() { return d().mapZoomD; }
    public static double mapPanXD() { return d().mapPanXD; }
    public static double mapPanZD() { return d().mapPanZD; }
    public static void setMapZoomD(double z) { d().mapZoomD = Math.max(0.05, Math.min(8.0, z)); }
    public static void setMapPan(double px, double pz) { Data s = d(); s.mapPanXD = px; s.mapPanZD = pz; }
    public static void mapPanBy(double dx, double dz) { Data s = d(); s.mapPanXD += dx; s.mapPanZD += dz; }
    public static void mapResetView() { Data s = d(); s.mapZoomD = 0.0; s.mapPanXD = 0.0; s.mapPanZD = 0.0; }

    // --- text input mutators (called from Screen text fields) ---
    public static void setFromQuery(String s) {
        Data st = d();
        st.fromQuery.setLength(0); st.fromQuery.append(s); st.fromGroupId = resolveExact(s);
    }
    public static void setToQuery(String s) {
        Data st = d();
        st.toQuery.setLength(0); st.toQuery.append(s); st.toGroupId = resolveExact(s);
    }
    public static void setScheduleQuery(String s) { Data st = d(); st.scheduleQuery.setLength(0); st.scheduleQuery.append(s); }

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
        Data s = d();
        s.lastResults = routes;
        s.lastResult = routes.isEmpty() ? null : routes.get(0);
        s.selectedRouteIdx = 0;
        s.expandedLegIdx = -1;
        s.lastResultBaseDayTime = serverDayTime;
    }
    public static java.util.List<ComposedRouteFinder.ComposedRoute> lastResults() { return d().lastResults; }
    public static int selectedRouteIdx() { return d().selectedRouteIdx; }
    /** 検索結果をクリア (履歴画面に戻る)。 */
    public static void clearResults() {
        Data s = d();
        s.lastResult = null;
        s.lastResults = java.util.List.of();
        s.selectedRouteIdx = 0;
        s.expandedLegIdx = -1;
        s.lastResultRequestNanos = 0;
    }
    public static void setSelectedRouteIdx(int i) {
        Data s = d();
        if (i < 0 || i >= s.lastResults.size()) return;
        s.selectedRouteIdx = i;
        s.lastResult = s.lastResults.get(i);
        s.expandedLegIdx = -1;
    }
    public static long lastResultBaseDayTime() { return d().lastResultBaseDayTime; }

    /** The search: sent to the server - or, on a wiki stand-in, answered by its own router at once. */
    public static void onSearchSubmit() {
        Data s = d();
        if (s.fromGroupId == null) s.fromGroupId = resolveBest(s.fromQuery.toString());
        if (s.toGroupId == null) s.toGroupId = resolveBest(s.toQuery.toString());
        if (s.fromGroupId == null || s.toGroupId == null) return;
        s.lastResult = null;
        s.expandedLegIdx = -1;
        s.lastResultRequestNanos = System.nanoTime();
        addHistory(new SearchHistoryEntry(s.fromGroupId, s.fromQuery.toString(),
                s.toGroupId, s.toQuery.toString(), System.currentTimeMillis()));
        if (s.router != null) {
            var mc = Minecraft.getInstance();
            setLastResults(s.router.apply(s.fromGroupId, s.toGroupId),
                    mc != null && mc.level != null ? mc.level.getDayTime() : 0);
            return;
        }
        com.trainsystemutilities.client.TransitClient.search(s.fromGroupId, s.toGroupId);
    }

    /** 出発駅と到着駅を入れ替える。 */
    public static void swapFromTo() {
        Data s = d();
        String q = s.fromQuery.toString();
        UUID id = s.fromGroupId;
        s.fromQuery.setLength(0); s.fromQuery.append(s.toQuery);
        s.fromGroupId = s.toGroupId;
        s.toQuery.setLength(0); s.toQuery.append(q);
        s.toGroupId = id;
    }

    public static long sinceLastResultRequestMs() {
        long at = d().lastResultRequestNanos;
        if (at == 0) return 0;
        return (System.nanoTime() - at) / 1_000_000L;
    }

    /** The station groups this terminal searches among: the stand-in's own, else the synced ones. */
    public static List<StationGroup> groups() {
        List<StationGroup> own = d().groups;
        return own != null ? own : StationGroupClientCache.all();
    }

    /** 完全一致 (case-insensitive) で 1 件あれば UUID を返す。 */
    private static UUID resolveExact(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        for (StationGroup g : groups()) {
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
        for (StationGroup g : groups()) {
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
        for (var g : groups()) {
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
        Data s = d();
        s.tabs.setCurrent(Tab.TOP);
        s.fromQuery.setLength(0); s.toQuery.setLength(0);
        s.fromGroupId = null; s.toGroupId = null;
        s.lastResult = null;
        s.expandedLegIdx = -1;
        s.scheduleQuery.setLength(0);
        s.scheduleScroll.setOffset(0);
        mapResetView();
    }
}
