package com.trainsystemutilities.client.gui;
import belugalab.experience.controller.DragDropPalette;
import belugalab.experience.render.HoverTilePreview;

import belugalab.mcss3.anim.Animation;
import belugalab.mcss3.anim.Easing;
import belugalab.mcss3.screen.JsonLayoutHandler;
import belugalab.mcss3.screen.JsonLayoutScreen;
import belugalab.experience.controller.ColorPickerController;
import belugalab.experience.controller.ColorTargetController;
import belugalab.experience.controller.ScrollViewport;
import belugalab.experience.controller.OverlayController;
import belugalab.experience.controller.TabController;
import belugalab.mcss3.anim.Transition;
import belugalab.mcss3.draw.VectorRenderer;
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

    private static final String[][] TABS = {
            {"map",      "tsu.mc.tab_map"},
            {"trains",   "tsu.mc.tab_trains"},
            {"schedule", "tsu.mc.tab_schedule"},
            {"stations", "tsu.mc.tab_stations"},
            {"symbol",   "tsu.mc.tab_symbol"},
            {"tickets",  "tsu.mc.tab_tickets"},
    };

    private final TabController<String> tabs = new TabController<>(
            java.util.List.of("map", "trains", "schedule", "stations", "symbol", "tickets"), "map")
            .onSwitch(this::onTabSwitch);
    /** タブ切替 dropdown の open/close (= tab-dropdown trigger + tab-item-{map,train,sched,…} items)。
     *  item class が独自命名のため OverlayController。 */
    private final OverlayController tabDropdown = new OverlayController();
    private Boolean localMonitorEnabled = null;
    /** Monitor enable toggle (= local + cache + server sync)。
     *  alias で旧名 mc-monitor-toggle-track/knob にも対応。 */
    private final belugalab.experience.controller.ToggleSwitchController monitorToggle =
            new belugalab.experience.controller.ToggleSwitchController(
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
    private final belugalab.experience.controller.ToggleSwitchController exportAllToggle =
            new belugalab.experience.controller.ToggleSwitchController(
                    "export-all-toggle-track", "export-all-toggle-knob",
                    () -> be().isExportAll(),
                    v -> { if (this.minecraft != null && this.minecraft.gameMode != null)
                               this.minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId, 20600); });

    // === Map tab state (view は MapRenderer、network data は下記で screen 保持) ===
    private final MapRenderer mapRenderer = new MapRenderer();
    private List<TrackNetworkScanner.NodeInfo> mapNodes = new ArrayList<>();
    private List<TrackNetworkScanner.EdgeInfo> mapEdges = new ArrayList<>();
    private List<TrackNetworkScanner.StationInfo> mapStations = new ArrayList<>();
    private List<TrackNetworkScanner.SignalInfo> mapSignals = new ArrayList<>();
    private List<TrackNetworkScanner.TrainInfo> mapTrains = new ArrayList<>();
    private BlockPos lastNetworkScanPos = null;
    private long lastNetworkRefreshNano = 0L;
    private static final long NETWORK_REFRESH_INTERVAL_NS = 250_000_000L;

    // === Trains tab state (Phase 9-F: rows / detail popup are JSON-driven) ===
    private static final int TRAIN_LIST_MAX_VISIBLE = 4;
    /** trains タブ list scroll (= §4.19 ScrollViewport, activeWhen で trains タブのみ scrollbar 表示)。 */
    private final ScrollViewport trainScroll = new ScrollViewport(() -> trainsForList().size(), TRAIN_LIST_MAX_VISIBLE)
            .activeWhen(() -> tabs.is("trains"));
    private UUID selectedTrainId = null;
    // Schedule snapshot for the currently selected train (refreshed each frame while popup open)
    private final List<String> selectedSchedEntries = new ArrayList<>();
    private int selectedSchedCurrent = -1;
    private String selectedTrainName = "";
    private int selectedTrainCars = 0;
    private double selectedTrainSpeed = 0;
    private String selectedTrainStation = "";

    // === Train detail popup の 3D モデル renderer (god-class 分割で TrainModelRenderer へ抽出) ===
    private final TrainModelRenderer trainModel = new TrainModelRenderer();

    // === Schedule tab state ===
    private UUID scheduleSelectedTrainId = null;
    /** 書き出し payload を入力スロット1回の充填につき1度だけ送るためのフラグ。 */
    private boolean exportRequestSent = false;
    private long scheduleSelectNano = 0L;

    private static final long SCHEDULE_ANIM_NS = 280_000_000L;  // 280ms (slideInRight 同等)
    /** schedule タブ list scroll (= §4.19 ScrollViewport)。 */
    private final ScrollViewport schedListScroll = new ScrollViewport(() -> trainsForList().size(), TRAIN_LIST_MAX_VISIBLE);
    /** schedule 詳細の entries scroll (= §4.19 ScrollViewport)。 */
    private final ScrollViewport schedEntryScroll = new ScrollViewport(() -> selectedSchedEntries.size(), SCHED_VIEW_MAX);
    // 種別行を「戻る」直下に入れたぶん entries ビューポートが 84px→70px に縮んだ (2026-07-18)。
    // management-computer.json の sched-entries h と必ず一致させること。
    private static final int SCHED_VIEW_MAX = 5;

    // === 時刻表共有 (P3) ===
    private boolean showScheduleShare = false;
    private static final int SCHED_SHARE_VISIBLE = 7;
    private static final int SCHED_SHARE_AREA_Y = 40;
    private static final int SCHED_SHARE_AREA_H = 168;
    private final ScrollViewport schedShareScroll =
            new ScrollViewport(() -> schedShareCandidates().size(), SCHED_SHARE_VISIBLE);
    /** 共有 popup の per-row トグル (= 候補列車を follower にする ON/OFF)。 */
    private final belugalab.experience.controller.IndexedToggleSwitchController schedShareToggle =
            new belugalab.experience.controller.IndexedToggleSwitchController(
                    "sched-share-toggle-track", "sched-share-toggle-knob",
                    idx -> {
                        var c = schedShareCandidates();
                        if (idx < 0 || idx >= c.size() || scheduleSelectedTrainId == null) return false;
                        return scheduleSelectedTrainId.equals(be().getTimetableShareSource(c.get(idx).id()));
                    },
                    this::sendSchedShareToggle);

    // === Stations tab state ===
    private String selectedStationKey = "";  // stationScroll.activeWhen が参照するため前方宣言 (R4.20.2)
    /** stations タブ list scroll (= §4.19 ScrollViewport)。 */
    private final ScrollViewport stationScroll = new ScrollViewport(() -> stationsForList().size(), STATION_LIST_MAX_VISIBLE)
            .activeWhen(() -> tabs.is("stations") && selectedStationKey.isEmpty());  // §4.19 R4.19.2: リスト表示中のみ scrollbar
    // コンテナ高さ = 138, stride = 23 → 138/23 = 6 行ピッタリ。
    // 7 にすると 7 行目がコンテナ下端を超えてクリック不可、かつ total <= 7 ではスクロールも発動しないため
    // 「+ ボタンを押せず、スクロールもできない」状態になる。6 にすることで 7 駅以上ある場合にスクロールが
    // 確実に発動し、リスト全体にアクセスできる。
    private static final int STATION_LIST_MAX_VISIBLE = 6;
    private static final int STATION_LIST_TRACK_H = STATION_LIST_MAX_VISIBLE * 23; // = 138 (= container h、券売機タブと同寸)
    private static final int STATION_LIST_THUMB_H = 20;

    // === Tickets tab state (券売機: ネットワーク駅の販売可を取捨選択) ===
    private static final int TICKETS_MAX_VISIBLE = 6;
    private static final int TICKETS_LIST_TRACK_H = 138; // = TICKETS_MAX_VISIBLE * 23 (= リスト高)
    private static final int TICKETS_LIST_THUMB_H = 20;
    /** 駅一覧スクロール (= BelugaExperience 標準 ScrollViewport, §4.19)。
     *  activeWhen で券売機タブ表示中のみ scrollbar を出す (= 他タブへの残存を構造的に防止)。 */
    private final belugalab.experience.controller.ScrollViewport ticketScroll =
            new belugalab.experience.controller.ScrollViewport(
                    () -> ticketGroups().size(),
                    TICKETS_MAX_VISIBLE)
                    .activeWhen(() -> tabs.is("tickets"));
    /** タブを開いた瞬間に 1 回だけ駅一覧 + 販売可設定を要求するためのフラグ。 */
    private boolean ticketDataRequested = false;
    /** 切符タブに表示する駅グループ = 自ネットワーク (= server が解決した networkGroups) のみ。 未確立なら空。 */
    private java.util.List<com.trainsystemutilities.station.StationGroup> ticketGroups() {
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
    private final belugalab.experience.controller.IndexedToggleSwitchController ticketToggle =
            new belugalab.experience.controller.IndexedToggleSwitchController(
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
    private final ElectrificationDetailController edDetail = new ElectrificationDetailController();
    /** 電化詳細 popup の車両リスト描画 (god-class 分割で ElectrificationCarListRenderer へ抽出)。 */
    private final ElectrificationCarListRenderer edCarList = new ElectrificationCarListRenderer();

    private final SymbolEditorController symEditor = new SymbolEditorController();

    // === Symbol delete confirm popup (SymbolDeleteController に集約) ===
    private final SymbolDeleteController symbolDelete = new SymbolDeleteController();

    // === HSV color picker popup state ===
    private boolean showColorPicker = false;
    /** HSV カラーピッカー state (= §6.18 標準 ColorPickerController。 旧 pickerHue/Sat/Val + java.awt.Color を置換)。 */
    private final ColorPickerController picker = new ColorPickerController(0xFFFF0000);
    private final List<String> customColors = new ArrayList<>();

    // === Monitor color settings popup state (ColorTargetController に集約) ===
    private boolean showMonitorColorSettings = false;
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
    private final ColorTargetController monitorColorPopup =
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
    private final LayoutEditorController layoutEditor = new LayoutEditorController();
    // Preview canvas geometry (popup-local 座標で更新; drag handler が参照)
    private int layoutPrevX, layoutPrevY, layoutPrevW, layoutPrevH;
    /** タイル中ボタン押し込みで開くパネル機能別設定 popup (overlay2) の対象 index。 -1 = 閉。 */
    private int layoutSettingsIdx = -1;
    // 既存パネル移動用 drag state
    private float layoutDragStartPanelX, layoutDragStartPanelY;
    private double layoutDragStartMouseX, layoutDragStartMouseY;
    // パレットからの drag-and-drop state (DragDropPalette helper に集約)
    private final DragDropPalette<String> palette = new DragDropPalette<>();

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
    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    // === Station assign dropdown (StationAssignController に集約。assignBtnScreenX/Y は overlay 座標ゆえ screen 残置) ===
    private final StationAssignController stationAssign = new StationAssignController();
    // クリックされた + ボタンの screen 座標 (overlayDefaultPosition で参照)
    private int assignBtnScreenX = 0;
    private int assignBtnScreenY = 0;

    // === Station detail door direction buttons (Phase 9-F: rendered by repeat) ===
    private static final String[][] DOOR_OPTS = {
            {"NORTH", "tsu.mc.door_north"}, {"SOUTH", "tsu.mc.door_south"},
            {"EAST", "tsu.mc.door_east"}, {"WEST", "tsu.mc.door_west"},
            {"AUTO", "tsu.mc.door_auto"}, {"NONE", "tsu.mc.door_none"},
    };

    // === Schedule editor (god-class 分割 v2 で ScheduleEditorController へ抽出) ===
    private final ScheduleEditorController schedEditor = new ScheduleEditorController();

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
    private boolean wikiMode = false;
    private List<TrackNetworkScanner.TrainInfo> wikiTrains = null;
    /** id -> {schedule エントリ数, 電子式(1/0)}。 */
    private final java.util.Map<UUID, int[]> wikiTrainMeta = new java.util.HashMap<>();

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
        // Phase 5d FIX: dialog scale != 1.0 でも overlay が dialog 内の正しい相対位置に出るよう、
        // dialog 内座標は dialogLocalToScreenX/Y、長さ計算は dialogScaleAmount を使う。
        int dispW = dialogScaleAmount(overlayW);  // overlay 表示幅 (= overlayW * dialogScale)
        int dispH = dialogScaleAmount(overlayH);
        // タブ dropdown はサイドバーの dropdown trigger 直下に配置。
        if (tabDropdown.isOpen()) {
            return new int[]{dialogLocalToScreenX(14 + 2), dialogLocalToScreenY(35 + 18 + 18)};
        }
        // 列車詳細 popup はメイン GUI の右側 (V1 と同じ)。
        // 画面右端を超えるなら左側へフォールバック。
        if (selectedTrainId != null) {
            int x = dialogLocalToScreenX(this.imageWidth + 6);
            int y = dialogLocalToScreenY(10);
            if (x + dispW + 4 > this.width) x = dialogLocalToScreenX(-overlayW - 6);
            int maxY = this.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // スケジュール編集 popup は時刻表本体の表示を遮らないようメイン GUI 左側へ。
        if (schedEditor.isOpen()) {
            int x = dialogLocalToScreenX(-overlayW - 6);
            int y = dialogLocalToScreenY(10);
            if (x < 4) x = dialogLocalToScreenX(this.imageWidth + 6);
            int maxY = this.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // 路線記号エディタもメイン GUI 左側に展開 (V1 と同じ)。
        if (symEditor.isOpen()) {
            int x = dialogLocalToScreenX(-overlayW - 6);
            int y = dialogLocalToScreenY(10);
            if (x < 4) x = dialogLocalToScreenX(this.imageWidth + 6);
            int maxY = this.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // モニター色設定 popup はメイン GUI 右側に展開 (RM 色 popup と同じレイアウト)。
        // 中央に重ねるとメイン GUI のボタンがクリックできなくなるため。
        if (showMonitorColorSettings) {
            int x = dialogLocalToScreenX(this.imageWidth + 8);
            int y = dialogLocalToScreenY(10);
            // 画面右に入らなければ左へフォールバック
            if (x + dispW + 4 > this.width) x = dialogLocalToScreenX(-overlayW - 8);
            if (x < 4) x = 4;
            int maxY = this.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // 駅タブの路線記号 assign dropdown は + ボタン直下に配置。
        if (stationAssign.isOpen()) {
            // ボタン右端を popup 右端に揃える (button x は + ボタンの左端)
            int x = assignBtnScreenX + dialogScaleAmount(14) - dispW;
            int y = assignBtnScreenY + 2;
            if (x < 4) x = 4;
            if (x + dispW + 4 > this.width) x = this.width - dispW - 4;
            if (y + dispH + 4 > this.height) y = assignBtnScreenY - dialogScaleAmount(14) - dispH - 4;
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        // 他 popup は親クラス default (画面中央) で表示。
        return null;
    }

    /** sub-dropdown (overlay2) の表示位置:
     *  - 条件追加 dropdown は記録したボタン直下に出す (entry の真下)
     *  - 駅選択 dropdown は editor popup の右隣
     *  - 路線記号エディタ + color picker は editor の右隣
     */
    @Override
    protected int[] overlayDefaultPosition2(int overlayW, int overlayH) {
        // Phase 5d FIX: overlay2 もスケール対応 (= overlayW/H は論理サイズ、表示は overlay2Scale=dialogScale 倍)
        int dispW = dialogScaleAmount(overlayW);
        int dispH = dialogScaleAmount(overlayH);
        if (symEditor.isOpen() && showColorPicker) {
            // editor (overlay1) の右隣に color picker (overlay2) を配置
            int x = this.overlayX() + dialogScaleAmount(this.overlayW() + 6);
            int y = this.overlayY();
            if (x + dispW + 4 > this.width) x = this.overlayX() - dispW - 6;
            int maxY = this.height - dispH - 4;
            if (y > maxY) y = maxY;
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        if (schedEditor.isOpen() && schedEditor.addCondForEntry() >= 0) {
            int x = schedEditor.addCondBtnScreenX();
            int y = schedEditor.addCondBtnScreenY() + 2;  // ボタン下端の少し下
            // 画面端クリップ
            if (x + dispW + 4 > this.width) x = this.width - dispW - 4;
            if (x < 4) x = 4;
            if (y + dispH + 4 > this.height) y = schedEditor.addCondBtnScreenY() - dispH - 4;  // 下に入らなければ上に
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        if (schedEditor.isOpen() && schedEditor.isAddEntryOpen()) {
            // 「動作を追加」ボタン (popup-local 8,252,h14) の下に overlay2 を出す。
            int btnX = this.overlayX() + dialogScaleAmount(8);
            int btnY = this.overlayY() + dialogScaleAmount(252 + 14);
            int x0 = btnX;
            int y0 = btnY + 2;
            if (x0 + dispW + 4 > this.width) x0 = this.width - dispW - 4;
            if (x0 < 4) x0 = 4;
            int maxY0 = this.height - dispH - 4;
            if (y0 > maxY0) y0 = maxY0;
            return new int[]{x0, y0};
        }
        if (schedEditor.isOpen() && schedEditor.isStationDropdownOpen()) {
            // 「動作を追加」→「駅へ移動」等も同じボタンの下に出す (時刻表編集パネル内、枠外に飛ばさない)。
            int btnX = this.overlayX() + dialogScaleAmount(8);
            int btnY = this.overlayY() + dialogScaleAmount(252 + 14);
            int x = btnX;
            int y = btnY + 2;
            if (x + dispW + 4 > this.width) x = this.width - dispW - 4;
            if (x < 4) x = 4;
            int maxY = this.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // Phase 24: 電化詳細 popup は画面中央に配置 (列車詳細 popup の上に重ねる)
        if (edDetail.isOpen() && selectedTrainId != null) {
            int x = (this.width - dispW) / 2;
            int y = (this.height - dispH) / 2;
            if (x < 4) x = 4;
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        return null;
    }

    /** MCSS 基底の loadModResourceJson に委譲 (TsuLayouts.load 経由)。 */
    private static String loadResourceJson(String path) { return TsuLayouts.load(path); }

    private ManagementComputerBlockEntity be() { return getMenu().getBlockEntity(); }

    private ManagementComputerBlockEntity serverBE() {
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

    /** B2 (MP desync 修正): 全列車停止。 client 直 mutate でなく server BE の
     *  startAllTrainsStop (1 台ずつ順次停止方式) を payload で呼ぶ。 */
    private void startAllStop() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementComputerControlPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementComputerControlPayload.ACTION_STOP_ALL,
                        new java.util.UUID(0, 0)));
    }

    /** Symbol タブのタイル hover preview (HoverTilePreview に集約)。 */
    private final HoverTilePreview symbolHover = new HoverTilePreview();

    /** カラーピッカーで現在フォーカスされているテキストフィールド: "hex" | "rgb" | "hsl" | null。 */
    private String focusedField = null;
    /** フォーカス中のフィールドの編集バッファ。 */
    private String fieldEditBuffer = "";
    private long fieldFocusBlinkNano = 0L;

    @Override
    protected void containerTick() {
        super.containerTick();
    }

    /** カラーピッカーのテキストフィールドにフォーカスを移し、現在値を編集バッファに読み込む。 */
    private void focusField(String field) {
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
    private void resumeAllStop() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementComputerControlPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementComputerControlPayload.ACTION_RESUME_ALL,
                        new java.util.UUID(0, 0)));
    }

    /** server 同期される runtime.paused を見て、停止中の列車数を数える (MP-safe)。 */
    private int pausedTrainCount() {
        int n = 0;
        for (var ti : trainsForList()) {
            if (ti.id() == null) continue;
            if (be().hasSyncedPaused(ti.id())) n++;   // server 同期の paused フラグ (MP-safe)
        }
        return n;
    }

    /** 何かしら paused な列車がいるか? (server 同期される runtime.paused を見る)。 */
    private boolean anyTrainPaused() {
        return pausedTrainCount() > 0;
    }

    /** Phase 5d: memory card (slot 0) + monitor link card (slot 1) の両方が設定済みで、
     *  かつ memory card 経由のスキャン対象 (railway/track-network) が解決できる場合に true。
     *  bottom-row の オンライン/オフライン 表示と連動。 */
    private boolean isOnline() {
        var memCard = getMenu().getSlot(0).getItem();
        if (memCard.isEmpty()) return false;
        var monCard = getMenu().getSlot(1).getItem();
        if (monCard.isEmpty()) return false;
        var b = be();
        if (!b.isLinkedToMonitor()) return false;
        return b.getLinkedTrackNetworkPos() != null || b.getLinkedRailwayManagerPos() != null;
    }

    private boolean monitorEnabled() {
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

    private String currentTabLabel() {
        for (String[] t : TABS) {
            if (t[0].equals(tabs.current())) return tr(t[1]) + " ▾";
        }
        return tr(TABS[0][1]) + " ▾";
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        // 券売機タブ: タイトル (販売可 N/M) + 行ごとの駅名
        if (tabs.is("tickets")) {
            int rt = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            var groups = ticketGroups();
            for (String c : classes) {
                if ("tickets-title".equals(c)) {
                    int sell = 0;
                    for (var g : groups)
                        if (com.trainsystemutilities.station.TicketConfigClientCache.isSellable(g.id())) sell++;
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.tickets_title_fmt", sell, groups.size()).getString();
                }
                if ("ticket-row-name".equals(c) && rt >= 0) {
                    int realIdx = rt + ticketScroll.offset();
                    if (realIdx < groups.size()) {
                        String name = groups.get(realIdx).name();
                        int maxW = 132;
                        if (this.font.width(name) <= maxW) return name;
                        while (name.length() > 0 && this.font.width(name + "…") > maxW)
                            name = name.substring(0, name.length() - 1);
                        return name + "…";
                    }
                    return "";
                }
            }
        }
        if (layoutEditor.isOpen()) {
            for (String c : classes) {
                if ("layout-info".equals(c)) {
                    int monW = serverBE().getMonitorW();
                    int monH = serverBE().getMonitorH();
                    if (monW <= 0 || monH <= 0) return tr("tsu.mc.monitor_unlinked");
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.monitor_size_panels_fmt", monW, monH, layoutEditor.getLayout().size()).getString();
                }
                // タイル機能別設定 popup (overlay2) のタイトル / 値
                if ("pset-title".equals(c)) {
                    var pp = psetPanel();
                    return pp == null ? "" : net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.pset_title_fmt", pp.getType().getDisplayName()).getString();
                }
                var pp = psetPanel();
                if (pp != null) {
                    switch (c) {
                        case "pset-font-val":        return psetValText(pp.getFontSize());
                        case "pset-maptext-val":     return psetValText(pp.getMapTextSize());
                        case "pset-trainicon-val":   return psetValText(pp.getTrainIconSize());
                        case "pset-stationicon-val": return psetValText(pp.getStationIconSize());
                        case "pset-signalicon-val":  return psetValText(pp.getSignalIconSize());
                    }
                }
            }
        }
        // Monitor 色設定 popup texts — controller に委譲
        if (showMonitorColorSettings) {
            int rIdx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            String t = monitorColorPopup.resolveText(classes, rIdx);
            if (t != null) return t;
        }
        // Train detail popup repeat-context texts
        int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
        if (ri >= 0 && selectedTrainId != null) {
            for (String c : classes) {
                if ("train-detail-sched-item".equals(c)) {
                    if (ri < selectedSchedEntries.size()) {
                        boolean cur = (ri == selectedSchedCurrent);
                        return (cur ? "▶ " : "○ ") + selectedSchedEntries.get(ri);
                    }
                }
            }
        }
        // Station assign dropdown items (controller 委譲)
        if (ri >= 0 && stationAssign.isOpen()) {
            String t = stationAssign.resolveItemText(classes, ri, () -> serverBE().getLineSymbols());
            if (t != null) return t;
        }
        // Symbol editor field values (controller 委譲)
        if (symEditor.isOpen()) {
            String t = symEditor.resolveText(classes);
            if (t != null) return t;
        }
        // Symbol delete confirm (controller 委譲)
        if (symbolDelete.isOpen()) {
            String t = symbolDelete.resolveText(classes, () -> serverBE().getLineSymbols());
            if (t != null) return t;
        }
        // Station assign title (per target, controller 委譲)
        if (stationAssign.isOpen()) {
            String t = stationAssign.resolveTitleText(classes);
            if (t != null) return t;
        }
        // HSV picker hex / RGB / HSL display (フォーカス中はバッファ + caret を返す)
        if (showColorPicker) {
            // 0.5 秒周期の caret 点滅
            boolean caretOn = ((System.nanoTime() - fieldFocusBlinkNano) / 500_000_000L) % 2 == 0;
            String caret = caretOn ? "_" : " ";
            for (String c : classes) {
                if ("cp-hex".equals(c)) return currentPickerHex();
                if ("cp-info-hex".equals(c)) {
                    if ("hex".equals(focusedField)) return fieldEditBuffer + caret;
                    return currentPickerHex().toUpperCase();
                }
                if ("cp-info-rgb".equals(c)) {
                    if ("rgb".equals(focusedField)) return fieldEditBuffer + caret;
                    int rgb = ColorPickerController.hsvToRgb(picker.hue(), picker.saturation(), picker.value());
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    return r + ", " + g + ", " + b;
                }
                if ("cp-info-hsl".equals(c)) {
                    if ("hsl".equals(focusedField)) return fieldEditBuffer + caret;
                    float h = picker.hue() * 360f;
                    float l = picker.value() * (1f - picker.saturation() / 2f);
                    float s = (l == 0f || l == 1f) ? 0f
                            : (picker.value() - l) / Math.min(l, 1f - l);
                    return Math.round(h) + "°, " + Math.round(s * 100) + "%, "
                            + Math.round(l * 100) + "%";
                }
            }
        }
        // Symbol tab: dynamic title + per-tile name (repeat-context)
        if (tabs.is("symbol")) {
            for (String c : classes) {
                if ("sym-tab-title".equals(c)) {
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.line_symbols_fmt", serverBE().getLineSymbols().size()).getString();
                }
                if ("sym-tile-name".equals(c) && ri >= 0) {
                    var syms = serverBE().getLineSymbols();
                    if (ri < syms.size()) {
                        String name = syms.get(ri).getName();
                        if (name.isEmpty()) return "";
                        // Truncate to fit tile width (36 - 2)
                        int maxW = 34;
                        if (this.font.width(name) <= maxW) return name;
                        while (name.length() > 0 && this.font.width(name + "…") > maxW)
                            name = name.substring(0, name.length() - 1);
                        return name + "…";
                    }
                    return "";
                }
            }
        }
        // Trains tab list rows (repeat context, with trainScroll offset)
        if (tabs.is("trains") && ri >= 0) {
            var live = trainsForList();
            int realIdx = ri + trainScroll.offset();
            if (realIdx < live.size()) {
                var info = live.get(realIdx);
                var liveOpt = trySafeLiveTrain(info.id());
                for (String c : classes) {
                    if ("train-row-name".equals(c)) return info.name();
                    if ("train-row-status".equals(c)) {
                        String station = (String) liveOpt[0];
                        double speed = (Double) liveOpt[1];
                        return !station.isEmpty() ? "█ " + station
                                : String.format("%.0f km/h", speed);
                    }
                    if ("train-row-cars".equals(c)) {
                        return net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.cars_unit_fmt", (Integer) liveOpt[2]).getString();
                    }
                    if ("train-row-dest".equals(c)) {
                        return (String) liveOpt[3];
                    }
                }
            }
        }
        // Schedule tab list rows
        if (tabs.is("schedule") && scheduleSelectedTrainId == null && ri >= 0) {
            var live = trainsForList();
            int realIdx = ri + schedListScroll.offset();
            if (realIdx < live.size()) {
                var info = live.get(realIdx);
                String prefix = "";
                int entries = 0;
                boolean elec = false;
                if (wikiMode) {
                    int[] meta = wikiTrainMeta.get(info.id());
                    if (meta != null) { entries = meta[0]; elec = meta[1] == 1; }
                } else {
                    // server 同期フラグ + SchedView から (MP-safe; client getTrainById は使わない)
                    if (be().hasSyncedPaused(info.id())) prefix = "⏸ ";
                    var sv = be().getSyncedSchedView(info.id());
                    if (sv != null) entries = sv.entries().size();
                    elec = isElectronicTimetable(info.id());
                }
                for (String c : classes) {
                    if ("sched-row-type".equals(c)) {
                        return TrainTypes.localize(be().getSyncedTrainType(info.id()));
                    }
                    if ("sched-row-name".equals(c)) return prefix + info.name();
                    if ("sched-row-entries".equals(c)) {
                        UUID shareSrc = be().getTimetableShareSource(info.id());
                        if (shareSrc != null) {
                            return net.minecraft.network.chat.Component.translatable(
                                    "tsu.mc.tt_shared_from_fmt", trainNameById(shareSrc)).getString();
                        }
                        if (entries <= 0) return tr("tsu.mc.tt_state_none");
                        return tr(elec ? "tsu.mc.tt_state_electronic" : "tsu.mc.tt_state_regular")
                                + " · " + net.minecraft.network.chat.Component.translatable("tsu.mc.entries_unit_fmt", entries).getString();
                    }
                }
            }
        }
        // Schedule tab detail (no repeat context except for entries)
        if (tabs.is("schedule") && scheduleSelectedTrainId != null) {
            for (String c : classes) {
                if ("sched-type-val".equals(c)) return TrainTypes.localizeForEditor(selectedTrainTypeCode());
                if ("sched-detail-name".equals(c)) return selectedTrainName.isEmpty() ? "?" : selectedTrainName;
                if ("sched-detail-info".equals(c)) {
                    String status = !selectedTrainStation.isEmpty() ? selectedTrainStation
                            : (isSelectedSchedTrainPaused() ? tr("tsu.mc.train_stopped") : tr("tsu.mc.train_running"));
                    String base = net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_summary_fmt",
                            selectedTrainCars, selectedTrainSpeed, status).getString();
                    UUID src = be().getTimetableShareSource(scheduleSelectedTrainId);
                    if (src != null) {
                        base += " · " + net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.sched_shared_banner_fmt", trainNameById(src)).getString();
                    }
                    return base;
                }
                if ("sched-pause".equals(c)) {
                    return tr(isSelectedSchedTrainPaused() ? "tsu.mc.train_resume_btn" : "tsu.mc.train_stop_btn");
                }
                if ("sched-edit".equals(c)) {
                    if (be().isTimetableFollower(scheduleSelectedTrainId))
                        return tr("tsu.mc.sched_edit_label_shared");
                    if (selectedSchedTrainHasSchedule() && !selectedSchedTrainIsElectronic())
                        return tr("tsu.mc.sched_edit_label_regular");
                    if (!selectedSchedTrainHasConductor())
                        return tr("tsu.mc.sched_edit_label_conductor");
                    return tr("tsu.mc.sched_edit_label_edit");
                }
                if ("sched-cyclic".equals(c)) {
                    Boolean cyc = selectedSchedCyclic();
                    if (cyc == null) return "";
                    return tr(cyc ? "tsu.mc.sched_cyclic_loop" : "tsu.mc.sched_cyclic_oneway");
                }
                if ("sched-share-title".equals(c)) {
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.sched_share_title_fmt",
                            selectedTrainName.isEmpty() ? "?" : selectedTrainName).getString();
                }
            }
            // Entries repeat (詳細) / 共有候補 repeat (popup)
            if (ri >= 0) {
                if (showScheduleShare) {
                    int sIdx = ri + schedShareScroll.offset();
                    var cand = schedShareCandidates();
                    if (sIdx < cand.size()) {
                        for (String c : classes) {
                            if ("sched-share-train-name".equals(c)) return cand.get(sIdx).name();
                        }
                    }
                } else {
                    int realIdx = ri + schedEntryScroll.offset();
                    if (realIdx < selectedSchedEntries.size()) {
                        boolean cur = (realIdx == selectedSchedCurrent);
                        String prefix = (cur ? "▶ " : "  ") + (realIdx + 1) + ". ";
                        for (String c : classes) {
                            if ("sched-entry-row".equals(c)) return prefix + selectedSchedEntries.get(realIdx);
                        }
                    }
                }
            }
        }
        // Stations tab list rows
        if (tabs.is("stations") && selectedStationKey.isEmpty() && ri >= 0) {
            var stations = stationsForList();
            int realIdx = ri + stationScroll.offset();
            if (realIdx < stations.size()) {
                var st = stations.get(realIdx);
                for (String c : classes) {
                    if ("station-row-name".equals(c)) {
                        String name = st.name();
                        int maxW = 128;
                        if (this.font.width(name) <= maxW) return name;
                        while (name.length() > 0 && this.font.width(name + "…") > maxW)
                            name = name.substring(0, name.length() - 1);
                        return name + "…";
                    }
                    if ("station-row-pos".equals(c)) {
                        return "(" + st.position().getX() + "," + st.position().getZ() + ")";
                    }
                    if ("station-row-link".equals(c)) {
                        return be().hasManagerForStation(st.name(), st.position()) ? "●" : "○";
                    }
                    if ("station-row-assign".equals(c)) {
                        var sym = be().getSymbolForStation(st.name(), st.position());
                        return sym != null ? "✎" : "+";
                    }
                }
            }
        }
        // Stations tab detail
        if (tabs.is("stations") && !selectedStationKey.isEmpty()) {
            var s = selectedStation();
            if (s != null) {
                var sym = be().getSymbolForStation(s.name(), s.position());
                BlockPos rmPos = be().getManagerPosForStation(s.name(), s.position());
                for (String c : classes) {
                    if ("station-detail-name".equals(c)) return "🚉 " + s.name();
                    if ("station-detail-pos".equals(c)) {
                        return net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.station_pos_fmt",
                                s.position().getX(), s.position().getY(), s.position().getZ()).getString();
                    }
                    if ("station-detail-rm".equals(c)) {
                        return rmPos != null
                                ? net.minecraft.network.chat.Component.translatable(
                                    "tsu.mc.station_rm_linked_fmt",
                                    rmPos.getX(), rmPos.getY(), rmPos.getZ()).getString()
                                : tr("tsu.mc.station_rm_unlinked");
                    }
                    if ("station-detail-monitor".equals(c)) {
                        if (rmPos == null) return "";
                        try {
                            if (be().getLevel() != null) {
                                var bbe = be().getLevel().getBlockEntity(rmPos);
                                if (bbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                                    int groups = rm.getLinkedMonitorGroupCount();
                                    return groups > 0
                                            ? net.minecraft.network.chat.Component.translatable(
                                                "tsu.mc.station_monitor_groups_fmt", groups).getString()
                                            : tr("tsu.mc.station_monitor_unlinked");
                                }
                            }
                        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
                        return "";
                    }
                    if ("station-detail-symbol".equals(c)) {
                        return sym != null
                                ? net.minecraft.network.chat.Component.translatable(
                                    "tsu.mc.station_symbol_fmt", sym.getLetters() + sym.getNumberStr(),
                                    sym.getName().isEmpty() ? "" : " (" + sym.getName() + ")").getString()
                                : tr("tsu.mc.station_symbol_none");
                    }
                }
            }
        }
        // Door direction button labels (repeat context)
        if (ri >= 0 && ri < DOOR_OPTS.length) {
            for (String c : classes) {
                if ("door-btn".equals(c)) return tr(DOOR_OPTS[ri][1]);
            }
        }
        // Schedule editor の dynamic text (controller 委譲)
        {
            String st = schedEditor.resolveText(classes, ri, this::schedStationNames, this.font);
            if (st != null) return st;
        }
        for (String c : classes) {
            switch (c) {
                case "tab-dropdown":
                    return currentTabLabel();
                case "mc-monitor-status-label":
                    return tr(monitorEnabled() ? "tsu.mc.monitor_on" : "tsu.mc.monitor_off");
                case "monitor-label":
                    return tr("tsu.mc.monitor_label");
                case "mc-monitor-info":
                    // online = card 入っていてリンク先が解決できる、それ以外は offline
                    return tr(isOnline() ? "tsu.mc.online" : "tsu.mc.offline");
                case "stat-station":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.stat_station_fmt", serverBE().getCachedStationCount()).getString();
                case "stat-train":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.stat_train_fmt", serverBE().getCachedTrainCount()).getString();
                case "stat-signal":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.stat_signal_fmt", serverBE().getCachedSignalCount()).getString();
                case "sched-tab-stat":
                    // 通常モード時に右側に表示 (例: "5列車 / 3駅")
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.sched_tab_stat_fmt", trainsForList().size(), stationsForList().size()).getString();
                case "sched-stopall-label": {
                    int paused = pausedTrainCount();
                    return paused > 0
                            ? net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.all_stop_active_fmt", paused).getString()
                            : tr("tsu.mc.all_stop_done");
                }
                case "tab-content-text":
                case "tab-content-hint":
                    // Hide placeholder text on tabs that have a real implementation
                    return "";
                case "train-detail-title":
                    return selectedTrainName.isEmpty() ? tr("tsu.mc.train_default_name") : selectedTrainName;
                case "train-detail-cars":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_cars_fmt", String.valueOf(selectedTrainCars)).getString();
                case "train-detail-speed":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_speed_fmt", String.format("%.1f", selectedTrainSpeed)).getString();
                case "train-detail-station":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_current_fmt",
                            selectedTrainStation.isEmpty() ? tr("tsu.mc.train_running") : selectedTrainStation).getString();
                case "train-detail-sched-header":
                    return selectedSchedEntries.isEmpty() ? tr("tsu.mc.train_sched_empty") : tr("tsu.mc.train_schedule_label");
            }
        }
        // Phase 24: 電化詳細 popup の動的テキスト (controller 委譲)
        if (edDetail.isOpen() && selectedTrainId != null) {
            String t = edDetail.resolveText(classes, selectedTrainId);
            if (t != null) return t;
        }
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("monitor-knob-x".equals(key)) return monitorToggle.knobX(defaultValue);
        if ("export-all-knob-x".equals(key)) return exportAllToggle.knobX(defaultValue);
        // hint-knob-x は JsonLayoutEngine が HintToggleHelper にルート (解決不要)
        if ("sched-count".equals(key)) {
            return Math.min(8, selectedSchedEntries.size());
        }
        if ("sched-share-count".equals(key)) {
            return showScheduleShare ? schedShareScroll.rowCount() : 0;
        }
        if ("sched-share-toggle-knob-x".equals(key)) {
            return schedShareToggle.knobXFor(schedShareRealIdx(), defaultValue);
        }
        if ("sched-share-scroll-thumb-y".equals(key)) {
            return schedShareScroll.thumbY(SCHED_SHARE_AREA_Y, SCHED_SHARE_AREA_H, schedShareThumbH());
        }
        if ("sched-share-scroll-thumb-h".equals(key)) {
            return schedShareThumbH();
        }
        if (showMonitorColorSettings) {
            Integer n = monitorColorPopup.resolveNumber(key);
            if (n != null) return n;
        }
        if ("assign-count".equals(key)) {
            return Math.min(12, serverBE().getLineSymbols().size());
        }
        if ("preset-count".equals(key)) {
            return SymbolEditorController.SYMBOL_COLOR_PRESETS.length;
        }
        if ("cp-pal-count".equals(key)) {
            return customColors.size();
        }
        if ("sym-edit-custom-count".equals(key)) {
            return customColors.size();
        }
        if ("sym-grid-count".equals(key)) {
            return serverBE().getLineSymbols().size();
        }
        if ("trains-row-count".equals(key)) {
            return trainScroll.rowCount();
        }
        if ("sched-list-count".equals(key)) {
            return schedListScroll.rowCount();
        }
        if ("sched-entries-count".equals(key)) {
            return schedEntryScroll.rowCount();
        }
        if ("stations-row-count".equals(key)) {
            return stationScroll.rowCount();
        }
        if ("tickets-row-count".equals(key)) {
            return ticketScroll.rowCount();
        }
        if ("ticket-toggle-knob-x".equals(key)) {
            int rt = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (rt >= 0) return ticketToggle.knobXFor(rt, defaultValue);
        }
        if ("tickets-scrollbar-thumb-y".equals(key)) {
            return ticketScroll.thumbY(defaultValue, TICKETS_LIST_TRACK_H - 2, TICKETS_LIST_THUMB_H);
        }
        if ("tickets-scrollbar-thumb-h".equals(key)) return TICKETS_LIST_THUMB_H;
        if ("stations-scrollbar-thumb-y".equals(key)) {
            return stationScroll.thumbY(defaultValue, STATION_LIST_TRACK_H - 2, STATION_LIST_THUMB_H);
        }
        if ("stations-scrollbar-thumb-h".equals(key)) return STATION_LIST_THUMB_H;
        if ("door-count".equals(key)) {
            return DOOR_OPTS.length;
        }
        {
            Integer sn = schedEditor.resolveNumber(key, this::schedStationNames);
            if (sn != null) return sn;
        }
        if ("sched-station-thumb-y".equals(key)) return schedEditor.stationThumbY(defaultValue);
        if ("sched-station-thumb-h".equals(key)) return schedEditor.stationThumbH();
        // Trains list scrollbar (track + thumb dynamic y/h)
        if ("trains-scrollbar-thumb-y".equals(key)) {
            return trainScroll.thumbY(defaultValue, TRAIN_LIST_TRACK_H - 2, TRAIN_LIST_THUMB_H);
        }
        if ("trains-scrollbar-thumb-h".equals(key)) return TRAIN_LIST_THUMB_H;
        if ("sched-entries-thumb-y".equals(key)) {
            return schedEntryScroll.thumbY(defaultValue, SCHED_ENTRIES_TRACK_H - 2, SCHED_ENTRIES_THUMB_H);
        }
        if ("sched-entries-thumb-h".equals(key)) return SCHED_ENTRIES_THUMB_H;
        return null;
    }

    private static final int TRAIN_LIST_TRACK_H = (28 + 2) * 4;  // (TRAINS_ROW_H+2) * TRAINS_MAX
    private static final int TRAIN_LIST_THUMB_H = 20;
    private static final int SCHED_ENTRIES_TRACK_H = 70;  // sched-entries repeat h (= SCHED_VIEW_MAX(5) * stride 14)
    private static final int SCHED_ENTRIES_THUMB_H = 18;

    /** 駅タブのリスト表示用に「優先度順」の駅一覧を返す:
     *  client-side scan (mapStations) を最優先、空なら server cache にフォールバック。
     *  これにより mapTrains/mapStations と同じ瞬間データで一致する。 */
    private List<TrackNetworkScanner.StationInfo> stationsForList() {
        if (!mapStations.isEmpty()) return mapStations;
        return be().getCachedStations();
    }

    /** 列車一覧用の統合リスト。scan結果を優先し、server cacheで補完する。 */
    private List<TrackNetworkScanner.TrainInfo> trainsForList() {
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

    private void clampTrainListScrolls() {
        trainScroll.clamp();
        schedListScroll.clamp();
    }

    private List<String> schedStationNames() {
        java.util.LinkedHashSet<String> nameSet = new java.util.LinkedHashSet<>();
        for (var s : mapStations) nameSet.add(s.name());
        if (nameSet.isEmpty()) for (var s : be().getCachedStations()) nameSet.add(s.name());
        return new ArrayList<>(nameSet);
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        if (showMonitorColorSettings) {
            Boolean b = monitorColorPopup.resolveBool(key);
            if (b != null) return b;
        }
        if ("cp-pal-empty".equals(key)) return customColors.isEmpty();
        if ("sym-edit-has-custom".equals(key))
            return symEditor.isOpen() && !customColors.isEmpty();
        // タイル機能別設定 popup: ROUTE_MAP は 4 サイズ行、それ以外は文字サイズ行のみ
        if ("pset-map-visible".equals(key)) {
            var pp = psetPanel();
            return pp != null && pp.getType()
                    == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP;
        }
        if ("pset-font-visible".equals(key)) {
            var pp = psetPanel();
            return pp != null && pp.getType()
                    != com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP;
        }
        // Phase 24: 電化ボタンは popup が開いていれば常に表示 (= 非電化列車でも
        // クリック時に詳細スクリーン側で「未同期」状態を表示する)。
        // 同期遅延やキャッシュ未到達でボタンが消える UX を防止。
        if ("electrification-btn-visible".equals(key)) {
            return selectedTrainId != null;
        }
        if ("trains-scrollbar-visible".equals(key)) {
            return trainScroll.needsScrollbar();  // activeWhen(trains) 内包 (§4.19 R4.19.2)
        }
        if ("sched-entries-scrollbar-visible".equals(key)) {
            return tabs.is("schedule") && scheduleSelectedTrainId != null && schedEntryScroll.needsScrollbar();
        }
        // Tab visibility (one-shot per tab)
        if ("tab-symbol-active".equals(key)) return tabs.is("symbol");
        if ("tab-symbol-active-with-items".equals(key))
            return tabs.is("symbol") && !serverBE().getLineSymbols().isEmpty();
        if ("tab-symbol-active-empty".equals(key))
            return tabs.is("symbol") && serverBE().getLineSymbols().isEmpty();
        if ("tab-map-active".equals(key)) return tabs.is("map");
        // Trains tab
        int liveTrainCount = trainsForList().size();
        if ("tab-trains-active".equals(key)) return tabs.is("trains");
        if ("tab-trains-empty".equals(key)) return tabs.is("trains") && liveTrainCount == 0;
        if ("tab-trains-list".equals(key)) return tabs.is("trains") && liveTrainCount > 0;
        // Schedule tab
        if ("tab-sched-empty".equals(key))
            return tabs.is("schedule") && scheduleSelectedTrainId == null && liveTrainCount == 0;
        if ("tab-sched-list".equals(key))
            return tabs.is("schedule") && scheduleSelectedTrainId == null && liveTrainCount > 0;
        if ("tab-sched-list-rows".equals(key))
            return tabs.is("schedule") && scheduleSelectedTrainId == null && liveTrainCount > 0;
        if ("tab-sched-detail".equals(key))
            return tabs.is("schedule") && scheduleSelectedTrainId != null;
        if ("sched-share-btn-visible".equals(key))
            return tabs.is("schedule") && scheduleSelectedTrainId != null
                    && selectedSchedTrainIsElectronic() && selectedSchedTrainHasSchedule()
                    && !be().isTimetableFollower(scheduleSelectedTrainId);
        if ("sched-share-scroll-visible".equals(key))
            return showScheduleShare && schedShareScroll.needsScrollbar();
        if ("sched-share-empty".equals(key))
            return showScheduleShare && schedShareCandidates().isEmpty();
        if ("tab-sched-edit-visible".equals(key))
            return tabs.is("schedule") && scheduleSelectedTrainId != null && isSelectedSchedTrainPaused();
        // editor 内 inline + entry dropdown の visibility + station-pick empty (controller 委譲)
        {
            Boolean sb = schedEditor.resolveBool(key, this::schedStationNames);
            if (sb != null) return sb;
        }
        // 全列車停止コントロールバー: list mode の時のみ表示。
        // - paused が無い → "停止" ボタン表示
        // - paused が存在 → "再開" ボタン表示
        if ("sched-stopall-show-stop".equals(key)) {
            return tabs.is("schedule") && scheduleSelectedTrainId == null
                    && !trainsForList().isEmpty() && !anyTrainPaused();
        }
        if ("sched-stopall-show-resume".equals(key)) {
            return tabs.is("schedule") && scheduleSelectedTrainId == null
                    && anyTrainPaused();
        }
        if ("sched-entries-empty".equals(key)) return selectedSchedEntries.isEmpty();
        // Stations tab — client-side scan を優先 (server cache の同期遅延を回避)
        var stationList = stationsForList();
        boolean inStations = tabs.is("stations");
        if ("tab-stations-empty".equals(key))
            return inStations && selectedStationKey.isEmpty() && stationList.isEmpty();
        if ("tab-stations-list".equals(key))
            return inStations && selectedStationKey.isEmpty();
        if ("tab-stations-list-rows".equals(key))
            return inStations && selectedStationKey.isEmpty() && !stationList.isEmpty();
        if ("stations-scrollbar-visible".equals(key))
            return stationScroll.needsScrollbar();  // activeWhen(stations & list) 内包 (§4.19 R4.19.2/R4.19.3)
        if ("tab-stations-detail".equals(key))
            return inStations && !selectedStationKey.isEmpty();
        if ("tab-stations-detail-rm".equals(key))
            return inStations && !selectedStationKey.isEmpty() && selectedStationHasRMBE();
        // 券売機タブ (ネットワーク駅の販売可選択)
        boolean inTickets = tabs.is("tickets");
        int ticketGroupCount = com.trainsystemutilities.station.StationGroupClientCache.all().size();
        if ("tab-tickets-list".equals(key)) return inTickets;
        if ("tab-tickets-empty".equals(key)) return inTickets && ticketGroupCount == 0;
        if ("tab-tickets-list-rows".equals(key)) return inTickets && ticketGroupCount > 0;
        if ("tickets-scrollbar-visible".equals(key)) return ticketScroll.needsScrollbar();
        // Procedural fallback placeholder — only for tabs not yet JSON-migrated (none left)
        if ("tab-procedural-active".equals(key)) return false;
        return null;
    }

    private boolean isSelectedSchedTrainPaused() {
        if (scheduleSelectedTrainId == null) return false;
        if (wikiMode) return true;
        return be().hasSyncedPaused(scheduleSelectedTrainId);   // server 同期の paused (MP-safe)
    }

    /** trainId が電子式時刻表 (管理用コンピューター管理) か。 同期済みクライアント BE 参照 (MP 対応)。 */
    private boolean isElectronicTimetable(java.util.UUID trainId) {
        try { return trainId != null && be().isElectronicTimetable(trainId); }
        catch (Exception ignored) { return false; }
    }

    /** 選択中の列車が Create schedule を持つか。 server 計算の同期フラグ参照 (MP 対応)。 */
    private boolean selectedSchedTrainHasSchedule() {
        try { return scheduleSelectedTrainId != null && be().hasSyncedSchedule(scheduleSelectedTrainId); }
        catch (Exception ignored) { return false; }
    }

    private boolean selectedSchedTrainIsElectronic() { return isElectronicTimetable(scheduleSelectedTrainId); }

    /** 選択中の列車に運転士が乗っているか。 client では Conductor 状態が正確に取れないため
     *  server 計算の同期フラグを使う (SP/MP 共通)。 */
    private boolean selectedSchedTrainHasConductor() {
        try { return scheduleSelectedTrainId != null && be().hasSyncedConductor(scheduleSelectedTrainId); }
        catch (Exception ignored) { return false; }
    }

    /** 編集可能か: 停止中 + 共有追従中でない + 通常時刻表でない (電子式/なし) + 運転士あり。 */
    private boolean selectedSchedEditable() {
        if (!isSelectedSchedTrainPaused()) return false;
        if (be().isTimetableFollower(scheduleSelectedTrainId)) return false; // 共有追従中は読み取り専用
        if (selectedSchedTrainHasSchedule() && !selectedSchedTrainIsElectronic()) return false; // 通常時刻表は編集不可
        return selectedSchedTrainHasConductor();
    }

    /** 共有候補の列車一覧 (= 同一ネットワークの列車から、source 自身と「共有元」列車を除く)。 */
    private java.util.List<TrackNetworkScanner.TrainInfo> schedShareCandidates() {
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
    private int schedShareRealIdx() {
        return belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex() + schedShareScroll.offset();
    }

    private int schedShareThumbH() {
        int total = schedShareCandidates().size();
        if (total <= SCHED_SHARE_VISIBLE) return SCHED_SHARE_AREA_H;
        return Math.max(8, SCHED_SHARE_AREA_H * SCHED_SHARE_VISIBLE / total);
    }

    /** UUID から列車表示名を解決 (= 共有元バナー / タイル表示用)。 */
    private String trainNameById(UUID id) {
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
    private String selectedTrainTypeCode() {
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
    private void cycleSelectedTrainType(int dir) {
        if (scheduleSelectedTrainId == null) return;
        String next = TrainTypes.cycle(selectedTrainTypeCode(), dir);
        pendingTypeTrainId = scheduleSelectedTrainId;
        pendingTypeCode = next;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.SetTrainTypePayload(
                        be().getBlockPos(), scheduleSelectedTrainId, TrainTypes.indexOf(next)));
    }

    private TrackNetworkScanner.StationInfo selectedStation() {
        if (selectedStationKey.isEmpty()) return null;
        for (var s : be().getCachedStations()) {
            if (selectedStationKey.equals(ManagementComputerBlockEntity.stationKey(s.name(), s.position()))) {
                return s;
            }
        }
        return null;
    }

    private boolean selectedStationHasRMBE() {
        var s = selectedStation();
        if (s == null) return false;
        return be().getManagerPosForStation(s.name(), s.position()) != null;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        // Monitor 色設定 popup の dynamic color
        if (showMonitorColorSettings) {
            Integer c = monitorColorPopup.resolveColor(key);
            if (c != null) return c;
        }
        // === hint-toggle-bg / hint-knob-bg は JsonLayoutEngine が HintToggleHelper にルート ===
        switch (key) {
            case "monitor-toggle-bg":    return monitorToggle.trackBg();
            case "monitor-knob-bg":      return monitorToggle.knobBg();
            case "monitor-status-color": return monitorToggle.statusText();
            case "export-all-toggle-bg": return exportAllToggle.trackBg();
            case "export-all-knob-bg":   return exportAllToggle.knobBg();
            case "owner-border":
                return belugalab.tsu.api.OwnerAccess.ringColor(be().isPrivateMode());
            case "mc-monitor-status-dot-bg":
            case "mc-monitor-info-color":
                return isOnline() ? 0xFF4caf50 : 0xFFef5350;
        }
        int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
        // 券売機タブ: per-row 販売可トグル
        if ("ticket-toggle-track-bg".equals(key) && ri >= 0) return ticketToggle.trackBgFor(ri);
        if ("ticket-toggle-knob-bg".equals(key) && ri >= 0) return ticketToggle.knobBgFor(ri);
        // Symbol editor preset palette (chip bg + selection border)
        if ("preset-bg".equals(key) && ri >= 0 && ri < SymbolEditorController.SYMBOL_COLOR_PRESETS.length) {
            return parseHexColor(SymbolEditorController.SYMBOL_COLOR_PRESETS[ri]);
        }
        if ("preset-border".equals(key) && ri >= 0) {
            return ri == symEditor.getColorIdx() ? 0xFFFFFFFF : 0xFF555555;
        }
        // HSV color picker
        if ("cp-preview-bg".equals(key)) {
            return picker.argb();
        }
        if ("cp-chip-bg".equals(key) && ri >= 0 && ri < customColors.size()) {
            return parseHexColor(customColors.get(ri));
        }
        if ("cp-chip-border".equals(key) && ri >= 0 && ri < customColors.size()) {
            return customColors.get(ri).equalsIgnoreCase(symEditor.getColor()) ? 0xFFFFFFFF : 0xFF555555;
        }
        if ("sym-edit-custom-bg".equals(key) && ri >= 0 && ri < customColors.size()) {
            return parseHexColor(customColors.get(ri));
        }
        if ("sym-edit-custom-border".equals(key) && ri >= 0 && ri < customColors.size()) {
            return customColors.get(ri).equalsIgnoreCase(symEditor.getColor()) ? 0xFFFFFFFF : 0xFF555555;
        }
        // Schedule detail pause button colors (paused = green, running = red)
        boolean paused = isSelectedSchedTrainPaused();
        if ("sched-pause-bg".equals(key)) return paused ? 0xFF1e5e2e : 0xFF5e1e1e;
        if ("sched-pause-color".equals(key)) return paused ? 0xFF80ffaa : 0xFFff8888;
        if ("sched-pause-border".equals(key)) return paused ? 0xFF66cc66 : 0xFFcc6666;
        if ("sched-edit-color".equals(key)) return selectedSchedEditable() ? 0xFF4fc3f7 : 0xFF888888;
        if ("sched-type-color".equals(key)) return TrainTypes.colorArgb(selectedTrainTypeCode());
        if ("sched-share-toggle-bg".equals(key)) return schedShareToggle.trackBgFor(schedShareRealIdx());
        if ("sched-share-toggle-knob-bg".equals(key)) return schedShareToggle.knobBgFor(schedShareRealIdx());
        // 車両タイルの種別バッジ色
        if ("sched-row-type-color".equals(key) && ri >= 0) {
            var live = trainsForList();
            int realIdx = ri + schedListScroll.offset();
            if (realIdx < live.size()) {
                return TrainTypes.colorArgb(be().getSyncedTrainType(live.get(realIdx).id()));
            }
        }
        // Schedule entry: highlight current entry
        if ("sched-entry-color".equals(key) && ri >= 0) {
            int realIdx = ri + schedEntryScroll.offset();
            return realIdx == selectedSchedCurrent ? 0xFF4fc3f7 : 0xFFcccccc;
        }
        // Station row link indicator (green/red)
        if ("station-row-link-color".equals(key) && ri >= 0) {
            var stations = stationsForList();
            int realIdx = ri + stationScroll.offset();
            if (realIdx < stations.size()) {
                var st = stations.get(realIdx);
                return be().hasManagerForStation(st.name(), st.position()) ? 0xFF4caf50 : 0xFFef5350;
            }
        }
        // Station detail RM line (green when linked, red when not)
        if ("station-detail-rm-color".equals(key)) {
            return selectedStationHasRMBE() ? 0xFF4caf50 : 0xFFef5350;
        }
        if ("station-detail-monitor-color".equals(key)) {
            var s = selectedStation();
            if (s == null) return 0xFF888888;
            BlockPos rmPos = be().getManagerPosForStation(s.name(), s.position());
            if (rmPos == null || be().getLevel() == null) return 0xFF888888;
            try {
                var bbe = be().getLevel().getBlockEntity(rmPos);
                if (bbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                    return rm.getLinkedMonitorGroupCount() > 0 ? 0xFF80deea : 0xFF888888;
                }
            } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
            return 0xFF888888;
        }
        // Door direction button colors (selection-driven)
        if (ri >= 0 && ri < DOOR_OPTS.length) {
            var cur = currentDoorSide();
            boolean selected = cur != null && cur.name().equals(DOOR_OPTS[ri][0]);
            if ("door-btn-bg".equals(key)) return selected ? 0xFF1e4e6e : 0xFF1a1a2e;
            if ("door-btn-border".equals(key)) return selected ? 0xFF4fc3f7 : 0xFF555555;
            if ("door-btn-color".equals(key)) return selected ? 0xFF4fc3f7 : 0xFFaaaaaa;
        }
        // Schedule editor cyclic toggle colors (controller 委譲)
        {
            Integer sc = schedEditor.resolveColor(key);
            if (sc != null) return sc;
        }
        return null;
    }

    private static int parseHexColor(String hex) {
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
    private Object[] trySafeLiveTrain(java.util.UUID id) {
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

    private Boolean selectedSchedCyclic() {
        if (scheduleSelectedTrainId == null) return null;
        if (wikiMode) return Boolean.TRUE;
        var sv = be().getSyncedSchedView(scheduleSelectedTrainId);   // server 同期 (MP-safe)
        return sv != null ? sv.cyclic() : null;
    }

    private com.trainsystemutilities.blockentity.RailwayManagementBlockEntity.DoorSide currentDoorSide() {
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
        switch (key) {
            case "sym-edit-preview" -> {
                drawSymbolBadge(g, x, y, Math.min(w, h), symEditor.buildSymbol());
            }
            case "sym-tile-badge" -> {
                int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                var syms = serverBE().getLineSymbols();
                if (ri >= 0 && ri < syms.size()) {
                    drawSymbolBadge(g, x, y, Math.min(w, h), syms.get(ri));
                }
            }
            case "map" -> mapRenderer.draw(g, x, y, w, h, this.font, this.leftPos, this.topPos, mapNodes, mapEdges, mapStations, mapSignals, mapTrains);
            case "owner-face" -> belugalab.tsu.api.OwnerFacePainter.draw(
                    g, x, y, w, h, be().getOwnerUUID());
            case "train-model" -> trainModel.draw(g, x, y, w, h, this.minecraft, this.font, selectedTrainId, overlayX(), overlayY());
            case "ed-car-list" -> edCarList.draw(g, x, y, w, h, this.font, selectedTrainId);
            case "station-row-badge" -> {
                int rii = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                if (rii >= 0) {
                    var stations = stationsForList();
                    int realIdx = rii + stationScroll.offset();
                    if (realIdx < stations.size()) {
                        var st = stations.get(realIdx);
                        var sym = be().getSymbolForStation(st.name(), st.position());
                        if (sym != null) drawSymbolBadge(g, x, y, Math.min(w, h), sym);
                    }
                }
            }
            case "station-detail-badge" -> {
                var s = selectedStation();
                if (s != null) {
                    var sym = be().getSymbolForStation(s.name(), s.position());
                    if (sym != null) drawSymbolBadge(g, x, y, Math.min(w, h), sym);
                }
            }
            case "assign-item-badge" -> {
                int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                var syms = serverBE().getLineSymbols();
                if (ri >= 0 && ri < syms.size()) {
                    drawSymbolBadge(g, x, y, Math.min(w, h), syms.get(ri));
                }
            }
            case "layout-preview" -> drawLayoutPreview(g, x, y, w, h, mouseX, mouseY);
            case "sched-edit-body" -> schedEditor.drawBody(g, x, y, w, h, mouseX, mouseY, overlayX(), overlayY(), this.font);
            case "cp-hue-bar" -> {
                for (int i = 0; i < w; i++) {
                    int col = ColorPickerController.hsvToRgb(i / (float) w, 1f, 1f) | 0xFF000000;
                    g.fill(x + i, y, x + i + 1, y + h, col);
                }
                int hueCx = x + (int) (picker.hue() * w);
                g.fill(hueCx - 1, y - 1, hueCx + 2, y + h + 1, 0xFFFFFFFF);
                g.fill(hueCx, y, hueCx + 1, y + h, 0xFF000000);
            }
            case "cp-sv-panel" -> {
                int step = 2;
                for (int sy = 0; sy < h; sy += step) {
                    float v = 1f - sy / (float) h;
                    for (int sx = 0; sx < w; sx += step) {
                        float s = sx / (float) w;
                        int col = ColorPickerController.hsvToRgb(picker.hue(), s, v) | 0xFF000000;
                        g.fill(x + sx, y + sy, x + sx + step, y + sy + step, col);
                    }
                }
                int svCx = x + (int) (picker.saturation() * w);
                int svCy = y + (int) ((1f - picker.value()) * h);
                g.fill(svCx - 4, svCy, svCx + 5, svCy + 1, 0xFFFFFFFF);
                g.fill(svCx, svCy - 4, svCx + 1, svCy + 5, 0xFFFFFFFF);
            }
            case "export-arrow-bar" -> {
                // 時刻表書き出しの進捗を「矢印型」で描く。 軸 + 矢じりを1ループの列描画で継ぎ目なく一体に。
                float p = Math.max(0f, Math.min(1f,
                        be().getExportProgress() / (float) ManagementComputerBlockEntity.EXPORT_TICKS));
                int cy = y + h / 2;
                int tip = x + w;
                int headW = 8;
                int bodyEnd = tip - headW;
                int shaftHalf = 2;  // 軸 4px
                int headHalf = 4;   // 矢じり 8px (基部)
                int progressX = x + Math.round(w * p);
                int track = 0xFF555566, fill = 0xFF66cc66;
                for (int px = x; px < tip; px++) {
                    int half = (px < bodyEnd) ? shaftHalf
                            : Math.round(headHalf * (1f - (float) (px - bodyEnd) / headW));
                    g.fill(px, cy - half, px + 1, cy + half, (px < progressX) ? fill : track);
                }
            }
        }
    }

    @Override
    public boolean onElementDrag(String[] classes, String key, int mouseX, int mouseY,
                                  int elX, int elY, int elW, int elH, boolean pressed) {
        if ("cp-hue-bar".equals(key)) {
            picker.setHueFromX(mouseX - elX, elW);
            return true;
        }
        if ("cp-sv-panel".equals(key)) {
            picker.setSvFromXY(mouseX - elX, mouseY - elY, elW, elH);
            return true;
        }
        if ("map-pan".equals(key)) {
            if (pressed) {
                mapRenderer.onPanDrag(mouseX, mouseY, true);
            } else if (mapRenderer.wasClick(mouseX, mouseY)) {
                // #15: タップ (ドラッグでない) = 駅/列車アイコンのクリック
                var hit = mapRenderer.hitTest(mouseX, mouseY);
                if (hit != null) handleMapHit(hit);
            } else {
                mapRenderer.onPanDrag(mouseX, mouseY, false);
            }
            return true;
        }
        // Layout editor: パレットタイルからの drag-and-drop で追加 (DragDropPalette に委譲)
        if ("layout-tile-drag".equals(key)) {
            if (pressed) {
                String type = null;
                for (String c : classes) {
                    if (c.startsWith("layout-tile-") && !"layout-tile-item".equals(c)) {
                        type = c.substring("layout-tile-".length());
                        break;
                    }
                }
                palette.onPress(type);
            }
            palette.update(mouseX, mouseY);
            return true;
        }
        // Layout editor: preview canvas のドラッグでパネル選択 + 移動。
        if ("layout-preview-drag".equals(key)) {
            if (layoutPrevW <= 0 || layoutPrevH <= 0) return true;
            // mouseX/Y は popup-local 座標。preview canvas の絶対座標を引いてキャンバス内座標に。
            int cx = mouseX - layoutPrevX;
            int cy = mouseY - layoutPrevY;
            if (pressed) {
                // クリック位置のパネルを選択 (一番上=後追加分から検査)
                layoutEditor.clearSelection();
                for (int i = layoutEditor.getLayout().size() - 1; i >= 0; i--) {
                    var p = layoutEditor.getLayout().get(i);
                    int px = (int)(p.getX() * layoutPrevW);
                    int py = (int)(p.getY() * layoutPrevH);
                    int pw = Math.max(8, (int)(p.getWidth() * layoutPrevW));
                    int ph = Math.max(8, (int)(p.getHeight() * layoutPrevH));
                    if (cx >= px && cx < px + pw && cy >= py && cy < py + ph) {
                        layoutEditor.select(i);
                        layoutDragStartPanelX = p.getX();
                        layoutDragStartPanelY = p.getY();
                        layoutDragStartMouseX = mouseX;
                        layoutDragStartMouseY = mouseY;
                        break;
                    }
                }
            } else if (layoutEditor.selectedIndex() >= 0 && layoutEditor.selectedIndex() < layoutEditor.getLayout().size()) {
                var p = layoutEditor.getLayout().get(layoutEditor.selectedIndex());
                float dx = (float)((mouseX - layoutDragStartMouseX) / (double) layoutPrevW);
                float dy = (float)((mouseY - layoutDragStartMouseY) / (double) layoutPrevH);
                p.setX(layoutDragStartPanelX + dx);
                p.setY(layoutDragStartPanelY + dy);
            }
            return true;
        }
        // 列車詳細 popup の 3D モデル: 左ドラッグで回転 (shift で pan) — TrainModelRenderer へ委譲。
        if ("train-rotate".equals(key)) {
            trainModel.onRotateDrag(mouseX, mouseY, pressed,
                    net.minecraft.client.gui.screens.Screen.hasShiftDown());
            return true;
        }
        return false;
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
        if ("map-zoom".equals(key)) {
            mapRenderer.onZoomWheel(scrollY);
            return true;
        }
        if ("train-zoom".equals(key)) {
            trainModel.onZoomWheel(scrollY);
            return true;
        }
        // Layout editor: preview 上のタイルを hover + wheel で拡大縮小 (中心アンカー)
        if ("layout-preview-wheel".equals(key)) {
            if (layoutEditor.isOpen()) {
                int idx = layoutPanelAt(mouseX, mouseY);
                if (idx >= 0) {
                    var p = layoutEditor.getLayout().get(idx);
                    float f = scrollY > 0 ? 1.08f : 1f / 1.08f;
                    float cxN = p.getX() + p.getWidth() / 2f;
                    float cyN = p.getY() + p.getHeight() / 2f;
                    p.setWidth(p.getWidth() * f);
                    p.setHeight(p.getHeight() * f);
                    p.setX(cxN - p.getWidth() / 2f);   // setX/setY が bezel 内へ clamp
                    p.setY(cyN - p.getHeight() / 2f);
                    layoutEditor.select(idx);
                }
            }
            return true;
        }
        // 列車種別: 値 hover + wheel で循環 (R4.13.0 / R4.13.0.8)。 値ピッカーなので非反転。
        if ("sched-type-val".equals(key)) {
            cycleSelectedTrainType(scrollY > 0 ? 1 : -1);
            return true;
        }
        // Panel settings popup: 値 hover + wheel で増減 (R4.13.0)。 0 = 自動 (推奨)。
        if (key != null && key.startsWith("pset-")) {
            var pp = psetPanel();
            if (pp != null) {
                int d = scrollY > 0 ? 1 : -1;
                switch (key) {
                    case "pset-font-val":        pp.setFontSize(adjustPsetValue(pp.getFontSize(), d)); return true;
                    case "pset-maptext-val":     pp.setMapTextSize(adjustPsetValue(pp.getMapTextSize(), d)); return true;
                    case "pset-trainicon-val":   pp.setTrainIconSize(adjustPsetValue(pp.getTrainIconSize(), d)); return true;
                    case "pset-stationicon-val": pp.setStationIconSize(adjustPsetValue(pp.getStationIconSize(), d)); return true;
                    case "pset-signalicon-val":  pp.setSignalIconSize(adjustPsetValue(pp.getSignalIconSize(), d)); return true;
                }
            }
            return true;
        }
        int delta = scrollY > 0 ? -1 : 1;
        if ("trains-scroll".equals(key)) {
            trainScroll.scroll(delta);
            return true;
        }
        if ("sched-list-scroll".equals(key)) {
            schedListScroll.scroll(delta);
            return true;
        }
        if ("sched-entries-scroll".equals(key)) {
            schedEntryScroll.scroll(delta);
            return true;
        }
        if ("sched-share-scroll".equals(key)) {
            schedShareScroll.scroll(delta);
            return true;
        }
        if ("stations-list-scroll".equals(key)) {
            stationScroll.scroll(delta);
            return true;
        }
        if ("tickets-list-scroll".equals(key)) {
            ticketScroll.scroll(delta);  // ScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        // Symbol editor field wheel-edit (wheel-up = increase) — controller 委譲
        if (symEditor.handleWheel(key, scrollY)) return true;
        if ("sched-station-pick-scroll".equals(key)) {
            return schedEditor.handleStationWheel(delta);
        }
        if ("sched-edit-body-scroll".equals(key)) {
            if (schedEditor.handleWheel(mouseX, mouseY, delta)) return true;
        }
        return false;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // Phase 9: 4-arg を完全 override しているため base の hint/wiki 処理を明示呼び出し
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        // Monitor toggle (= track/knob + alias 旧名)
        if (monitorToggle.handleClick(classes)) return;
        if (exportAllToggle.handleClick(classes)) return;
        if (showScheduleShare && schedShareToggle.handleClick(classes, schedShareRealIdx())) return;
        // 券売機タブ: 販売可トグル (repeat 行ごと)
        if (ticketToggle.handleClick(classes, belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex())) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) {
                    belugalab.mcss3.wiki.Wiki.open(pid);
                }
                return;
            }
        }
        // === Schedule editor (sub-dropdown chain + frame、controller 委譲) ===
        if (schedEditor.handleClick(classes, mouseX, mouseY, overlayX(), overlayY(),
                this::schedStationNames, this::applyScheduleEdit, this::clearOverlayAnimByClass)) return;
        // HSV picker (highest popup priority)
        if (showColorPicker) {
            int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("cp-close".equals(c) || "cp-close-btn".equals(c) || "mc-popup-close".equals(c)) {
                    showColorPicker = false; return;
                }
                if ("cp-info-hex".equals(c)) { focusField("hex"); return; }
                if ("cp-info-rgb".equals(c)) { focusField("rgb"); return; }
                if ("cp-info-hsl".equals(c)) { focusField("hsl"); return; }
                if ("cp-add".equals(c)) {
                    String hex = currentPickerHex();
                    if (!customColors.contains(hex)) customColors.add(hex);
                    symEditor.setColorCustom(hex);
                    return;
                }
                if ("cp-chip".equals(c) && ri >= 0 && ri < customColors.size()) {
                    if (button == 1) {
                        customColors.remove(ri);
                    } else {
                        String picked = customColors.get(ri);
                        symEditor.setColorCustom(picked);
                        setPickerFromColor(picked);
                    }
                    return;
                }
            }
            // fallthrough → main GUI
        }
        // Symbol delete confirm popup (controller 委譲)
        if (symbolDelete.isOpen()) {
            if (symbolDelete.handleClick(classes, this::confirmDeleteSymbol)) return;
            // fallthrough → main GUI
        }
        // Symbol editor popup (save/delete/cp-btn は screen helper 結合ゆえ screen、他は controller 委譲)
        if (symEditor.isOpen()) {
            for (String c : classes) {
                if ("sym-edit-save".equals(c)) {
                    saveEditedSymbol();
                    symEditor.close();
                    return;
                }
                if ("sym-edit-delete".equals(c) && symEditor.getIndex() >= 0) {
                    symbolDelete.open(symEditor.getIndex());
                    symEditor.close();
                    return;
                }
                if ("sym-edit-cp-btn".equals(c)) {
                    showColorPicker = true;
                    setPickerFromColor(symEditor.getColor());
                    return;
                }
            }
            if (symEditor.handleClick(classes, () -> customColors)) return;
            // fallthrough → main GUI
        }
        // Station assign dropdown (controller 委譲)
        if (stationAssign.isOpen()) {
            int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (stationAssign.handleClick(classes, ri, () -> serverBE().getLineSymbols(),
                    (n, p, sym) -> assignSymbolOnServer(n, p, sym == null ? null : sym.getId()))) return;
            // fallthrough → main GUI
        }
        // Monitor color settings popup — ColorTargetController に dispatch
        if (showMonitorColorSettings) {
            int dIdx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            // popup-close は controller では false を返す (popup 自体を閉じる責務は screen)
            for (String c : classes) {
                if ("mcol-popup-close".equals(c) || "mc-popup-close".equals(c)) {
                    showMonitorColorSettings = false;
                    monitorColorPopup.resetTransientState();
                    return;
                }
            }
            // dropdown open 直後の anim re-trigger (controller では行わないので screen 側で)
            boolean wasOpen = monitorColorPopup.isDropdownOpen();
            if (monitorColorPopup.handleClick(classes, dIdx)) {
                if (!wasOpen && monitorColorPopup.isDropdownOpen()) {
                    clearOverlayAnimByClass("mcol-target-list");
                }
                return;
            }
            // 一致なし → main GUI ハンドラに落とす
        }
        // Layout editor popup clicks
        if (layoutEditor.isOpen()) {
            // タイル上で中ボタン押し込み → パネル機能別設定 popup (overlay2)
            if (button == 2) {
                for (String c : classes) {
                    if ("layout-preview".equals(c)) {
                        int idx = layoutPanelAt(mouseX, mouseY);
                        if (idx >= 0) { layoutEditor.select(idx); layoutSettingsIdx = idx; }
                        return;
                    }
                }
            }
            for (String c : classes) {
                if ("pset-close-btn".equals(c)) { layoutSettingsIdx = -1; return; }
                if ("pset-auto-btn".equals(c)) {
                    var pp = psetPanel();
                    if (pp != null) {
                        // おすすめ = 全て 0 (自動)。 renderer がモニター/パネルの px サイズから最適値を算出
                        pp.setFontSize(0); pp.setMapTextSize(0);
                        pp.setTrainIconSize(0); pp.setStationIconSize(0); pp.setSignalIconSize(0);
                    }
                    return;
                }
                if ("layout-edit-close".equals(c) || "mc-popup-close".equals(c)) {
                    layoutSettingsIdx = -1;
                    layoutEditor.close();
                    return;
                }
                if ("layout-clear-btn".equals(c)) {
                    layoutEditor.getLayout().clear();
                    layoutEditor.clearSelection();
                    layoutSettingsIdx = -1;
                    return;
                }
                if ("layout-recommend-btn".equals(c)) {
                    applyRecommendedLayout();
                    layoutSettingsIdx = -1;
                    return;
                }
                if ("layout-save-btn".equals(c)) {
                    saveLayoutToServer();
                    layoutSettingsIdx = -1;
                    layoutEditor.close();
                    return;
                }
                if (c.startsWith("layout-tile-") && !"layout-tile-item".equals(c)) {
                    String typeName = c.substring("layout-tile-".length());
                    addLayoutPanel(typeName);
                    return;
                }
            }
            // 一致なし → main GUI ハンドラに落とす (popup 中も main の他ボタン操作可)
        }
        // Phase 24: 電化詳細 popup clicks (列車詳細 popup と並存しているため、
        // mc-popup-close は ed-close-btn 経由の場合のみ受ける = ユニーククラス判定)
        if (edDetail.isOpen() && selectedTrainId != null && !tabDropdown.isOpen()) {
            if (edDetail.handleClick(classes, selectedTrainId)) return;
            // パンタ個別 toggle は edPantoHits (drawCanvas が populate する hit-box) の
            // hit-test ゆえ controller でなく screen 側で処理する。
            for (String c : classes) {
                if ("ed-car-list-canvas".equals(c)) {
                    // canvas 内のパンタ hit-box を順にチェック
                    for (ElectrificationCarListRenderer.EdPantoHit ph : edCarList.pantoHits()) {
                        if (mouseX >= ph.x0() && mouseX <= ph.x1()
                                && mouseY >= ph.y0() && mouseY <= ph.y1()) {
                            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                                    "[PantoToggle-DEBUG] CLIENT click toggle-one train={} car={} pos={}",
                                    selectedTrainId, ph.carriageIndex(), ph.pos());
                            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                    new com.trainsystemutilities.network.PantographTogglePayload(
                                            selectedTrainId,
                                            com.trainsystemutilities.network.PantographTogglePayload.ACTION_TOGGLE_ONE,
                                            ph.carriageIndex(), ph.pos()));
                            return;
                        }
                    }
                    com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                            "[PantoToggle-DEBUG] CLIENT canvas-click missed hits ({} pantos cached)",
                            edCarList.pantoHits().size());
                    return;
                }
            }
            // 電化詳細のクラスにマッチしなかった click は train-detail / main GUI へ落とす
            // (= 列車詳細 popup と並存しているため両方クリック可能)
        }
        // Train detail popup clicks
        if (selectedTrainId != null && !tabDropdown.isOpen()) {
            for (String c : classes) {
                if ("mc-popup-close".equals(c) || "train-detail-close".equals(c)) {
                    selectedTrainId = null;
                    edDetail.close();
                    return;
                }
                if ("train-detail-electrification-btn".equals(c)) {
                    // 列車詳細 popup を閉じて電化詳細 popup を表示
                    edDetail.open();
                    return;
                }
            }
            // fallthrough → main GUI
        }

        if (tabDropdown.isOpen()) {
            for (String c : classes) {
                if (c.startsWith("tab-item-")) {
                    String key = c.substring("tab-item-".length());
                    for (String[] t : TABS) {
                        if (t[0].equals(key)) {
                            tabs.switchTo(key);
                            tabDropdown.close();
                            return;
                        }
                    }
                }
            }
            tabDropdown.close();
            // fallthrough → main GUI
        }

        for (String c : classes) {
            switch (c) {
                case "tab-dropdown":
                    tabDropdown.setOpen(true);
                    return;
                case "mc-popup-close":
                case "mc-header-close":
                    onClose();
                    return;
                // hint-toggle-track/knob は base class HintToggleHelper が自動処理
                // monitor toggle (new + alias) は下の controller dispatch で処理
                case "layout-edit-btn":
                    openLayoutEditor();
                    return;
                case "monitor-color-btn":
                    showMonitorColorSettings = !showMonitorColorSettings;
                    if (!showMonitorColorSettings) monitorColorPopup.resetTransientState();
                    return;
                case "symbol-edit-btn":
                    tabs.switchTo("symbol");
                    tabDropdown.close();
                    return;
                case "color-edit-btn":
                    showColorPicker = !showColorPicker;
                    return;
                case "owner-face-box":
                case "owner-face":
                case "owner-face-canvas": // canvas の class は "owner-face-canvas" (canvasKey "owner-face" ではない)。innermost auto-clickable で実クリックはこちらに来る
                    if (this.minecraft != null && this.minecraft.gameMode != null) {
                        this.minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId, 9000);
                    }
                    return;
                case "sym-create-btn":
                    openSymbolEditorNew();
                    return;
                case "sym-tile":
                case "sym-tile-badge":   // canvas は auto-clickable のため innermost-rule でこちらに来る
                case "sym-tile-name": {
                    int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (ri >= 0 && ri < serverBE().getLineSymbols().size()) {
                        if (button == 1) {
                            symbolDelete.open(ri);
                        } else {
                            openSymbolEditorExisting(ri);
                        }
                    }
                    return;
                }
                case "train-row": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var live = trainsForList();
                        int realIdx = idx + trainScroll.offset();
                        if (realIdx < live.size()) selectedTrainId = live.get(realIdx).id();
                    }
                    return;
                }
                case "sched-row": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var live = trainsForList();
                        int realIdx = idx + schedListScroll.offset();
                        if (realIdx < live.size()) {
                            scheduleSelectedTrainId = live.get(realIdx).id();
                            scheduleSelectNano = System.nanoTime();
                            schedEntryScroll.setOffset(0);
                            // 詳細ビュー要素の slide-in animation を再トリガー
                            clearMainAnimByClass("sched-detail-name");
                        }
                    }
                    return;
                }
                case "sched-stop-all-btn":
                    startAllStop();
                    return;
                case "sched-resume-all-btn":
                    resumeAllStop();
                    return;
                case "sched-back":
                    scheduleSelectedTrainId = null;
                    showScheduleShare = false;
                    return;
                case "sched-pause":
                    togglePauseSelected();
                    return;
                case "sched-edit":
                    openScheduleEditor();
                    return;
                case "sched-share-btn":
                    showScheduleShare = true;
                    schedShareScroll.clamp();
                    return;
                case "sched-share-close":
                    showScheduleShare = false;
                    return;
                case "station-row": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var stations = stationsForList();
                        int realIdx = idx + stationScroll.offset();
                        if (realIdx < stations.size()) {
                            var st = stations.get(realIdx);
                            selectedStationKey = ManagementComputerBlockEntity.stationKey(st.name(), st.position());
                            // 詳細ビュー要素の slide-in animation を再トリガー
                            clearMainAnimByClass("station-detail-name");
                            clearMainAnimByClass("station-detail-badge");
                            clearMainAnimByClass("station-detail-pos");
                            clearMainAnimByClass("station-detail-rm");
                            clearMainAnimByClass("station-detail-monitor");
                            clearMainAnimByClass("station-detail-door-label");
                        }
                    }
                    return;
                }
                case "station-row-assign": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var stations = stationsForList();
                        int realIdx = idx + stationScroll.offset();
                        if (realIdx < stations.size()) {
                            var st = stations.get(realIdx);
                            stationAssign.open(st.name(), st.position());
                            // + ボタンの screen 座標を記録 → overlayDefaultPosition で参照。
                            // findElementByClass は repeat の template も走査して row-0 の rect を返す
                            // ので、idx 行は y に stride (= STATION_ROW_H + 1 = 23) を加算する。
                            int[] r = findElementByClass("station-row-assign");
                            if (r != null) {
                                assignBtnScreenX = this.leftPos + r[0];
                                assignBtnScreenY = this.topPos + r[1] + idx * (22 + 1) + r[3];
                            } else {
                                assignBtnScreenX = this.leftPos + (int) mouseX;
                                assignBtnScreenY = this.topPos + (int) mouseY + 8;
                            }
                        }
                    }
                    return;
                }
                case "station-back":
                    selectedStationKey = "";
                    return;
                case "door-btn": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0 && idx < DOOR_OPTS.length) {
                        var s = selectedStation();
                        if (s != null) {
                            var side = com.trainsystemutilities.blockentity
                                    .RailwayManagementBlockEntity.DoorSide.valueOf(DOOR_OPTS[idx][0]);
                            setDoorSideOnServer(s.name(), s.position(), side);
                        }
                    }
                    return;
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // (Schedule editor + sub-dropdowns — all JSON-driven via onElementClick.)
        // (Symbol / trains / schedule / stations / door / assign — all JSON-driven.)
        return super.mouseClicked(mx, my, button);
    }

    private void togglePauseSelected() {
        if (scheduleSelectedTrainId == null) return;
        // B2 (MP desync 修正): client で直接 mutate せず server 権威の payload を送る。
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ManagementComputerControlPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ManagementComputerControlPayload.ACTION_TOGGLE_ONE,
                        scheduleSelectedTrainId));
    }

    // === Symbol editor helpers ===
    private void openSymbolEditorNew() {
        symEditor.openNew();
    }

    private void openSymbolEditorExisting(int idx) {
        var syms = serverBE().getLineSymbols();
        if (idx < 0 || idx >= syms.size()) return;
        symEditor.openExisting(idx, syms.get(idx));
    }

    private void saveEditedSymbol() {
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

    private void confirmDeleteSymbol(int delIdx) {
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

    private void assignSymbolOnServer(String stationName, BlockPos stationPos, java.util.UUID symId) {
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
    private void setPickerFromColor(String hex) {
        picker.setHex(hex);                 // §6.18 ColorPickerController (旧 java.awt.Color.RGBtoHSB)
    }

    private String currentPickerHex() {
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
    private void openLayoutEditor() {
        layoutEditor.getLayout().clear();
        for (var p : serverBE().getMonitorLayout()) layoutEditor.getLayout().add(p.copy());
        layoutEditor.clearSelection();
        layoutSettingsIdx = -1;
        layoutEditor.open();
    }

    /** layout preview canvas 上 (popup-local mouse 座標) のパネル index。 一番上 = 後追加分から検査。 */
    private int layoutPanelAt(int mouseX, int mouseY) {
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
    private static int adjustPsetValue(int cur, int delta) {
        if (cur == 0) return delta > 0 ? 8 : 0;
        return Math.max(0, cur + delta);
    }

    /** pset 値表示: 0 = 自動 (推奨)、 それ以外は px 表記。 */
    private String psetValText(int v) {
        return v == 0 ? tr("tsu.mc.pset_auto_value") : (v + "px");
    }

    /** 設定 popup の対象パネル (範囲外なら null)。 */
    private com.trainsystemutilities.blockentity.MonitorLayoutPanel psetPanel() {
        if (layoutSettingsIdx >= 0 && layoutSettingsIdx < layoutEditor.getLayout().size()) {
            return layoutEditor.getLayout().get(layoutSettingsIdx);
        }
        return null;
    }

    private void saveLayoutToServer() {
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

    private void addLayoutPanel(String typeName) {
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

    private void applyRecommendedLayout() {
        layoutEditor.getLayout().clear();
        int mw = be().getMonitorW();
        int mh = be().getMonitorH();
        int area = Math.max(1, mw * mh);
        // 寸法に応じてレイアウトを切り替え。 area = 横ブロック数 × 縦ブロック数。
        if (area <= 2) {
            // 1x1 / 1x2 / 2x1: clock + train list の最小構成
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.04f, 0.04f, 0.92f, 0.22f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.04f, 0.30f, 0.92f, 0.66f));
        } else if (area <= 4) {
            // 2x2 / 1x4 / 4x1: route map + clock + train list + stat 1 個
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP, 0.04f, 0.04f, 0.55f, 0.92f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.62f, 0.04f, 0.34f, 0.18f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.STATION_COUNT, 0.62f, 0.25f, 0.16f, 0.22f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_COUNT, 0.80f, 0.25f, 0.16f, 0.22f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.62f, 0.50f, 0.34f, 0.46f));
        } else if (area <= 8) {
            // 3x2 / 2x3 / 4x2 / 2x4: route map + clock + stat 3 個 + train list + schedule
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP, 0.02f, 0.02f, 0.55f, 0.96f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.60f, 0.02f, 0.38f, 0.14f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.STATION_COUNT, 0.60f, 0.18f, 0.12f, 0.18f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_COUNT, 0.73f, 0.18f, 0.12f, 0.18f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SIGNAL_COUNT, 0.86f, 0.18f, 0.12f, 0.18f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.60f, 0.38f, 0.38f, 0.30f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SCHEDULE, 0.60f, 0.70f, 0.38f, 0.28f));
        } else {
            // 大型 (3x3 以上): 全 panel type を配置
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP, 0.02f, 0.02f, 0.55f, 0.96f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.60f, 0.02f, 0.38f, 0.12f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.STATION_COUNT, 0.60f, 0.16f, 0.12f, 0.14f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_COUNT, 0.73f, 0.16f, 0.12f, 0.14f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SIGNAL_COUNT, 0.86f, 0.16f, 0.12f, 0.14f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.60f, 0.32f, 0.38f, 0.32f));
            layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SCHEDULE, 0.60f, 0.66f, 0.38f, 0.32f));
        }
        layoutEditor.clearSelection();
    }

    // === Schedule editor helpers ===
    private void openScheduleEditor() {
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
        List<ScheduleEditorController.EditEntryData> entries = new ArrayList<>();
        try {
            for (var e : sched.entries) {
                String type = "destination"; String text = ""; int value = 0;
                try {
                    if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.DestinationInstruction d) {
                        type = "destination"; text = d.getFilter();
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.DeliverPackagesInstruction) {
                        type = "deliver"; text = e.instruction.getData().getString("Text");
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.FetchPackagesInstruction) {
                        type = "fetch"; text = e.instruction.getData().getString("Text");
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction) {
                        type = "rename"; text = e.instruction.getData().getString("Text");
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.ChangeThrottleInstruction) {
                        type = "throttle"; value = e.instruction.getData().getInt("Value");
                    } else {
                        var s2 = e.instruction.getSummary();
                        text = s2 != null ? s2.getSecond().getString() : "?";
                    }
                } catch (Exception ex) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] schedule entry read failed", ex); }
                List<ScheduleEditorController.EditCondData> conds = new ArrayList<>();
                for (var cg : e.conditions) {
                    for (var c : cg) {
                        try {
                            var cd = c.getData();
                            String cType = "delay"; int cVal = 5; int cUnit = 1;
                            if (c instanceof com.simibubi.create.content.trains.schedule.condition.ScheduledDelay) {
                                cType = "delay"; cVal = cd.getInt("Value"); cUnit = cd.getInt("TimeUnit");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.TimeOfDayCondition) {
                                cType = "time_of_day"; cVal = cd.getInt("Hour");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.ItemThresholdCondition) {
                                cType = "item_threshold"; cVal = cd.getInt("Threshold");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.FluidThresholdCondition) {
                                cType = "fluid_threshold"; cVal = cd.getInt("Threshold");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.RedstoneLinkCondition) {
                                cType = "redstone_link"; cVal = cd.getInt("Inverted");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.PlayerPassengerCondition) {
                                cType = "passenger"; cVal = cd.getInt("Count");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.IdleCargoCondition) {
                                cType = "idle"; cVal = cd.getInt("Value"); cUnit = cd.getInt("TimeUnit");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.StationPoweredCondition) {
                                cType = "powered";
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.StationUnloadedCondition) {
                                cType = "unloaded";
                            }
                            conds.add(new ScheduleEditorController.EditCondData(cType, cVal, cUnit));
                        } catch (Exception ex) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] schedule condition read failed", ex); }
                    }
                }
                entries.add(new ScheduleEditorController.EditEntryData(type, text, value, conds));
            }
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "schedule editor: failed to parse existing schedule into edit form", e);
        }
        return entries;
    }

    private void applyScheduleEdit() {
        if (scheduleSelectedTrainId == null) { schedEditor.close(); return; }
        try {
            var nbt = new net.minecraft.nbt.CompoundTag();
            var entriesList = new net.minecraft.nbt.ListTag();
            for (var entry : schedEditor.getEntries()) {
                var entryNbt = new net.minecraft.nbt.CompoundTag();
                var instrNbt = new net.minecraft.nbt.CompoundTag();
                switch (entry.type) {
                    case "destination" -> { instrNbt.putString("Id", CreateScheduleIds.DESTINATION); instrNbt.putString("Text", entry.text); }
                    case "deliver" -> { instrNbt.putString("Id", CreateScheduleIds.PACKAGE_DELIVERY); instrNbt.putString("Text", entry.text); }
                    case "fetch" -> { instrNbt.putString("Id", CreateScheduleIds.PACKAGE_RETRIEVAL); instrNbt.putString("Text", entry.text); }
                    case "rename" -> { instrNbt.putString("Id", CreateScheduleIds.RENAME); instrNbt.putString("Text", entry.text); }
                    case "throttle" -> { instrNbt.putString("Id", CreateScheduleIds.THROTTLE); instrNbt.putInt("Value", entry.value); }
                }
                entryNbt.put("Instruction", instrNbt);
                var condListNbt = new net.minecraft.nbt.ListTag();
                if (!entry.conditions.isEmpty()) {
                    var condGroupNbt = new net.minecraft.nbt.ListTag();
                    for (var cond : entry.conditions) {
                        var condNbt = new net.minecraft.nbt.CompoundTag();
                        switch (cond.type) {
                            case "delay" -> { condNbt.putString("Id", CreateScheduleIds.DELAY); condNbt.putInt("Value", cond.value); condNbt.putInt("TimeUnit", cond.timeUnit); }
                            case "time_of_day" -> { condNbt.putString("Id", CreateScheduleIds.TIME_OF_DAY); condNbt.putInt("Hour", cond.value); condNbt.putInt("Minute", 0); condNbt.putInt("Rotation", 0); }
                            case "item_threshold" -> { condNbt.putString("Id", CreateScheduleIds.ITEM_THRESHOLD); condNbt.putInt("Threshold", cond.value); condNbt.putInt("Operator", 0); condNbt.putInt("Measure", 0); }
                            case "fluid_threshold" -> { condNbt.putString("Id", CreateScheduleIds.FLUID_THRESHOLD); condNbt.putInt("Threshold", cond.value); condNbt.putInt("Operator", 0); }
                            case "redstone_link" -> { condNbt.putString("Id", CreateScheduleIds.REDSTONE_LINK); condNbt.putInt("Inverted", cond.value); }
                            case "passenger" -> { condNbt.putString("Id", CreateScheduleIds.PLAYER_COUNT); condNbt.putInt("Count", cond.value); condNbt.putInt("Exact", 0); }
                            case "idle" -> { condNbt.putString("Id", CreateScheduleIds.IDLE); condNbt.putInt("Value", cond.value); condNbt.putInt("TimeUnit", cond.timeUnit); }
                            case "powered" -> condNbt.putString("Id", CreateScheduleIds.POWERED);
                            case "unloaded" -> condNbt.putString("Id", CreateScheduleIds.UNLOADED);
                            case "coupling" -> { condNbt.putString("Id", "trainsystemutilities:coupling"); condNbt.putInt("Mode", 0); condNbt.putInt("WaitTime", 5); }
                            case "decoupling" -> { condNbt.putString("Id", "trainsystemutilities:coupling"); condNbt.putInt("Mode", 1); condNbt.putInt("WaitTime", 5); }
                        }
                        condGroupNbt.add(condNbt);
                    }
                    condListNbt.add(condGroupNbt);
                }
                entryNbt.put("Conditions", condListNbt);
                entriesList.add(entryNbt);
            }
            nbt.put("Entries", entriesList);
            nbt.putBoolean("Cyclic", schedEditor.isCyclic());

            // MP 対応: server 権威の payload で適用 (ゲートは ApplyScheduleEditPayload.handle で検証)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.trainsystemutilities.network.ApplyScheduleEditPayload(
                            be().getBlockPos(), scheduleSelectedTrainId, nbt));
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "applyScheduleEdit failed for train {}", scheduleSelectedTrainId, e);
        }
        schedEditor.close();
    }

    private void setDoorSideOnServer(String stationName, BlockPos stationPos,
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
        // 時刻表書き出しスロットの可視/有効化 + 入力充填で自動書き出し (server 権威)
        boolean schedDetail = tabs.is("schedule") && scheduleSelectedTrainId != null;
        com.trainsystemutilities.gui.ManagementComputerMenu.exportSlotsVisible = schedDetail;
        if (schedDetail && getMenu().slots.size() > 3) {
            boolean inFilled = !getMenu().slots.get(2).getItem().isEmpty();
            if (inFilled && getMenu().slots.get(3).getItem().isEmpty()
                    && be().getExportProgress() == 0 && !exportRequestSent) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.trainsystemutilities.network.ExportTimetablePayload(
                                be().getBlockPos(), scheduleSelectedTrainId));
                exportRequestSent = true;
            }
            if (!inFilled) exportRequestSent = false;
        } else {
            exportRequestSent = false;
        }
        if (tabDropdown.isOpen()) return;
        refreshNetworkData();
        if (selectedTrainId != null) {
            refreshSelectedTrainSnapshot(selectedTrainId);
        } else if (tabs.is("schedule") && scheduleSelectedTrainId != null) {
            // 時刻表タブで列車タイルを選択したときも明細 snapshot を更新する
            // (旧実装は selectedTrainId のみ対象で scheduleSelectedTrainId が未反映 →「スケジュールなし」固定だった)
            if (!wikiMode) refreshSelectedTrainSnapshot(scheduleSelectedTrainId); // wiki: 事前設定 snapshot を保持
        }
        // 路線記号タブで hover 中のタイル detail preview を描画 (V1 renderSymbolTilePreview 同等)。
        if (tabs.is("symbol") && !symEditor.isOpen() && !showColorPicker
                && !symbolDelete.isOpen()) {
            renderSymbolTileHoverPreview(g, mouseX, mouseY);
        } else {
            symbolHover.reset();
        }
        // Layout editor: drag 中のパレットタイルを DragDropPalette ghost で描画。
        // Phase 5d FIX: palette.update に渡される mouseX/Y は overlay popup-local 座標。
        // afterDialogRender は screen-pose で呼ばれるので、overlay の translate+scale
        // を再適用してから描画する (これがないと cursor からずれる)。
        if (layoutEditor.isOpen() && palette.isDragging()) {
            g.pose().pushPose();
            g.pose().translate(overlayX(), overlayY(), 700);
            float s = overlayScale();
            if (s != 1.0f) g.pose().scale(s, s, 1f);
            palette.drawGhost(g, this.font, paletteLabelFor(palette.payload()));
            g.pose().popPose();
        }
    }

    private String paletteLabelFor(String type) {
        if (type == null) return "";
        for (String[] t : LAYOUT_TILE_TYPES) if (t[0].equals(type)) return tr(t[1]);
        return type;
    }

    /** 路線記号タブのタイル hover 詳細パネル (HoverTilePreview に hit-test/タイミング委譲)。 */
    private void renderSymbolTileHoverPreview(GuiGraphics g, int mouseX, int mouseY) {
        var symbols = serverBE().getLineSymbols();
        // タイル位置: (CONTENT_X + 6, CONTENT_Y + 22) から 7 cols × stride (36+4)。
        // gen 側の SYM_TAB_PAD=6, SYM_TILE=36, SYM_GAP=4, SYM_COLS=7。
        final int SYM_TILE = 36, SYM_GAP = 4, SYM_COLS = 7;
        final int CONTENT_X_LOCAL = 148, CONTENT_Y_LOCAL = 35;  // gen 側と整合
        int gridX0 = this.leftPos + CONTENT_X_LOCAL + 6;
        int gridY0 = this.topPos  + CONTENT_Y_LOCAL + 22;
        int idx = symbolHover.update(mouseX, mouseY,
                gridX0, gridY0, SYM_TILE, SYM_GAP, SYM_COLS, symbols.size());
        if (idx < 0) return;
        var sym = symbols.get(idx);

        // scale-in アニメ進捗 (HoverTilePreview に委譲)
        float eased = symbolHover.animProgress();
        eased = 1f - (1f - eased) * (1f - eased);  // ease-out quad
        int previewW = 100, previewH = 70;
        int drawW = (int) (previewW * eased);
        int drawH = (int) (previewH * eased);
        if (drawW <= 0 || drawH <= 0) return;
        int padding = 6;
        int panelW = drawW + padding * 2;
        int panelH = drawH + padding * 2;

        // メイン GUI 右側にフロート表示 (画面端は左へフォールバック)
        int[] pos = HoverTilePreview.sideSnapPosition(
                this.leftPos, this.topPos, this.imageWidth, this.imageHeight,
                panelW, panelH, this.width, this.height);
        int px = pos[0];
        int py = Math.max(4, mouseY - panelH / 2);
        if (py + panelH + 4 > this.height) py = this.height - panelH - 4;

        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(px, py, px + panelW, py + panelH, 0xDD1a1a2e);
        g.fill(px, py, px + panelW, py + 1, 0xFF4fc3f7);
        g.fill(px, py + panelH - 1, px + panelW, py + panelH, 0xFF4fc3f7);
        g.fill(px, py, px + 1, py + panelH, 0xFF4fc3f7);
        g.fill(px + panelW - 1, py, px + panelW, py + panelH, 0xFF4fc3f7);

        int iconSize = Math.min(drawW - 4, 30);
        int iconX = px + padding + 2;
        int iconY = py + padding + 2;
        // LineSymbolPainter 経由でアイコン描画
        LineSymbolPainter.draw(g, iconX, iconY, iconSize, sym, this.font);

        int textX = iconX + iconSize + 6;
        int textY = py + padding + 2;
        if (!sym.getName().isEmpty()) {
            g.drawString(this.font, sym.getName(), textX, textY, 0xFF4fc3f7, false);
            textY += 11;
        }
        g.drawString(this.font, sym.getLetters() + " " + sym.getNumberStr(), textX, textY, 0xFFFFFFFF, false);
        textY += 11;
        g.drawString(this.font,
                net.minecraft.network.chat.Component.translatable("tsu.mc.sym_color_label_fmt", sym.getBorderColor()).getString(),
                textX, textY, 0xFF888888, false);
        textY += 11;
        g.drawString(this.font, "R: " + sym.getBorderRadius() + "px", textX, textY, 0xFF888888, false);
        g.pose().popPose();
    }

    /** #15: 路線マップ上の駅/列車アイコンをクリックしたときの動作。
     *  列車 → 列車詳細 popup を開く (selectedTrainId、 snapshot は定期更新で追従)。
     *  駅 → 駅タブに切り替えてその駅を選択 (詳細を表示)。 いずれも既存の行クリックと同じ選択機構。 */
    private void handleMapHit(MapRenderer.MapHit hit) {
        if (hit.trainId() != null) {
            selectedTrainId = hit.trainId();
        } else if (hit.stationName() != null && hit.stationPos() != null) {
            tabs.switchTo("stations");
            selectedStationKey = ManagementComputerBlockEntity.stationKey(hit.stationName(), hit.stationPos());
        }
    }

    private void refreshNetworkData() {
        var computer = be();
        if (computer == null) return;

        BlockPos scanPos = null;
        var cardSlot = getMenu().getSlot(0);
        if (cardSlot != null && cardSlot.hasItem()) {
            var card = cardSlot.getItem();
            if (card.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                var tag = card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
                String type = tag.getString("Type");
                if ("track_network".equals(type) || "railway_manager".equals(type)) {
                    scanPos = BlockPos.of(tag.getLong("Pos"));
                }
            }
        }

        if (scanPos == null) {
            if (!mapNodes.isEmpty() || !mapStations.isEmpty() || !mapTrains.isEmpty()) {
                mapNodes = new ArrayList<>();
                mapEdges = new ArrayList<>();
                mapStations = new ArrayList<>();
                mapSignals = new ArrayList<>();
                mapTrains = new ArrayList<>();
                mapRenderer.resetInit();
            }
            lastNetworkScanPos = null;
            lastNetworkRefreshNano = 0L;
            return;
        }

        long now = System.nanoTime();
        boolean sourceChanged = lastNetworkScanPos == null || !lastNetworkScanPos.equals(scanPos);
        if (!sourceChanged && !mapNodes.isEmpty()
                && now - lastNetworkRefreshNano < NETWORK_REFRESH_INTERVAL_NS) {
            return;
        }

        try {
            // サーバー権威化 (MP desync 修正): client 側で TrackNetworkScanner を回さず、
            // server が updateNetworkCache() で毎秒 scan → sendBlockUpdated 同期した BE cache を読む。
            // dedicated server では client の Create RAILWAYS/TrackGraph が非同期・不完全で、
            // 旧 client scan + setClientSideCache が駅/列車を 0↔正常 にフリッカーさせていた。
            var be = be();
            mapNodes = be.getCachedNodes();
            mapEdges = be.getCachedEdges();
            mapStations = be.getCachedStations();
            mapSignals = be.getCachedSignals();
            mapTrains = be.getCachedTrains();
            clampTrainListScrolls();
            if (sourceChanged) mapRenderer.resetInit();
            lastNetworkScanPos = scanPos.immutable();
            lastNetworkRefreshNano = now;
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
    }

    private void refreshSelectedTrainSnapshot(UUID id) {
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
    private void drawLayoutPreview(GuiGraphics g, int cx, int cy, int cw, int ch,
                                    int mouseX, int mouseY) {
        int monW = serverBE().getMonitorW();
        int monH = serverBE().getMonitorH();
        if (monW <= 0 || monH <= 0) {
            String msg = tr("tsu.mc.monitor_unlinked");
            int tw = this.font.width(msg);
            g.drawString(this.font, msg, cx + (cw - tw) / 2, cy + ch / 2 - 4, 0xFFff8888, false);
            layoutPrevW = layoutPrevH = 0;
            return;
        }
        // アスペクト比保持
        float aspect = (float) monW / monH;
        int prevW, prevH;
        int pad = 6;
        int availW = cw - pad * 2, availH = ch - pad * 2;
        if (availW / aspect <= availH) { prevW = availW; prevH = (int)(availW / aspect); }
        else { prevH = availH; prevW = (int)(availH * aspect); }
        if (prevW <= 0 || prevH <= 0) return;
        int prevX = cx + (cw - prevW) / 2;
        int prevY = cy + (ch - prevH) / 2;
        // drag handler 用 (canvas-local; pose は popup 原点に既に変換済みなので追加変換不要)
        layoutPrevX = prevX;
        layoutPrevY = prevY;
        layoutPrevW = prevW;
        layoutPrevH = prevH;

        // モニター枠 + 背景
        g.fill(prevX - 1, prevY - 1, prevX + prevW + 1, prevY + prevH + 1, 0xFF4fc3f7);
        g.fill(prevX, prevY, prevX + prevW, prevY + prevH, 0xFF0a0a14);

        // 各パネル
        for (int i = 0; i < layoutEditor.getLayout().size(); i++) {
            var p = layoutEditor.getLayout().get(i);
            int px = prevX + (int)(p.getX() * prevW);
            int py = prevY + (int)(p.getY() * prevH);
            int pw = Math.max(8, (int)(p.getWidth() * prevW));
            int ph = Math.max(8, (int)(p.getHeight() * prevH));
            boolean selected = (i == layoutEditor.selectedIndex());
            int bgCol = selected ? 0x60ffc107 : 0x304fc3f7;
            int bcCol = selected ? 0xFFffc107 : 0xFF4fc3f7;
            g.fill(px, py, px + pw, py + ph, bgCol);
            // 枠
            g.fill(px, py, px + pw, py + 1, bcCol);
            g.fill(px, py + ph - 1, px + pw, py + ph, bcCol);
            g.fill(px, py + 1, px + 1, py + ph - 1, bcCol);
            g.fill(px + pw - 1, py + 1, px + pw, py + ph - 1, bcCol);
            // ラベル
            String label = p.getType().getDisplayName();
            int tw = this.font.width(label);
            if (tw < pw - 4 && this.font.lineHeight < ph - 2) {
                g.drawString(this.font, label, px + 3, py + 3,
                        selected ? 0xFFffc107 : 0xFFFFFFFF, false);
            }
        }
        // ヘルプ
        if (layoutEditor.getLayout().isEmpty()) {
            String hint = tr("tsu.mc.layout_click_hint");
            int tw = this.font.width(hint);
            g.drawString(this.font, hint, prevX + (prevW - tw) / 2, prevY + prevH / 2 - 4,
                    0xFF666688, false);
        } else if (layoutEditor.selectedIndex() >= 0) {
            String help = net.minecraft.network.chat.Component.translatable("tsu.mc.layout_help").getString();
            g.drawString(this.font, help, cx + 4, cy + ch - 11, 0xFF888888, false);
        }
    }

    /** LineSymbolPainter に委譲 (TSU 共有 util)。 */
    private void drawSymbolBadge(GuiGraphics g, int x, int y, int size,
                                  com.trainsystemutilities.blockentity.LineSymbol sym) {
        LineSymbolPainter.draw(g, x, y, size, sym, this.font);
    }
}
