package com.trainsystemutilities.client.gui;
import com.manta.api.anim.Transition;
import com.manta.api.draw.SmoothRenderer;
import com.manta.api.anim.Easing;
import com.manta.api.anim.Animation;

import com.manta.api.screen.JsonLayoutEngine;
import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.controller.ColorTargetController;
import com.manta.api.controller.ScrollViewport;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.schedule.TrainTypes;
import com.trainsystemutilities.gui.RailwayManagementMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * RailwayManagementScreen V2 (Phase 5-B-1)。
 * <p>主要レイアウト + 列車一覧 + 次列車 + monitor section + 基本トグル。
 * popup (settings/color/symbol) は Phase 5-B-2/3 で追加 (現状クリックしても何も起きない)。
 */
public class RailwayManagementScreenV2 extends JsonLayoutScreen<RailwayManagementMenu>
        implements com.manta.api.wiki.WikiLocalMenu {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    @Override
    protected String wikiPageId() { return "railway-management"; }

    /** Phase 18: SAS 統合アナウンス設定 popup の表示状態。 */
    boolean showAnnouncement = false;
    /** Phase 21: ホームドア設定 popup の表示状態。 */
    boolean showScreenDoor = false;
    /** Phase 21: ホームドア帯色 picker の表示状態 (= screen-door popup 内に重ねる)。 */
    boolean showScreenDoorColorPicker = false;
    /** Phase 21: 条件 entries の表示開始 index (= 5 個以上のときスクロール)。 */
    static final int SD_COND_VISIBLE = 4;
    final ScrollViewport sdCondScroll =
            new ScrollViewport(() -> be().getScreenDoorConditions().size(), SD_COND_VISIBLE);
    static final int SD_COND_AREA_Y = 200;
    static final int SD_COND_AREA_H = 72;
    /** 共有先候補リスト (= 8 個以上のときスクロール)。 */
    static final int ANN_SHARE_VISIBLE = 8;
    final ScrollViewport annShareScroll =
            new ScrollViewport(() -> getShareCandidateStations().size(), ANN_SHARE_VISIBLE);
    static final int ANN_SHARE_AREA_Y = 74;
    static final int ANN_SHARE_AREA_H = 196;
    /**
     * アナウンス entry 一覧 (= 6 個以上のときスクロール)。
     *
     * <p>領域は y=98 から 178px、layout の {@code stride} は 35px なので<b>5 行しか入らない</b>
     * (5x35=175)。以前は {@code ann-entry-count} が全件を返しており、6 個目以降が popup の
     * 枠外へはみ出して下の要素に重なっていた (2026-07-26 実機報告)。
     * 表示は 5 行に固定し、残りは scroll で見せる。
     *
     * <p><b>{@code ANN_ENTRY_AREA_H} は stride x VISIBLE と一致させること</b> —
     * ここがずれると scrollbar の thumb 位置と実際の行送りが食い違う。
     */
    static final int ANN_ENTRY_VISIBLE = 5;
    final ScrollViewport annEntryScroll = new ScrollViewport(() -> {
        var c = announcementConfig();
        return c != null ? c.size() : 0;
    }, ANN_ENTRY_VISIBLE);
    /** scrollbar の縦範囲 = 実際に見えている 5 行ぶん (stride 35 x 5)。 */
    static final int ANN_ENTRY_AREA_Y = 98;
    static final int ANN_ENTRY_AREA_H = 175;
    /** Phase 21: 3D preview のマウスドラッグ rotation / zoom / pan。 */
    // god-class 分割 増分 1: 3D preview の view 状態/描画/card 読取は controller が所有
    private final ScreenDoorPreviewController sdPreview = new ScreenDoorPreviewController();
    /** Phase 21: 帯色 preset (= picker JSON の sd-preset-N と同じ並び)。 */
    static final int[] SCREEN_DOOR_BAND_PRESETS = {
            0xFF66BB6A, 0xFF4FC3F7, 0xFFFFD54F, 0xFFFF8A65,
            0xFFEF5350, 0xFFAB47BC, 0xFF80DEEA, 0xFFFFFFFF,
            0xFF888888, 0xFF444444, 0xFFFFC107, 0xFF00BCD4
    };
    /** Phase 21: 機能ドロップダウン (= アナウンス / ホームドア 切替) の開閉。 */
    boolean showFunctionDropdown = false;
    /** How the function drop-down unfolds and folds (Manta DropdownMotion, 2026-10-07 - it replaced the open serial). */
    final com.manta.api.anim.DropdownMotion functionDropdownMotion = new com.manta.api.anim.DropdownMotion();
    /** Condition dropdown が開いている entry index。-1 = 閉じている。 Its panel's motion is the controller's. */
    final com.manta.api.controller.IndexedOverlayController conditionDropdown =
            new com.manta.api.controller.IndexedOverlayController();
    /** popup を開いた瞬間の nanoTime。アイテムを popup 開放アニメ (popIn 220ms) と同期 scale させるため。 */
    long announcementOpenedAtNanos = 0L;
    /** popIn(220) と同じ timing でアイテムをスケールイン。 */
    static final long ANNOUNCEMENT_OPEN_ANIM_NS = 220_000_000L;
    /** 検知カード共有先選択 sub-popup の表示状態。 */
    boolean showAnnouncementShareList = false;
    /** Entry reorder animation bookkeeping. Mirrors PosterManagement row shuffle. */
    static final int ANNOUNCEMENT_ENTRY_STRIDE = 35;
    private static final long ANNOUNCEMENT_SHUFFLE_ANIM_NS = 220_000_000L;
    private int lastAnnouncementMovedUpIdx = -1;
    private int lastAnnouncementMovedDownIdx = -1;
    private boolean pendingAnnouncementShuffle = false;
    private long announcementShuffleStartedAtNanos = 0L;
    private long announcementShuffleRequestedAtNanos = 0L;
    private com.trainsystemutilities.announce.AnnouncementConfig lastObservedAnnouncementConfig = null;

    static final int NEXT_TRAIN_PER_PAGE = 2;
    private static final int FUNCTION_DD_X = 156;
    private static final int FUNCTION_DD_W = 66;
    private static final int FUNCTION_DD_ITEM_H = 16;
    private static final int FUNCTION_DD_DOOR_Y = 228;
    private static final int FUNCTION_DD_ANNOUNCEMENT_Y = 246;
    private static final int FUNCTION_DD_BG_X = FUNCTION_DD_X - 2;
    private static final int FUNCTION_DD_BG_Y = FUNCTION_DD_DOOR_Y - 2;
    private static final int FUNCTION_DD_BG_W = FUNCTION_DD_W + 4;

    private Boolean localMonitorEnabled = null;
    int nextTrainPageIndex = 0;
    private int nextTrainPageTimer = 0;
    private static final int NEXT_TRAIN_ROTATE_TICKS = 200;

    // 5-B-2: settings popup
    boolean showSettings = false;
    boolean showBackFace = false;
    private Boolean localBatchApply = null;
    /** Batch apply toggle (= local optimistic state + clickButton(1) で server 反映)。 */
    final com.manta.api.controller.ToggleSwitchController batchToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "batch-toggle-track", "batch-toggle-knob",
                    this::batchApply,
                    v -> { localBatchApply = v; resetLocalOverrides(); clickButton(1); });
    /** Announcement master toggle (= cfg.isEnabled、TOGGLE_ENABLED payload で server 反映)。 */
    final com.manta.api.controller.ToggleSwitchController annMasterToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "ann-master-toggle-track", "ann-master-toggle-knob",
                    () -> { var c = announcementConfig(); return c != null && c.isEnabled(); },
                    v -> sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandAction.OP_TOGGLE_ENABLED, 0, 0, 0));
    /** Range frame toggle (= client-only state)。 */
    final com.manta.api.controller.ToggleSwitchController annRangeFrameToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "ann-rangeframe-toggle-track", "ann-rangeframe-toggle-knob",
                    () -> com.trainsystemutilities.client.gui.RangeFrameToggleState.isEnabled(be().getBlockPos()),
                    v -> com.trainsystemutilities.client.gui.RangeFrameToggleState.toggle(be().getBlockPos()));
    /** Phase 21: ホームドア group highlight toggle (= client-only)。 */
    final com.manta.api.controller.ToggleSwitchController sdHighlightToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "sd-highlight-toggle-track", "sd-highlight-toggle-knob",
                    () -> com.trainsystemutilities.client.gui.ScreenDoorHighlightToggleState.isEnabled(be().getBlockPos()),
                    v -> com.trainsystemutilities.client.gui.ScreenDoorHighlightToggleState.toggle(be().getBlockPos()));
    /** Attenuation toggle (= cfg.isAttenuationMode、未受信時 default ON)。 */
    final com.manta.api.controller.ToggleSwitchController annAttenuationToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "ann-attenuation-toggle-track", "ann-attenuation-toggle-knob",
                    () -> { var c = announcementConfig(); return c == null || c.isAttenuationMode(); },
                    v -> sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandAction.OP_TOGGLE_ATTENUATION, 0, 0, 0));
    /** Monitor toggle (= localMonitorEnabled + clickButton(0)、derived visual: monitorEnabled && groups > 0)。 */
    final com.manta.api.controller.ToggleSwitchController monitorToggle =
            new com.manta.api.controller.ToggleSwitchController(
                    "monitor-toggle-track", "monitor-toggle-knob",
                    this::monitorEnabled, v -> { localMonitorEnabled = v; clickButton(0); })
                    .withVisualState(() -> monitorEnabled() && be().getLinkedMonitorGroupCount() > 0);
    /** Per-station detection sharing toggle (= repeat idx ごと、サーバ payload で反映)。 */
    final com.manta.api.controller.IndexedToggleSwitchController annShareDetToggle =
            new com.manta.api.controller.IndexedToggleSwitchController(
                    "ann-share-det-toggle", "ann-share-det-knob",
                    idx -> {
                        var sts = getShareCandidateStations();
                        if (idx < 0 || idx >= sts.size()) return false;
                        var c = announcementConfig();
                        return c != null && c.isDetectionSharedTo(sts.get(idx).name());
                    },
                    idx -> sendShareToggle(idx,
                            com.trainsystemutilities.network.AnnouncementShareToggleAction.TYPE_DETECTION));
    /** Per-station range sharing toggle (= repeat idx ごと)。 */
    final com.manta.api.controller.IndexedToggleSwitchController annShareRngToggle =
            new com.manta.api.controller.IndexedToggleSwitchController(
                    "ann-share-rng-toggle", "ann-share-rng-knob",
                    idx -> {
                        var sts = getShareCandidateStations();
                        if (idx < 0 || idx >= sts.size()) return false;
                        var c = announcementConfig();
                        return c != null && c.isRangeSharedTo(sts.get(idx).name());
                    },
                    idx -> sendShareToggle(idx,
                            com.trainsystemutilities.network.AnnouncementShareToggleAction.TYPE_RANGE));
    private int localTrackNumber = -1;
    private int localTrackFontSize = -1;
    int localTrackPosition = -1;
    int localClockVisible = -1;
    private int localClockFontSize = -1;
    int selectedGroupIndex = 0;

    // 5-B-3: color popup
    boolean showColorSettings = false;
    /** Color popup controller (state + click/text resolvers)。
     *  RM は color update を server に直接書かず button id 経由で送る (V1 互換)。
     *  presetIdx と targetIdx を使って `base + targetIdx*100 + presetIdx` を encode。 */
    final ColorTargetController colorPopup =
            new ColorTargetController("color", COLOR_KEYS, COLOR_LABELS, COLOR_DEFAULTS,
                    new String[] {
                            "#4fc3f7", "#80deea", "#ff8a65", "#ffc107",
                            "#66bb6a", "#ef5350", "#ab47bc", "#ffffff",
                            "#888888", "#555555", "#444444", "#333333"
                    },
                    new ColorTargetController.ColorOps() {
                        @Override
                        public void applyPreset(int targetIdx, String key, int presetIdx, String hex) {
                            int base = showBackFace ? 20000 : 10000;
                            clickButton(base + targetIdx * 100 + presetIdx);
                        }
                        @Override
                        public void resetTarget(int targetIdx, String key) {
                            int base = showBackFace ? 20000 : 10000;
                            clickButton(base + targetIdx * 100 + 99);
                        }
                        @Override
                        public void resetAll() {
                            clickButton(showBackFace ? 21000 : 11000);
                        }
                        @Override
                        public String currentColor(String key, String defaultHex) {
                            // The face being edited, as the swatch beside it (getCurrentSelectedColorHex):
                            // the hex read the front face's while the presets wrote the back's.
                            return be().getColorOrDefault((showBackFace ? "back." : "") + key, defaultHex);
                        }
                    });
    private static final String[] COLOR_KEYS = {"arrTime", "depTime", "stopInfo", "routeType", "stopSec", "trainName", "nextName", "sectionTitle", "countdown", "trackNumber"};
    private static final String[] COLOR_LABELS = {
            Component.translatable("tsu.rm.color_label_arr_time").getString(),
            Component.translatable("tsu.rm.color_label_dep_time").getString(),
            Component.translatable("tsu.rm.color_label_stop_info").getString(),
            Component.translatable("tsu.rm.color_label_route_type").getString(),
            Component.translatable("tsu.rm.color_label_stop_sec").getString(),
            Component.translatable("tsu.rm.color_label_train_name").getString(),
            Component.translatable("tsu.rm.color_label_next_name").getString(),
            Component.translatable("tsu.rm.color_label_section_title").getString(),
            Component.translatable("tsu.rm.color_label_countdown").getString(),
            Component.translatable("tsu.rm.color_label_track_number").getString()};
    private static final String[] COLOR_DEFAULTS = {"#80deea", "#ff8a65", "#ffc107", "#555555", "#444444", "#4fc3f7", "#555555", "#4fc3f7", "#ffc107", "#4fc3f7"};
    static {
        // 配列長の整合性チェック (将来 KEYS だけ増やしたとき OOB を防ぐ)
        if (COLOR_KEYS.length != COLOR_LABELS.length || COLOR_KEYS.length != COLOR_DEFAULTS.length) {
            throw new IllegalStateException("RM color array length mismatch: KEYS="
                    + COLOR_KEYS.length + " LABELS=" + COLOR_LABELS.length
                    + " DEFAULTS=" + COLOR_DEFAULTS.length);
        }
    }
    /** Color preset palette — RailwayManagementMenu (server) presets[] と必ず同じ並びに保つこと。
     *  異なるとクリックしたボタン id がサーバ側で別 index に解釈されて違う色が適用される。 */
    private static final String[] COLOR_PRESETS = {
            "#4fc3f7", "#80deea", "#ff8a65", "#ffc107",
            "#66bb6a", "#ef5350", "#ab47bc", "#ffffff",
            "#888888", "#555555", "#444444", "#333333",
    };

    private boolean showResetConfirm = false;
    private boolean showResetAllConfirm = false;
    // 色対象ドロップダウン展開状態 / 選択中 index は colorPopup controller に集約済み

    static final int MAX_GROUPS = 4;

    public RailwayManagementScreenV2(RailwayManagementMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // A press outside an open drop-down folds it and does nothing else (the user's decision, 2026-10-07).
        foldOnOutsidePress(() -> showFunctionDropdown, () -> setFunctionDropdownOpen(false),
                "function-dd-trigger", "function-dd-item", "function-dd-bg");
        // The popup's own close button is the list's too: it closes the popup in one press, as ColorTargetController
        // documents (second reading, 2026-10-07 - unregistered, the first press only folded the list).
        foldOnOutsidePress(() -> showColorSettings && colorPopup.isDropdownOpen(), colorPopup::closeDropdown,
                "color-target-dropdown", "color-target-item", "color-target-caret", "color-target-list-bg",
                "color-popup-close");
        foldOnOutsidePress(() -> showAnnouncement && conditionDropdown.isOpen(), conditionDropdown::close,
                "ann-cond-display", "ann-cond-dd-item", "ann-cond-dd-bg");
    }

    // ===== Wiki live-capture support (WikiLiveCapture から呼ばれる) =====

    /**
     * Built by wikiCreate: the block entity is the wiki's stand-in and the menu its throwaway one, so what a press would
     * send to the server is applied to them here instead (clickButton, sendAnnouncementCmd) - the playback's containment
     * stops every packet, and the page showed presses that changed nothing (改善1, 2026-10-07).
     */
    boolean wikiMode = false;
    /** The entry a wiki test play lights, and until when (the server's playing entry, which no host sends here). */
    private int wikiPlayingEntry = -1;
    private long wikiPlayingUntil = 0L;

    /** wiki キャプチャ用にダミー BE + 空インベントリで screen を生成。 失敗時 null。 */
    public static RailwayManagementScreenV2 wikiCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.core.BlockPos pos = mc.player.blockPosition();
        var be = new RailwayManagementBlockEntity(
                pos, com.trainsystemutilities.registry.ModBlocks.RAILWAY_MANAGEMENT_BLOCK.get().defaultBlockState());
        be.setLevel(mc.level);
        be.setLineSymbol("JA", 1, "#4fc3f7"); // サンプル路線記号 (実物の SVG が出る)
        // The demo the page shows (改善1, 2026-10-07: a train at the platform and the trains coming, where it showed
        // none): the management computer's demo trains, a station with its line symbol, one linked monitor group.
        long tick = mc.level.getGameTime();
        long day = mc.level.getDayTime();
        be.wikiSeed("東京", new com.trainsystemutilities.blockentity.LineSymbol("JA", 1, "#4fc3f7", "Loop", 12),
                java.util.List.of(new RailwayManagementBlockEntity.ArrivedTrain(
                        UUID.nameUUIDFromBytes("tsu-wiki-rm-arrived".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "山手線 E235系", tick - WIKI_STOPPED_TICKS, day - WIKI_STOPPED_TICKS, 11, "品川",
                        WIKI_STOP_SEC, "CIRCULAR", "LOCAL", "", "")),
                java.util.List.of(
                        new RailwayManagementBlockEntity.NextTrain("中央線快速 E233系", 10, "神田", "SHUTTLE", 30,
                                day + 600, "神田", true, "RAPID"),
                        new RailwayManagementBlockEntity.NextTrain("京浜東北線 E233系", 10, "有楽町", "", 20,
                                day + 1500, "", false, "LOCAL"),
                        new RailwayManagementBlockEntity.NextTrain("回送列車", 4, "上野", "", 0,
                                day + 3000, "", false, "")),
                new RailwayManagementBlockEntity.MonitorGroupInfo(pos, 4, 2, true,
                        0, 0, 0, 0, 0, 0, 1, 0, 1, 0));
        Inventory inv = new Inventory(mc.player); // 持ち物アイテムを写さない
        // The SAS page puts this recording into an entry (a shift-click from the hotbar's first slot). Without SAS the
        // stack is empty: that step, the second last, then stops the playback (Manta's slot verdict - a click that
        // changes nothing) after the entry's settings were shown; the page says the popup needs SAS.
        inv.setItem(0, com.trainsystemutilities.compat.sas.SasIntegration.wikiRecordingMedium(
                "departure_melody.ogg", "ogg", 12));
        var menu = new RailwayManagementMenu(0, inv, be);
        var screen = new RailwayManagementScreenV2(menu, inv,
                Component.translatable("tsu.railway_management.title"));
        screen.wikiMode = true;
        return screen;
    }

    /** The demo train has stood this long when the page opens, and stops this long - then it arrives again. */
    private static final int WIKI_STOPPED_TICKS = 200;
    private static final int WIKI_STOP_SEC = 45;

    /** Only the SAS page opens the menu to a playback: its recording goes into an entry from the inventory. */
    @Override
    public boolean wikiLocalMenu() {
        return wikiMode && showAnnouncement;
    }

    /**
     * The demo as time goes on, without the server's sync: the train at the platform arrives again a few seconds after its
     * countdown ends, and the coming trains turn a page every ten seconds (containerTick's rotation - the wiki draws a
     * screen, it does not tick it).
     */
    private void wikiDemoTick() {
        var be = be();
        if (be.getLevel() == null) return;
        long tick = be.getLevel().getGameTime();
        var arrived = be.getArrivedTrains();
        if (!arrived.isEmpty()) {
            var t = arrived.get(0);
            if (tick - t.arrivalTick() >= (WIKI_STOP_SEC + 3) * 20L) {
                arrived.set(0, new RailwayManagementBlockEntity.ArrivedTrain(t.id(), t.name(), tick,
                        be.getLevel().getDayTime(), t.carriageCount(), t.destination(), t.scheduledStopSec(),
                        t.routeType(), t.trainType(), t.couplingStatus(), t.couplingPartner()));
            }
        }
        int pages = Math.max(1, (be.getNextTrains().size() + NEXT_TRAIN_PER_PAGE - 1) / NEXT_TRAIN_PER_PAGE);
        nextTrainPageIndex = (int) ((tick / NEXT_TRAIN_ROTATE_TICKS) % pages);
    }

    /** wiki キャプチャ用に overlay state を強制設定 (ドロップダウン等は閉じる)。 */
    public void wikiApplyState(String state) {
        showSettings = false;
        showColorSettings = false;
        showAnnouncement = false;
        showScreenDoor = false;
        showScreenDoorColorPicker = false;
        setFunctionDropdownOpen(false);     // ドロップダウンは常に閉じる (= 開きっぱなし防止)
        showAnnouncementShareList = false;
        switch (state) {
            case "settings"     -> showSettings = true;
            case "color"        -> showColorSettings = true;
            case "announcement" -> showAnnouncement = true;
            case "screen-door"  -> showScreenDoor = true;
            // BelugaAOS's UI sweep alone (WikiLiveCapture's sweep-only states, 2026-10-21): each list holding twice the
            // rows it shows, so its scrollbar thumb is up to be dragged. The demo block entity's own lists; nothing is sent.
            case "announcement-long" -> {
                showAnnouncement = true;
                var cfg = be().getAnnouncementConfig();
                for (int i = cfg.size(); i < 2 * ANN_ENTRY_VISIBLE; i++) cfg.addEntry();
            }
            case "announcement-share-long" -> {
                showAnnouncement = true;
                showAnnouncementShareList = true;
                wikiShareStations = new java.util.ArrayList<>();
                for (int i = 0; i < 2 * ANN_SHARE_VISIBLE; i++) {
                    wikiShareStations.add(new com.trainsystemutilities.network.TrackNetworkScanner.StationInfo(
                            "駅 " + (i + 1), new net.minecraft.core.BlockPos(0, 64, 16 * i)));
                }
            }
            case "screen-door-long" -> {
                showScreenDoor = true;
                for (int i = be().getScreenDoorConditions().size(); i < 2 * SD_COND_VISIBLE; i++) {
                    be().addScreenDoorCondition(com.trainsystemutilities.screendoor.ScreenDoorCondition.defaultEntry());
                }
            }
            default -> { /* main: overlay なし */ }
        }
    }

    /** The share list's stations of the sweep's "announcement-share-long", or null: the linked computer's, as always. */
    private java.util.List<com.trainsystemutilities.network.TrackNetworkScanner.StationInfo> wikiShareStations = null;

    // dialog-open scaleIn 同期の inventory item scale-in は MCSS 基底 JsonLayoutScreen が
    // default で提供。subclass 側のフィールド/override は不要。

    @Override
    protected String layoutJson() {
        return loadResourceJson("layouts/railway-management.json");
    }

    /** Settings popup を最初の overlay (左)、Color popup を 2 つ目の overlay (右) に
     *  別々に登録 → 両方を同時に開いておける (V1 同等)。 */
    @Override
    protected String overlayJson() {
        if (showSettings) return loadResourceJson("layouts/railway-management-settings.json");
        return null;
    }

    @Override
    protected String overlayJson2() {
        // 排他: アナウンス / ホームドア / Color popup は同じ右スロットを共有
        if (showScreenDoor) return loadResourceJson("layouts/railway-management-screen-door.json");
        if (showAnnouncement) return loadResourceJson("layouts/railway-management-announcement.json");
        if (showColorSettings) return loadResourceJson("layouts/railway-management-color.json");
        return null;
    }

    @Override
    protected int[] overlayDefaultPosition(int overlayW, int overlayH) {
        // Settings popup → ダイアログ左
        // Phase 5d FIX: dialogScale 適用 (autoscale 対応)
        return new int[]{dialogLocalToScreenX(-overlayW - 8), dialogLocalToScreenY(0)};
    }

    @Override
    protected int[] overlayDefaultPosition2(int overlayW, int overlayH) {
        // Color popup → ダイアログ右
        // Phase 5d FIX: dialogScale 適用 (autoscale 対応)
        return new int[]{dialogLocalToScreenX(this.imageWidth + 8), dialogLocalToScreenY(0)};
    }

    boolean batchApply() {
        return localBatchApply != null ? localBatchApply : be().isBatchApply();
    }

    int currentGroupIndex() {
        var groups = be().getMonitorGroups();
        return groups.isEmpty() ? 0 : Math.min(selectedGroupIndex, groups.size() - 1);
    }

    int currentTrackNumber() {
        var be = be();
        if (localTrackNumber >= 0) return localTrackNumber;
        var groups = be.getMonitorGroups();
        int gi = currentGroupIndex();
        if (showBackFace) {
            return batchApply() ? be.getGlobalBackTrackNumber()
                    : (!groups.isEmpty() ? groups.get(gi).backTrackNumber() : 0);
        }
        return batchApply() ? be.getGlobalTrackNumber()
                : (!groups.isEmpty() ? groups.get(gi).trackNumber() : 0);
    }

    int currentTrackFontSize() {
        var be = be();
        if (localTrackFontSize >= 0) return localTrackFontSize;
        var groups = be.getMonitorGroups();
        int gi = currentGroupIndex();
        if (showBackFace) {
            return batchApply() ? be.getGlobalBackTrackFontSize()
                    : (!groups.isEmpty() ? groups.get(gi).backTrackFontSize() : 0);
        }
        return batchApply() ? be.getGlobalTrackFontSize()
                : (!groups.isEmpty() ? groups.get(gi).trackFontSize() : 0);
    }

    int currentTrackPosition() {
        var be = be();
        if (localTrackPosition >= 0) return localTrackPosition;
        var groups = be.getMonitorGroups();
        int gi = currentGroupIndex();
        if (showBackFace) {
            return batchApply() ? be.getGlobalBackTrackPosition()
                    : (!groups.isEmpty() ? groups.get(gi).backTrackPosition() : 0);
        }
        return batchApply() ? be.getGlobalTrackPosition()
                : (!groups.isEmpty() ? groups.get(gi).trackPosition() : 0);
    }

    int currentClockVisible() {
        var be = be();
        if (localClockVisible >= 0) return localClockVisible;
        var groups = be.getMonitorGroups();
        int gi = currentGroupIndex();
        if (showBackFace) {
            return batchApply() ? be.getGlobalBackClockVisible()
                    : (!groups.isEmpty() ? groups.get(gi).backClockVisible() : 1);
        }
        return batchApply() ? be.getGlobalClockVisible()
                : (!groups.isEmpty() ? groups.get(gi).clockVisible() : 1);
    }

    int currentClockFontSize() {
        var be = be();
        if (localClockFontSize >= 0) return localClockFontSize;
        var groups = be.getMonitorGroups();
        int gi = currentGroupIndex();
        if (showBackFace) {
            return batchApply() ? be.getGlobalBackClockFontSize()
                    : (!groups.isEmpty() ? groups.get(gi).backClockFontSize() : 0);
        }
        return batchApply() ? be.getGlobalClockFontSize()
                : (!groups.isEmpty() ? groups.get(gi).clockFontSize() : 0);
    }

    /** 選択中ターゲットの現在色 hex 文字列 (BE から)。 */
    String getCurrentSelectedColorHex() {
        int idx = colorPopup.getSelectedIndex();
        if (idx < 0 || idx >= COLOR_KEYS.length) return "#000000";
        String key = (showBackFace ? "back." : "") + COLOR_KEYS[idx];
        return be().getColorOrDefault(key, COLOR_DEFAULTS[idx]);
    }

    // parseHexArgb は MCSS 基底 (JsonLayoutScreen.parseHexArgb) を使用。

    void resetLocalOverrides() {
        localTrackNumber = -1; localTrackFontSize = -1; localTrackPosition = -1;
        localClockVisible = -1; localClockFontSize = -1;
    }

    /** MCSS 基底の loadModResourceJson に委譲 (TsuLayouts.load 経由)。 */
    private static String loadResourceJson(String path) { return TsuLayouts.load(path); }

    RailwayManagementBlockEntity be() { return getMenu().getBlockEntity(); }
    /** Dispatch 用 package アクセサ (Screen.font は protected で他 class から不可視)。 */
    net.minecraft.client.gui.Font fontOrNull() { return this.font; }
    /** Dispatch 用 package アクセサ (AbstractContainerScreen.leftPos/topPos は protected で他 class から不可視)。 */
    int leftPosAccess() { return this.leftPos; }
    int topPosAccess() { return this.topPos; }

    boolean monitorEnabled() {
        return localMonitorEnabled != null ? localMonitorEnabled : be().isMonitorEnabled();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        pollData();
        var nextTrains = be().getNextTrains();
        int totalPages = nextTrains.isEmpty() ? 1
                : (nextTrains.size() + NEXT_TRAIN_PER_PAGE - 1) / NEXT_TRAIN_PER_PAGE;
        if (totalPages > 1) {
            nextTrainPageTimer++;
            if (nextTrainPageTimer >= NEXT_TRAIN_ROTATE_TICKS) {
                nextTrainPageIndex = (nextTrainPageIndex + 1) % totalPages;
                nextTrainPageTimer = 0;
            }
        }
        if (nextTrainPageIndex >= totalPages) nextTrainPageIndex = 0;
        updatePendingAnnouncementShuffle();
    }

    static String formatDayTime(long dayTime) {
        long ticks = (dayTime + 6000L) % 24000L;
        long hours = ticks / 1000L;
        long minutes = (ticks % 1000L) * 60L / 1000L;
        return String.format("%02d:%02d", hours, minutes);
    }

    /** arrivalDayTime + scheduledStopSec*20 → 発車予定時刻文字列。 */
    static String getDepartureTime(long arrivalDayTime, int stopSec) {
        if (stopSec <= 0) return "";
        return formatDayTime(arrivalDayTime + (long) stopSec * 20L);
    }

    /** train type コード → ローカライズ表示。 種別の定義は {@link TrainTypes} が単一情報源。 */
    static String trainTypeText(String code) {
        return TrainTypes.localize(code);
    }
    /** route type コード (SHUTTLE/CIRCULAR) → ローカライズ表示。 */
    static String routeTypeText(String code) {
        return switch (code) {
            case "SHUTTLE" -> Component.translatable("tsu.monitor.route_type_shuttle").getString();
            case "CIRCULAR" -> Component.translatable("tsu.monitor.route_type_circular").getString();
            default -> code;
        };
    }

    /** 文字列を指定 px 幅以下に切り詰め (超過したら末尾に "…")。font.width はピクセル単位。 */
    String fit(String text, int maxWidth) {
        if (text == null || text.isEmpty() || this.font == null) return text;
        return com.manta.api.hud.HudText.ellipsize(this.font, text, maxWidth);
    }

    String getMinecraftTime() {
        var be = be();
        if (be.getLevel() == null) return "00:00";
        return formatDayTime(be.getLevel().getDayTime());
    }

    /** The three lists' thumbs, grabbed and dragged (Manta 2.87.0's dragThumb, R4.19.5). */
    @Override
    public boolean onElementDrag(String[] classes, String key, int mouseX, int mouseY,
                                 int elementX, int elementY, int elementW, int elementH, boolean pressed) {
        if ("sd-cond-thumb-drag".equals(key)) {
            return sdCondScroll.dragThumb(mouseY, pressed);
        }
        if ("ann-entry-thumb-drag".equals(key)) {
            return annEntryScroll.dragThumb(mouseY, pressed);
        }
        if ("ann-share-thumb-drag".equals(key)) {
            return annShareScroll.dragThumb(mouseY, pressed);
        }
        return super.onElementDrag(classes, key, mouseX, mouseY, elementX, elementY, elementW, elementH, pressed);
    }

    @Override
    public boolean onElementWheel(String[] classes, String key,
                                   int mouseX, int mouseY, double scrollY) {
        if (showSettings && key != null && key.startsWith("settings-wheel-")) {
            wheelSettingsDisplay(key.substring("settings-wheel-".length()), scrollY > 0 ? 1 : -1);
            return true;
        }
        if ("ann-delay-wheel".equals(key)) {
            int idx = annEntryRealIdx();
            if (idx < 0) return false;
            int delta = scrollY > 0 ? 1 : -1;
            sendAnnouncementCmd(
                    com.trainsystemutilities.network.AnnouncementCommandAction.OP_ADJUST_ENTRY_DELAY,
                    idx, delta, 0);
            adjustEntryDelayLocally(idx, delta);
            return true;
        }
        if ("ann-count-wheel".equals(key)) {
            int idx = annEntryRealIdx();
            if (idx < 0) return false;
            int delta = scrollY > 0 ? 1 : -1;
            sendAnnouncementCmd(
                    com.trainsystemutilities.network.AnnouncementCommandAction.OP_ADJUST_ENTRY_PLAYCOUNT,
                    idx, delta, 0);
            adjustEntryPlayCountLocally(idx, delta);
            return true;
        }
        if ("sd-cond-track-wheel".equals(key)) {
            int idx = sdCondRealIdx();
            var conds = be().getScreenDoorConditions();
            if (idx < 0 || idx >= conds.size()) return false;
            int delta = scrollY > 0 ? 1 : -1;
            var cur = conds.get(idx);
            int next = Math.max(1, Math.min(99, cur.trackNumber() + delta));
            if (next != cur.trackNumber()) sendScreenDoorCondUpdate(idx, cur.withTrack(next));
            return true;
        }
        return false;
    }

    /** Phase 21: ホームドア条件 add (= server + client 即時反映)。 */
    void sendScreenDoorCondAdd() {
        sendData(com.trainsystemutilities.network.RailwayManagementData.DOOR_CONDITION,
                        com.trainsystemutilities.network.ScreenDoorConditionAction.OP_ADD,
                        0, 0, 0, 0);
        be().addScreenDoorCondition(
                com.trainsystemutilities.screendoor.ScreenDoorCondition.defaultEntry());
    }

    void sendScreenDoorCondRemove(int idx) {
        sendData(com.trainsystemutilities.network.RailwayManagementData.DOOR_CONDITION,
                        com.trainsystemutilities.network.ScreenDoorConditionAction.OP_REMOVE,
                        idx, 0, 0, 0);
        be().removeScreenDoorCondition(idx);
        sdCondScroll.clamp();
    }

    void sendScreenDoorCondUpdate(int idx,
            com.trainsystemutilities.screendoor.ScreenDoorCondition next) {
        sendData(com.trainsystemutilities.network.RailwayManagementData.DOOR_CONDITION,
                        com.trainsystemutilities.network.ScreenDoorConditionAction.OP_UPDATE,
                        idx, next.trackNumber(), next.eventType(), next.actionType());
        be().updateScreenDoorCondition(idx, next);
    }

    /** 現在の AnnouncementConfig (client-side cache から取得)。 The wiki's: the stand-in's own, which no host syncs. */
    com.trainsystemutilities.announce.AnnouncementConfig announcementConfig() {
        if (wikiMode) return be().getAnnouncementConfig();
        return com.trainsystemutilities.client.gui.RailwayAnnouncementClientState.getConfig(
                this.menu.getBlockEntity().getBlockPos());
    }

    /** R4.9.1: OP_SET_ENTRY_CONDITION を server へ送った直後に client cache の config を即時更新し、
     *  server 同期 (= 画面再オープン) を待たず表示へ反映させる (server は同値で reconcile)。 */
    void applyConditionTypeLocally(int entryIdx, int typeOrd) {
        var cfg = announcementConfig();
        var types = com.trainsystemutilities.announce.AnnouncementCondition.Type.values();
        if (cfg == null || entryIdx < 0 || entryIdx >= cfg.size() || typeOrd < 0 || typeOrd >= types.length) return;
        var entry = cfg.get(entryIdx);
        if (entry != null) entry.setCondition(entry.condition().withType(types[typeOrd]));
    }

    /** R4.9.1: OP_ADJUST_ENTRY_DELAY 送信直後の即時ローカル反映 (server と同一 withDelay で reconcile)。 */
    private void adjustEntryDelayLocally(int entryIdx, int delta) {
        var cfg = announcementConfig();
        if (cfg == null || entryIdx < 0 || entryIdx >= cfg.size()) return;
        var entry = cfg.get(entryIdx);
        if (entry != null) entry.setCondition(entry.condition().withDelay(entry.condition().delaySeconds + delta));
    }

    /** R4.9.1: OP_ADJUST_ENTRY_PLAYCOUNT 送信直後の即時ローカル反映 (server と同一 setPlayCount で reconcile)。 */
    private void adjustEntryPlayCountLocally(int entryIdx, int delta) {
        var cfg = announcementConfig();
        if (cfg == null || entryIdx < 0 || entryIdx >= cfg.size()) return;
        var entry = cfg.get(entryIdx);
        if (entry != null) entry.setPlayCount(entry.playCount() + delta);
    }

    int announcementPlayingEntryIndex() {
        int idx = wikiMode
                ? (System.currentTimeMillis() < wikiPlayingUntil ? wikiPlayingEntry : -1)
                : com.trainsystemutilities.client.gui.RailwayAnnouncementClientState.getPlayingEntry(
                        this.menu.getBlockEntity().getBlockPos());
        var cfg = announcementConfig();
        if (idx < 0 || cfg == null || idx >= cfg.size()) return -1;
        return idx;
    }

    void sendAnnouncementCmd(byte op, int a1, int a2, int a3) {
        if (wikiMode) {
            wikiAnnouncementCmd(op, a1, a2);
            return;
        }
        sendData(com.trainsystemutilities.network.RailwayManagementData.ANNOUNCEMENT, (int) op, a1, a2, a3);
    }

    /**
     * The wiki's stand-in takes a command as AnnouncementCommandAction applies it on the server, to its own config and
     * media. The condition, delay and play count are not here: their callers already apply them locally (R4.9.1). A test
     * play lights its entry for three seconds; nothing sounds.
     */
    private void wikiAnnouncementCmd(byte op, int a1, int a2) {
        var cfg = be().getAnnouncementConfig();
        switch (op) {
            case com.trainsystemutilities.network.AnnouncementCommandAction.OP_ADD_ENTRY -> cfg.addEntry();
            case com.trainsystemutilities.network.AnnouncementCommandAction.OP_REMOVE_ENTRY -> {
                be().removeAnnouncementMedia(a1);
                cfg.removeEntry(a1);
                annEntryScroll.clamp();
            }
            case com.trainsystemutilities.network.AnnouncementCommandAction.OP_TEST_PLAY -> {
                wikiPlayingEntry = a1;
                wikiPlayingUntil = System.currentTimeMillis() + 3000L;
            }
            case com.trainsystemutilities.network.AnnouncementCommandAction.OP_STOP_PLAYBACK -> wikiPlayingUntil = 0L;
            case com.trainsystemutilities.network.AnnouncementCommandAction.OP_TOGGLE_ENABLED ->
                    cfg.setEnabled(!cfg.isEnabled());
            case com.trainsystemutilities.network.AnnouncementCommandAction.OP_TOGGLE_ATTENUATION ->
                    cfg.setAttenuationMode(!cfg.isAttenuationMode());
            case com.trainsystemutilities.network.AnnouncementCommandAction.OP_REORDER_ENTRY -> {
                if (cfg.moveEntry(a1, a2)) be().swapAnnouncementMedia(a1, a2);
            }
            default -> { }
        }
    }

    /** Per-station share toggle (= IndexedToggleSwitchController から呼ばれる)。 */
    private void sendShareToggle(int idx, byte type) {
        var stations = getShareCandidateStations();
        if (idx < 0 || idx >= stations.size()) return;
        String stName = stations.get(idx).name();
        if (stName == null || stName.isEmpty()) return;
        // The server keeps a name only if SafeName admits it (128 bytes, the declaration): one it would drop is not
        // sent, and so none can reach past the declaration. The name is compared with the world's station names byte
        // for byte, so its param is declared exact (a string param is NFC) - and a name with no UTF-8 form (an unpaired
        // surrogate, which SafeName counts as '?') is refused by that declaration, so it is not sent either.
        try {
            com.manta.api.util.safe.SafeName.validate(stName);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (com.trainsystemutilities.network.ExactText.utf8Length(stName) < 0) return;
        sendData(com.trainsystemutilities.network.RailwayManagementData.ANNOUNCEMENT_SHARE, stName, (int) type);
    }

    /** 共有先候補となる station 一覧 (linked computer の cachedStations から、自局を除く)。
     *  client 側で BE.getUpdateTag 経由で同期されているため、server 経由しなくても取得可能。 */
    java.util.List<com.trainsystemutilities.network.TrackNetworkScanner.StationInfo> getShareCandidateStations() {
        if (wikiShareStations != null) return wikiShareStations;
        var be = be();
        if (this.minecraft == null || this.minecraft.level == null) return java.util.Collections.emptyList();
        if (be.getLinkedComputerPos() == null) return java.util.Collections.emptyList();
        var cbe = this.minecraft.level.getBlockEntity(be.getLinkedComputerPos());
        if (!(cbe instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity mcbe)) {
            return java.util.Collections.emptyList();
        }
        var all = mcbe.getCachedStations();
        if (all == null || all.isEmpty()) return java.util.Collections.emptyList();
        String selfName = be.getLinkedStationName();
        java.util.List<com.trainsystemutilities.network.TrackNetworkScanner.StationInfo> result =
                new java.util.ArrayList<>(all.size());
        for (var s : all) {
            if (selfName != null && selfName.equals(s.name())) continue;
            result.add(s);
        }
        return result;
    }

    void triggerAnnouncementSwap(int fromIdx, int toIdx) {
        if (fromIdx == toIdx) return;
        if (fromIdx > toIdx) {
            lastAnnouncementMovedUpIdx = toIdx;
            lastAnnouncementMovedDownIdx = fromIdx;
        } else {
            lastAnnouncementMovedDownIdx = toIdx;
            lastAnnouncementMovedUpIdx = fromIdx;
        }
        pendingAnnouncementShuffle = true;
        announcementShuffleRequestedAtNanos = System.nanoTime();
        lastObservedAnnouncementConfig = announcementConfig();
    }

    private void updatePendingAnnouncementShuffle() {
        var cfg = announcementConfig();
        long now = System.nanoTime();
        if (cfg != lastObservedAnnouncementConfig) {
            if (pendingAnnouncementShuffle && showAnnouncement && cfg != null) {
                announcementShuffleStartedAtNanos = now;
                pendingAnnouncementShuffle = false;
            }
            // entry 削除はサーバー往復なので、送信直後ではなく **config が実際に入れ替わった
            // ここ** で clamp する。最終ページで削除したときに offset が総数を追い越したまま
            // 残ると、空行が並ぶか index が範囲外を指す。
            annEntryScroll.clamp();
            lastObservedAnnouncementConfig = cfg;
        }
        if (pendingAnnouncementShuffle
                && announcementShuffleRequestedAtNanos > 0L
                && now - announcementShuffleRequestedAtNanos > 1_000_000_000L) {
            pendingAnnouncementShuffle = false;
        }
        if (announcementShuffleStartedAtNanos > 0L
                && now - announcementShuffleStartedAtNanos >= ANNOUNCEMENT_SHUFFLE_ANIM_NS) {
            announcementShuffleStartedAtNanos = 0L;
            lastAnnouncementMovedUpIdx = -1;
            lastAnnouncementMovedDownIdx = -1;
        }
    }

    float announcementEntryShuffleOffset(int entryIdx) {
        if (announcementShuffleStartedAtNanos <= 0L) return 0f;
        float from = announcementEntryShuffleDistance(entryIdx);
        if (from == 0f) return 0f;
        long elapsed = System.nanoTime() - announcementShuffleStartedAtNanos;
        if (elapsed >= ANNOUNCEMENT_SHUFFLE_ANIM_NS) return 0f;
        float t = Math.max(0f, Math.min(1f, elapsed / (float) ANNOUNCEMENT_SHUFFLE_ANIM_NS));
        float eased = com.manta.api.anim.Easing.EASE_OUT.apply(t);
        return from + (0f - from) * eased;
    }

    float announcementEntryShuffleDistance(int entryIdx) {
        if (announcementShuffleStartedAtNanos <= 0L) return 0f;
        if (entryIdx == lastAnnouncementMovedUpIdx) return ANNOUNCEMENT_ENTRY_STRIDE;
        if (entryIdx == lastAnnouncementMovedDownIdx) return -ANNOUNCEMENT_ENTRY_STRIDE;
        return 0f;
    }

    /** popup の右側展開位置に基づいて detection / range / per-entry media slots を配置。
     *  popup 開放アニメ中も slot 位置は確定させる (描画側 {@link RailwayManagementDispatch#renderPopupOverlayItems}
     *  でアニメに同期した scale を適用するため)。 */
    void positionAnnouncementSlots() {

        // 検知カードスロット: frame x=68, y=291, 18x18 → slot x=69, y=292
        RailwayManagementDispatch.setMenuSlotPos(getMenu(), com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_DETECTION_CARD,
                popupSlotX(69), popupSlotY(292));
        // 範囲指定ボード: frame x=174, y=291, 18x18 → slot x=175, y=292
        RailwayManagementDispatch.setMenuSlotPos(getMenu(), com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_RANGE_BOARD,
                popupSlotX(175), popupSlotY(292));

        // Entry media slots: 画面上の行 r の frame x=192, y=68+r*35 → slot x=193, y=103+r*35
        //
        // **entry index ではなく画面行で置く**。media slot は JSON layout ではなく Minecraft の
        // Slot なので repeat の表示件数に従わない。以前は entry index をそのまま y に使っており、
        // スクロールで隠れているはずの 6 個目以降の slot が一覧の下 (共有ボタン付近) に残って
        // クリック判定とアイテム描画が出ていた (2026-07-26 実機報告)。
        // 表示窓の外は off-screen に追い出す — これで「スクロールすると数字だけ動く」も直る。
        //
        // share popup が開いている間は entry 行が visibleKey で隠れるので同様に追い出す。
        var cfg = announcementConfig();
        int n = cfg != null ? cfg.size() : 0;
        boolean hideMedia = showAnnouncementShareList;
        int first = annEntryScroll.offset();
        int last = first + ANN_ENTRY_VISIBLE;   // exclusive
        for (int i = 0; i < com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES; i++) {
            int slotIdx = com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE + i;
            boolean onScreen = i < n && !hideMedia && i >= first && i < last;
            if (onScreen) {
                int row = i - first;
                RailwayManagementDispatch.setMenuSlotPos(getMenu(), slotIdx,
                        popupSlotX(193), popupSlotY(103 + row * 35));
            } else {
                RailwayManagementDispatch.setMenuSlotPos(getMenu(), slotIdx, -1000, -1000);
            }
        }
    }

    void hideAnnouncementSlots() {
        RailwayManagementDispatch.setMenuSlotPos(getMenu(), com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_DETECTION_CARD, -1000, -1000);
        RailwayManagementDispatch.setMenuSlotPos(getMenu(), com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_RANGE_BOARD, -1000, -1000);
        for (int i = 0; i < com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES; i++) {
            RailwayManagementDispatch.setMenuSlotPos(getMenu(), com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE + i,
                    -1000, -1000);
        }
    }

    /** Screen door popup の slot 位置決定 (= popup x=94, y=32, 18x18)。 */
    void positionScreenDoorSlot() {
        RailwayManagementDispatch.setMenuSlotPos(getMenu(), com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD,
                popupSlotX(95), popupSlotY(291));
    }

    /** popup (overlay2) 上の論理座標 → Menu Slot 座標。
     *  overlay は自身の原点 pivot で scale するため、manta の変換 API を必ず通す
     *  (screen 差分 + 論理座標の素朴な足し算は scale != 1.0 でずれる)。
     *  overlay2 が未配置 (座標 0,0) の間だけ等倍の暫定位置に置く。 */
    private int popupSlotX(float localX) {
        if (overlay2X() != 0 || overlay2Y() != 0) return overlay2LocalToSlotX(localX);
        return this.imageWidth + 8 + Math.round(localX);
    }

    private int popupSlotY(float localY) {
        if (overlay2X() != 0 || overlay2Y() != 0) return overlay2LocalToSlotY(localY);
        return Math.round(localY);
    }

    void hideScreenDoorSlot() {
        RailwayManagementDispatch.setMenuSlotPos(getMenu(), com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD,
                -1000, -1000);
    }

    void resetAnnouncementTransientState() {
        announcementOpenedAtNanos = 0L;
        conditionDropdown.close();
        showAnnouncementShareList = false;
        pendingAnnouncementShuffle = false;
        announcementShuffleStartedAtNanos = 0L;
        announcementShuffleRequestedAtNanos = 0L;
        lastAnnouncementMovedUpIdx = -1;
        lastAnnouncementMovedDownIdx = -1;
    }

    void setFunctionDropdownOpen(boolean open) {
        if (showFunctionDropdown == open) return;
        showFunctionDropdown = open;
        functionDropdownMotion.setOpen(open);
    }

    void toggleFunctionDropdown() {
        setFunctionDropdownOpen(!showFunctionDropdown);
    }

    void resetScreenDoorPreviewView() {
        sdPreview.resetView();
    }

    /** 引数は dialog-local ({@link #isRawHovering} と同じ空間)。現在 呼び出し元なし。 */
    private boolean isFunctionDropdownItemHovering(double localX, double localY) {
        if (!showFunctionDropdown) return false;
        if (isRawHovering(FUNCTION_DD_X, FUNCTION_DD_DOOR_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, localX, localY)) {
            return true;
        }
        return com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()
                && isRawHovering(FUNCTION_DD_X, FUNCTION_DD_ANNOUNCEMENT_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, localX, localY);
    }

    int functionDropdownPanelHeight() {
        return com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()
                ? FUNCTION_DD_ITEM_H * 2 + 6
                : FUNCTION_DD_ITEM_H + 4;
    }

    /** 引数は vanilla の論理座標 (= 基底が pointer を見る super すべて — render / mouseClicked /
     *  mouseDragged / mouseReleased — に渡す、縮尺を戻した値。{@code leftPos} を引くと dialog-local)。
     *  {@link #isHovering} 経由専用で、vanilla は findSlot からその 4 経路で呼ぶ。 */
    private boolean isFunctionDropdownPanelHovering(double logicalX, double logicalY) {
        return showFunctionDropdown
                && isRawHovering(FUNCTION_DD_BG_X, FUNCTION_DD_BG_Y,
                FUNCTION_DD_BG_W, functionDropdownPanelHeight(),
                logicalX - this.leftPos, logicalY - this.topPos);
    }

    /**
     * x/y/width/height は layout の論理座標なので、<b>localX/localY も dialog-local で渡すこと</b>。
     *
     * <p>呼び出し元で空間が違う: {@link #isHovering} には vanilla の論理座標が来る（{@code leftPos}
     * を引けば local）が、{@code mouseClicked} には生の screen が来る（{@code toLocalMx} を通す）。
     * 2026-09-12 に「screen とみなして一律 {@code toLocalMx}」に変えたところ、前者が二重変換に
     * なり UI スケールつまみ 0.910 でドロップダウン下端の slot が拾えなくなった（二次読みが検出）。
     * 変換は呼び出し元の仕事、ここは比較だけ。
     */
    private boolean isRawHovering(int x, int y, int width, int height, double localX, double localY) {
        return localX >= (double) (x - 1)
                && localX < (double) (x + width + 1)
                && localY >= (double) (y - 1)
                && localY < (double) (y + height + 1);
    }

    @Override
    protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (showFunctionDropdown && width <= 20 && height <= 20
                && isFunctionDropdownPanelHovering(mouseX, mouseY)) {
            return false;
        }
        return super.isHovering(x, y, width, height, mouseX, mouseY);
    }

    private boolean handleFunctionDropdownClickBeforeSlots(double mouseX, double mouseY, int button) {
        if (!showFunctionDropdown) return false;
        // ここは mouseClicked からの生 screen 座標なので、基底の写像で dialog-local に落とす
        // (UI スケールつまみが 1.0 でないと引き算だけでは合わない - 実機 2026-09-12)。
        final double localX = toLocalMx(mouseX);
        final double localY = toLocalMy(mouseY);
        if (isRawHovering(FUNCTION_DD_X, FUNCTION_DD_DOOR_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, localX, localY)) {
            if (button != 0) return true;
            toggleScreenDoorPopupFromFunctionDropdown();
            return true;
        }
        if (com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()
                && isRawHovering(FUNCTION_DD_X, FUNCTION_DD_ANNOUNCEMENT_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, localX, localY)) {
            if (button != 0) return true;
            toggleAnnouncementPopupFromFunctionDropdown();
            return true;
        }
        return false;
    }

    private boolean handleScreenDoorColorPickerClick(double mouseX, double mouseY, int button) {
        if (!showScreenDoor || !showScreenDoorColorPicker) return false;
        if (button != 0) return isOverlay2RawHovering(40, 80, 160, 150, mouseX, mouseY);
        if (isOverlay2RawHovering(174, 88, 16, 14, mouseX, mouseY)) {
            showScreenDoorColorPicker = false;
            return true;
        }
        for (int i = 0; i < SCREEN_DOOR_BAND_PRESETS.length; i++) {
            int row = i < 5 ? 0 : (i < 10 ? 1 : 2);
            int col = row == 0 ? i : (row == 1 ? i - 5 : i - 10);
            int x = 52 + col * 26;
            int y = 114 + row * 26;
            if (isOverlay2RawHovering(x, y, 22, 22, mouseX, mouseY)) {
                applyScreenDoorBandColor(SCREEN_DOOR_BAND_PRESETS[i]);
                return true;
            }
        }
        return isOverlay2RawHovering(40, 80, 160, 150, mouseX, mouseY);
    }

    /** vanilla が今の frame に選んだ hover (popup の下に隠れた slot は manta の基底が外した後)。 */
    net.minecraft.world.inventory.Slot hoveredSlotAccess() {
        return this.hoveredSlot;
    }

    /** popup が開いている間のツールチップは render が popup の上 (z=900) に 1 枚だけ描く — 基底のものは 2 枚目になる
     *  (2026-10-02 実機: 縮尺 0.67 で、カーソルの位置と論理座標の位置に 2 枚並んだ)。 */
    @Override
    protected void renderTooltip(net.minecraft.client.gui.GuiGraphics g, int x, int y) {
        if (showAnnouncement || showScreenDoor) return;
        super.renderTooltip(g, x, y);
    }

    private boolean isOverlay2RawHovering(int x, int y, int width, int height,
                                          double mouseX, double mouseY) {
        // popup は自身の原点 pivot で scale するので、原点を引いたあと scale で割って
        // popup-local へ戻す (引くだけだと つまみが 1.0 でないときずれる - 実機 2026-09-12)。
        float s = overlay2Scale() > 0f ? overlay2Scale() : 1f;
        mouseX = (mouseX - overlay2X()) / s;
        mouseY = (mouseY - overlay2Y()) / s;
        return mouseX >= (double) x
                && mouseX < (double) (x + width)
                && mouseY >= (double) y
                && mouseY < (double) (y + height);
    }

    private void applyScreenDoorBandColor(int argb) {
        sendData(com.trainsystemutilities.network.RailwayManagementData.DOOR_BAND_COLOR, argb);
        be().setScreenDoorBandColorARGB(argb);
        showScreenDoorColorPicker = false;
    }

    private void toggleAnnouncementPopupFromFunctionDropdown() {
        setFunctionDropdownOpen(false);
        if (!com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()) return;
        if (showColorSettings) { showColorSettings = false; colorPopup.resetTransientState(); }
        if (showScreenDoor) { showScreenDoor = false; hideScreenDoorSlot(); }
        showAnnouncement = !showAnnouncement;
        if (showAnnouncement) {
            resetAnnouncementTransientState();
            positionAnnouncementSlots();
        } else {
            resetAnnouncementTransientState();
            hideAnnouncementSlots();
        }
    }

    private void toggleScreenDoorPopupFromFunctionDropdown() {
        setFunctionDropdownOpen(false);
        if (showColorSettings) { showColorSettings = false; colorPopup.resetTransientState(); }
        if (showAnnouncement) {
            showAnnouncement = false;
            resetAnnouncementTransientState();
            hideAnnouncementSlots();
        }
        showScreenDoor = !showScreenDoor;
        if (showScreenDoor) {
            resetScreenDoorPreviewView();
            positionScreenDoorSlot();
        } else {
            hideScreenDoorSlot();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (handleScreenDoorColorPickerClick(mouseX, mouseY, button)) {
            return true;
        }
        if (handleFunctionDropdownClickBeforeSlots(mouseX, mouseY, button)) {
            return true;
        }
        // 3D preview のマウスドラッグ開始 (= 0:左=回転, 1:右=pan)
        if ((button == 0 || button == 1) && isOverScreenDoorPreview(mouseX, mouseY)) {
            sdPreview.beginDrag(button, mouseX, mouseY);
            return true;
        }
        // popup overlay の click は JsonLayoutScreen が常に消費するため、
        // popup 内 slot のクリックがそのままだと slot 操作 (取り出し/設置) に届かない。
        // 先に hover 判定して slot click なら直接 slotClicked() を呼ぶ。
        if ((showAnnouncement || showScreenDoor) && button >= 0 && button <= 2) {
            // クリック処理前に slot 位置を最新化 (overlay2 ドラッグ後 / 開いた直後でも正しい hit 判定をするため)。
            if (showAnnouncement) positionAnnouncementSlots();
            if (showScreenDoor) positionScreenDoorSlot();
            for (int i = com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE;
                 i < this.menu.slots.size(); i++) {
                var slot = this.menu.slots.get(i);
                if (!slot.isActive()) continue;
                if (slot.x < -500) continue;
                if (RailwayManagementDispatch.isOverPopupSlot(this, slot, mouseX, mouseY)) {
                    net.minecraft.world.inventory.ClickType type;
                    if (com.manta.api.hud.ModifierKeys.shift(net.minecraft.client.Minecraft.getInstance().getWindow().getWindow())) {
                        type = net.minecraft.world.inventory.ClickType.QUICK_MOVE;
                    } else if (button == 2
                            && this.minecraft != null && this.minecraft.player != null
                            && this.minecraft.player.getAbilities().instabuild) {
                        type = net.minecraft.world.inventory.ClickType.CLONE;
                    } else {
                        type = net.minecraft.world.inventory.ClickType.PICKUP;
                    }
                    this.slotClicked(slot, slot.index, button, type);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** popup 内 slot 上での release は base class に渡さず消費。
     *  base の AbstractContainerScreen.mouseReleased は press 時に内部 state
     *  (skipNextRelease 等) を立てない我々の独自経路と整合せず、quick-craft drag-end として
     *  carrying item を意図せず drop する。我々は mouseClicked 時点で slotClicked を発行
     *  済み (= vanilla の skipNextRelease=true 相当) なので release は no-op で良い。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (sdPreview.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (sdPreview.mouseReleased(button)) {
            return true;
        }
        // quick-craft の最中は飲まない: popup slot の上の press は上で slotClicked にしたので vanilla の quick-craft は始まって
        // いない。始まっているなら popup の外からのドラッグで、ここで飲むと分配が次の release まで開いたまま残る
        // (2026-10-02 実機: 29 → popup slot 37 のドラッグで何も分配されず 64 個を持ったまま)。基底が consumer の slot の上の
        // release を vanilla に渡し、vanilla が分配を確定する。
        if (!this.isQuickCrafting && (showAnnouncement || showScreenDoor) && button >= 0 && button <= 2) {
            for (int i = com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE;
                 i < this.menu.slots.size(); i++) {
                var slot = this.menu.slots.get(i);
                if (!slot.isActive()) continue;
                if (slot.x < -500) continue;
                if (RailwayManagementDispatch.isOverPopupSlot(this, slot, mouseX, mouseY)) {
                    return true; // 同じ popup slot で release: pickup/place は既に click 時に処理済み
                }
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ===== Manta 7 push (Phase 4, 2026-09-24) =====
    // The pages' values are WRITTEN by RailwayManagementPush each frame before the engine draws, and
    // once when a page opens: the screen overrides no getDynamic*.
    final RailwayManagementPush pushState = new RailwayManagementPush(this);

    @Override
    protected void pageOpened(Object page, com.manta.api.screen.PageLayer layer) {
        pushState.pageOpened(page, layer);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // popup が「初めて」見える瞬間に anim 開始時刻を engine と同フレームでセット。
        // これで popup の popIn と slot scale-in が完全同期する (click 時に set すると 1 フレーム早く始まる)。
        if (showAnnouncement && announcementOpenedAtNanos == 0L) {
            announcementOpenedAtNanos = System.nanoTime();
        }
        if (wikiMode) wikiDemoTick();
        updatePendingAnnouncementShuffle();
        // popup が開いている間は毎フレーム slot 位置を popup 座標に合わせる
        // (overlay 位置はドラッグで変わる可能性があるため)。閉じている間は off-screen に強制。
        if (showAnnouncement) positionAnnouncementSlots();
        else hideAnnouncementSlots();
        if (showScreenDoor && !showScreenDoorColorPicker) positionScreenDoorSlot();
        else hideScreenDoorSlot();
        syncScreenDoorHighlight();
        pushState.pushAll();
        super.render(g, mouseX, mouseY, partialTick);

        // popup 内の slot は super.render で z=0 に描画されるため popup (z=600) の下に隠れる。
        // 上書きで slot icon + item + carried item を z=700 で再描画する。
        if (showAnnouncement || showScreenDoor) RailwayManagementDispatch.renderPopupOverlayItems(this, g, mouseX, mouseY);

        // popup を開いている時はベースの renderTooltip (= super.render 内で z≈0) では
        // popup の下に隠れてしまうため、ホバー中の slot に対するツールチップを popup の上 (z=900) に
        // 改めて描き直す。プレイヤーインベントリ / アナウンスポップアップ両方の slot で機能する。
        if ((showAnnouncement || showScreenDoor) && this.menu.getCarried().isEmpty()) {
            net.minecraft.world.inventory.Slot hovered = RailwayManagementDispatch.findHoveredSlot(this, mouseX, mouseY);
            if (hovered != null && hovered.hasItem()) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 900);
                var stack = hovered.getItem();
                com.manta.api.render.ItemDraw.tooltip(g, this.font,
                        this.getTooltipFromContainerItem(stack),
                        stack.getTooltipImage(), stack, mouseX, mouseY);
                g.pose().popPose();
            }
        }
    }

    @Override
    protected void renderSlot(net.minecraft.client.gui.GuiGraphics g,
                              net.minecraft.world.inventory.Slot slot) {
        // Announcement popup の slot は z=700 (renderPopupOverlayItems) で描画するため、
        // 基底側の z=0 描画はスキップ。アイテム重複描画と popup 透過時の bleed-through を防ぐ。
        if ((showAnnouncement || showScreenDoor)
                && slot.index >= com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE) {
            return;
        }
        if (showFunctionDropdown && isSlotCoveredByFunctionDropdown(slot)) {
            return;
        }
        super.renderSlot(g, slot);
    }

    private boolean isSlotCoveredByFunctionDropdown(net.minecraft.world.inventory.Slot slot) {
        int panelH = functionDropdownPanelHeight();
        return slot.x < FUNCTION_DD_BG_X + FUNCTION_DD_BG_W
                && slot.x + 16 > FUNCTION_DD_BG_X
                && slot.y < FUNCTION_DD_BG_Y + panelH
                && slot.y + 16 > FUNCTION_DD_BG_Y;
    }


    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // 右クリックで「条件をなしに戻す」 (cond-display only)
        if (button == 1) {
            for (String c : classes) {
                if ("ann-cond-display".equals(c)) {
                    int idx = annEntryRealIdx();
                    if (idx >= 0) {
                        int noneOrd = com.trainsystemutilities.announce.AnnouncementCondition.Type.NONE.ordinal();
                        sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandAction.OP_SET_ENTRY_CONDITION,
                                idx, noneOrd, 0);
                        applyConditionTypeLocally(idx, noneOrd);
                    }
                    return;
                }
            }
        }
        // BelugaExperience 標準ヘッダ部品 (R4.17): hint / wiki本 / × を base より先に処理。
        // railway は 4-arg を super 無しで override しているため、ここで明示的にルートする。
        if (com.manta.api.hud.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) com.manta.api.wiki.Wiki.open(pid);
                return;
            }
            if ("mc-popup-close".equals(c)) { if (closeOpenOverlay()) return; onClose(); return; }
        }
        onElementClick(classes, mouseX, mouseY);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        RailwayManagementDispatch.click(this, classes, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        // 3D preview の zoom (= 長いホーム想定で上限 30x まで拡大可)
        if (isOverScreenDoorPreview(mx, my)) {
            sdPreview.zoomBy(dy);
            return true;
        }
        // 条件 entries 領域上で wheel → スクロール offset 増減
        if (showScreenDoor && !showScreenDoorColorPicker
                && isOverScreenDoorCondArea(mx, my)) {
            if (sdCondScroll.needsScrollbar()) {
                sdCondScroll.scroll(dy > 0 ? -1 : 1);
                return true;
            }
        }
        // 共有リスト表示中の wheel → スクロール
        if (showAnnouncement && showAnnouncementShareList && annShareScroll.needsScrollbar()) {
            annShareScroll.scroll(dy > 0 ? -1 : 1);
            return true;
        }
        // アナウンス entry 一覧の wheel → スクロール。
        // 共有リストが手前に出ているときは上の分岐が先に食うので、ここは一覧が見えている時だけ。
        if (showAnnouncement && !showAnnouncementShareList && annEntryScroll.needsScrollbar()) {
            annEntryScroll.scroll(dy > 0 ? -1 : 1);
            return true;
        }
        if (showSettings) {
            // The displays' wheel is the engine's (their wheelKey settings-wheel-*, onElementWheel - 2026-10-07, so a wiki
            // playback's wheel is aimed at its display as a press is); any other wheel over the open popup is consumed.
            super.mouseScrolled(mx, my, dx, dy);
            return true;  // popup 内のスクロール消費
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    /** A settings display under the wheel (its wheelKey settings-wheel-*): the numbers step, the switches flip. */
    private void wheelSettingsDisplay(String display, int delta) {
        int gi = currentGroupIndex();
        switch (display) {
            case "track" -> {
                int newV = Math.max(0, Math.min(99, currentTrackNumber() + delta));
                localTrackNumber = newV;
                if (showBackFace) clickButton(batchApply() ? 2000 + newV : 2200 + gi * 100 + newV);
                else clickButton(batchApply() ? 2 + newV : 200 + gi * 100 + newV);
            }
            case "font" -> {
                int newV = Math.max(0, Math.min(97, currentTrackFontSize() + delta));
                localTrackFontSize = newV;
                if (showBackFace) clickButton(batchApply() ? 2100 + newV : 2500 + gi * 100 + newV);
                else clickButton(batchApply() ? 102 + newV : 500 + gi * 100 + newV);
            }
            case "clockfs" -> {
                int newV = Math.max(0, Math.min(97, currentClockFontSize() + delta));
                localClockFontSize = newV;
                if (showBackFace) clickButton(batchApply() ? 5100 + newV : 5200 + gi * 100 + newV);
                else clickButton(batchApply() ? 4100 + newV : 4200 + gi * 100 + newV);
            }
            // pos / clock visible は wheel でも切替 (mouseClicked と同じ挙動)
            case "pos", "clock" -> onElementClick(new String[]{"track-display-" + display}, 0, 0);
            default -> { }
        }
    }

    /** MCSS 基底の sendButtonClick に委譲 (旧 clickButton 名は call site 互換のため残す)。 The wiki's stand-in takes the
     *  button as the server's menu would (RailwayManagementMenu.clickMenuButton: its block entity's values only). */
    void clickButton(int id) {
        if (wikiMode) {
            getMenu().clickMenuButton(Minecraft.getInstance().player, id);
            return;
        }
        sendButtonClick(id);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (closeOpenOverlay()) return true;
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean closeOpenOverlay() {
        if (showColorSettings && colorPopup.isDropdownOpen()) {
            colorPopup.closeDropdown(); return true;
        }
        if (showFunctionDropdown) {
            setFunctionDropdownOpen(false); return true;
        }
        if (showAnnouncement && showAnnouncementShareList) {
            showAnnouncementShareList = false; return true;
        }
        if (showAnnouncement && conditionDropdown.isOpen()) {
            conditionDropdown.close(); return true;
        }
        if (showAnnouncement) {
            showAnnouncement = false; resetAnnouncementTransientState();
            hideAnnouncementSlots(); return true;
        }
        if (showColorSettings) {
            showColorSettings = false; colorPopup.resetTransientState(); return true;
        }
        if (showSettings) { showSettings = false; resetLocalOverrides(); return true; }
        return false;
    }

    /** Color popup 内の dropdown だけは外クリックで閉じる (transient)。
     *  色設定 popup / 設定 popup 自体は persistent (× で閉じる)。 */
    @Override
    protected boolean closeTransientOverlays() {
        if (showColorSettings && colorPopup.isDropdownOpen()) {
            colorPopup.closeDropdown(); return true;
        }
        return false;
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        switch (key) {
            case "owner-face" -> drawOwnerFace(g, x, y, w, h);
            case "header-sym" -> drawHeaderSymbol(g, x, y, w, h);
            case "monitor-preview" -> drawMonitorPreview(g, x, y, w, h);
            case "sd-preview" -> drawScreenDoorPreview(g, x, y, w, h);
        }
    }

    /** ホームドア popup 内の 3D preview。本体は {@link ScreenDoorPreviewController#draw}。 */
    private void drawScreenDoorPreview(GuiGraphics g, int x, int y, int w, int h) {
        sdPreview.draw(g, x, y, w, h, this.font, this.menu,
                this.minecraft != null ? this.minecraft.level : null, showScreenDoorColorPicker);
    }

    /** layout の repeat index を実 entry index に変換 (= scroll offset を加算)。 */
    int sdCondRealIdx() {
        return com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex()
                + sdCondScroll.offset();
    }

    /** 共有リストの repeat index を実 candidate index に変換 (= scroll offset を加算)。 */
    int annShareRealIdx() {
        return com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex()
                + annShareScroll.offset();
    }

    /**
     * アナウンス entry 一覧の repeat index → 実 entry index (= scroll offset を加算)。
     *
     * <p>スクロール中は「画面の 1 行目」が entry #1 とは限らない。<b>この変換を通さない
     * ハンドラが 1 つでも残ると、その行だけ別 entry を指す</b> (表示は #3 なのに削除すると
     * #1 が消える等) ので、アナウンス popup の repeat 内で index を使う箇所は全部これを通す。
     */
    int annEntryRealIdx() {
        return com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex()
                + annEntryScroll.offset();
    }

    /** メモリーカードが slot or carried に挿入され、 screen_door_group type なら online。 */
    boolean isScreenDoorOnline() {
        net.minecraft.world.item.ItemStack card = ScreenDoorPreviewController.cardFrom(this.menu);
        if (card.isEmpty()
                || !card.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
            return false;
        }
        net.minecraft.nbt.CompoundTag tag =
                card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        return com.trainsystemutilities.item.MemoryCardItem.TYPE_SCREEN_DOOR_GROUP
                .equals(tag.getString("Type"));
    }

    /** 条件 entries 表示領域 (= popup local 12, 200, 216, 72) の hit 判定。 */
    private boolean isOverScreenDoorCondArea(double mouseX, double mouseY) {
        // popup は自身の原点 pivot で scale するので、当たり判定も scale を通す
        // (isOverScreenDoorPreview と同じ形。overlay2X() + local だけだと
        //  UI スケールつまみが 1.0 でないときずれる - 実機 2026-09-12)。
        float absX = overlay2LocalToScreenX(12);
        float absY = overlay2LocalToScreenY(SD_COND_AREA_Y);
        float w = overlay2LocalToScreenLen(216);
        float h = overlay2LocalToScreenLen(SD_COND_AREA_H);
        return com.manta.api.hud.Rects.contains(mouseX, mouseY, absX, absY, w, h);
    }

    /** preview box (= popup local 12,50,216,100) の absolute 範囲に hit 判定。 */
    private boolean isOverScreenDoorPreview(double mouseX, double mouseY) {
        if (!showScreenDoor || showScreenDoorColorPicker) return false;
        // popup は自身の原点 pivot で scale するので、当たり判定も scale を通す
        float absX = overlay2LocalToScreenX(12);
        float absY = overlay2LocalToScreenY(50);
        float w = overlay2LocalToScreenLen(216);
        float h = overlay2LocalToScreenLen(100);
        return com.manta.api.hud.Rects.contains(mouseX, mouseY, absX, absY, w, h);
    }

    private long[] lastScreenDoorHighlight = null;

    /** popup 開閉 + card 装着状態に応じて world highlight を同期。
     *  card は client BE フィールドではなく menu の slot or carried 経由で取得 (= vanilla の slot sync が反映される)。 */
    private void syncScreenDoorHighlight() {
        long[] now = null;
        String reason = "show=false";
        if (showScreenDoor
                && com.trainsystemutilities.client.gui.ScreenDoorHighlightToggleState
                        .isEnabled(be().getBlockPos())) {
            // slot 内 or マウス carried (= ユーザー click で持ち上げ中) のどちらかを採用
            net.minecraft.world.item.ItemStack card = ScreenDoorPreviewController.cardFrom(this.menu);
            if (card.isEmpty()) {
                reason = "card empty (slot+carried)";
            } else if (!card.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                reason = "card no CUSTOM_DATA";
            } else {
                net.minecraft.nbt.CompoundTag tag =
                        card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
                String type = tag.getString("Type");
                if (!com.trainsystemutilities.item.MemoryCardItem.TYPE_SCREEN_DOOR_GROUP
                        .equals(type)) {
                    reason = "card type=" + type + " (expected screen_door_group)";
                } else {
                    now = com.trainsystemutilities.item.MemoryCardItem.readMembers(tag);
                    if (now.length == 0) { now = null; reason = "members empty"; }
                    else reason = "members=" + now.length;
                }
            }
        }
        if (!java.util.Arrays.equals(now, lastScreenDoorHighlight)) {
            com.trainsystemutilities.client.structure.ScreenDoorGroupHighlightRenderer
                    .setGuiHighlight(now);
            lastScreenDoorHighlight = now;
        }
    }

    @Override
    public void removed() {
        com.trainsystemutilities.client.structure.ScreenDoorGroupHighlightRenderer
                .setGuiHighlight(null);
        super.removed();
        if (data != null) {
            data.close();
            data = null;
        }
    }

    // ===== manta:data (MANTA_7_CONCEPT C4, network.RailwayManagementData) =====

    /**
     * The block's host: opened at the first tick for the block entity the client's level holds (never for the
     * wiki capture's stand-in) and closed with the screen. Its fields - the announcement settings and the entry
     * playing, which AnnouncementSyncPayload and AnnouncementPlaybackStatePayload carried - go into
     * RailwayAnnouncementClientState, which the popup and the range-frame renderer read as before.
     */
    private com.manta.api.data.Mirror data;
    private int dataRevision = -1;

    /** An action of the block's host, its params in railway-management-state.json's order. */
    void sendData(String action, Object... args) {
        com.manta.api.data.Mirror m = dataMirror();
        if (m != null) m.send(action, args);
    }

    private com.manta.api.data.Mirror dataMirror() {
        if (data == null) {
            var be = be();
            // Not for the wiki capture's stand-in: it has the client's level (setLevel), but it is not the block
            // entity that level holds at its position - and its position is the player's, not a block's.
            if (be.getLevel() == null || be.getLevel().getBlockEntity(be.getBlockPos()) != be
                    || minecraft == null || minecraft.getConnection() == null) return null;
            data = com.manta.api.data.Mirror.open(
                    com.trainsystemutilities.network.RailwayManagementData.channel(be.getLevel(), be.getBlockPos()),
                    com.trainsystemutilities.network.RailwayManagementData.schema());
        }
        return data;
    }

    /** Every container tick: what the host sent since the last one, into the client state the screen reads. */
    private void pollData() {
        com.manta.api.data.Mirror m = dataMirror();
        if (m == null) return;
        m.poll();
        int revision = m.revision();
        if (revision < 0 || revision == dataRevision) return;
        dataRevision = revision;
        net.minecraft.core.BlockPos pos = be().getBlockPos();
        RailwayAnnouncementClientState.setConfig(pos, com.trainsystemutilities.network.RailwayManagementData.configOf(
                (Boolean) m.get("ann-enabled"), (Boolean) m.get("ann-attenuation"),
                (java.util.List<?>) m.get("ann-entries"), (java.util.List<?>) m.get("ann-shared-detection"),
                (java.util.List<?>) m.get("ann-shared-range")));
        RailwayAnnouncementClientState.setPlayingEntry(pos, (Integer) m.get("ann-playing"));
    }

    /** NBT sync で ListTag<LongTag> → LongArrayTag に自動圧縮されるため両方対応。 */
    /** 設定 popup 上部の小型モニタープレビュー (h=60)。
     *  - 番線とシンボルは同時表示可能 (シンボル=上、番線=下)
     *  - 時計は番線パネル下部 (h=60 でディメンションテキストとは重ならない位置) */
    private void drawMonitorPreview(GuiGraphics g, int x, int y, int w, int h) {
        // 外枠
        com.manta.api.hud.HudChrome.innerRect(g, x, y, w, h, 0xFF0a0a18, 0xFF000000);

        int trackNumber = currentTrackNumber();
        int trackPosition = currentTrackPosition();
        int clockVisible = currentClockVisible();
        boolean hasSym = getAssignedLineSymbol() != null;
        boolean hasSidePanel = trackNumber > 0 || hasSym;

        int sidePanelW = 0;
        int sideX = 0;
        if (hasSidePanel) {
            sidePanelW = Math.max(24, w / 4);
            sideX = (trackPosition == 1) ? x + w - sidePanelW : x;
            g.fill(sideX + 1, y + 1, sideX + sidePanelW - 1, y + h - 1, 0xFF1a1a2e);
            int sepX = (trackPosition == 1) ? sideX : sideX + sidePanelW - 1;
            g.fill(sepX, y + 1, sepX + 1, y + h - 1, 0xFF333333);

            // 路線記号 (上半分) — 番線とは独立して必ず描画
            int curY = y + 4;
            if (hasSym) {
                var sym = getAssignedLineSymbol();
                int iconSize = 16;
                int iconX = sideX + (sidePanelW - iconSize) / 2;
                int borderColor = parseHexArgb(sym.getBorderColor(), 0xFF4fc3f7);
                com.manta.api.draw.SmoothRenderer.fillRoundedRect(g,
                        iconX, curY, iconSize, iconSize, 5f, 0xFFFFFFFF);
                com.manta.api.draw.SmoothRenderer.strokeRoundedRect(g,
                        iconX, curY, iconSize, iconSize, 5f, 1.5f, borderColor);
                curY += iconSize + 2;
            }
            // 番線数字 (シンボル下 or 上部)
            if (trackNumber > 0) {
                String numStr = String.valueOf(trackNumber);
                int textW = com.manta.api.text.MantaText.uiWidth(this.font, numStr);
                int textX = sideX + (sidePanelW - textW) / 2;
                com.manta.api.text.MantaText.draw(g, this.font, numStr, textX, curY, 0xFF4fc3f7);
            }

            // 時計 (パネル底部)
            if (clockVisible == 1) {
                String clockStr = getMinecraftTime();
                int cw = com.manta.api.text.MantaText.uiWidth(this.font, clockStr);
                int cx = sideX + (sidePanelW - cw) / 2;
                int cy = y + h - 12;
                com.manta.api.text.MantaText.draw(g, this.font, clockStr, cx, cy, 0xFFffc107);
            }
        }

        // info 領域 (擬似テキストで停車中/次列車の構造を示唆)
        int infoX = (trackPosition == 1 || !hasSidePanel) ? x + 3 : sideX + sidePanelW + 3;
        int infoEndX = (trackPosition == 1 && hasSidePanel) ? sideX - 3 : x + w - 3;
        // 停車中タイトル (青)
        g.fill(infoX, y + 6, infoEndX, y + 9, 0xFF4fc3f7);
        // 列車詳細
        g.fill(infoX, y + 14, infoX + (infoEndX - infoX) * 7 / 10, y + 17, 0xFF888888);
        g.fill(infoX, y + 22, infoX + (infoEndX - infoX) * 5 / 10, y + 25, 0xFF80deea);
        // 次列車タイトル (青)
        g.fill(infoX, y + 34, infoX + (infoEndX - infoX) / 2, y + 37, 0xFF4fc3f7);
        // 詳細
        g.fill(infoX, y + 42, infoX + (infoEndX - infoX) * 8 / 10, y + 45, 0xFF555555);
        g.fill(infoX, y + 50, infoX + (infoEndX - infoX) * 6 / 10, y + 53, 0xFF333333);
    }

    private void drawOwnerFace(GuiGraphics g, int x, int y, int w, int h) {
        com.manta.api.hud.OwnerFacePainter.draw(g, x, y, w, h, be().getOwnerUUID());
    }

    /** ヘッダの路線記号アイコンを LineSymbolPainter (TSU 共通) で描画。 */
    private void drawHeaderSymbol(GuiGraphics g, int x, int y, int w, int h) {
        var sym = getAssignedLineSymbol();
        if (sym == null) return;
        int size = Math.min(w, h);
        if (size <= 0) return;
        LineSymbolPainter.draw(g, x, y, size, sym, this.font);
    }

    /**
     * 割り当てられた路線記号を取得。
     *
     * <p>1.0.10: 同期済みの BE の値だけを読む。 以前は SP のときだけ server level と
     * {@code ManagementComputerBlockEntity} を覗いて別解を返しており、 権威 (LineSymbolStore) が
     * 取り下げた記号を GUI だけが表示し続けた。 §5.1 (client から server 状態を直読みしない) にも反する。
     */
    com.trainsystemutilities.blockentity.LineSymbol getAssignedLineSymbol() {
        var be = be();
        String stationName = be.getLinkedStationName();
        if (stationName == null || stationName.isEmpty()) return null;
        return be.getAssignedLineSymbol();
    }

}
