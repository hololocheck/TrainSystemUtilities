package com.trainsystemutilities.client.gui;
import com.manta.api.controller.DragDropPalette;
import com.manta.api.render.HoverTilePreview;

import com.manta.api.anim.Animation;
import com.manta.api.anim.Easing;
import com.manta.api.screen.JsonLayoutHandler;
import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.controller.ColorPickerController;
import com.manta.api.controller.ColorTargetController;
import com.manta.api.controller.ScrollViewport;
import com.manta.api.controller.OverlayController;
import com.manta.api.controller.TabController;
import com.manta.api.anim.Transition;
import com.manta.api.draw.VectorRenderer;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.gui.ManagementComputerMenu;
import com.trainsystemutilities.schedule.CreateScheduleIds;
import com.trainsystemutilities.schedule.TrainTypes;
import com.trainsystemutilities.network.TrackNetworkScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * ManagementComputerScreen V2 (Phase 6-1 〜 6-6 list views).
 * 6-1: ヘッダー / タブ切替 / モニタートグル / インベントリ
 * 6-2: 路線マップタブ (pan/zoom + ノード/エッジ/駅/信号/列車描画)
 * 6-3: 列車タブ (リスト + 詳細 popup)
 * 6-4: 時刻表タブ (リスト + 詳細 + 一時停止/再開、エディタ popup は未実装)
 * 6-5: 駅タブ (リスト + 簡易詳細、ドア方向選択 popup は未実装)
 * 6-6: 路線記号タブ (グリッド表示、編集 popup は未実装)
 */
public class ManagementComputerScreenV2 extends JsonLayoutScreen<ManagementComputerMenu> {

    @Override
    protected String wikiPageId() { return "management-computer/overview"; }

    public String wikiCaptureState() {
        return tabs.current();
    }

    static final String[][] TABS = {
            {"map",      "tsu.mc.tab_map"},
            {"trains",   "tsu.mc.tab_trains"},
            {"schedule", "tsu.mc.tab_schedule"},
            {"stations", "tsu.mc.tab_stations"},
            {"symbol",   "tsu.mc.tab_symbol"},
            {"tickets",  "tsu.mc.tab_tickets"},
    };

    final TabController<String> tabs = new TabController<>(
            java.util.List.of("map", "trains", "schedule", "stations", "symbol", "tickets"), "map")
            .onSwitch(this::onTabSwitch);
    /** タブ切替 dropdown の open/close (= tab-dropdown trigger + tab-item-{map,train,sched,…} items)。
     *  item class が独自命名のため OverlayController。 */
    final OverlayController tabDropdown = new OverlayController();
    private Boolean localMonitorEnabled = null;
    /** Monitor enable toggle (= local + cache + server sync)。
     *  alias で旧名 mc-monitor-toggle-track/knob にも対応。 */
    final com.manta.api.controller.ToggleSwitchController monitorToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "monitor-toggle-track", "monitor-toggle-knob",
                    this::monitorEnabled,
                    v -> {
                        localMonitorEnabled = v;
                        MonitorClientCache.monitorEnabledCache.put(be().getBlockPos(), v);
                        // MP desync 修正: serverBE() 直 mutate は dedicated server に届かない → payload で server 権威化
                        be().setMonitorEnabled(v);
                        sendMonitorPayload(
                                com.trainsystemutilities.network.MonitorLayoutPayload.ACTION_SET_ENABLED,
                                d -> d.putBoolean("E", v));
                    })
                    .aliasClasses("mc-monitor-toggle-track", "mc-monitor-toggle-knob");

    /** すべて書き出しトグル (= 入力スタック分まとめ書き出し)。 server 反映は menu button 20600 (MP 安全)。 */
    final com.manta.api.controller.ToggleSwitchController exportAllToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "export-all-toggle-track", "export-all-toggle-knob",
                    () -> be().isExportAll(),
                    v -> { if (this.minecraft != null && this.minecraft.gameMode != null)
                               this.minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId, 20600); });

    // === Map tab state (view は MapRenderer、network data は下記で screen 保持) ===
    final MapRenderer mapRenderer = new MapRenderer();
    List<TrackNetworkScanner.NodeInfo> mapNodes = new ArrayList<>();
    List<TrackNetworkScanner.EdgeInfo> mapEdges = new ArrayList<>();
    List<TrackNetworkScanner.StationInfo> mapStations = new ArrayList<>();
    List<TrackNetworkScanner.SignalInfo> mapSignals = new ArrayList<>();
    List<TrackNetworkScanner.TrainInfo> mapTrains = new ArrayList<>();
    BlockPos lastNetworkScanPos = null;
    long lastNetworkRefreshNano = 0L;
    static final long NETWORK_REFRESH_INTERVAL_NS = 250_000_000L;

    // === Trains tab state (Phase 9-F: rows / detail popup are JSON-driven) ===
    private static final int TRAIN_LIST_MAX_VISIBLE = 4;
    /** trains タブ list scroll (= §4.19 ScrollViewport, activeWhen で trains タブのみ scrollbar 表示)。 */
    final ScrollViewport trainScroll = new ScrollViewport(() -> trainsForList().size(), TRAIN_LIST_MAX_VISIBLE)
            .activeWhen(() -> tabs.is("trains"));
    UUID selectedTrainId = null;
    // Schedule snapshot for the currently selected train (refreshed each frame while popup open)
    final List<String> selectedSchedEntries = new ArrayList<>();
    int selectedSchedCurrent = -1;
    String selectedTrainName = "";
    int selectedTrainCars = 0;
    double selectedTrainSpeed = 0;
    String selectedTrainStation = "";

    // === Train detail popup の 3D モデル renderer (god-class 分割で TrainModelRenderer へ抽出) ===
    final TrainModelRenderer trainModel = new TrainModelRenderer();

    // === Schedule tab state ===
    UUID scheduleSelectedTrainId = null;
    /** 書き出し payload を入力スロット1回の充填につき1度だけ送るためのフラグ。 */
    boolean exportRequestSent = false;
    long scheduleSelectNano = 0L;

    private static final long SCHEDULE_ANIM_NS = 280_000_000L;  // 280ms (slideInRight 同等)
    /** schedule タブ list scroll (= §4.19 ScrollViewport)。 */
    final ScrollViewport schedListScroll = new ScrollViewport(() -> trainsForList().size(), TRAIN_LIST_MAX_VISIBLE);
    /** schedule 詳細の entries scroll (= §4.19 ScrollViewport)。 */
    final ScrollViewport schedEntryScroll = new ScrollViewport(() -> selectedSchedEntries.size(), SCHED_VIEW_MAX);
    // 種別行を「戻る」直下に入れたぶん entries ビューポートが 84px→70px に縮んだ (2026-07-18)。
    // management-computer.json の sched-entries h と必ず一致させること。
    private static final int SCHED_VIEW_MAX = 5;

    // === 時刻表共有 (P3) ===
    boolean showScheduleShare = false;
    private static final int SCHED_SHARE_VISIBLE = 7;
    static final int SCHED_SHARE_AREA_Y = 40;
    static final int SCHED_SHARE_AREA_H = 168;
    final ScrollViewport schedShareScroll =
            new ScrollViewport(() -> schedShareCandidates().size(), SCHED_SHARE_VISIBLE);
    /** 共有 popup の per-row トグル (= 候補列車を follower にする ON/OFF)。 */
    final com.manta.api.controller.IndexedToggleSwitchController schedShareToggle =
            new com.manta.api.controller.IndexedToggleSwitchController(
                    "sched-share-toggle-track", "sched-share-toggle-knob",
                    idx -> {
                        var c = schedShareCandidates();
                        if (idx < 0 || idx >= c.size() || scheduleSelectedTrainId == null) return false;
                        return scheduleSelectedTrainId.equals(be().getTimetableShareSource(c.get(idx).id()));
                    },
                    this::sendSchedShareToggle);

    // === Stations tab state ===
    String selectedStationKey = "";  // stationScroll.activeWhen が参照するため前方宣言 (R4.20.2)
    /** stations タブ list scroll (= §4.19 ScrollViewport)。 */
    final ScrollViewport stationScroll = new ScrollViewport(() -> stationsForList().size(), STATION_LIST_MAX_VISIBLE)
            .activeWhen(() -> tabs.is("stations") && selectedStationKey.isEmpty());  // §4.19 R4.19.2: リスト表示中のみ scrollbar
    // コンテナ高さ = 138, stride = 23 → 138/23 = 6 行ピッタリ。
    // 7 にすると 7 行目がコンテナ下端を超えてクリック不可、かつ total <= 7 ではスクロールも発動しないため
    // 「+ ボタンを押せず、スクロールもできない」状態になる。6 にすることで 7 駅以上ある場合にスクロールが
    // 確実に発動し、リスト全体にアクセスできる。
    private static final int STATION_LIST_MAX_VISIBLE = 6;
    static final int STATION_LIST_TRACK_H = STATION_LIST_MAX_VISIBLE * 23; // = 138 (= container h、券売機タブと同寸)
    static final int STATION_LIST_THUMB_H = 20;

    // === Tickets tab state (券売機: ネットワーク駅の販売可を取捨選択) ===
    private static final int TICKETS_MAX_VISIBLE = 6;
    static final int TICKETS_LIST_TRACK_H = 138; // = TICKETS_MAX_VISIBLE * 23 (= リスト高)
    static final int TICKETS_LIST_THUMB_H = 20;
    /** 駅一覧スクロール (= BelugaExperience 標準 ScrollViewport, §4.19)。
     *  activeWhen で券売機タブ表示中のみ scrollbar を出す (= 他タブへの残存を構造的に防止)。 */
    final com.manta.api.controller.ScrollViewport ticketScroll =
            new com.manta.api.controller.ScrollViewport(
                    () -> ticketGroups().size(),
                    TICKETS_MAX_VISIBLE)
                    .activeWhen(() -> tabs.is("tickets"));
    /** タブを開いた瞬間に 1 回だけ駅一覧 + 販売可設定を要求するためのフラグ。 */
    private boolean ticketDataRequested = false;
    /** 切符タブに表示する駅グループ = 自ネットワーク (= server が解決した networkGroups) のみ。 未確立なら空。 */
    java.util.List<com.trainsystemutilities.station.StationGroup> ticketGroups() {
        var net = com.trainsystemutilities.station.TicketConfigClientCache.networkGroups();
        java.util.List<com.trainsystemutilities.station.StationGroup> out = new java.util.ArrayList<>();
        for (var g : com.trainsystemutilities.station.StationGroupClientCache.all()) {
            if (net.contains(g.id())) out.add(g);
        }
        return out;
    }
    /** repeat 行 idx (+ scroll) → 対応する StationGroup。範囲外なら null。 */
    private com.trainsystemutilities.station.StationGroup ticketGroupAt(int repeatIdx) {
        var groups = ticketGroups();
        int real = repeatIdx + ticketScroll.offset();
        return (real >= 0 && real < groups.size()) ? groups.get(real) : null;
    }
    /** 行ごとの販売可トグル (= IndexedToggleSwitchController, §4.14 repeat 内トグル)。 */
    final com.manta.api.controller.IndexedToggleSwitchController ticketToggle =
            new com.manta.api.controller.IndexedToggleSwitchController(
                    "ticket-toggle-track", "ticket-toggle-knob",
                    idx -> {
                        var g = ticketGroupAt(idx);
                        return g != null && com.trainsystemutilities.station.TicketConfigClientCache.isSellable(g.id());
                    },
                    idx -> {
                        var g = ticketGroupAt(idx);
                        if (g == null) return;
                        boolean next = !com.trainsystemutilities.station.TicketConfigClientCache.isSellable(g.id());
                        com.trainsystemutilities.station.TicketConfigClientCache.setLocal(g.id(), next); // 即時反映 (§4.9)
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.trainsystemutilities.network.TicketConfigUpdatePayload(be().getBlockPos(), g.id(), next));
                    });
    /** 券売機タブを開いたとき、駅一覧 + 販売可設定を server から取得 (1 回)。 */
    private void requestTicketDataOnce() {
        if (ticketDataRequested) return;
        ticketDataRequested = true;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.StationGroupListRequestPayload());
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.TicketConfigRequestPayload(be().getBlockPos()));
    }

    // === Symbol tab state ===
    // (Phase 9-C: tile / create button / hover frame are all driven by the JSON
    //  layout via <repeat> + <canvas> + visibleKey + onElementClick(button).)

    // === Symbol editor popup state ===
    // Phase 24: 電化詳細 popup (列車詳細 popup と同じ overlay 方式で表示)
    final ElectrificationDetailController edDetail = new ElectrificationDetailController();
    /** 電化詳細 popup の車両リスト描画 (god-class 分割で ElectrificationCarListRenderer へ抽出)。 */
    final ElectrificationCarListRenderer edCarList = new ElectrificationCarListRenderer();

    final SymbolEditorController symEditor = new SymbolEditorController();

    // === Symbol delete confirm popup (SymbolDeleteController に集約) ===
    final SymbolDeleteController symbolDelete = new SymbolDeleteController();

    // === HSV color picker popup state ===
    boolean showColorPicker = false;
    /** HSV カラーピッカー state (= §6.18 標準 ColorPickerController。 旧 pickerHue/Sat/Val + java.awt.Color を置換)。 */
    final ColorPickerController picker = new ColorPickerController(0xFFFF0000);
    final List<String> customColors = new ArrayList<>();

    // === Monitor color settings popup state (ColorTargetController に集約) ===
    boolean showMonitorColorSettings = false;
    private static final String[] MCOL_KEYS = {
            "panelTitle", "panelBorder", "trainName", "trainStatus", "trainDest",
            "clock", "statValue", "signalGreen", "signalRed",
            "mapLine", "mapStation", "mapTrain"
    };
    private static final String[] MCOL_LABEL_KEYS = {
            "tsu.mc.mcol_panel_title", "tsu.mc.mcol_panel_border", "tsu.mc.mcol_train_name",
            "tsu.mc.mcol_train_status", "tsu.mc.mcol_train_dest",
            "tsu.mc.mcol_clock", "tsu.mc.mcol_stat_value",
            "tsu.mc.mcol_signal_green", "tsu.mc.mcol_signal_red",
            "tsu.mc.mcol_map_line", "tsu.mc.mcol_map_station", "tsu.mc.mcol_map_train"
    };
    /** 実行時に lang から解決する MCOL ラベル配列。lang リロードに追従する。 */
    private static String[] mcolLabels() {
        String[] out = new String[MCOL_LABEL_KEYS.length];
        for (int i = 0; i < MCOL_LABEL_KEYS.length; i++) {
            out[i] = net.minecraft.network.chat.Component.translatable(MCOL_LABEL_KEYS[i]).getString();
        }
        return out;
    }
    private static final String[] MCOL_DEFAULTS = {
            "#4fc3f7", "#2A5570", "#4fc3f7", "#80deea", "#ffc107",
            "#4fc3f7", "#4fc3f7", "#2D6B30", "#9A2A22",
            "#3A5068", "#2A7A9C", "#9A5C00"
    };
    private static final String[] MCOL_PRESETS = {
            "#4fc3f7", "#80deea", "#ff8a65", "#ffc107", "#66bb6a",
            "#ef5350", "#ab47bc", "#ffffff", "#888888", "#555555", "#444444", "#333333"
    };
    /** Color popup controller (state machine + click/text resolvers)。 */
    final ColorTargetController monitorColorPopup =
            new ColorTargetController("mcol", MCOL_KEYS, mcolLabels(), MCOL_DEFAULTS, MCOL_PRESETS,
                    new ColorTargetController.ColorOps() {
                        @Override
                        public void applyPreset(int targetIdx, String key, int presetIdx, String hex) {
                            ManagementComputerScreenV2.this.applyMonitorColor(key, hex);
                        }
                        @Override
                        public void resetTarget(int targetIdx, String key) {
                            ManagementComputerScreenV2.this.applyMonitorColor(key, "");
                        }
                        @Override
                        public void resetAll() {
                            for (String k : MCOL_KEYS) ManagementComputerScreenV2.this.applyMonitorColor(k, "");
                        }
                        @Override
                        public String currentColor(String key, String defaultHex) {
                            return serverBE().getColorOrDefault(key, defaultHex);
                        }
                    });

    // === Layout editor state (Phase 9-G MVP。flag/list/選択 index は LayoutEditorController に集約) ===
    final LayoutEditorController layoutEditor = new LayoutEditorController();
    // Preview canvas geometry (popup-local 座標で更新; drag handler が参照)
    int layoutPrevX, layoutPrevY, layoutPrevW, layoutPrevH;
    /** タイル中ボタン押し込みで開くパネル機能別設定 popup (overlay2) の対象 index。 -1 = 閉。 */
    int layoutSettingsIdx = -1;
    // 既存パネル移動用 drag state
    float layoutDragStartPanelX, layoutDragStartPanelY;
    double layoutDragStartMouseX, layoutDragStartMouseY;
    // パレットからの drag-and-drop state (DragDropPalette helper に集約)
    final DragDropPalette<String> palette = new DragDropPalette<>();

    /** {enumName, translationKey} pairs. Resolve label via tr(). */
    private static final String[][] LAYOUT_TILE_TYPES = {
            {"ROUTE_MAP",     "tsu.mc.layout_tile_route_map"},
            {"TRAIN_LIST",    "tsu.mc.layout_tile_train_list"},
            {"SCHEDULE",      "tsu.mc.layout_tile_schedule"},
            {"STATION_COUNT", "tsu.mc.layout_tile_station_count"},
            {"TRAIN_COUNT",   "tsu.mc.layout_tile_train_count"},
            {"SIGNAL_COUNT",  "tsu.mc.layout_tile_signal_count"},
            {"CLOCK",         "tsu.mc.layout_tile_clock"},
    };

    /** Lang リソースから 1 つの翻訳キーを解決するヘルパ。 */
    static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    // === Station assign dropdown (StationAssignController に集約。assignBtnScreenX/Y は overlay 座標ゆえ screen 残置) ===
    final StationAssignController stationAssign = new StationAssignController();
    // クリックされた + ボタンの screen 座標 (overlayDefaultPosition で参照)
    int assignBtnScreenX = 0;
    int assignBtnScreenY = 0;

    // === Station detail door direction buttons (Phase 9-F: rendered by repeat) ===
    static final String[][] DOOR_OPTS = {
            {"NORTH", "tsu.mc.door_north"}, {"SOUTH", "tsu.mc.door_south"},
            {"EAST", "tsu.mc.door_east"}, {"WEST", "tsu.mc.door_west"},
            {"AUTO", "tsu.mc.door_auto"}, {"NONE", "tsu.mc.door_none"},
    };

    // === Schedule editor (god-class 分割 v2 で ScheduleEditorController へ抽出) ===
    final ScheduleEditorController schedEditor = new ScheduleEditorController();

    public ManagementComputerScreenV2(ManagementComputerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // ===== Wiki live-capture support =====
    // wiki が実画面を自動キャプチャするための off-block 構築 + state 適用。
    // ダミー BlockEntity (client level) + サンプル路線記号で、実 render 経路 (drawCanvas /
    // LineSymbolPainter / カラーピッカー) がそのまま走る = 実物そっくりのキャプチャになる。

    // === Wiki 自動キャプチャ用サンプル列車 ===
    // 列車が存在しない wiki dummy BE でも、時刻表タブを実物そっくり (3状態タイル) で撮るためのサンプル。
    // symbol タブが wikiCreate のサンプル路線記号で撮れるのと同趣旨。 wikiMode のみで分岐し gameplay 非影響。
    boolean wikiMode = false;
    private List<TrackNetworkScanner.TrainInfo> wikiTrains = null;
    /** id -> {schedule エントリ数, 電子式(1/0)}。 */
    final java.util.Map<UUID, int[]> wikiTrainMeta = new java.util.HashMap<>();

    private void initWikiSamples() {
        java.nio.charset.Charset u = java.nio.charset.StandardCharsets.UTF_8;
        UUID a = UUID.nameUUIDFromBytes("tsu-wiki-yamanote".getBytes(u));
        UUID b = UUID.nameUUIDFromBytes("tsu-wiki-chuo".getBytes(u));
        UUID c = UUID.nameUUIDFromBytes("tsu-wiki-keihin".getBytes(u));
        UUID d = UUID.nameUUIDFromBytes("tsu-wiki-kaisou".getBytes(u));
        wikiTrains = List.of(
                new TrackNetworkScanner.TrainInfo(a, "山手線 E235系", 11, 0.0, true, "東京", 0, 0),
                new TrackNetworkScanner.TrainInfo(b, "中央線快速 E233系", 10, 95.0, false, "", 0, 0),
                new TrackNetworkScanner.TrainInfo(c, "京浜東北線 E233系", 10, 60.0, false, "", 0, 0),
                new TrackNetworkScanner.TrainInfo(d, "回送列車", 4, 0.0, false, "", 0, 0));
        wikiTrainMeta.put(a, new int[]{6, 1}); // 電子式 6件
        wikiTrainMeta.put(b, new int[]{4, 1}); // 電子式 4件
        wikiTrainMeta.put(c, new int[]{5, 0}); // 通常 5件
        wikiTrainMeta.put(d, new int[]{0, 0}); // なし
    }

    /** wiki キャプチャ用にダミー BE で screen を生成。 失敗時は null (caller が握る)。 */
    public static ManagementComputerScreenV2 wikiCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.core.BlockPos pos = mc.player.blockPosition();
        var be = new com.trainsystemutilities.blockentity.ManagementComputerBlockEntity(
                pos, com.trainsystemutilities.registry.ModBlocks.MANAGEMENT_COMPUTER.get().defaultBlockState());
        be.setLevel(mc.level);
        // サンプル路線記号 (路線記号タブ / 記号エディタ を実物で見せる)
        be.addLineSymbol(new com.trainsystemutilities.blockentity.LineSymbol("JA", 1, "#4fc3f7", "山手線", 12));
        be.addLineSymbol(new com.trainsystemutilities.blockentity.LineSymbol("CH", 2, "#ff8a65", "中央線", 12));
        be.addLineSymbol(new com.trainsystemutilities.blockentity.LineSymbol("KK", 3, "#66bb6a", "京浜東北線", 12));
        // 空インベントリ (= 実プレイヤーの持ち物アイテムを wiki に写さない)
        Inventory inv = new Inventory(mc.player);
        var menu = new ManagementComputerMenu(0, inv, be);
        ManagementComputerScreenV2 screen = new ManagementComputerScreenV2(menu, inv,
                Component.translatable("tsu.management_computer.title"));
        screen.wikiMode = true;
        screen.initWikiSamples();
        be.wikiSeed(screen.wikiTrainMeta);
        return screen;
    }

    /** wiki キャプチャ用に tab / overlay state を強制設定。 */
    public void wikiApplyState(String state) {
        // overlay を全リセットしてから対象だけ立てる
        showColorPicker = false;
        symEditor.close();
        schedEditor.close();
        stationAssign.close();
        layoutEditor.close();
        layoutSettingsIdx = -1;
        showMonitorColorSettings = false;
        symbolDelete.close();
        selectedTrainId = null;
        scheduleSelectedTrainId = null;
        showScheduleShare = false;
        switch (state) {
            case "map", "trains", "schedule", "stations", "symbol" -> tabs.setCurrent(state);
            // 時刻表 詳細 / 共有: サンプル電子式列車を選択した状態で撮る
            case "schedule-detail" -> { tabs.setCurrent("schedule"); applyWikiSchedDetail(); }
            case "schedule-share"  -> { tabs.setCurrent("schedule"); applyWikiSchedDetail(); showScheduleShare = true; }
            case "schedule-editor" -> { tabs.setCurrent("schedule"); applyWikiSchedDetail(); schedEditor.open(wikiEditorEntries(), true); }
            // color-picker は overlayJson で単体表示 (symEditor は開かない)
            case "color-picker"   -> { tabs.setCurrent("symbol"); showColorPicker = true; }
            case "symbol-editor"  -> { tabs.setCurrent("symbol"); symEditor.open(); }
            case "layout-edit"    -> { tabs.setCurrent("stations"); openLayoutEditor(); }
            case "monitor-color"  -> { tabs.setCurrent("stations"); showMonitorColorSettings = true; }
            default -> tabs.setCurrent("map");
        }
    }

    /** wiki 詳細キャプチャ用: サンプル電子式列車を選択し snapshot を事前設定 (refresh は wikiMode で抑止)。 */
    private void applyWikiSchedDetail() {
        if (wikiTrains == null || wikiTrains.isEmpty()) return;
        var t = wikiTrains.get(0); // 山手線 E235系 (電子式・6件)
        scheduleSelectedTrainId = t.id();
        selectedTrainName = t.name();
        selectedTrainCars = t.carriageCount();
        selectedTrainSpeed = 0.0;
        selectedTrainStation = "";
        selectedSchedEntries.clear();
        selectedSchedEntries.add("東京");
        selectedSchedEntries.add("品川");
        selectedSchedEntries.add("渋谷");
        selectedSchedEntries.add("新宿");
        selectedSchedEntries.add("池袋");
        selectedSchedEntries.add("上野");
        selectedSchedCurrent = 0;
    }

    /** wiki エディタキャプチャ用のサンプルエントリ (駅指定 + 待機条件)。 */
    private java.util.List<ScheduleEditorController.EditEntryData> wikiEditorEntries() {
        java.util.List<ScheduleEditorController.EditEntryData> e = new java.util.ArrayList<>();
        e.add(new ScheduleEditorController.EditEntryData("destination", "東京", 0,
                new java.util.ArrayList<>(java.util.List.of(new ScheduleEditorController.EditCondData("delay", 30, 1)))));
        e.add(new ScheduleEditorController.EditEntryData("destination", "品川", 0,
                new java.util.ArrayList<>(java.util.List.of(new ScheduleEditorController.EditCondData("delay", 20, 1)))));
        e.add(new ScheduleEditorController.EditEntryData("destination", "渋谷", 0, new java.util.ArrayList<>()));
        e.add(new ScheduleEditorController.EditEntryData("destination", "新宿", 0, new java.util.ArrayList<>()));
        return e;
    }

    // dialog 開封 scale-in 同期 inventory item アニメは MCSS 基底 JsonLayoutScreen が default 提供。

    @Override
    protected String layoutJson() {
        return loadResourceJson("layouts/management-computer.json");
    }

    @Override
    protected String overlayJson() {
        if (tabDropdown.isOpen()) return loadResourceJson("layouts/management-computer-tab-menu.json");
        if (symbolDelete.isOpen()) return loadResourceJson("layouts/management-computer-symbol-delete.json");
        // 路線記号エディタを optimal: editor は overlay1、color picker は overlay2 で重ねる
        // (color picker 単体で開いた場合は overlay1 を使う)。
        if (symEditor.isOpen()) return loadResourceJson("layouts/management-computer-symbol-editor.json");
        if (showColorPicker) return loadResourceJson("layouts/management-computer-color-picker.json");
        // Schedule editor: メインの overlay として常に表示。sub-dropdown は overlay2 で重ねる
        // (entry 追加で editor 自体が消える問題を回避)。
        if (schedEditor.isOpen())
            return loadResourceJson("layouts/management-computer-sched-editor.json");
        if (showScheduleShare)
            return loadResourceJson("layouts/management-computer-sched-share.json");
        if (stationAssign.isOpen()) return loadResourceJson("layouts/management-computer-station-assign.json");
        if (layoutEditor.isOpen()) return loadResourceJson("layouts/management-computer-layout-edit.json");
        // モニター色設定 popup はメイン GUI の 🎨 色 ボタンから単独で開く
        if (showMonitorColorSettings) return loadResourceJson("layouts/management-computer-monitor-color.json");
        // 列車詳細 popup は電化詳細を開いたあとも維持される (= overlay1 のまま)。
        // 電化詳細は overlay2 として中央に重ねる ({@link #overlayJson2()} 参照)。
        if (selectedTrainId != null) return loadResourceJson("layouts/management-computer-train-detail.json");
        return null;
    }

    /** Schedule editor の sub-dropdown / Symbol editor 連動の color picker を overlay2 で重ねる。
     *  editor 系は overlay1 のまま残るので両方が同時に表示される。 */
    @Override
    protected String overlayJson2() {
        // Layout editor のタイル機能別設定 (中ボタン押し込みで開く)
        if (layoutEditor.isOpen() && layoutSettingsIdx >= 0)
            return loadResourceJson("layouts/management-computer-panel-settings.json");
        if (symEditor.isOpen() && showColorPicker)
            return loadResourceJson("layouts/management-computer-color-picker.json");
        if (schedEditor.isOpen() && schedEditor.isStationDropdownOpen())
            return loadResourceJson("layouts/management-computer-sched-station-pick.json");
        if (schedEditor.isOpen() && schedEditor.addCondForEntry() >= 0)
            return loadResourceJson("layouts/management-computer-sched-add-cond.json");
        if (schedEditor.isOpen() && schedEditor.isAddEntryOpen())
            return loadResourceJson("layouts/management-computer-sched-add-entry.json");
        // Phase 24: 電化詳細は overlay2 として列車詳細 popup の上に重ねて表示
        if (edDetail.isOpen() && selectedTrainId != null)
            return loadResourceJson("layouts/management-computer-electrification-detail.json");
        return null;
    }

    @Override
    protected int[] overlayDefaultPosition(int overlayW, int overlayH) {
        return ManagementComputerRender.overlayDefaultPosition(this, overlayW, overlayH);
    }

    /** sub-dropdown (overlay2) の表示位置:
     *  - 条件追加 dropdown は記録したボタン直下に出す (entry の真下)
     *  - 駅選択 dropdown は editor popup の右隣
     *  - 路線記号エディタ + color picker は editor の右隣
     */
    @Override
    protected int[] overlayDefaultPosition2(int overlayW, int overlayH) {
        return ManagementComputerRender.overlayDefaultPosition2(this, overlayW, overlayH);
    }

    /** MCSS 基底の loadModResourceJson に委譲 (TsuLayouts.load 経由)。 */
    private static String loadResourceJson(String path) { return TsuLayouts.load(path); }

    ManagementComputerBlockEntity be() { return getMenu().getBlockEntity(); }

    ManagementComputerBlockEntity serverBE() {
        var be = be();
        if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
            var sl = this.minecraft.getSingleplayerServer().getLevel(be.getLevel().dimension());
            if (sl != null) {
                var sbe = sl.getBlockEntity(be.getBlockPos());
                if (sbe instanceof ManagementComputerBlockEntity sc) return sc;
            }
        }
        return be;
    }

    // === god-class 分割: ManagementComputerDispatch 用 package-private bridge ===
    // (vanilla Screen / manta 基底の protected メンバーは同 package の companion から直接参照できないため)
    net.minecraft.client.gui.Font fontOrNull() { return this.font; }
    net.minecraft.client.Minecraft minecraftAccess() { return this.minecraft; }
    int[] findElementByClassAccess(String className) { return findElementByClass(className); }
    int dialogLocalToScreenXAccess(int localX) { return dialogLocalToScreenX(localX); }
    int dialogLocalToScreenYAccess(int localY) { return dialogLocalToScreenY(localY); }
    int leftPosAccess() { return this.leftPos; }
    int topPosAccess() { return this.topPos; }
    int imageWidthAccess() { return this.imageWidth; }
    int imageHeightAccess() { return this.imageHeight; }
    int dialogScaleAmountAccess(int localDelta) { return dialogScaleAmount(localDelta); }

    /** B2 (MP desync 修正): 全列車停止。 client 直 mutate でなく server BE の
     *  startAllTrainsStop (1 台ずつ順次停止方式) を payload で呼ぶ。 */
    void startAllStop() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementComputerControlPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementComputerControlPayload.ACTION_STOP_ALL,
                        new java.util.UUID(0, 0)));
    }

    /** Symbol タブのタイル hover preview (HoverTilePreview に集約)。 */
    final HoverTilePreview symbolHover = new HoverTilePreview();

    /** カラーピッカーで現在フォーカスされているテキストフィールド: "hex" | "rgb" | "hsl" | null。 */
    String focusedField = null;
    /** フォーカス中のフィールドの編集バッファ。 */
    String fieldEditBuffer = "";
    long fieldFocusBlinkNano = 0L;

    @Override
    protected void containerTick() {
        super.containerTick();
    }

    /** カラーピッカーのテキストフィールドにフォーカスを移し、現在値を編集バッファに読み込む。 */
    void focusField(String field) {
        focusedField = field;
        fieldFocusBlinkNano = System.nanoTime();
        switch (field) {
            case "hex" -> fieldEditBuffer = currentPickerHex().toUpperCase();
            case "rgb" -> {
                int rgb = ColorPickerController.hsvToRgb(picker.hue(), picker.saturation(), picker.value());
                fieldEditBuffer = ((rgb >> 16) & 0xFF) + ", " + ((rgb >> 8) & 0xFF)
                        + ", " + (rgb & 0xFF);
            }
            case "hsl" -> {
                float h = picker.hue() * 360f;
                float l = picker.value() * (1f - picker.saturation() / 2f);
                float s = (l == 0f || l == 1f) ? 0f
                        : (picker.value() - l) / Math.min(l, 1f - l);
                fieldEditBuffer = Math.round(h) + ", " + Math.round(s * 100)
                        + ", " + Math.round(l * 100);
            }
        }
    }

    /** 編集バッファをパースして picker state へ反映。失敗時は無視。 */
    private void commitFieldEdit() {
        if (focusedField == null) return;
        try {
            String buf = fieldEditBuffer.trim();
            switch (focusedField) {
                case "hex" -> {
                    String hex = buf.startsWith("#") ? buf.substring(1) : buf;
                    if (hex.length() == 6) setPickerFromColor("#" + hex);
                }
                case "rgb" -> {
                    String[] parts = buf.split("[,\\s]+");
                    if (parts.length >= 3) {
                        int r = clamp(Integer.parseInt(parts[0].trim()), 0, 255);
                        int g = clamp(Integer.parseInt(parts[1].trim()), 0, 255);
                        int b = clamp(Integer.parseInt(parts[2].trim()), 0, 255);
                        setPickerFromColor(String.format("#%02X%02X%02X", r, g, b));
                    }
                }
                case "hsl" -> {
                    String[] parts = buf.replace("°", "").replace("%", "").split("[,\\s]+");
                    if (parts.length >= 3) {
                        float h = (float) (clampD(Double.parseDouble(parts[0].trim()), 0, 360) / 360.0);
                        float s = (float) (clampD(Double.parseDouble(parts[1].trim()), 0, 100) / 100.0);
                        float l = (float) (clampD(Double.parseDouble(parts[2].trim()), 0, 100) / 100.0);
                        // HSL → HSV
                        float v = l + s * Math.min(l, 1f - l);
                        float sv = v == 0f ? 0f : 2f * (1f - l / v);
                        picker.setHsv(h, clampF(sv, 0f, 1f), clampF(v, 0f, 1f));
                    }
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
        focusedField = null;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static double clampD(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private static float clampF(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (schedEditor.isOpen() && schedEditor.charTyped(c)) return true;
        if (focusedField != null && fieldEditBuffer.length() < 32) {
            // 数字・カンマ・スペース・# 16 進数のみ受け入れる
            if (Character.isDigit(c) || c == ',' || c == ' ' || c == '#'
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '°' || c == '%') {
                fieldEditBuffer += c;
                fieldFocusBlinkNano = System.nanoTime();
                return true;
            }
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (schedEditor.isOpen() && schedEditor.keyPressed(keyCode)) return true;
        if (focusedField != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                if (!fieldEditBuffer.isEmpty()) {
                    fieldEditBuffer = fieldEditBuffer.substring(0, fieldEditBuffer.length() - 1);
                    fieldFocusBlinkNano = System.nanoTime();
                }
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                commitFieldEdit();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                focusedField = null;
                return true;
            }
            // フォーカス中は他のキー (ESC 含むのは上で処理) は消費
            return true;
        }
        // Layout editor: DEL で選択パネル削除
        if (layoutEditor.isOpen() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                && layoutEditor.deleteSelected()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** B2 (MP desync 修正): 全列車再開。 server BE の resumeAllTrains を payload で呼ぶ。 */
    void resumeAllStop() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementComputerControlPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementComputerControlPayload.ACTION_RESUME_ALL,
                        new java.util.UUID(0, 0)));
    }

    /** server 同期される runtime.paused を見て、停止中の列車数を数える (MP-safe)。 */
    int pausedTrainCount() {
        int n = 0;
        for (var ti : trainsForList()) {
            if (ti.id() == null) continue;
            if (be().hasSyncedPaused(ti.id())) n++;   // server 同期の paused フラグ (MP-safe)
        }
        return n;
    }

    /** 何かしら paused な列車がいるか? (server 同期される runtime.paused を見る)。 */
    boolean anyTrainPaused() {
        return pausedTrainCount() > 0;
    }

    /** Phase 5d: memory card (slot 0) + monitor link card (slot 1) の両方が設定済みで、
     *  かつ memory card 経由のスキャン対象 (railway/track-network) が解決できる場合に true。
     *  bottom-row の オンライン/オフライン 表示と連動。 */
    boolean isOnline() {
        var memCard = getMenu().getSlot(0).getItem();
        if (memCard.isEmpty()) return false;
        var monCard = getMenu().getSlot(1).getItem();
        if (monCard.isEmpty()) return false;
        var b = be();
        if (!b.isLinkedToMonitor()) return false;
        return b.getLinkedTrackNetworkPos() != null || b.getLinkedRailwayManagerPos() != null;
    }

    boolean monitorEnabled() {
        if (localMonitorEnabled != null) return localMonitorEnabled;
        BlockPos pos = be().getBlockPos();
        Boolean cached = MonitorClientCache.monitorEnabledCache.get(pos);
        return cached != null ? cached : be().isMonitorEnabled();
    }

    /** タブ切替時の副作用 (= sub-selection クリア + tickets/map 固有処理)。
     *  TabController.onSwitch から、実際にタブが変わったときのみ呼ばれる。 */
    private void onTabSwitch(String id) {
        if ("tickets".equals(id)) requestTicketDataOnce();
        if ("map".equals(id)) mapRenderer.resetInit();
        // Clear sub-selections when switching tabs
        selectedTrainId = null;
        edDetail.close();
        scheduleSelectedTrainId = null;
        showScheduleShare = false;
        selectedStationKey = "";
    }

    /**
     * タブ dropdown のラベル。MANTA_5 Wave 7 / W7-1 (R4.23.1): 末尾の {@code ▾} は
     * dropdown control なので layout 側の子 icon ({@code manta:chevron-down}) へ移した。
     * {@code ▾}(U+25BE) は gate の glyph 集合に {@code ▼}(U+25BC) しか無かったため
     * <b>異体字として見逃されていた</b> (2026-07-26 に集合を拡張)。
     */
    String currentTabLabel() {
        for (String[] t : TABS) {
            if (t[0].equals(tabs.current())) return tr(t[1]);
        }
        return tr(TABS[0][1]);
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        return ManagementComputerDispatch.getDynamicText(this, classes, defaultText);
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        return ManagementComputerDispatch.getDynamicNumber(this, classes, key, defaultValue);
    }

    static final int TRAIN_LIST_TRACK_H = (28 + 2) * 4;  // (TRAINS_ROW_H+2) * TRAINS_MAX
    static final int TRAIN_LIST_THUMB_H = 20;
    static final int SCHED_ENTRIES_TRACK_H = 70;  // sched-entries repeat h (= SCHED_VIEW_MAX(5) * stride 14)
    static final int SCHED_ENTRIES_THUMB_H = 18;

    /** 駅タブのリスト表示用に「優先度順」の駅一覧を返す:
     *  client-side scan (mapStations) を最優先、空なら server cache にフォールバック。
     *  これにより mapTrains/mapStations と同じ瞬間データで一致する。 */
    List<TrackNetworkScanner.StationInfo> stationsForList() {
        if (!mapStations.isEmpty()) return mapStations;
        return be().getCachedStations();
    }

    /** 列車一覧用の統合リスト。scan結果を優先し、server cacheで補完する。 */
    List<TrackNetworkScanner.TrainInfo> trainsForList() {
        if (wikiMode && wikiTrains != null) return wikiTrains;
        LinkedHashMap<UUID, TrackNetworkScanner.TrainInfo> merged = new LinkedHashMap<>();
        addTrainsById(merged, mapTrains);
        var client = be();
        var server = serverBE();
        addTrainsById(merged, client.getCachedTrains());
        if (server != client) addTrainsById(merged, server.getCachedTrains());
        var list = new ArrayList<>(merged.values());
        // Prefetch: 全列車の preview snapshot をバックグラウンドで先読み (1秒間隔)。
        // requestIfNeeded 内で per-train rate limit + 既キャッシュ skip があるため安全。
        // user がクリックした時点で snapshot が既に揃っており、待ち時間がほぼ消える。
        long now = System.nanoTime();
        if (now - lastPrefetchNanos > 1_000_000_000L) {
            lastPrefetchNanos = now;
            for (var ti : list) {
                if (ti.id() != null) {
                    com.trainsystemutilities.client.preview.TrainPreviewCache.requestIfNeeded(ti.id());
                }
            }
        }
        return list;
    }
    /** trainsForList prefetch 用 throttle (1秒間隔)。 */
    private long lastPrefetchNanos = 0L;

    private void addTrainsById(LinkedHashMap<UUID, TrackNetworkScanner.TrainInfo> out,
                               List<TrackNetworkScanner.TrainInfo> trains) {
        for (var train : trains) {
            if (train == null || train.id() == null) continue;
            out.putIfAbsent(train.id(), train);
        }
    }

    void clampTrainListScrolls() {
        trainScroll.clamp();
        schedListScroll.clamp();
    }

    List<String> schedStationNames() {
        java.util.LinkedHashSet<String> nameSet = new java.util.LinkedHashSet<>();
        for (var s : mapStations) nameSet.add(s.name());
        if (nameSet.isEmpty()) for (var s : be().getCachedStations()) nameSet.add(s.name());
        return new ArrayList<>(nameSet);
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        return ManagementComputerDispatch.getDynamicBool(this, classes, key, defaultValue);
    }

    boolean isSelectedSchedTrainPaused() {
        if (scheduleSelectedTrainId == null) return false;
        if (wikiMode) return true;
        return be().hasSyncedPaused(scheduleSelectedTrainId);   // server 同期の paused (MP-safe)
    }

    /** trainId が電子式時刻表 (管理用コンピューター管理) か。 同期済みクライアント BE 参照 (MP 対応)。 */
    boolean isElectronicTimetable(java.util.UUID trainId) {
        try { return trainId != null && be().isElectronicTimetable(trainId); }
        catch (Exception ignored) { return false; }
    }

    /** 選択中の列車が Create schedule を持つか。 server 計算の同期フラグ参照 (MP 対応)。 */
    boolean selectedSchedTrainHasSchedule() {
        try { return scheduleSelectedTrainId != null && be().hasSyncedSchedule(scheduleSelectedTrainId); }
        catch (Exception ignored) { return false; }
    }

    boolean selectedSchedTrainIsElectronic() { return isElectronicTimetable(scheduleSelectedTrainId); }

    /** 選択中の列車に運転士が乗っているか。 client では Conductor 状態が正確に取れないため
     *  server 計算の同期フラグを使う (SP/MP 共通)。 */
    boolean selectedSchedTrainHasConductor() {
        try { return scheduleSelectedTrainId != null && be().hasSyncedConductor(scheduleSelectedTrainId); }
        catch (Exception ignored) { return false; }
    }

    /** 編集可能か: 停止中 + 共有追従中でない + 通常時刻表でない (電子式/なし) + 運転士あり。 */
    boolean selectedSchedEditable() {
        if (!isSelectedSchedTrainPaused()) return false;
        if (be().isTimetableFollower(scheduleSelectedTrainId)) return false; // 共有追従中は読み取り専用
        if (selectedSchedTrainHasSchedule() && !selectedSchedTrainIsElectronic()) return false; // 通常時刻表は編集不可
        return selectedSchedTrainHasConductor();
    }

    /** 共有候補の列車一覧 (= 同一ネットワークの列車から、source 自身と「共有元」列車を除く)。 */
    java.util.List<TrackNetworkScanner.TrainInfo> schedShareCandidates() {
        java.util.List<TrackNetworkScanner.TrainInfo> out = new java.util.ArrayList<>();
        UUID source = scheduleSelectedTrainId;
        if (source == null) return out;
        for (var ti : trainsForList()) {
            if (ti.id() == null || ti.id().equals(source)) continue;
            if (be().isTimetableShareSource(ti.id())) continue; // 共有元の列車は follower にできない
            out.add(ti);
        }
        return out;
    }

    /** 共有 popup repeat の実 index (= repeat idx + scroll offset)。 */
    int schedShareRealIdx() {
        return com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex() + schedShareScroll.offset();
    }

    int schedShareThumbH() {
        int total = schedShareCandidates().size();
        if (total <= SCHED_SHARE_VISIBLE) return SCHED_SHARE_AREA_H;
        return Math.max(8, SCHED_SHARE_AREA_H * SCHED_SHARE_VISIBLE / total);
    }

    /** UUID から列車表示名を解決 (= 共有元バナー / タイル表示用)。 */
    String trainNameById(UUID id) {
        if (id == null) return "?";
        for (var ti : trainsForList()) if (id.equals(ti.id())) return ti.name();
        return "?";
    }

    /** 共有トグル → server へ ON/OFF payload。 */
    private void sendSchedShareToggle(int idx) {
        var c = schedShareCandidates();
        if (idx < 0 || idx >= c.size() || scheduleSelectedTrainId == null) return;
        UUID target = c.get(idx).id();
        if (target == null) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ShareTimetablePayload(
                        be().getBlockPos(), scheduleSelectedTrainId, target));
    }

    // 種別ホイールの client 即時反映 (R4.9.1)。 server 同期は tick 単位なので、
    // 届くまでのあいだローカル値を優先し、 一致したら破棄する。
    private UUID pendingTypeTrainId = null;
    private String pendingTypeCode = null;

    /** 選択中列車の種別コード。 未同期のローカル変更があればそれを優先する。 */
    String selectedTrainTypeCode() {
        UUID id = scheduleSelectedTrainId;
        if (id == null) return TrainTypes.NONE;
        String synced = be().getSyncedTrainType(id);
        if (id.equals(pendingTypeTrainId) && pendingTypeCode != null) {
            if (pendingTypeCode.equals(synced)) {
                pendingTypeTrainId = null;
                pendingTypeCode = null;
            } else {
                return pendingTypeCode;
            }
        }
        return synced;
    }

    /** 種別ホイール 1 段 → server payload + client 即時反映 (R4.9.1)。 */
    void cycleSelectedTrainType(int dir) {
        if (scheduleSelectedTrainId == null) return;
        String next = TrainTypes.cycle(selectedTrainTypeCode(), dir);
        pendingTypeTrainId = scheduleSelectedTrainId;
        pendingTypeCode = next;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.SetTrainTypePayload(
                        be().getBlockPos(), scheduleSelectedTrainId, TrainTypes.indexOf(next)));
    }

    TrackNetworkScanner.StationInfo selectedStation() {
        if (selectedStationKey.isEmpty()) return null;
        for (var s : be().getCachedStations()) {
            if (selectedStationKey.equals(ManagementComputerBlockEntity.stationKey(s.name(), s.position()))) {
                return s;
            }
        }
        return null;
    }

    boolean selectedStationHasRMBE() {
        var s = selectedStation();
        if (s == null) return false;
        return be().getManagerPosForStation(s.name(), s.position()) != null;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        return ManagementComputerDispatch.getDynamicColor(this, classes, key, defaultArgb);
    }

    static int parseHexColor(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) { return 0xFF555555; }
    }

    @Override
    public Animation getDynamicAnimation(String[] classes, String key) {
        // 基底が dialog-open / *-popup-open を default 解決
        Animation base = super.getDynamicAnimation(classes, key);
        if (base != null) return base;
        return switch (key) {
            // Modal-style popups (centered): playful overshoot
            case "popup-pop" -> Animation.popIn(180);
            // Dropdowns / menus: railway-management と同じ EASE_OUT_BACK バウンス展開。
            // tab-menu の高さは ITEM_H(16)*5 + 4 = 84 → 半分 42 を translate に。
            case "popup-slide" -> Animation.dropdownDown(220, 84);
            // 時刻表詳細ビューが list から detail に遷移するときの右からスライドイン。
            case "sched-detail-slide" -> Animation.slideInFromRight(280, 60f);
            // 駅タブで駅タイルを選択したときの詳細パネル右からスライドイン。
            case "station-detail-slide" -> Animation.slideInFromRight(280, 60f);
            // モニター色設定 popup の対象 dropdown (高さ 11*12+4=136 → 半分 68)
            case "mcol-target-open" -> Animation.dropdownDown(280, 136);
            // + エントリ追加 inline dropdown を上方向に展開 (高さ 74 → 半分 37)。
            // dropdownUp は下端固定で scaleY 0→1。EASE_OUT_BACK で末端 bounce。
            case "sched-add-entry-open" -> Animation.dropdownUp(220, 74);
            default -> null;
        };
    }

    @Override
    public Transition getDynamicTransition(String[] classes, String key) {
        // 共通 toggle-bg / toggle-knob は MCSS 基底が解決 (railway-management 等と同じ動き)
        Transition base = super.getDynamicTransition(classes, key);
        if (base != null) return base;
        // Door direction button bg/border/text: 150ms ease-out fade on selection change
        if ("door-transition".equals(key)) return Transition.of(150);
        // Schedule pause/resume button: brief color crossfade
        if ("sched-pause-transition".equals(key)) return Transition.of(120);
        // 旧名 (互換): "knob-transition" は今は toggle-knob で良いが残しておく
        if ("knob-transition".equals(key)) return Transition.of(150, Easing.EASE_OUT);
        return null;
    }

    /** Returns [station(String), speed(Double), cars(Integer), dest(String)]. */
    Object[] trySafeLiveTrain(java.util.UUID id) {
        String station = "", dest = "";
        double speed = 0;
        int cars = 0;
        // server 同期の cachedTrains + SchedView から (MP-safe; client getTrainById は使わない)
        var be = be();
        for (var ti : be.getCachedTrains()) {
            if (id.equals(ti.id())) {
                speed = Math.abs(ti.speed()) * 20 * 3.6;
                cars = ti.carriageCount();
                station = ti.currentStationName() == null ? "" : ti.currentStationName();
                break;
            }
        }
        var livePos = com.trainsystemutilities.client.transit.TransitTerminalClientCache.trainPositions().get(id);
        if (livePos != null) speed = Math.abs(livePos.speed()) * 20 * 3.6;
        var sv = be.getSyncedSchedView(id);
        if (sv != null && sv.current() >= 0 && sv.current() < sv.entries().size()) {
            dest = "→ " + sv.entries().get(sv.current());
        }
        return new Object[]{station, speed, cars, dest};
    }

    Boolean selectedSchedCyclic() {
        if (scheduleSelectedTrainId == null) return null;
        if (wikiMode) return Boolean.TRUE;
        var sv = be().getSyncedSchedView(scheduleSelectedTrainId);   // server 同期 (MP-safe)
        return sv != null ? sv.cyclic() : null;
    }

    com.trainsystemutilities.blockentity.RailwayManagementBlockEntity.DoorSide currentDoorSide() {
        var s = selectedStation();
        if (s == null) return null;
        BlockPos rmPos = be().getManagerPosForStation(s.name(), s.position());
        if (rmPos == null || be().getLevel() == null) return null;
        try {
            var bbe = be().getLevel().getBlockEntity(rmPos);
            if (bbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                return rm.getDoorOpenSide();
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
        return null;
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        ManagementComputerRender.drawCanvas(this, g, classes, key, x, y, w, h, mouseX, mouseY);
    }

    @Override
    public boolean onElementDrag(String[] classes, String key, int mouseX, int mouseY,
                                  int elX, int elY, int elW, int elH, boolean pressed) {
        return ManagementComputerDispatch.onElementDrag(this, classes, key, mouseX, mouseY, elX, elY, elW, elH, pressed);
    }

    @Override
    public void onElementDragEnd(String[] classes, String key) {
        // パレットからの drop: preview 範囲内なら panel を追加
        if ("layout-tile-drag".equals(key) && palette.isDragging()) {
            String draggedType = palette.payload();
            int mx = palette.mouseX(), my = palette.mouseY();
            if (layoutPrevW > 0 && layoutPrevH > 0
                    && mx >= layoutPrevX && mx < layoutPrevX + layoutPrevW
                    && my >= layoutPrevY && my < layoutPrevY + layoutPrevH) {
                float defW = "ROUTE_MAP".equals(draggedType) ? 0.45f : 0.25f;
                float defH = "ROUTE_MAP".equals(draggedType) ? 0.45f : 0.18f;
                // ドロップ位置を中心に配置 (画面端でクランプ)
                float cx = (mx - layoutPrevX) / (float) layoutPrevW;
                float cy = (my - layoutPrevY) / (float) layoutPrevH;
                float px = Math.max(0.03f, Math.min(0.97f - defW, cx - defW / 2f));
                float py = Math.max(0.03f, Math.min(0.97f - defH, cy - defH / 2f));
                try {
                    var type = com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType
                            .valueOf(draggedType);
                    layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                            type, px, py, defW, defH));
                    layoutEditor.select(layoutEditor.getLayout().size() - 1);
                } catch (IllegalArgumentException ignored) {}
            }
            palette.onRelease();
        }
    }

    @Override
    public boolean onElementWheel(String[] classes, String key,
                                   int mouseX, int mouseY, double scrollY) {
        return ManagementComputerDispatch.onElementWheel(this, classes, key, mouseX, mouseY, scrollY);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        ManagementComputerDispatch.onElementClick(this, classes, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // (Schedule editor + sub-dropdowns — all JSON-driven via onElementClick.)
        // (Symbol / trains / schedule / stations / door / assign — all JSON-driven.)
        return super.mouseClicked(mx, my, button);
    }

    void togglePauseSelected() {
        if (scheduleSelectedTrainId == null) return;
        // B2 (MP desync 修正): client で直接 mutate せず server 権威の payload を送る。
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementComputerControlPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementComputerControlPayload.ACTION_TOGGLE_ONE,
                        scheduleSelectedTrainId));
    }

    // === Symbol editor helpers ===
    void openSymbolEditorNew() {
        symEditor.openNew();
    }

    void openSymbolEditorExisting(int idx) {
        var syms = serverBE().getLineSymbols();
        if (idx < 0 || idx >= syms.size()) return;
        symEditor.openExisting(idx, syms.get(idx));
    }

    void saveEditedSymbol() {
        // サーバー権威化 (MP desync 修正): client BE 直 mutate でなく payload で server BE を編集。
        // server 側 saveLineSymbol が sendBlockUpdated で client へ同期し直すため記号が消えない。
        var sym = symEditor.buildSymbol();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementSymbolPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementSymbolPayload.ACTION_SAVE,
                        symEditor.getIndex(),
                        sym.getLetters(), sym.getNumber(), sym.getBorderColor(), sym.getName(), sym.getBorderRadius(),
                        "", net.minecraft.core.BlockPos.ZERO, new java.util.UUID(0, 0)));
    }

    void confirmDeleteSymbol(int delIdx) {
        if (delIdx < 0) return;
        // サーバー権威化 (MP desync 修正): server BE を payload で削除 → sendBlockUpdated で client 同期。
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementSymbolPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementSymbolPayload.ACTION_DELETE,
                        delIdx,
                        "", 0, "", "", 12,
                        "", net.minecraft.core.BlockPos.ZERO, new java.util.UUID(0, 0)));
    }

    void assignSymbolOnServer(String stationName, BlockPos stationPos, java.util.UUID symId) {
        if (stationName == null || stationPos == null) return;
        // サーバー権威化 (MP desync 修正): 駅↔記号 割当も server BE を payload で更新。
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementSymbolPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementSymbolPayload.ACTION_ASSIGN,
                        -1,
                        "", 0, "", "", 12,
                        stationName, stationPos, symId == null ? new java.util.UUID(0, 0) : symId));
    }

    // === HSV color picker helpers ===
    void setPickerFromColor(String hex) {
        picker.setHex(hex);                 // §6.18 ColorPickerController (旧 java.awt.Color.RGBtoHSB)
    }

    String currentPickerHex() {
        return picker.hexText();            // "#RRGGBB" 大文字 (旧 String.format("#%06X", ...) と同形式)
    }

    // === Monitor color settings helpers ===
    /** モニター色を server へ payload 送信 (空文字 = リセット = デフォルト復帰)。
     *  MP desync 修正: serverBE() 直 mutate は dedicated server に届かない。
     *  client BE にも反映して renderer が NBT 同期前から正しい色を出せるようにする。 */
    private void applyMonitorColor(String key, String value) {
        sendMonitorPayload(
                com.trainsystemutilities.network.MonitorLayoutPayload.ACTION_SET_COLOR,
                d -> { d.putString("K", key); d.putString("V", value); });
        // client BE にも反映 (NBT 同期遅延対策、layout 保存と同じ手法)
        be().setColor(key, value);
    }

    /** モニター設定 payload 送信 (MP desync 修正: serverBE() 直 mutate の置き換え共通経路)。 */
    private void sendMonitorPayload(int action,
            java.util.function.Consumer<net.minecraft.nbt.CompoundTag> fill) {
        var data = new net.minecraft.nbt.CompoundTag();
        fill.accept(data);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.MonitorLayoutPayload(be().getBlockPos(), action, data));
    }

    // === Layout editor helpers ===
    void openLayoutEditor() {
        layoutEditor.getLayout().clear();
        for (var p : serverBE().getMonitorLayout()) layoutEditor.getLayout().add(p.copy());
        layoutEditor.clearSelection();
        layoutSettingsIdx = -1;
        layoutEditor.open();
    }

    /** layout preview canvas 上 (popup-local mouse 座標) のパネル index。 一番上 = 後追加分から検査。 */
    int layoutPanelAt(int mouseX, int mouseY) {
        if (layoutPrevW <= 0 || layoutPrevH <= 0) return -1;
        int cx = mouseX - layoutPrevX;
        int cy = mouseY - layoutPrevY;
        for (int i = layoutEditor.getLayout().size() - 1; i >= 0; i--) {
            var p = layoutEditor.getLayout().get(i);
            int px = (int)(p.getX() * layoutPrevW);
            int py = (int)(p.getY() * layoutPrevH);
            int pw = Math.max(8, (int)(p.getWidth() * layoutPrevW));
            int ph = Math.max(8, (int)(p.getHeight() * layoutPrevH));
            if (cx >= px && cx < px + pw && cy >= py && cy < py + ph) return i;
        }
        return -1;
    }

    /** pset 値の wheel 増減: 0(自動) から上げると 8 に jump、 下げると 0 (=自動) で止まる。 */
    static int adjustPsetValue(int cur, int delta) {
        if (cur == 0) return delta > 0 ? 8 : 0;
        return Math.max(0, cur + delta);
    }

    /** pset 値表示: 0 = 自動 (推奨)、 それ以外は px 表記。 */
    String psetValText(int v) {
        return v == 0 ? tr("tsu.mc.pset_auto_value") : (v + "px");
    }

    /** 設定 popup の対象パネル (範囲外なら null)。 */
    com.trainsystemutilities.blockentity.MonitorLayoutPanel psetPanel() {
        if (layoutSettingsIdx >= 0 && layoutSettingsIdx < layoutEditor.getLayout().size()) {
            return layoutEditor.getLayout().get(layoutSettingsIdx);
        }
        return null;
    }

    void saveLayoutToServer() {
        // 1) サーバーへ payload (MP desync 修正: serverBE() 直 mutate は dedicated server に届かず、
        //    毎秒の NBT 同期で空に上書きされて「保存しても次に開くと 0 パネル」になっていた)
        sendMonitorPayload(
                com.trainsystemutilities.network.MonitorLayoutPayload.ACTION_SAVE_LAYOUT,
                d -> d.put("L", com.trainsystemutilities.blockentity.MonitorLayoutPanel
                        .saveList(layoutEditor.getLayout())));
        // 2) クライアント BE (renderer は client BE を見るので、NBT 同期到着前から表示)
        var cbe = be();
        cbe.getMonitorLayout().clear();
        for (var p : layoutEditor.getLayout()) cbe.getMonitorLayout().add(p.copy());
        // 3) MonitorWorldRenderer は MonitorClientCache.layoutCache を優先参照する。
        //    NBT 同期遅延中にレイアウトが消えないよう client BE と並行で書き込む。
        // HOTFIX N+0.5 #3: putLayout 経由で immutable snapshot として書込み、
        // render thread の iteration と race しないようにする。
        var lcCopy = new ArrayList<com.trainsystemutilities.blockentity.MonitorLayoutPanel>();
        for (var p : layoutEditor.getLayout()) lcCopy.add(p.copy());
        MonitorClientCache.putLayout(cbe.getBlockPos(), lcCopy);
    }

    void addLayoutPanel(String typeName) {
        try {
            var type = com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.valueOf(typeName);
            // 路線マップは大きめ、その他は中サイズで追加 (V1 と同じデフォルト)
            float w = "ROUTE_MAP".equals(typeName) ? 0.6f : 0.3f;
            float h = "ROUTE_MAP".equals(typeName) ? 0.6f : 0.2f;
            // 重ならない位置を探す (簡易: 0.05 ずつずらす)
            float px = 0.05f, py = 0.05f;
            for (int tries = 0; tries < 20; tries++) {
                boolean overlap = false;
                for (var p : layoutEditor.getLayout()) {
                    if (px < p.getX() + p.getWidth() && px + w > p.getX()
                            && py < p.getY() + p.getHeight() && py + h > p.getY()) {
                        overlap = true; break;
                    }
                }
                if (!overlap) break;
                px += 0.04f;
                if (px + w > 0.95f) { px = 0.05f; py += 0.04f; }
                if (py + h > 0.95f) break;
            }
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(type, px, py, w, h));
            layoutEditor.select(layoutEditor.getLayout().size() - 1);
        } catch (IllegalArgumentException ignored) {}
    }

    void applyRecommendedLayout() {
        ManagementComputerRender.applyRecommendedLayout(this);
    }

    // === Schedule editor helpers ===
    void openScheduleEditor() {
        if (scheduleSelectedTrainId == null) return;
        if (!selectedSchedEditable()) {
            if (minecraft != null && minecraft.player != null) {
                String msg = (selectedSchedTrainHasSchedule() && !selectedSchedTrainIsElectronic())
                        ? "tsu.mc.tt_regular_readonly" : "tsu.mc.tt_need_conductor";
                minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(msg), true);
            }
            return;
        }
        // schedule は server 権威。client の Train.runtime.getSchedule() は Create が全 client に
        // 確実には同期せず、運行停止直後などに null/空になる (= 編集で全エントリ空欄バグ)。
        // server に現在の schedule を要求し、応答 (ScheduleEditDataPayload) で editor を開く。
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.RequestScheduleEditPayload(
                        be().getBlockPos(), scheduleSelectedTrainId));
    }

    /** {@link com.trainsystemutilities.network.ScheduleEditDataPayload} 応答で editor を開く (server 権威の schedule)。 */
    public void onScheduleEditData(UUID trainId, boolean hasData, net.minecraft.nbt.CompoundTag scheduleNbt) {
        // 応答到達までに選択列車が変わっていたら無視
        if (scheduleSelectedTrainId == null || !scheduleSelectedTrainId.equals(trainId)) return;
        com.simibubi.create.content.trains.schedule.Schedule sched = null;
        try {
            if (hasData && minecraft != null && minecraft.level != null) {
                sched = com.simibubi.create.content.trains.schedule.Schedule
                        .fromTag(minecraft.level.registryAccess(), scheduleNbt);
            }
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "schedule editor: failed to decode schedule nbt", e);
        }
        if (sched == null || sched.entries.isEmpty()) {
            schedEditor.open(new ArrayList<>(), true);
            return;
        }
        schedEditor.open(buildEditEntries(sched), sched.cyclic);
    }

    /** Create Schedule を editor の編集モデル (EditEntryData) に変換 (server 権威の schedule を parse)。 */
    private List<ScheduleEditorController.EditEntryData> buildEditEntries(
            com.simibubi.create.content.trains.schedule.Schedule sched) {
        return ManagementComputerRender.buildEditEntries(this, sched);
    }

    void applyScheduleEdit() {
        ManagementComputerRender.applyScheduleEdit(this);
    }

    void setDoorSideOnServer(String stationName, BlockPos stationPos,
                                      com.trainsystemutilities.blockentity.RailwayManagementBlockEntity.DoorSide side) {
        if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) return;
        var clientBE = be();
        BlockPos bePos = clientBE.getBlockPos();
        var dim = clientBE.getLevel().dimension();
        this.minecraft.getSingleplayerServer().execute(() -> {
            var sl = this.minecraft.getSingleplayerServer().getLevel(dim);
            if (sl == null) return;
            var sbe = sl.getBlockEntity(bePos);
            if (!(sbe instanceof ManagementComputerBlockEntity sBe)) return;
            BlockPos rmPos = sBe.getManagerPosForStation(stationName, stationPos);
            if (rmPos == null) return;
            var rbe = sl.getBlockEntity(rmPos);
            if (rbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                rm.setDoorOpenSide(side);
            }
        });
    }

    // mouseDragged / mouseReleased — MCSS now handles all drag sessions via
    // dragKey + onElementDrag/onElementDragEnd. Default super behavior suffices.


    /**
     * Transient overlay (= dropdown 系) のみを閉じる。editor / confirm 等の
     * persistent な popup は閉じない。外クリック時に MCSS 基底から呼ばれる。
     */
    @Override
    protected boolean closeTransientOverlays() {
        if (tabDropdown.isOpen())              { tabDropdown.close(); return true; }
        if (showMonitorColorSettings && monitorColorPopup.isDropdownOpen()) {
            monitorColorPopup.closeDropdown(); return true;
        }
        if (stationAssign.isOpen()) { stationAssign.close(); return true; }
        if (schedEditor.closeTransientSubPopups()) return true;
        return false;
    }

    /**
     * MCSS 基底 (JsonLayoutScreen) の ESC 挙動と統合: 開いている popup を 1 段階閉じる。
     * 何も開いていなければ false を返し、基底が onClose() に進む。
     */
    @Override
    protected boolean closeOpenOverlay() {
        if (showScheduleShare) { showScheduleShare = false; return true; }
        if (showColorPicker) { showColorPicker = false; return true; }
        if (tabDropdown.isOpen()) { tabDropdown.close(); return true; }
        if (schedEditor.handleEscape()) return true;
        if (symbolDelete.isOpen()) { symbolDelete.close(); return true; }
        if (symEditor.isOpen()) { symEditor.close(); return true; }
        if (showMonitorColorSettings) {
            showMonitorColorSettings = false;
            // 子状態 (target dropdown) も同時にクリア → 再 open 時に開きっぱなしバグ防止
            monitorColorPopup.resetTransientState();
            return true;
        }
        if (layoutEditor.isOpen()) { layoutEditor.close(); return true; }
        if (stationAssign.isOpen()) { stationAssign.close(); return true; }
        if (selectedTrainId != null) { selectedTrainId = null; return true; }
        if (scheduleSelectedTrainId != null) { scheduleSelectedTrainId = null; return true; }
        if (!selectedStationKey.isEmpty()) { selectedStationKey = ""; return true; }
        return false;
    }

    // === afterDialogRender: per-frame data refresh only ===
    // All visual elements are JSON-driven (frames as <div>, lists as <repeat>,
    // freely-painted regions as <canvas>). This hook now only feeds the data
    // hooks (getDynamic*) and canvas painters by refreshing snapshots once a
    // frame, never draws pixels itself.

    @Override
    protected void afterDialogRender(GuiGraphics g, int mouseX, int mouseY, float partial) {
        ManagementComputerRender.afterDialogRender(this, g, mouseX, mouseY, partial);
    }

    String paletteLabelFor(String type) {
        if (type == null) return "";
        for (String[] t : LAYOUT_TILE_TYPES) if (t[0].equals(type)) return tr(t[1]);
        return type;
    }

    /** 路線記号タブのタイル hover 詳細パネル (HoverTilePreview に hit-test/タイミング委譲)。 */
    void renderSymbolTileHoverPreview(GuiGraphics g, int mouseX, int mouseY) {
        ManagementComputerRender.renderSymbolTileHoverPreview(this, g, mouseX, mouseY);
    }

    /** #15: 路線マップ上の駅/列車アイコンをクリックしたときの動作。
     *  列車 → 列車詳細 popup を開く (selectedTrainId、 snapshot は定期更新で追従)。
     *  駅 → 駅タブに切り替えてその駅を選択 (詳細を表示)。 いずれも既存の行クリックと同じ選択機構。 */
    void handleMapHit(MapRenderer.MapHit hit) {
        if (hit.trainId() != null) {
            selectedTrainId = hit.trainId();
        } else if (hit.stationName() != null && hit.stationPos() != null) {
            tabs.switchTo("stations");
            selectedStationKey = ManagementComputerBlockEntity.stationKey(hit.stationName(), hit.stationPos());
        }
    }

    void refreshNetworkData() {
        ManagementComputerRender.refreshNetworkData(this);
    }

    void refreshSelectedTrainSnapshot(UUID id) {
        selectedSchedEntries.clear();
        selectedSchedCurrent = -1;
        // サーバー権威化 (MP): client の getTrainById は dedicated server で不安定なため使わず、
        // server が同期した BE cache (cachedTrains + SchedView) から明細を読む。
        // 列車が同期リストに無いときだけ選択解除 (= server 権威で本当に消滅した場合のみ)。
        var be = be();
        TrackNetworkScanner.TrainInfo info = null;
        for (var ti : be.getCachedTrains()) { if (id.equals(ti.id())) { info = ti; break; } }
        if (info == null) {
            if (id.equals(selectedTrainId)) selectedTrainId = null;
            if (id.equals(scheduleSelectedTrainId)) { scheduleSelectedTrainId = null; showScheduleShare = false; }
            return;
        }
        selectedTrainName = info.name() == null ? "" : info.name();
        selectedTrainCars = info.carriageCount();
        selectedTrainSpeed = Math.abs(info.speed()) * 20 * 3.6;
        // 速度は高頻度 broadcast (TrainPositionPayload, 5Hz) があればそちらを優先 (= 滑らかな更新)
        var livePos = com.trainsystemutilities.client.transit.TransitTerminalClientCache.trainPositions().get(id);
        if (livePos != null) selectedTrainSpeed = Math.abs(livePos.speed()) * 20 * 3.6;
        selectedTrainStation = info.currentStationName() == null ? "" : info.currentStationName();
        var sv = be.getSyncedSchedView(id);
        if (sv != null) {
            selectedSchedCurrent = sv.current();
            for (int i = 0; i < sv.entries().size() && i < 64; i++) selectedSchedEntries.add(sv.entries().get(i));
        }
    }


    // === Schedule editor rendering は ScheduleEditorController.drawBody へ抽出済 ===

    /** Layout editor の monitor preview canvas painter。
     *  モニターのアスペクト比を保ったプレビューを中央配置し、各パネルを矩形描画。
     *  drag 用に layoutPrev{X,Y,W,H} を更新 (canvas-local 原点)。 */
    void drawLayoutPreview(GuiGraphics g, int cx, int cy, int cw, int ch,
                                    int mouseX, int mouseY) {
        ManagementComputerRender.drawLayoutPreview(this, g, cx, cy, cw, ch, mouseX, mouseY);
    }

    /** LineSymbolPainter に委譲 (TSU 共有 util)。 */
    void drawSymbolBadge(GuiGraphics g, int x, int y, int size,
                                  com.trainsystemutilities.blockentity.LineSymbol sym) {
        LineSymbolPainter.draw(g, x, y, size, sym, this.font);
    }
}
