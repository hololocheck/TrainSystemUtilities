package com.trainsystemutilities.client.gui;
import belugalab.mcss3.anim.Transition;
import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.mcss3.anim.Easing;
import belugalab.mcss3.anim.Animation;

import belugalab.mcss3.screen.JsonLayoutEngine;
import belugalab.mcss3.screen.JsonLayoutScreen;
import belugalab.experience.controller.ColorTargetController;
import belugalab.experience.controller.ScrollViewport;
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
public class RailwayManagementScreenV2 extends JsonLayoutScreen<RailwayManagementMenu> {

    @Override
    protected String wikiPageId() { return "railway-management"; }

    /** Phase 18: SAS 統合アナウンス設定 popup の表示状態。 */
    private boolean showAnnouncement = false;
    /** Phase 21: ホームドア設定 popup の表示状態。 */
    private boolean showScreenDoor = false;
    /** Phase 21: ホームドア帯色 picker の表示状態 (= screen-door popup 内に重ねる)。 */
    private boolean showScreenDoorColorPicker = false;
    /** Phase 21: 条件 entries の表示開始 index (= 5 個以上のときスクロール)。 */
    private static final int SD_COND_VISIBLE = 4;
    private final ScrollViewport sdCondScroll =
            new ScrollViewport(() -> be().getScreenDoorConditions().size(), SD_COND_VISIBLE);
    private static final int SD_COND_AREA_Y = 200;
    private static final int SD_COND_AREA_H = 72;
    /** 共有先候補リスト (= 8 個以上のときスクロール)。 */
    private static final int ANN_SHARE_VISIBLE = 8;
    private final ScrollViewport annShareScroll =
            new ScrollViewport(() -> getShareCandidateStations().size(), ANN_SHARE_VISIBLE);
    private static final int ANN_SHARE_AREA_Y = 74;
    private static final int ANN_SHARE_AREA_H = 196;
    /** Phase 21: 3D preview のマウスドラッグ rotation / zoom / pan。 */
    private static final float SD_PREVIEW_DEFAULT_ROT_Y = 0f;
    private static final float SD_PREVIEW_DEFAULT_ROT_X = 25f;
    private static final float SD_PREVIEW_DEFAULT_ZOOM = 3.0f;
    private float sdPreviewRotY = SD_PREVIEW_DEFAULT_ROT_Y;
    private float sdPreviewRotX = SD_PREVIEW_DEFAULT_ROT_X;
    private float sdPreviewZoom = SD_PREVIEW_DEFAULT_ZOOM;
    private float sdPreviewPanX = 0f;
    private float sdPreviewPanY = 0f;
    private int sdPreviewDragButton = -1; // -1=なし, 0=回転, 1=pan
    private double sdPreviewLastMouseX = 0;
    private double sdPreviewLastMouseY = 0;
    /** Phase 21: 帯色 preset (= picker JSON の sd-preset-N と同じ並び)。 */
    private static final int[] SCREEN_DOOR_BAND_PRESETS = {
            0xFF66BB6A, 0xFF4FC3F7, 0xFFFFD54F, 0xFFFF8A65,
            0xFFEF5350, 0xFFAB47BC, 0xFF80DEEA, 0xFFFFFFFF,
            0xFF888888, 0xFF444444, 0xFFFFC107, 0xFF00BCD4
    };
    /** Phase 21: 機能ドロップダウン (= アナウンス / ホームドア 切替) の開閉。 */
    private boolean showFunctionDropdown = false;
    private int functionDropdownOpenSerial = 0;
    /** Condition dropdown が開いている entry index。-1 = 閉じている。 */
    private final belugalab.experience.controller.IndexedOverlayController conditionDropdown =
            new belugalab.experience.controller.IndexedOverlayController();
    /** Condition dropdown 再 open のたびに増やし anim spec を変える (= function dropdown と同じ再生 trigger)。 */
    private int conditionDropdownOpenSerial = 0;
    /** popup を開いた瞬間の nanoTime。アイテムを popup 開放アニメ (popIn 220ms) と同期 scale させるため。 */
    private long announcementOpenedAtNanos = 0L;
    /** popIn(220) と同じ timing でアイテムをスケールイン。 */
    private static final long ANNOUNCEMENT_OPEN_ANIM_NS = 220_000_000L;
    /** 検知カード共有先選択 sub-popup の表示状態。 */
    private boolean showAnnouncementShareList = false;
    /** Entry reorder animation bookkeeping. Mirrors PosterManagement row shuffle. */
    private static final int ANNOUNCEMENT_ENTRY_STRIDE = 35;
    private static final long ANNOUNCEMENT_SHUFFLE_ANIM_NS = 220_000_000L;
    private int lastAnnouncementMovedUpIdx = -1;
    private int lastAnnouncementMovedDownIdx = -1;
    private boolean pendingAnnouncementShuffle = false;
    private long announcementShuffleStartedAtNanos = 0L;
    private long announcementShuffleRequestedAtNanos = 0L;
    private com.trainsystemutilities.announce.AnnouncementConfig lastObservedAnnouncementConfig = null;

    private static final int NEXT_TRAIN_PER_PAGE = 2;
    private static final int FUNCTION_DD_X = 156;
    private static final int FUNCTION_DD_W = 66;
    private static final int FUNCTION_DD_ITEM_H = 16;
    private static final int FUNCTION_DD_DOOR_Y = 228;
    private static final int FUNCTION_DD_ANNOUNCEMENT_Y = 246;
    private static final int FUNCTION_DD_BG_X = FUNCTION_DD_X - 2;
    private static final int FUNCTION_DD_BG_Y = FUNCTION_DD_DOOR_Y - 2;
    private static final int FUNCTION_DD_BG_W = FUNCTION_DD_W + 4;

    private Boolean localMonitorEnabled = null;
    private int nextTrainPageIndex = 0;
    private int nextTrainPageTimer = 0;
    private static final int NEXT_TRAIN_ROTATE_TICKS = 200;

    // 5-B-2: settings popup
    private boolean showSettings = false;
    private boolean showBackFace = false;
    private Boolean localBatchApply = null;
    /** Batch apply toggle (= local optimistic state + clickButton(1) で server 反映)。 */
    private final belugalab.experience.controller.ToggleSwitchController batchToggle =
            new belugalab.experience.controller.ToggleSwitchController(
                    "batch-toggle-track", "batch-toggle-knob",
                    this::batchApply,
                    v -> { localBatchApply = v; resetLocalOverrides(); clickButton(1); });
    /** Announcement master toggle (= cfg.isEnabled、TOGGLE_ENABLED payload で server 反映)。 */
    private final belugalab.experience.controller.ToggleSwitchController annMasterToggle =
            new belugalab.experience.controller.ToggleSwitchController(
                    "ann-master-toggle-track", "ann-master-toggle-knob",
                    () -> { var c = announcementConfig(); return c != null && c.isEnabled(); },
                    v -> sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_TOGGLE_ENABLED, 0, 0, 0));
    /** Range frame toggle (= client-only state)。 */
    private final belugalab.experience.controller.ToggleSwitchController annRangeFrameToggle =
            new belugalab.experience.controller.ToggleSwitchController(
                    "ann-rangeframe-toggle-track", "ann-rangeframe-toggle-knob",
                    () -> com.trainsystemutilities.client.gui.RangeFrameToggleState.isEnabled(be().getBlockPos()),
                    v -> com.trainsystemutilities.client.gui.RangeFrameToggleState.toggle(be().getBlockPos()));
    /** Phase 21: ホームドア group highlight toggle (= client-only)。 */
    private final belugalab.experience.controller.ToggleSwitchController sdHighlightToggle =
            new belugalab.experience.controller.ToggleSwitchController(
                    "sd-highlight-toggle-track", "sd-highlight-toggle-knob",
                    () -> com.trainsystemutilities.client.gui.ScreenDoorHighlightToggleState.isEnabled(be().getBlockPos()),
                    v -> com.trainsystemutilities.client.gui.ScreenDoorHighlightToggleState.toggle(be().getBlockPos()));
    /** Attenuation toggle (= cfg.isAttenuationMode、未受信時 default ON)。 */
    private final belugalab.experience.controller.ToggleSwitchController annAttenuationToggle =
            new belugalab.experience.controller.ToggleSwitchController(
                    "ann-attenuation-toggle-track", "ann-attenuation-toggle-knob",
                    () -> { var c = announcementConfig(); return c == null || c.isAttenuationMode(); },
                    v -> sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_TOGGLE_ATTENUATION, 0, 0, 0));
    /** Monitor toggle (= localMonitorEnabled + clickButton(0)、derived visual: monitorEnabled && groups > 0)。 */
    private final belugalab.experience.controller.ToggleSwitchController monitorToggle =
            new belugalab.experience.controller.ToggleSwitchController(
                    "monitor-toggle-track", "monitor-toggle-knob",
                    this::monitorEnabled, v -> { localMonitorEnabled = v; clickButton(0); })
                    .withVisualState(() -> monitorEnabled() && be().getLinkedMonitorGroupCount() > 0);
    /** Per-station detection sharing toggle (= repeat idx ごと、サーバ payload で反映)。 */
    private final belugalab.experience.controller.IndexedToggleSwitchController annShareDetToggle =
            new belugalab.experience.controller.IndexedToggleSwitchController(
                    "ann-share-det-toggle", "ann-share-det-knob",
                    idx -> {
                        var sts = getShareCandidateStations();
                        if (idx < 0 || idx >= sts.size()) return false;
                        var c = announcementConfig();
                        return c != null && c.isDetectionSharedTo(sts.get(idx).name());
                    },
                    idx -> sendShareToggle(idx,
                            com.trainsystemutilities.network.AnnouncementShareTogglePayload.TYPE_DETECTION));
    /** Per-station range sharing toggle (= repeat idx ごと)。 */
    private final belugalab.experience.controller.IndexedToggleSwitchController annShareRngToggle =
            new belugalab.experience.controller.IndexedToggleSwitchController(
                    "ann-share-rng-toggle", "ann-share-rng-knob",
                    idx -> {
                        var sts = getShareCandidateStations();
                        if (idx < 0 || idx >= sts.size()) return false;
                        var c = announcementConfig();
                        return c != null && c.isRangeSharedTo(sts.get(idx).name());
                    },
                    idx -> sendShareToggle(idx,
                            com.trainsystemutilities.network.AnnouncementShareTogglePayload.TYPE_RANGE));
    private int localTrackNumber = -1;
    private int localTrackFontSize = -1;
    private int localTrackPosition = -1;
    private int localClockVisible = -1;
    private int localClockFontSize = -1;
    private int selectedGroupIndex = 0;

    // 5-B-3: color popup
    private boolean showColorSettings = false;
    /** Color popup controller (state + click/text resolvers)。
     *  RM は color update を server に直接書かず button id 経由で送る (V1 互換)。
     *  presetIdx と targetIdx を使って `base + targetIdx*100 + presetIdx` を encode。 */
    private final ColorTargetController colorPopup =
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
                            return be().getColorOrDefault(key, defaultHex);
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

    // Symbol dropdown
    private boolean showSymbolDropdown = false;
    private static final int MAX_GROUPS = 4;
    private static final int MAX_SYMBOLS = 8;

    public RailwayManagementScreenV2(RailwayManagementMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // ===== Wiki live-capture support (WikiLiveCapture から呼ばれる) =====

    /** wiki キャプチャ用にダミー BE + 空インベントリで screen を生成。 失敗時 null。 */
    public static RailwayManagementScreenV2 wikiCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.core.BlockPos pos = mc.player.blockPosition();
        var be = new RailwayManagementBlockEntity(
                pos, com.trainsystemutilities.registry.ModBlocks.RAILWAY_MANAGEMENT_BLOCK.get().defaultBlockState());
        be.setLevel(mc.level);
        be.setLineSymbol("JA", 1, "#4fc3f7"); // サンプル路線記号 (実物の SVG が出る)
        Inventory inv = new Inventory(mc.player); // 空 (持ち物アイテムを写さない)
        var menu = new RailwayManagementMenu(0, inv, be);
        return new RailwayManagementScreenV2(menu, inv,
                Component.translatable("tsu.railway_management.title"));
    }

    /** wiki キャプチャ用に overlay state を強制設定 (ドロップダウン等は閉じる)。 */
    public void wikiApplyState(String state) {
        showSettings = false;
        showColorSettings = false;
        showAnnouncement = false;
        showScreenDoor = false;
        showScreenDoorColorPicker = false;
        showFunctionDropdown = false;       // ドロップダウンは常に閉じる (= 開きっぱなし防止)
        showAnnouncementShareList = false;
        switch (state) {
            case "settings"     -> showSettings = true;
            case "color"        -> showColorSettings = true;
            case "announcement" -> showAnnouncement = true;
            case "screen-door"  -> showScreenDoor = true;
            default -> { /* main: overlay なし */ }
        }
    }

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

    private boolean batchApply() {
        return localBatchApply != null ? localBatchApply : be().isBatchApply();
    }

    private int currentGroupIndex() {
        var groups = be().getMonitorGroups();
        return groups.isEmpty() ? 0 : Math.min(selectedGroupIndex, groups.size() - 1);
    }

    private int currentTrackNumber() {
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

    private int currentTrackFontSize() {
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

    private int currentTrackPosition() {
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

    private int currentClockVisible() {
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

    private int currentClockFontSize() {
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
    private String getCurrentSelectedColorHex() {
        int idx = colorPopup.getSelectedIndex();
        if (idx < 0 || idx >= COLOR_KEYS.length) return "#000000";
        String key = (showBackFace ? "back." : "") + COLOR_KEYS[idx];
        return be().getColorOrDefault(key, COLOR_DEFAULTS[idx]);
    }

    // parseHexArgb は MCSS 基底 (JsonLayoutScreen.parseHexArgb) を使用。

    private void resetLocalOverrides() {
        localTrackNumber = -1; localTrackFontSize = -1; localTrackPosition = -1;
        localClockVisible = -1; localClockFontSize = -1;
    }

    /** MCSS 基底の loadModResourceJson に委譲 (TsuLayouts.load 経由)。 */
    private static String loadResourceJson(String path) { return TsuLayouts.load(path); }

    private RailwayManagementBlockEntity be() { return getMenu().getBlockEntity(); }

    private boolean monitorEnabled() {
        return localMonitorEnabled != null ? localMonitorEnabled : be().isMonitorEnabled();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        var nextTrains = be().getNextTrains();
        int totalPages = nextTrains.isEmpty() ? 1
                : (nextTrains.size() + NEXT_TRAIN_PER_PAGE - 1) / NEXT_TRAIN_PER_PAGE;
        if (totalPages > 1) {
            nextTrainPageTimer++;
            if (nextTrainPageTimer >= NEXT_TRAIN_ROTATE_TICKS) {
                nextTrainPageIndex = (nextTrainPageIndex + 1) % totalPages;
                nextTrainPageTimer = 0;
                // ページ切替時に列車行 (next-row) のスライドインを再トリガー。
                // V1 の .page-slide-in と同じ視覚効果。
                // 全 anim 破棄ではなく next-row class だけクリア (ダイアログ scaleIn を破壊しない)
                clearMainAnimByClass("next-row");
            }
        }
        if (nextTrainPageIndex >= totalPages) nextTrainPageIndex = 0;
        updatePendingAnnouncementShuffle();
    }

    private static String formatDayTime(long dayTime) {
        long ticks = (dayTime + 6000L) % 24000L;
        long hours = ticks / 1000L;
        long minutes = (ticks % 1000L) * 60L / 1000L;
        return String.format("%02d:%02d", hours, minutes);
    }

    /** arrivalDayTime + scheduledStopSec*20 → 発車予定時刻文字列。 */
    private static String getDepartureTime(long arrivalDayTime, int stopSec) {
        if (stopSec <= 0) return "";
        return formatDayTime(arrivalDayTime + (long) stopSec * 20L);
    }

    /** train type コード → ローカライズ表示。 種別の定義は {@link TrainTypes} が単一情報源。 */
    private static String trainTypeText(String code) {
        return TrainTypes.localize(code);
    }
    /** route type コード (SHUTTLE/CIRCULAR) → ローカライズ表示。 */
    private static String routeTypeText(String code) {
        return switch (code) {
            case "SHUTTLE" -> Component.translatable("tsu.monitor.route_type_shuttle").getString();
            case "CIRCULAR" -> Component.translatable("tsu.monitor.route_type_circular").getString();
            default -> code;
        };
    }

    /** 文字列を指定 px 幅以下に切り詰め (超過したら末尾に "…")。font.width はピクセル単位。 */
    private String fit(String text, int maxWidth) {
        if (text == null || text.isEmpty() || this.font == null) return text;
        if (this.font.width(text) <= maxWidth) return text;
        while (text.length() > 0 && this.font.width(text + "…") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    private String getMinecraftTime() {
        var be = be();
        if (be.getLevel() == null) return "00:00";
        return formatDayTime(be.getLevel().getDayTime());
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        var be = be();
        // Phase 21: ホームドア条件 repeat の per-entry テキスト
        for (String c : classes) {
            if ("sd-status-label".equals(c)) {
                return Component.translatable(isScreenDoorOnline()
                        ? "tsu.rm.sd_online" : "tsu.rm.sd_offline").getString();
            }
            if ("sd-cond-track".equals(c)) {
                int idx = sdCondRealIdx();
                var conds = be.getScreenDoorConditions();
                if (idx < 0 || idx >= conds.size()) return defaultText;
                return String.valueOf(conds.get(idx).trackNumber());
            }
            if ("sd-cond-event".equals(c)) {
                int idx = sdCondRealIdx();
                var conds = be.getScreenDoorConditions();
                if (idx < 0 || idx >= conds.size()) return defaultText;
                return switch (conds.get(idx).eventType()) {
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP -> Component.translatable("tsu.rm.sd_event_stop").getString();
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_DEPART -> Component.translatable("tsu.rm.sd_event_depart").getString();
                    default -> defaultText;
                };
            }
            if ("sd-cond-action".equals(c)) {
                int idx = sdCondRealIdx();
                var conds = be.getScreenDoorConditions();
                if (idx < 0 || idx >= conds.size()) return defaultText;
                return switch (conds.get(idx).actionType()) {
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_OPEN -> Component.translatable("tsu.rm.sd_action_open").getString();
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_CLOSE -> Component.translatable("tsu.rm.sd_action_close").getString();
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_COLOR -> Component.translatable("tsu.rm.sd_action_color").getString();
                    default -> defaultText;
                };
            }
        }
        // Phase 18: アナウンス popup 内 repeat の per-entry テキスト
        for (String c : classes) {
            if ("ann-entry-index".equals(c)) {
                int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                return idx >= 0 ? "#" + (idx + 1) : "";
            }
            if ("ann-cond-display".equals(c)) {
                int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                var cfg = announcementConfig();
                if (cfg == null || idx < 0 || idx >= cfg.size()) return "";
                var entry = cfg.get(idx);
                if (entry == null) return "";
                String key = switch (entry.condition().type) {
                    case NONE -> "tsu.announcement.cond_none";
                    case ON_DETECTION_PASS -> "tsu.announcement.cond_pass";
                    case ON_DETECTION_STOPPED -> "tsu.announcement.cond_stop";
                };
                // ▼ で dropdown 可能なことを示す
                return net.minecraft.network.chat.Component.translatable(key).getString() + " ▾";
            }
            if ("ann-delay-display".equals(c)) {
                int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                var cfg = announcementConfig();
                if (cfg == null || idx < 0 || idx >= cfg.size()) return "↕ 0s";
                var entry = cfg.get(idx);
                if (entry == null) return "↕ 0s";
                int s = entry.condition().delaySeconds;
                String body = (s > 0 ? "+" + s : String.valueOf(s)) + "s";
                return "↕ " + body;
            }
            if ("ann-count-display".equals(c)) {
                int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                var cfg = announcementConfig();
                if (cfg == null || idx < 0 || idx >= cfg.size()) return "↕x1";
                var entry = cfg.get(idx);
                if (entry == null) return "↕x1";
                return "↕x" + entry.playCount();
            }
            if ("ann-share-station-name".equals(c)) {
                int idx = annShareRealIdx();
                if (idx < 0) return "";
                var stations = getShareCandidateStations();
                if (idx >= stations.size()) return "";
                return stations.get(idx).name();
            }
            if ("ann-incoming-share-info".equals(c)) {
                var sources = be.getIncomingShareSources();
                if (sources.isEmpty()) return "";
                // 共有元 1 件目だけを優先表示 (複数は通常稀)。
                // 検知 / 範囲 / 両方 の組み合わせで翻訳キーを切替。
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sources.size(); i++) {
                    var info = sources.get(i);
                    String key;
                    if (info.detection && info.range) key = "tsu.announcement.shared_from_both_fmt";
                    else if (info.range) key = "tsu.announcement.shared_from_rng_fmt";
                    else key = "tsu.announcement.shared_from_det_fmt";
                    if (i > 0) sb.append(" / ");
                    sb.append(net.minecraft.network.chat.Component
                            .translatable(key, info.sourceStationName).getString());
                }
                return sb.toString();
            }
            if ("ann-media-info".equals(c)) {
                int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                if (idx < 0) return "";
                int slotIdx = com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE + idx;
                if (slotIdx >= this.menu.slots.size()) return "";
                var stack = this.menu.slots.get(slotIdx).getItem();
                if (stack.isEmpty() || !com.trainsystemutilities.compat.sas.SasIntegration.hasAudio(stack)) {
                    return net.minecraft.network.chat.Component.translatable("tsu.announcement.no_media").getString();
                }
                String name = belugalab.sas.api.SasApi.getAudioFileName(stack);
                String fmt = belugalab.sas.api.SasApi.getAudioFormat(stack);
                int durSec = belugalab.sas.api.SasApi.getAudioDurationSeconds(stack);
                if (name == null || name.isEmpty()) name = "audio";
                StringBuilder sb = new StringBuilder(name);
                if (fmt != null) sb.append(" [").append(fmt).append("]");
                if (durSec > 0) {
                    sb.append(" ").append(durSec / 60).append(":");
                    int s = durSec % 60;
                    if (s < 10) sb.append('0');
                    sb.append(s);
                }
                return sb.toString();
            }
        }
        for (String c : classes) {
            switch (c) {
                case "mc-time":
                    return getMinecraftTime();
                case "header-badge": {
                    String name = be.getLinkedStationName();
                    return (name != null && !name.isEmpty()) ? name : Component.translatable("tsu.rm.station_unlinked").getString();
                }
                case "monitor-status-label": {
                    int g = be.getLinkedMonitorGroupCount();
                    boolean on = monitorEnabled() && g > 0;
                    return Component.translatable(on ? "tsu.poster.monitor_online" : "tsu.poster.monitor_offline").getString();
                }
                case "monitor-info": {
                    int g = be.getLinkedMonitorGroupCount();
                    return g > 0
                            ? Component.translatable("tsu.poster.monitor_groups_linked_fmt", g).getString()
                            : Component.translatable("tsu.rm.monitor_disconnected").getString();
                }
                case "page-indicator": {
                    var nextTrains = be.getNextTrains();
                    int totalPages = nextTrains.isEmpty() ? 1
                            : (nextTrains.size() + NEXT_TRAIN_PER_PAGE - 1) / NEXT_TRAIN_PER_PAGE;
                    return totalPages > 1 ? (nextTrainPageIndex + 1) + "/" + totalPages : "";
                }
                case "arrived-empty":
                    return be.getArrivedTrains().isEmpty()
                            ? (be.getLinkedStationName() != null ? Component.translatable("tsu.rm.no_train").getString() : Component.translatable("tsu.rm.link_station_hint").getString())
                            : "";
                case "next-empty":
                    return be.getNextTrains().isEmpty() ? Component.translatable("tsu.rm.none").getString() : "";
                // settings popup displays
                case "track-display-track": {
                    int v = currentTrackNumber();
                    return v == 0 ? Component.translatable("tsu.rm.none").getString() : Component.translatable("tsu.rm.track_number_fmt", v).getString();
                }
                case "track-display-font": {
                    int v = currentTrackFontSize();
                    return v == 0 ? Component.translatable("tsu.rm.auto").getString() : v + "px";
                }
                case "track-display-pos":
                    return Component.translatable(currentTrackPosition() == 0 ? "tsu.rm.pos_left" : "tsu.rm.pos_right").getString();
                case "track-display-clock":
                    return Component.translatable(currentClockVisible() == 1 ? "tsu.rm.show" : "tsu.rm.hide").getString();
                case "track-display-clockfs": {
                    int v = currentClockFontSize();
                    return v == 0 ? Component.translatable("tsu.rm.auto").getString() : v + "px";
                }
                // settings popup: face indicator + symbol display
                case "settings-face-indicator":
                    return Component.translatable(showBackFace ? "tsu.rm.face_back" : "tsu.rm.face_front").getString();
                case "preview-dims": {
                    var groups = be().getMonitorGroups();
                    if (groups.isEmpty()) return Component.translatable("tsu.rm.not_linked").getString();
                    var g = groups.get(currentGroupIndex());
                    return g.height() + "×" + g.width();
                }
                case "sym-display": {
                    var rbe = be();
                    return rbe.hasLineSymbol()
                            ? rbe.getLineSymbolLetters() + " " + String.format("%02d", rbe.getLineSymbolNumber())
                            : Component.translatable("tsu.rm.none").getString();
                }
                // color popup (controller delegate)
                case "color-face-label":
                    return Component.translatable(showBackFace ? "tsu.rm.face_back_color" : "tsu.rm.face_front_color").getString();
            }
        }
        // arrived row
        int idx = JsonLayoutEngine.currentRepeatIndex();
        // Color popup の dynamic text は controller に委譲 (target-dropdown / current-hex / target-item)
        if (showColorSettings) {
            String t = colorPopup.resolveText(classes, idx);
            if (t != null) return t;
        }
        if (idx < 0) return null;
        // 停車中の列車行: 全フィールドを font.width でクリップ (overflow 防止)。
        var arrived = be.getArrivedTrains();
        if (idx < arrived.size()) {
            var t = arrived.get(idx);
            for (String c : classes) {
                switch (c) {
                    case "train-name":      return fit(t.name(), 70);
                    case "train-cars":      return Component.translatable("tsu.mc.cars_unit_fmt", t.carriageCount()).getString();
                    case "train-dest":
                        return (t.destination() != null && !t.destination().isEmpty())
                                ? fit("→ " + t.destination(), 54) : "";
                    case "train-route":
                        return (t.routeType() != null && !t.routeType().isEmpty())
                                ? fit(routeTypeText(t.routeType()), 56) : "";
                    case "train-type-badge":
                        return TrainTypes.isSet(t.trainType())
                                ? fit("[" + trainTypeText(t.trainType()) + "]", 40) : "";
                    case "train-arr-time":
                        return Component.translatable("tsu.rm.time_arr_fmt", formatDayTime(t.arrivalDayTime())).getString();
                    case "train-dep-time":
                        return t.scheduledStopSec() > 0
                                ? Component.translatable("tsu.rm.time_dep_fmt", getDepartureTime(t.arrivalDayTime(), t.scheduledStopSec())).getString()
                                : "";
                    case "train-time": {
                        long currentTick = be.getLevel() != null ? be.getLevel().getGameTime() : 0;
                        int elapsedSec = (int)((currentTick - t.arrivalTick()) / 20);
                        if (t.scheduledStopSec() > 0) {
                            int remaining = t.scheduledStopSec() - elapsedSec;
                            return remaining > 0 ? Component.translatable("tsu.rm.time_remaining_fmt", remaining).getString() : Component.translatable("tsu.rm.preparing_departure").getString();
                        }
                        return Component.translatable("tsu.rm.time_stopped_fmt", elapsedSec).getString();
                    }
                }
            }
        }
        // 次に停車する列車行: 接近中の場合 dep-time/stop-sec は隠す (line 2 の混雑回避)。
        int pageStart = nextTrainPageIndex * NEXT_TRAIN_PER_PAGE;
        int realNextIdx = pageStart + idx;
        var next = be.getNextTrains();
        if (realNextIdx < next.size()) {
            var n = next.get(realNextIdx);
            for (String c : classes) {
                switch (c) {
                    case "next-name":      return fit(n.name(), 70);
                    case "next-cars":      return Component.translatable("tsu.mc.cars_unit_fmt", n.carriageCount()).getString();
                    case "next-route":
                        return (n.routeType() != null && !n.routeType().isEmpty())
                                ? fit(routeTypeText(n.routeType()), 50) : "";
                    case "next-type-badge":
                        return TrainTypes.isSet(n.trainType())
                                ? fit("[" + trainTypeText(n.trainType()) + "]", 50) : "";
                    case "next-stop-info":
                        if (n.currentStopStation() != null && !n.currentStopStation().isEmpty())
                            return fit(Component.translatable("tsu.rm.stopping_at_fmt", n.currentStopStation()).getString(), 92);
                        if (n.fromStation() != null && !n.fromStation().isEmpty())
                            return fit(Component.translatable("tsu.rm.from_station_fmt", n.fromStation()).getString(), 92);
                        return "";
                    case "next-arr-time":
                        if (n.estimatedArrivalDayTime() <= 0) return "";
                        // 接近中なら "(接近)" を簡略表示にして dep/sec を隠す側で
                        // 衝突回避。通常時は "HH:MM着予定" のみ。
                        return n.isApproaching()
                                ? Component.translatable("tsu.rm.time_approaching_fmt", formatDayTime(n.estimatedArrivalDayTime())).getString()
                                : Component.translatable("tsu.rm.time_arr_eta_fmt", formatDayTime(n.estimatedArrivalDayTime())).getString();
                    case "next-dep-time":
                        // 接近中は dep-time を隠す (line 2 重複防止)
                        if (n.isApproaching()) return "";
                        return (n.estimatedArrivalDayTime() > 0 && n.scheduledStopSec() > 0)
                                ? Component.translatable("tsu.rm.time_dep_fmt", getDepartureTime(n.estimatedArrivalDayTime(), n.scheduledStopSec())).getString()
                                : "";
                    case "next-stop-sec":
                        if (n.isApproaching()) return "";
                        return n.scheduledStopSec() > 0
                                ? Component.translatable("tsu.rm.seconds_fmt", n.scheduledStopSec()).getString()
                                : "";
                }
            }
        }
        // Group selector: 「グループ1 (HxW) 5番線」 形式
        if (showSettings) {
            for (String c : classes) {
                if ("group-item".equals(c)) {
                    var groups = be().getMonitorGroups();
                    if (idx >= groups.size()) return null;
                    var g = groups.get(idx);
                    String prefix = (idx == selectedGroupIndex) ? "● " : "○ ";
                    String t = prefix + Component.translatable("tsu.rm.group_label_fmt", (idx + 1), g.height(), g.width()).getString();
                    if (g.trackNumber() > 0) t += " " + Component.translatable("tsu.rm.track_number_fmt", g.trackNumber()).getString();
                    return t;
                }
                if ("sym-dropdown-item".equals(c)) {
                    if (idx == 0) return Component.translatable("tsu.rm.none").getString();
                    var symbols = getAvailableSymbols();
                    int symIdx = idx - 1;
                    if (symIdx >= symbols.size()) return null;
                    var sym = symbols.get(symIdx);
                    String t = sym.getLetters() + " " + sym.getNumberStr();
                    if (sym.getName() != null && !sym.getName().isEmpty()) t += "  " + sym.getName();
                    return t;
                }
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if (showColorSettings) {
            Integer n = colorPopup.resolveNumber(key);
            if (n != null) return n;
        }
        switch (key) {
            case "monitor-knob-x":     return monitorToggle.knobX(defaultValue);
            // hint-knob-x は JsonLayoutEngine が HintToggleHelper にルート (解決不要)
            case "batch-knob-x":
                return batchToggle.knobX(defaultValue);
            case "arrived-count":
                return be().getArrivedTrains().size();
            case "next-count": {
                var nextTrains = be().getNextTrains();
                int total = nextTrains.size();
                if (total == 0) return 0;
                int pageStart = nextTrainPageIndex * NEXT_TRAIN_PER_PAGE;
                return Math.min(NEXT_TRAIN_PER_PAGE, total - pageStart);
            }
            case "group-count":
                return Math.min(MAX_GROUPS, be().getMonitorGroups().size());
            case "sd-cond-count":
                return sdCondScroll.rowCount();
            case "sd-cond-scroll-thumb-y": {
                int total = be().getScreenDoorConditions().size();
                if (total <= SD_COND_VISIBLE) return SD_COND_AREA_Y;
                int thumbH = Math.max(8, SD_COND_AREA_H * SD_COND_VISIBLE / total);
                return sdCondScroll.thumbY(SD_COND_AREA_Y, SD_COND_AREA_H, thumbH);
            }
            case "sd-cond-scroll-thumb-h": {
                int total = be().getScreenDoorConditions().size();
                if (total <= SD_COND_VISIBLE) return SD_COND_AREA_H;
                return Math.max(8, SD_COND_AREA_H * SD_COND_VISIBLE / total);
            }
            case "ann-share-scroll-thumb-y": {
                int total = getShareCandidateStations().size();
                if (total <= ANN_SHARE_VISIBLE) return ANN_SHARE_AREA_Y;
                int thumbH = Math.max(8, ANN_SHARE_AREA_H * ANN_SHARE_VISIBLE / total);
                return annShareScroll.thumbY(ANN_SHARE_AREA_Y, ANN_SHARE_AREA_H, thumbH);
            }
            case "ann-share-scroll-thumb-h": {
                int total = getShareCandidateStations().size();
                if (total <= ANN_SHARE_VISIBLE) return ANN_SHARE_AREA_H;
                return Math.max(8, ANN_SHARE_AREA_H * ANN_SHARE_VISIBLE / total);
            }
            case "sd-highlight-knob-x":
                return sdHighlightToggle.knobX(defaultValue);
            case "function-dd-bg-h":
                return functionDropdownPanelHeight();
            // color-target-count は controller に委譲 (下で resolveNumber 経由)
            case "header-badge-w": {
                String name = be().getLinkedStationName();
                String text = (name != null && !name.isEmpty()) ? name : Component.translatable("tsu.rm.station_unlinked").getString();
                if (this.font == null) return defaultValue;
                return this.font.width(text) + 12; // 左右 padding 6px ずつ
            }
            case "header-badge-x": {
                String name = be().getLinkedStationName();
                String text = (name != null && !name.isEmpty()) ? name : Component.translatable("tsu.rm.station_unlinked").getString();
                if (this.font == null) return defaultValue;
                int w = this.font.width(text) + 12;
                // ダイアログ内 right-align (PAD + DLG_INNER_W - w)
                return 14 + 236 - w;
            }
            case "sym-dropdown-count":
                // +1 for "なし"
                return showSymbolDropdown
                        ? Math.min(MAX_SYMBOLS, getAvailableSymbols().size()) + 1
                        : 0;
            case "ann-entry-count": {
                if (!showAnnouncement) return 0;
                var cfg = announcementConfig();
                return cfg != null ? cfg.size() : 0;
            }
            case "ann-master-knob-x": {
                var cfg = announcementConfig();
                boolean on = cfg != null && cfg.isEnabled();
                return on ? defaultValue + 12 : defaultValue;
            }
            case "ann-cond-dd-bg-y": {
                if (!conditionDropdown.isOpen()) return defaultValue;
                return 98 + conditionDropdown.openIdx() * 35 + 14;
            }
            case "ann-cond-dd-item-0-y": {
                if (!conditionDropdown.isOpen()) return defaultValue;
                return 98 + conditionDropdown.openIdx() * 35 + 15;
            }
            case "ann-cond-dd-item-1-y": {
                if (!conditionDropdown.isOpen()) return defaultValue;
                return 98 + conditionDropdown.openIdx() * 35 + 26;
            }
            case "ann-cond-dd-item-2-y": {
                if (!conditionDropdown.isOpen()) return defaultValue;
                return 98 + conditionDropdown.openIdx() * 35 + 37;
            }
            case "ann-playing-frame-y": {
                int idx = announcementPlayingEntryIndex();
                return idx >= 0 ? 98 + idx * ANNOUNCEMENT_ENTRY_STRIDE : defaultValue;
            }
            case "ann-rangeframe-knob-x":  return annRangeFrameToggle.knobX(defaultValue);
            case "ann-attenuation-knob-x": return annAttenuationToggle.knobX(defaultValue);
            case "ann-share-count": {
                if (!showAnnouncement || !showAnnouncementShareList) return 0;
                return annShareScroll.rowCount();
            }
            case "ann-share-det-knob-x": return annShareDetToggle.knobXFor(annShareRealIdx(), defaultValue);
            case "ann-share-rng-knob-x": return annShareRngToggle.knobXFor(annShareRealIdx(), defaultValue);
        }
        return null;
    }

    @Override
    public boolean onElementWheel(String[] classes, String key,
                                   int mouseX, int mouseY, double scrollY) {
        if ("ann-delay-wheel".equals(key)) {
            int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (idx < 0) return false;
            int delta = scrollY > 0 ? 1 : -1;
            sendAnnouncementCmd(
                    com.trainsystemutilities.network.AnnouncementCommandPayload.OP_ADJUST_ENTRY_DELAY,
                    idx, delta, 0);
            adjustEntryDelayLocally(idx, delta);
            return true;
        }
        if ("ann-count-wheel".equals(key)) {
            int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (idx < 0) return false;
            int delta = scrollY > 0 ? 1 : -1;
            sendAnnouncementCmd(
                    com.trainsystemutilities.network.AnnouncementCommandPayload.OP_ADJUST_ENTRY_PLAYCOUNT,
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
    private void sendScreenDoorCondAdd() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ScreenDoorConditionPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ScreenDoorConditionPayload.OP_ADD,
                        0, 0, 0, 0));
        be().addScreenDoorCondition(
                com.trainsystemutilities.screendoor.ScreenDoorCondition.defaultEntry());
    }

    private void sendScreenDoorCondRemove(int idx) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ScreenDoorConditionPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ScreenDoorConditionPayload.OP_REMOVE,
                        idx, 0, 0, 0));
        be().removeScreenDoorCondition(idx);
        sdCondScroll.clamp();
    }

    private void sendScreenDoorCondUpdate(int idx,
            com.trainsystemutilities.screendoor.ScreenDoorCondition next) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ScreenDoorConditionPayload(
                        be().getBlockPos(),
                        com.trainsystemutilities.network.ScreenDoorConditionPayload.OP_UPDATE,
                        idx, next.trackNumber(), next.eventType(), next.actionType()));
        be().updateScreenDoorCondition(idx, next);
    }

    /** 現在の AnnouncementConfig (client-side cache から取得)。 */
    private com.trainsystemutilities.announce.AnnouncementConfig announcementConfig() {
        return com.trainsystemutilities.client.gui.RailwayAnnouncementClientState.getConfig(
                this.menu.getBlockEntity().getBlockPos());
    }

    /** R4.9.1: OP_SET_ENTRY_CONDITION を server へ送った直後に client cache の config を即時更新し、
     *  server 同期 (= 画面再オープン) を待たず表示へ反映させる (server は同値で reconcile)。 */
    private void applyConditionTypeLocally(int entryIdx, int typeOrd) {
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

    private int announcementPlayingEntryIndex() {
        int idx = com.trainsystemutilities.client.gui.RailwayAnnouncementClientState.getPlayingEntry(
                this.menu.getBlockEntity().getBlockPos());
        var cfg = announcementConfig();
        if (idx < 0 || cfg == null || idx >= cfg.size()) return -1;
        return idx;
    }

    private void sendAnnouncementCmd(byte op, int a1, int a2, int a3) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.AnnouncementCommandPayload(
                        be().getBlockPos(), op, a1, a2, a3));
    }

    /** Per-station share toggle (= IndexedToggleSwitchController から呼ばれる)。 */
    private void sendShareToggle(int idx, byte type) {
        var stations = getShareCandidateStations();
        if (idx < 0 || idx >= stations.size()) return;
        String stName = stations.get(idx).name();
        if (stName == null || stName.isEmpty()) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.AnnouncementShareTogglePayload(
                        be().getBlockPos(), stName, type));
    }

    /** 共有先候補となる station 一覧 (linked computer の cachedStations から、自局を除く)。
     *  client 側で BE.getUpdateTag 経由で同期されているため、server 経由しなくても取得可能。 */
    private java.util.List<com.trainsystemutilities.network.TrackNetworkScanner.StationInfo> getShareCandidateStations() {
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

    private void triggerAnnouncementSwap(int fromIdx, int toIdx) {
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
                clearOverlay2AnimByClass("ann-entry-row");
                pendingAnnouncementShuffle = false;
            }
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

    private float announcementEntryShuffleOffset(int entryIdx) {
        if (announcementShuffleStartedAtNanos <= 0L) return 0f;
        float from = announcementEntryShuffleDistance(entryIdx);
        if (from == 0f) return 0f;
        long elapsed = System.nanoTime() - announcementShuffleStartedAtNanos;
        if (elapsed >= ANNOUNCEMENT_SHUFFLE_ANIM_NS) return 0f;
        float t = Math.max(0f, Math.min(1f, elapsed / (float) ANNOUNCEMENT_SHUFFLE_ANIM_NS));
        float eased = belugalab.mcss3.anim.Easing.EASE_OUT.apply(t);
        return from + (0f - from) * eased;
    }

    private float announcementEntryShuffleDistance(int entryIdx) {
        if (announcementShuffleStartedAtNanos <= 0L) return 0f;
        if (entryIdx == lastAnnouncementMovedUpIdx) return ANNOUNCEMENT_ENTRY_STRIDE;
        if (entryIdx == lastAnnouncementMovedDownIdx) return -ANNOUNCEMENT_ENTRY_STRIDE;
        return 0f;
    }

    /** popup の右側展開位置に基づいて detection / range / per-entry media slots を配置。
     *  popup 開放アニメ中も slot 位置は確定させる (描画側 {@link #renderPopupOverlayItems}
     *  でアニメに同期した scale を適用するため)。 */
    private void positionAnnouncementSlots() {
        int overX = overlay2X();
        int overY = overlay2Y();
        if (overX == 0 && overY == 0) {
            overX = this.leftPos + this.imageWidth + 8;
            overY = this.topPos;
        }
        int dx = overX - this.leftPos;
        int dy = overY - this.topPos;

        // 検知カードスロット: frame x=68, y=291, 18x18 → slot x=69, y=292
        setMenuSlotPos(com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_DETECTION_CARD,
                dx + 69, dy + 292);
        // 範囲指定ボード: frame x=174, y=291, 18x18 → slot x=175, y=292
        setMenuSlotPos(com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_RANGE_BOARD,
                dx + 175, dy + 292);

        // Entry media slots: 行 i の frame x=192, y=74+i*35 → slot x=193, y=75+i*35
        // share popup が開いている間は entry 行が visibleKey で隠れるので、
        // media slot も off-screen に追い出して透過アイテムを防ぐ。
        var cfg = announcementConfig();
        int n = cfg != null ? cfg.size() : 0;
        boolean hideMedia = showAnnouncementShareList;
        for (int i = 0; i < com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES; i++) {
            int slotIdx = com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE + i;
            if (i < n && !hideMedia) {
                setMenuSlotPos(slotIdx, dx + 193, dy + 103 + i * 35);
            } else {
                setMenuSlotPos(slotIdx, -1000, -1000);
            }
        }
    }

    private void hideAnnouncementSlots() {
        setMenuSlotPos(com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_DETECTION_CARD, -1000, -1000);
        setMenuSlotPos(com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_RANGE_BOARD, -1000, -1000);
        for (int i = 0; i < com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES; i++) {
            setMenuSlotPos(com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE + i,
                    -1000, -1000);
        }
    }

    /** Screen door popup の slot 位置決定 (= popup x=94, y=32, 18x18)。 */
    private void positionScreenDoorSlot() {
        int overX = overlay2X();
        int overY = overlay2Y();
        if (overX == 0 && overY == 0) {
            overX = this.leftPos + this.imageWidth + 8;
            overY = this.topPos;
        }
        int dx = overX - this.leftPos;
        int dy = overY - this.topPos;
        setMenuSlotPos(com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD,
                dx + 95, dy + 291);
    }

    private void hideScreenDoorSlot() {
        setMenuSlotPos(com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD,
                -1000, -1000);
    }

    private void resetAnnouncementTransientState() {
        announcementOpenedAtNanos = 0L;
        conditionDropdown.close();
        showAnnouncementShareList = false;
        pendingAnnouncementShuffle = false;
        announcementShuffleStartedAtNanos = 0L;
        announcementShuffleRequestedAtNanos = 0L;
        lastAnnouncementMovedUpIdx = -1;
        lastAnnouncementMovedDownIdx = -1;
    }

    private void setFunctionDropdownOpen(boolean open) {
        if (showFunctionDropdown == open) return;
        showFunctionDropdown = open;
        if (open) functionDropdownOpenSerial++;
    }

    private void toggleFunctionDropdown() {
        setFunctionDropdownOpen(!showFunctionDropdown);
    }

    private void resetScreenDoorPreviewView() {
        sdPreviewRotY = SD_PREVIEW_DEFAULT_ROT_Y;
        sdPreviewRotX = SD_PREVIEW_DEFAULT_ROT_X;
        sdPreviewZoom = SD_PREVIEW_DEFAULT_ZOOM;
        sdPreviewPanX = 0f;
        sdPreviewPanY = 0f;
    }

    private boolean isFunctionDropdownItemHovering(double mouseX, double mouseY) {
        if (!showFunctionDropdown) return false;
        if (isRawHovering(FUNCTION_DD_X, FUNCTION_DD_DOOR_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, mouseX, mouseY)) {
            return true;
        }
        return com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()
                && isRawHovering(FUNCTION_DD_X, FUNCTION_DD_ANNOUNCEMENT_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, mouseX, mouseY);
    }

    private int functionDropdownPanelHeight() {
        return com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()
                ? FUNCTION_DD_ITEM_H * 2 + 6
                : FUNCTION_DD_ITEM_H + 4;
    }

    private boolean isFunctionDropdownPanelHovering(double mouseX, double mouseY) {
        return showFunctionDropdown
                && isRawHovering(FUNCTION_DD_BG_X, FUNCTION_DD_BG_Y,
                FUNCTION_DD_BG_W, functionDropdownPanelHeight(), mouseX, mouseY);
    }

    private boolean isRawHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        mouseX -= (double) this.leftPos;
        mouseY -= (double) this.topPos;
        return mouseX >= (double) (x - 1)
                && mouseX < (double) (x + width + 1)
                && mouseY >= (double) (y - 1)
                && mouseY < (double) (y + height + 1);
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
        if (isRawHovering(FUNCTION_DD_X, FUNCTION_DD_DOOR_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, mouseX, mouseY)) {
            if (button != 0) return true;
            toggleScreenDoorPopupFromFunctionDropdown();
            return true;
        }
        if (com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()
                && isRawHovering(FUNCTION_DD_X, FUNCTION_DD_ANNOUNCEMENT_Y,
                FUNCTION_DD_W, FUNCTION_DD_ITEM_H, mouseX, mouseY)) {
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

    private boolean isOverlay2RawHovering(int x, int y, int width, int height,
                                          double mouseX, double mouseY) {
        mouseX -= (double) overlay2X();
        mouseY -= (double) overlay2Y();
        return mouseX >= (double) x
                && mouseX < (double) (x + width)
                && mouseY >= (double) y
                && mouseY < (double) (y + height);
    }

    private void applyScreenDoorBandColor(int argb) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.trainsystemutilities.network.ScreenDoorBandColorPayload(
                        be().getBlockPos(), argb));
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
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.trainsystemutilities.network.AnnouncementCommandPayload(
                            be().getBlockPos(),
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_SYNC_REQUEST,
                            0, 0, 0));
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
            sdPreviewDragButton = button;
            sdPreviewLastMouseX = mouseX;
            sdPreviewLastMouseY = mouseY;
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
                if (this.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                    net.minecraft.world.inventory.ClickType type;
                    if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
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
        if (sdPreviewDragButton == button) {
            double dx = mouseX - sdPreviewLastMouseX;
            double dy = mouseY - sdPreviewLastMouseY;
            if (button == 0) {
                sdPreviewRotY += (float) dx * 0.6f;
                sdPreviewRotX += (float) dy * 0.6f;
                sdPreviewRotX = Math.max(-89f, Math.min(89f, sdPreviewRotX));
            } else if (button == 1) {
                sdPreviewPanX += (float) dx;
                sdPreviewPanY += (float) dy;
            }
            sdPreviewLastMouseX = mouseX;
            sdPreviewLastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (sdPreviewDragButton == button) {
            sdPreviewDragButton = -1;
            return true;
        }
        if ((showAnnouncement || showScreenDoor) && button >= 0 && button <= 2) {
            for (int i = com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE;
                 i < this.menu.slots.size(); i++) {
                var slot = this.menu.slots.get(i);
                if (!slot.isActive()) continue;
                if (slot.x < -500) continue;
                if (this.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                    return true; // 同じ popup slot で release: pickup/place は既に click 時に処理済み
                }
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // popup が「初めて」見える瞬間に anim 開始時刻を engine と同フレームでセット。
        // これで popup の popIn と slot scale-in が完全同期する (click 時に set すると 1 フレーム早く始まる)。
        if (showAnnouncement && announcementOpenedAtNanos == 0L) {
            announcementOpenedAtNanos = System.nanoTime();
        }
        updatePendingAnnouncementShuffle();
        // popup が開いている間は毎フレーム slot 位置を popup 座標に合わせる
        // (overlay 位置はドラッグで変わる可能性があるため)。閉じている間は off-screen に強制。
        if (showAnnouncement) positionAnnouncementSlots();
        else hideAnnouncementSlots();
        if (showScreenDoor && !showScreenDoorColorPicker) positionScreenDoorSlot();
        else hideScreenDoorSlot();
        syncScreenDoorHighlight();
        super.render(g, mouseX, mouseY, partialTick);

        // popup 内の slot は super.render で z=0 に描画されるため popup (z=600) の下に隠れる。
        // 上書きで slot icon + item + carried item を z=700 で再描画する。
        if (showAnnouncement || showScreenDoor) renderPopupOverlayItems(g, mouseX, mouseY);

        // popup を開いている時はベースの renderTooltip (= super.render 内で z≈0) では
        // popup の下に隠れてしまうため、ホバー中の slot に対するツールチップを popup の上 (z=900) に
        // 改めて描き直す。プレイヤーインベントリ / アナウンスポップアップ両方の slot で機能する。
        if ((showAnnouncement || showScreenDoor) && this.menu.getCarried().isEmpty()) {
            net.minecraft.world.inventory.Slot hovered = findHoveredSlot(mouseX, mouseY);
            if (hovered != null && hovered.hasItem()) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 900);
                var stack = hovered.getItem();
                g.renderTooltip(this.font,
                        this.getTooltipFromContainerItem(stack),
                        stack.getTooltipImage(), stack, mouseX, mouseY);
                g.pose().popPose();
            }
        }
    }

    /** マウス位置からアクティブな slot を探す (popup slot 含む)。tooltip 再描画 / hit 判定で利用。 */
    private net.minecraft.world.inventory.Slot findHoveredSlot(int mouseX, int mouseY) {
        for (int i = 0; i < this.menu.slots.size(); i++) {
            var slot = this.menu.slots.get(i);
            if (!slot.isActive()) continue;
            if (slot.x < -500) continue;
            if (this.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return slot;
            }
        }
        return null;
    }

    /** popup の上 (z=700) に slot icon + hover highlight + 手持ちアイテムを再描画。
     *  popup 開放アニメと同期して scale-in (popIn と同じ 0.7→1.0 + EASE_OUT_BACK) を適用し、
     *  popup と「同時に」アイテムが拡大されて見えるようにする (他 GUI と同等の挙動)。 */
    private void renderPopupOverlayItems(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY) {
        // popup 開放アニメ進捗 (0..1)。完了後は scale=1.0 で固定。
        long elapsedNs = announcementOpenedAtNanos > 0
                ? System.nanoTime() - announcementOpenedAtNanos : ANNOUNCEMENT_OPEN_ANIM_NS;
        float t = elapsedNs >= ANNOUNCEMENT_OPEN_ANIM_NS
                ? 1f
                : Math.max(0f, Math.min(1f, elapsedNs / (float) ANNOUNCEMENT_OPEN_ANIM_NS));
        float ease = belugalab.mcss3.anim.Easing.EASE_OUT_BACK.apply(t);
        float scale = 0.7f + (1.0f - 0.7f) * ease;

        // popup の中心 (アニメの anchor) — overlay2X/Y + popup root size。
        int popupW = overlay2W() > 0 ? overlay2W() : 240;
        int popupH = overlay2H() > 0 ? overlay2H() : 340;
        int popupCx = overlay2X() + popupW / 2;
        int popupCy = overlay2Y() + popupH / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        // 開放アニメ中は overshoot (>1.0) も含めて popup と同じ pose に乗せる。
        if (Math.abs(scale - 1f) > 0.001f) {
            g.pose().translate(popupCx, popupCy, 0);
            g.pose().scale(scale, scale, 1f);
            g.pose().translate(-popupCx, -popupCy, 0);
        }
        // slot に格納されたアイテム + hover highlight + lock 表示 (× 線)
        boolean lockDet = be().isSharedDetectionTarget();
        boolean lockRng = be().isSharedRangeTarget();
        for (int i = com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE;
             i < this.menu.slots.size(); i++) {
            var slot = this.menu.slots.get(i);
            if (!slot.isActive()) continue;
            // off-screen (-1000) の slot はスキップ
            if (slot.x < -500) continue;
            float entryOffsetY = 0f;
            if (showAnnouncement
                    && i >= com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE
                    && i < com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD) {
                entryOffsetY = announcementEntryShuffleOffset(
                        i - com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE);
            }
            int sx = this.leftPos + slot.x;
            int sy = Math.round(this.topPos + slot.y + entryOffsetY);
            boolean hovered = this.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY);
            boolean screenDoorCardSlot = slot.index
                    == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD;
            if (screenDoorCardSlot) {
                drawScreenDoorCardSlotFrame(g, sx, sy, hovered);
            }
            if (hovered && !screenDoorCardSlot) {
                g.fillGradient(sx, sy, sx + 16, sy + 16, 0x80FFFFFF, 0x80FFFFFF);
            }
            var stack = slot.getItem();
            if (!stack.isEmpty()) {
                g.renderItem(stack, sx, sy);
                g.renderItemDecorations(this.font, stack, sx, sy);
            }
            // 共有先になっているスロットには × 線を描画 (アイテム配置不可だと視覚的に示す)。
            boolean drawLock = false;
            if (slot.index == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_DETECTION_CARD
                    && lockDet) drawLock = true;
            else if (slot.index == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_RANGE_BOARD
                    && lockRng) drawLock = true;
            if (drawLock) drawSlotLockedHatch(g, sx, sy);
        }
        g.pose().popPose();

        // 手持ち (drag 中) アイテムは scale 影響を受けない z=700 layer に描画。
        // ツールチップは render() の最後に z=900 で再描画する (popup の上に表示)。
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        var carried = this.menu.getCarried();
        if (!carried.isEmpty()) {
            int cx = mouseX - 8;
            int cy = mouseY - 8;
            g.renderItem(carried, cx, cy);
            g.renderItemDecorations(this.font, carried, cx, cy);
        }
        g.pose().popPose();
    }

    private void drawScreenDoorCardSlotFrame(net.minecraft.client.gui.GuiGraphics g,
                                             int slotX, int slotY, boolean hovered) {
        int left = slotX - 1;
        int top = slotY - 1;
        int right = left + 18;
        int bottom = top + 18;
        int border = hovered ? 0xFF4FC3F7 : 0xFF2A2A3A;
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, left, top, 18, 18, 5f, 0x8C000000);
        if (hovered) {
            belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, left + 1, top + 1, 16, 16, 4f, 0x264FC3F7);
        }
        belugalab.mcss3.draw.SmoothRenderer.strokeRoundedRect(g, left, top, 18, 18, 5f, 1f, border);
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

    /** 共有先になっているスロット (16x16) に × 状の斜線を描画する。
     *  ユーザにアイテムが配置できないことを視覚的に伝える。 */
    private void drawSlotLockedHatch(net.minecraft.client.gui.GuiGraphics g, int x, int y) {
        // 半透明の暗いオーバーレイで「使用不可」の雰囲気を出す
        g.fill(x, y, x + 16, y + 16, 0x80101020);
        // 赤い斜線 (\\ と /) を 16 ドット分プロット。各ドットは 1x1 px の塗り。
        int line = 0xFFff5555;
        for (int i = 0; i < 16; i++) {
            // \ : (i, i)
            g.fill(x + i, y + i, x + i + 1, y + i + 1, line);
            // / : (15-i, i)
            g.fill(x + 15 - i, y + i, x + 16 - i, y + i + 1, line);
        }
    }

    /** Menu slot の x/y を reflection 経由で書き換える (Slot.x/y は package-private)。 */
    private void setMenuSlotPos(int slotIndex, int x, int y) {
        if (slotIndex < 0 || slotIndex >= this.menu.slots.size()) return;
        var slot = this.menu.slots.get(slotIndex);
        try {
            java.lang.reflect.Field fx = net.minecraft.world.inventory.Slot.class.getDeclaredField("x");
            java.lang.reflect.Field fy = net.minecraft.world.inventory.Slot.class.getDeclaredField("y");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setInt(slot, x);
            fy.setInt(slot, y);
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[RailwayScreen] GUI op failed", e); }
    }


    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // 右クリックで「条件をなしに戻す」 (cond-display only)
        if (button == 1) {
            for (String c : classes) {
                if ("ann-cond-display".equals(c)) {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        int noneOrd = com.trainsystemutilities.announce.AnnouncementCondition.Type.NONE.ordinal();
                        sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandPayload.OP_SET_ENTRY_CONDITION,
                                idx, noneOrd, 0);
                        applyConditionTypeLocally(idx, noneOrd);
                    }
                    return;
                }
            }
        }
        // BelugaExperience 標準ヘッダ部品 (R4.17): hint / wiki本 / × を base より先に処理。
        // railway は 4-arg を super 無しで override しているため、ここで明示的にルートする。
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) belugalab.mcss3.wiki.Wiki.open(pid);
                return;
            }
            if ("mc-popup-close".equals(c)) { if (closeOpenOverlay()) return; onClose(); return; }
        }
        onElementClick(classes, mouseX, mouseY);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        // batch / monitor toggle は popup 中も操作可
        if (batchToggle.handleClick(classes)) return;
        if (monitorToggle.handleClick(classes)) return;
        // popup が開いていても、popup 内クラスでなければ main ハンドラに落とす。
        // (V1 と同じく popup 開中もメイン GUI 操作可。Settings + Color 同時表示にも必要。)
        if (showColorSettings) {
            // 色対象ドロップダウン項目クリック
            // Color popup click → ColorTargetController に委譲
            int dIdx = JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("color-popup-close".equals(c)) {
                    showColorSettings = false;
                    colorPopup.resetTransientState();
                    return;
                }
            }
            boolean wasOpen = colorPopup.isDropdownOpen();
            if (colorPopup.handleClick(classes, dIdx)) {
                if (!wasOpen && colorPopup.isDropdownOpen()) {
                    // dropdown 開く瞬間にリストアニメを再トリガー (popup 全体は pop し直さない)
                    clearOverlay2AnimByClass("color-target-list");
                }
                return;
            }
            // 一致なし → fall through to main (色 popup 中も main の他ボタンが押せる)
        }
        if (showSettings) {
            // Group / symbol-dropdown items inside repeat
            int idx = JsonLayoutEngine.currentRepeatIndex();
            if (idx >= 0) {
                for (String c : classes) {
                    if ("group-item".equals(c)) {
                        var groups = be().getMonitorGroups();
                        if (idx < groups.size()) {
                            selectedGroupIndex = idx;
                            resetLocalOverrides();
                        }
                        return;
                    }
                    if ("sym-dropdown-item".equals(c)) {
                        var rbe = be();
                        if (idx == 0) {
                            rbe.clearLineSymbol();
                            // server に同期するための button id (V1 と同じ)
                            clickButton(7000);
                        } else {
                            var symbols = getAvailableSymbols();
                            int symIdx = idx - 1;
                            if (symIdx < symbols.size()) {
                                var sym = symbols.get(symIdx);
                                rbe.setLineSymbol(sym.getLetters(), sym.getNumber(), sym.getBorderColor());
                                // V1 はクライアント直接 set + サーバ同期は別チャネル。
                                // ここでは button id 7001+ で server に通知
                                clickButton(7001 + symIdx);
                            }
                        }
                        showSymbolDropdown = false;
                        return;
                    }
                }
            }
            for (String c : classes) {
                switch (c) {
                    case "settings-popup-close":
                        // mc-popup-close は使わない (両 popup 共通クラスのため)
                        showSettings = false; showSymbolDropdown = false;
                        resetLocalOverrides(); return;
                    case "face-flip-btn":
                        showBackFace = !showBackFace;
                        resetLocalOverrides();
                        // プレビュー canvas だけを flip 演出対象にする (500ms scaleX from 0.05)。
                        // popup root には触らないので popup 全体は pop 再生されない。
                        clearOverlayAnimByClass("monitor-preview-canvas");
                        return;
                    // batch-toggle-track/knob は batchToggle controller が下で処理
                    case "sym-display":
                        showSymbolDropdown = !showSymbolDropdown;
                        return;
                    case "track-display-pos": {
                        // クリックで左/右切替
                        int cur = currentTrackPosition();
                        localTrackPosition = cur == 0 ? 1 : 0;
                        int gi = currentGroupIndex();
                        if (showBackFace) {
                            clickButton(batchApply() ? 3000 : 3001 + gi);
                        } else {
                            clickButton(batchApply() ? 1000 : 1001 + gi);
                        }
                        return;
                    }
                    case "track-display-clock": {
                        int cur = currentClockVisible();
                        localClockVisible = cur == 1 ? 0 : 1;
                        int gi = currentGroupIndex();
                        if (showBackFace) {
                            clickButton(batchApply() ? 5000 : 5001 + gi);
                        } else {
                            clickButton(batchApply() ? 4000 : 4001 + gi);
                        }
                        return;
                    }
                }
            }
            // 一致なし → main ハンドラに落とす (popup 中も main 操作可)
        }
        for (String c : classes) {
            switch (c) {
                // hint-toggle-track/knob は base class HintToggleHelper が自動処理
                // monitor-toggle-track/knob は下の controller dispatch で処理
                case "settings-btn":
                    // 同じボタンで開閉トグル
                    setFunctionDropdownOpen(false);
                    showSettings = !showSettings;
                    if (!showSettings) { showSymbolDropdown = false; resetLocalOverrides(); }
                    return;
                case "color-btn":
                    setFunctionDropdownOpen(false);
                    // 排他: アナウンス / ホームドア popup を閉じてから Color popup を開く
                    if (showAnnouncement) {
                        showAnnouncement = false; resetAnnouncementTransientState();
                        hideAnnouncementSlots();
                    }
                    if (showScreenDoor) { showScreenDoor = false; hideScreenDoorSlot(); }
                    showColorSettings = !showColorSettings;
                    return;
                case "announcement-btn": {
                    setFunctionDropdownOpen(false);
                    if (!com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()) return;
                    if (showColorSettings) { showColorSettings = false; colorPopup.resetTransientState(); }
                    if (showScreenDoor) { showScreenDoor = false; hideScreenDoorSlot(); }
                    showAnnouncement = !showAnnouncement;
                    if (showAnnouncement) {
                        // 開いた時刻は render() 内で popup 初出現と同フレームで set される。
                        resetAnnouncementTransientState();
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.trainsystemutilities.network.AnnouncementCommandPayload(
                                        be().getBlockPos(),
                                        com.trainsystemutilities.network.AnnouncementCommandPayload.OP_SYNC_REQUEST,
                                        0, 0, 0));
                        positionAnnouncementSlots();
                    } else {
                        resetAnnouncementTransientState();
                        hideAnnouncementSlots();
                    }
                    return;
                }
                case "announcement-popup-close":
                    showAnnouncement = false;
                    resetAnnouncementTransientState();
                    hideAnnouncementSlots();
                    return;
                case "function-dd-trigger":
                    toggleFunctionDropdown();
                    return;
                case "screen-door-btn": {
                    // ドロップダウン項目: ホームドア popup を開く
                    setFunctionDropdownOpen(false);
                    // 排他: アナウンス / Color popup を閉じてから開く
                    if (showColorSettings) { showColorSettings = false; colorPopup.resetTransientState(); }
                    if (showAnnouncement) {
                        showAnnouncement = false; resetAnnouncementTransientState();
                        hideAnnouncementSlots();
                    }
                    showScreenDoor = !showScreenDoor;
                    if (showScreenDoor) {
                        resetScreenDoorPreviewView();
                        positionScreenDoorSlot();
                    } else {
                        hideScreenDoorSlot();
                    }
                    return;
                }
                case "screen-door-popup-close":
                    showScreenDoor = false;
                    showScreenDoorColorPicker = false;
                    hideScreenDoorSlot();
                    return;
                case "sd-color-swatch":
                    // popup 内 picker をトグル開閉
                    if (showScreenDoor) showScreenDoorColorPicker = !showScreenDoorColorPicker;
                    return;
                case "sd-color-picker-close":
                case "sd-color-picker-modal":
                    showScreenDoorColorPicker = false;
                    return;
                case "sd-test-open-btn":
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.trainsystemutilities.network.ScreenDoorTestActionPayload(
                                    be().getBlockPos(),
                                    com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_OPEN));
                    return;
                case "sd-test-close-btn":
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.trainsystemutilities.network.ScreenDoorTestActionPayload(
                                    be().getBlockPos(),
                                    com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_CLOSE));
                    return;
                case "sd-cond-add-btn":
                    sendScreenDoorCondAdd();
                    return;
                case "sd-cond-event": {
                    int idx = sdCondRealIdx();
                    var conds = be().getScreenDoorConditions();
                    if (idx < 0 || idx >= conds.size()) return;
                    var cur = conds.get(idx);
                    int next = (cur.eventType()
                            == com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP)
                            ? com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_DEPART
                            : com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP;
                    sendScreenDoorCondUpdate(idx, cur.withEvent(next));
                    return;
                }
                case "sd-cond-action": {
                    int idx = sdCondRealIdx();
                    var conds = be().getScreenDoorConditions();
                    if (idx < 0 || idx >= conds.size()) return;
                    var cur = conds.get(idx);
                    int next = (cur.actionType() + 1) % 3;
                    sendScreenDoorCondUpdate(idx, cur.withAction(next));
                    return;
                }
                case "sd-cond-del-btn": {
                    int idx = sdCondRealIdx();
                    if (idx < 0) return;
                    sendScreenDoorCondRemove(idx);
                    return;
                }
                default:
                    // sd-preset-N (= N: 0..11) → 帯色を BE に反映 + picker 閉じる
                    if (c.startsWith("sd-preset-")) {
                        try {
                            int idx = Integer.parseInt(c.substring("sd-preset-".length()));
                            if (idx >= 0 && idx < SCREEN_DOOR_BAND_PRESETS.length) {
                                int argb = SCREEN_DOOR_BAND_PRESETS[idx];
                                // server 同期
                                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                        new com.trainsystemutilities.network.ScreenDoorBandColorPayload(
                                                be().getBlockPos(), argb));
                                // client 即時反映 (= getDynamicColor が次フレームで新色を返す)
                                be().setScreenDoorBandColorARGB(argb);
                                showScreenDoorColorPicker = false;
                            }
                        } catch (NumberFormatException ignored) {}
                        return;
                    }
                    break;
                // ann-master / ann-rangeframe / ann-attenuation toggle は下の controller dispatch で処理
                case "ann-add-entry-btn":
                    sendAnnouncementCmd(com.trainsystemutilities.network.AnnouncementCommandPayload.OP_ADD_ENTRY, 0, 0, 0);
                    return;
                case "ann-test-play-btn": {
                    var cfg = announcementConfig();
                    if (cfg != null && cfg.size() > 0) {
                        sendAnnouncementCmd(com.trainsystemutilities.network.AnnouncementCommandPayload.OP_TEST_PLAY, 0, 0, 0);
                    }
                    return;
                }
                case "ann-cond-display": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx < 0) return;
                    conditionDropdown.toggleFor(idx);
                    if (conditionDropdown.isOpen()) {
                        // 再 open ごとに anim spec を変えて AnimationNode の animChanged restart を発火
                        // (= function dropdown と同手法。clearAnimStateForClass は V3 で no-op のため依存不可)。
                        conditionDropdownOpenSerial++;
                    }
                    return;
                }
                case "ann-cond-dd-item-0":
                case "ann-cond-dd-item-1":
                case "ann-cond-dd-item-2": {
                    int typeOrd = c.charAt(c.length() - 1) - '0';
                    if (conditionDropdown.isOpen()) {
                        int eIdx = conditionDropdown.openIdx();
                        sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandPayload.OP_SET_ENTRY_CONDITION,
                                eIdx, typeOrd, 0);
                        applyConditionTypeLocally(eIdx, typeOrd);
                    }
                    conditionDropdown.close();
                    return;
                }
                case "ann-entry-pause-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_STOP_PLAYBACK,
                            idx, 0, 0);
                    return;
                }
                case "ann-entry-up-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx > 0) {
                        triggerAnnouncementSwap(idx, idx - 1);
                        sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandPayload.OP_REORDER_ENTRY,
                                idx, idx - 1, 0);
                    }
                    return;
                }
                case "ann-entry-down-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    var cfg = announcementConfig();
                    if (idx >= 0 && cfg != null && idx + 1 < cfg.size()) {
                        triggerAnnouncementSwap(idx, idx + 1);
                        sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandPayload.OP_REORDER_ENTRY,
                                idx, idx + 1, 0);
                    }
                    return;
                }
                case "ann-entry-test-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_TEST_PLAY, idx, 0, 0);
                    return;
                }
                case "ann-entry-del-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_REMOVE_ENTRY, idx, 0, 0);
                    return;
                }
                case "ann-share-btn": {
                    if (!showAnnouncement) return;
                    showAnnouncementShareList = !showAnnouncementShareList;
                    if (showAnnouncementShareList) {
                        // 開く瞬間に share panel の popIn だけを再トリガー。
                        clearOverlay2AnimByClass("ann-share-list-bg");
                        // dropdown が開いていたら閉じる
                        conditionDropdown.close();
                        annShareScroll.clamp();
                    }
                    return;
                }
                case "ann-share-close-btn":
                    showAnnouncementShareList = false;
                    return;
                // ann-share-det / ann-share-rng toggle は下の controller dispatch で処理
                case "owner-face-box":
                case "owner-face-canvas": // 中央の顔 canvas が innermost auto-clickable で実クリックはこちらに来る
                    // サーバ側で button id 9000 経由で togglePrivateMode 呼び出し。
                    // client は getUpdateTag 同期で反映 (client 直 mutate を廃止)。
                    clickButton(belugalab.tsu.api.OwnerAccess.TOGGLE_BUTTON);
                    return;
            }
        }
        // Announcement-related toggles (= controller dispatch、popup 中のみ意味あり)
        if (showAnnouncement) {
            if (annMasterToggle.handleClick(classes)) return;
            if (annRangeFrameToggle.handleClick(classes)) return;
            if (annAttenuationToggle.handleClick(classes)) return;
        }
        if (showScreenDoor) {
            if (sdHighlightToggle.handleClick(classes)) return;
        }
        if (showAnnouncement && showAnnouncementShareList) {
            int idx = annShareRealIdx();
            if (annShareDetToggle.handleClick(classes, idx)) return;
            if (annShareRngToggle.handleClick(classes, idx)) return;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        // 3D preview の zoom (= 長いホーム想定で上限 30x まで拡大可)
        if (isOverScreenDoorPreview(mx, my)) {
            sdPreviewZoom = Math.max(0.2f, Math.min(30.0f, sdPreviewZoom * (dy > 0 ? 1.2f : 0.85f)));
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
        if (showSettings) {
            // hit-test each settings display for wheel-driven adjustment
            int delta = dy > 0 ? 1 : -1;
            // Track number
            if (overSettingsDisplay("track-display-track", mx, my)) {
                int newV = Math.max(0, Math.min(99, currentTrackNumber() + delta));
                localTrackNumber = newV;
                int gi = currentGroupIndex();
                if (showBackFace) clickButton(batchApply() ? 2000 + newV : 2200 + gi * 100 + newV);
                else clickButton(batchApply() ? 2 + newV : 200 + gi * 100 + newV);
                return true;
            }
            if (overSettingsDisplay("track-display-font", mx, my)) {
                int newV = Math.max(0, Math.min(97, currentTrackFontSize() + delta));
                localTrackFontSize = newV;
                int gi = currentGroupIndex();
                if (showBackFace) clickButton(batchApply() ? 2100 + newV : 2500 + gi * 100 + newV);
                else clickButton(batchApply() ? 102 + newV : 500 + gi * 100 + newV);
                return true;
            }
            if (overSettingsDisplay("track-display-clockfs", mx, my)) {
                int newV = Math.max(0, Math.min(97, currentClockFontSize() + delta));
                localClockFontSize = newV;
                int gi = currentGroupIndex();
                if (showBackFace) clickButton(batchApply() ? 5100 + newV : 5200 + gi * 100 + newV);
                else clickButton(batchApply() ? 4100 + newV : 4200 + gi * 100 + newV);
                return true;
            }
            // pos / clock visible は wheel でも切替 (mouseClicked と同じ挙動)
            if (overSettingsDisplay("track-display-pos", mx, my)) {
                onElementClick(new String[]{"track-display-pos"}, (int) mx, (int) my);
                return true;
            }
            if (overSettingsDisplay("track-display-clock", mx, my)) {
                onElementClick(new String[]{"track-display-clock"}, (int) mx, (int) my);
                return true;
            }
            return true;  // popup 内のスクロール消費
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    /** Hit-test a class element inside the overlay. ドラッグで移動した位置に
     *  追従するため JsonLayoutScreen の overlayX/Y() を使用する (固定中央配置を仮定しない)。 */
    private boolean overSettingsDisplay(String className, double mx, double my) {
        int[] r = findElementByClass(className);
        if (r == null) return false;
        int overX = overlayX();
        int overY = overlayY();
        int x = overX + r[0], y = overY + r[1];
        return mx >= x && mx < x + r[2] && my >= y && my < y + r[3];
    }

    /** MCSS 基底の sendButtonClick に委譲 (旧 clickButton 名は call site 互換のため残す)。 */
    private void clickButton(int id) { sendButtonClick(id); }

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

    /** ホームドア popup 内の 3D preview。 メモリーカードのメンバー BlockPos からブロックを取得して描画。
     *  slot + carried 両方を見る (= ユーザーが card を click で持ち上げ中も継続描画)。 */
    private void drawScreenDoorPreview(GuiGraphics g, int x, int y, int w, int h) {
        if (showScreenDoorColorPicker) return;
        net.minecraft.world.item.ItemStack card = this.menu.slots.get(
                com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD).getItem();
        if (card.isEmpty()) {
            net.minecraft.world.item.ItemStack carried = this.menu.getCarried();
            if (!carried.isEmpty()
                    && carried.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem) {
                card = carried;
            }
        }
        java.util.List<belugalab.mcss3.preview.GuiBlock3DRenderer.Block3DEntry> entries =
                new java.util.ArrayList<>();
        if (!card.isEmpty()
                && card.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
            net.minecraft.nbt.CompoundTag tag =
                    card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            if (com.trainsystemutilities.item.MemoryCardItem.TYPE_SCREEN_DOOR_GROUP
                    .equals(tag.getString("Type"))) {
                long[] members = readScreenDoorMembers(tag);
                if (this.minecraft != null && this.minecraft.level != null) {
                    for (long packed : members) {
                        net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(packed);
                        net.minecraft.world.level.block.state.BlockState st =
                                this.minecraft.level.getBlockState(p);
                        if (!st.isAir()) {
                            entries.add(new belugalab.mcss3.preview.GuiBlock3DRenderer.Block3DEntry(p, st));
                        }
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            // メモリーカード未挿入 or chunk 未ロード等の場合は説明テキストのみ
            String msg = Component.translatable(card.isEmpty()
                    ? "tsu.rm.card_not_inserted" : "tsu.rm.block_loading").getString();
            int tw = this.font.width(msg);
            g.drawString(this.font, msg, x + (w - tw) / 2, y + h / 2 - 4, 0xFF888888, false);
            return;
        }
        belugalab.mcss3.preview.GuiBlock3DRenderer.render(
                g, x, y, w, h, entries,
                sdPreviewRotY, sdPreviewRotX, sdPreviewZoom, sdPreviewPanX, sdPreviewPanY);
    }

    /** layout の repeat index を実 entry index に変換 (= scroll offset を加算)。 */
    private int sdCondRealIdx() {
        return belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex()
                + sdCondScroll.offset();
    }

    /** 共有リストの repeat index を実 candidate index に変換 (= scroll offset を加算)。 */
    private int annShareRealIdx() {
        return belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex()
                + annShareScroll.offset();
    }

    /** メモリーカードが slot or carried に挿入され、 screen_door_group type なら online。 */
    private boolean isScreenDoorOnline() {
        net.minecraft.world.item.ItemStack card = this.menu.slots.get(
                com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD).getItem();
        if (card.isEmpty()) {
            net.minecraft.world.item.ItemStack carried = this.menu.getCarried();
            if (!carried.isEmpty()
                    && carried.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem) {
                card = carried;
            }
        }
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
        int absX = overlay2X() + 12;
        int absY = overlay2Y() + SD_COND_AREA_Y;
        return mouseX >= absX && mouseX < absX + 216
                && mouseY >= absY && mouseY < absY + SD_COND_AREA_H;
    }

    /** preview box (= popup local 12,50,216,100) の absolute 範囲に hit 判定。 */
    private boolean isOverScreenDoorPreview(double mouseX, double mouseY) {
        if (!showScreenDoor || showScreenDoorColorPicker) return false;
        int absX = overlay2X() + 12;
        int absY = overlay2Y() + 50;
        return mouseX >= absX && mouseX < absX + 216 && mouseY >= absY && mouseY < absY + 100;
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
            net.minecraft.world.item.ItemStack card = this.menu.slots.get(
                    com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD).getItem();
            if (card.isEmpty()) {
                net.minecraft.world.item.ItemStack carried = this.menu.getCarried();
                if (!carried.isEmpty()
                        && carried.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem) {
                    card = carried;
                }
            }
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
                    now = readScreenDoorMembers(tag);
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
    }

    /** NBT sync で ListTag<LongTag> → LongArrayTag に自動圧縮されるため両方対応。 */
    private static long[] readScreenDoorMembers(net.minecraft.nbt.CompoundTag tag) {
        net.minecraft.nbt.Tag raw = tag.get(
                com.trainsystemutilities.item.MemoryCardItem.TAG_MEMBERS);
        if (raw instanceof net.minecraft.nbt.LongArrayTag lat) {
            return lat.getAsLongArray();
        }
        if (raw instanceof net.minecraft.nbt.ListTag lt) {
            long[] arr = new long[lt.size()];
            for (int i = 0; i < lt.size(); i++) {
                if (lt.get(i) instanceof net.minecraft.nbt.LongTag longTag) {
                    arr[i] = longTag.getAsLong();
                }
            }
            return arr;
        }
        return new long[0];
    }

    /** 設定 popup 上部の小型モニタープレビュー (h=60)。
     *  - 番線とシンボルは同時表示可能 (シンボル=上、番線=下)
     *  - 時計は番線パネル下部 (h=60 でディメンションテキストとは重ならない位置) */
    private void drawMonitorPreview(GuiGraphics g, int x, int y, int w, int h) {
        // 外枠
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, 0xFF000000);
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, x + 1, y + 1, w - 2, h - 2, 4f, 0xFF0a0a18);

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
                belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g,
                        iconX, curY, iconSize, iconSize, 5f, 0xFFFFFFFF);
                belugalab.mcss3.draw.SmoothRenderer.strokeRoundedRect(g,
                        iconX, curY, iconSize, iconSize, 5f, 1.5f, borderColor);
                curY += iconSize + 2;
            }
            // 番線数字 (シンボル下 or 上部)
            if (trackNumber > 0) {
                String numStr = String.valueOf(trackNumber);
                int textW = this.font.width(numStr);
                int textX = sideX + (sidePanelW - textW) / 2;
                g.drawString(this.font, numStr, textX, curY, 0xFF4fc3f7, false);
            }

            // 時計 (パネル底部)
            if (clockVisible == 1) {
                String clockStr = getMinecraftTime();
                int cw = this.font.width(clockStr);
                int cx = sideX + (sidePanelW - cw) / 2;
                int cy = y + h - 12;
                g.drawString(this.font, clockStr, cx, cy, 0xFFffc107, false);
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
        belugalab.tsu.api.OwnerFacePainter.draw(g, x, y, w, h, be().getOwnerUUID());
    }

    /** ヘッダの路線記号アイコンを LineSymbolPainter (TSU 共通) で描画。 */
    private void drawHeaderSymbol(GuiGraphics g, int x, int y, int w, int h) {
        var sym = getAssignedLineSymbol();
        if (sym == null) return;
        int size = Math.min(w, h);
        if (size <= 0) return;
        LineSymbolPainter.draw(g, x, y, size, sym, this.font);
    }

    /** リンク先 ManagementComputer / 周辺 chunk から利用可能な路線記号を集める。 */
    private java.util.List<com.trainsystemutilities.blockentity.LineSymbol> getAvailableSymbols() {
        java.util.List<com.trainsystemutilities.blockentity.LineSymbol> result = new java.util.ArrayList<>();
        if (this.minecraft == null || this.minecraft.level == null) return result;
        try {
            var server = this.minecraft.getSingleplayerServer();
            if (server == null) return result;
            var serverLevel = server.getLevel(this.minecraft.level.dimension());
            if (serverLevel == null) return result;
            var bePos = be().getBlockPos();
            for (int dx = -64; dx <= 64; dx += 16) {
                for (int dz = -64; dz <= 64; dz += 16) {
                    var chunk = serverLevel.getChunkAt(bePos.offset(dx, 0, dz));
                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        if (entry.getValue() instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity mbe) {
                            result.addAll(mbe.getLineSymbols());
                        }
                    }
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[RailwayScreen] GUI op failed", e); }
        return result;
    }

    /** 割り当てられた路線記号を取得。BE が直接持っていなければ ManagementComputer 経由で解決。 */
    private com.trainsystemutilities.blockentity.LineSymbol getAssignedLineSymbol() {
        var be = be();
        String stationName = be.getLinkedStationName();
        if (stationName == null || stationName.isEmpty()) return null;
        if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
            var sl = this.minecraft.getSingleplayerServer().getLevel(be.getLevel().dimension());
            if (sl != null) {
                var sbe = sl.getBlockEntity(be.getBlockPos());
                if (sbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rmbe) {
                    if (rmbe.getAssignedLineSymbol() != null) return rmbe.getAssignedLineSymbol();
                    if (rmbe.getLinkedComputerPos() != null) {
                        var cbe = sl.getBlockEntity(rmbe.getLinkedComputerPos());
                        if (cbe instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity mcbe) {
                            return mcbe.getSymbolForStation(stationName, be.getLinkedStationPos());
                        }
                    }
                }
            }
        }
        return be.getAssignedLineSymbol();
    }

    @Override
    public belugalab.mcss3.anim.Transition getDynamicTransition(String[] classes, String key) {
        // toggle-bg / toggle-knob は基底 JsonLayoutScreen が解決するので super に委譲。
        belugalab.mcss3.anim.Transition base = super.getDynamicTransition(classes, key);
        if (base != null) return base;
        return switch (key) {
            case "ann-entry-active" ->
                    belugalab.mcss3.anim.Transition.of(160, belugalab.mcss3.anim.Easing.EASE_OUT);
            case "ann-playing-frame-move" ->
                    belugalab.mcss3.anim.Transition.of(220, belugalab.mcss3.anim.Easing.EASE_OUT);
            default -> null;
        };
    }

    @Override
    public belugalab.mcss3.anim.Animation getDynamicAnimation(String[] classes, String key) {
        // 基底クラスが dialog-open / *-popup-open を処理。それ以外は本 Screen 固有。
        // ann-share-popup-open は "-popup-open" で終わるので base が popIn(220) を返す
        // → 他の popup (settings/color/announcement) と完全に同じ展開アニメ。
        belugalab.mcss3.anim.Animation base = super.getDynamicAnimation(classes, key);
        if (base != null) return base;
        return switch (key) {
            // 列車行のスライドイン: 距離だけ違う同パターン → preset 1 行で完結。
            case "next-row-enter"     -> belugalab.mcss3.anim.Animation.slideInFromRight(260, 80f);
            case "arrived-row-enter"  -> belugalab.mcss3.anim.Animation.slideInFromRight(280, 60f);
            // 色ドロップダウン: scaleY + translateY -h/2 で上端固定の下方向展開 + バウンス。
            // dropdown panel h = 11*10 + 4 = 114。
            // dropdown helper の prefix="color-target" → animationKey は "color-target-open"
            case "color-target-open" -> belugalab.mcss3.anim.Animation.dropdownDown(280, 114);
            // 条件 dropdown (NONE / PASS / STOP の 3 項目): panel h = 34
            case "ann-cond-dd-open"  -> belugalab.mcss3.anim.Animation.dropdownDown(220 + (conditionDropdownOpenSerial & 1), 34);
            // 機能 dropdown (ホームドア / アナウンス 2 項目): 下展開、 anchor top、 panel h = 36
            case "function-dd-open"  -> belugalab.mcss3.anim.Animation.dropdownDown(220 + (functionDropdownOpenSerial & 1), 36);
            // 帯色 picker (= popup on popup): popIn 軽量
            case "sd-color-picker-open" -> belugalab.mcss3.anim.Animation.popIn(200);
            case "ann-entry-shuffle" -> {
                int repeatIdx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                float distance = announcementEntryShuffleDistance(repeatIdx);
                if (distance == 0f) yield null;
                yield belugalab.mcss3.anim.Animation.of(220)
                        .easing(belugalab.mcss3.anim.Easing.EASE_OUT)
                        .translateY(distance, 0f)
                        .build();
            }
            // モニタープレビューのカードフリップ (scaleX 0→1)。
            case "preview-flip"        -> belugalab.mcss3.anim.Animation.flipX(500);
            default -> null;
        };
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        if ("color-current-bg".equals(key)) {
            return parseHexArgb(getCurrentSelectedColorHex(), 0xFF000000);
        }
        // hint-toggle-bg / hint-knob-bg は JsonLayoutEngine が HintToggleHelper にルートするので解決不要。
        // 全 toggle (monitor/batch/ann*) は controller、monitor は withVisualState で derived 解決。
        switch (key) {
            case "monitor-toggle-bg":   return monitorToggle.trackBg();
            case "monitor-knob-bg":     return monitorToggle.knobBg();
            case "monitor-indicator-bg":return monitorToggle.indicatorBg();
            case "monitor-status-color":return monitorToggle.statusText();
            case "batch-toggle-bg":     return batchToggle.trackBg();
            case "batch-knob-bg":       return batchToggle.knobBg();
            case "ann-master-toggle-bg":      return annMasterToggle.trackBg();
            case "ann-master-knob-bg":        return annMasterToggle.knobBg();
            case "ann-rangeframe-toggle-bg":  return annRangeFrameToggle.trackBg();
            case "ann-rangeframe-knob-bg":    return annRangeFrameToggle.knobBg();
            case "ann-attenuation-toggle-bg": return annAttenuationToggle.trackBg();
            case "ann-attenuation-knob-bg":   return annAttenuationToggle.knobBg();
            case "ann-entry-row-bg": {
                int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                return idx == announcementPlayingEntryIndex() ? 0x224FC3F7 : null;
            }
            case "ann-entry-row-border": {
                int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                return idx == announcementPlayingEntryIndex() ? 0xFF4FC3F7 : null;
            }
            case "ann-playing-frame-bg":
                return 0x124FC3F7;
            case "ann-playing-frame-border":
                return 0xFF4FC3F7;
            case "ann-share-det-bg":      return annShareDetToggle.trackBgFor(annShareRealIdx());
            case "ann-share-det-knob-bg": return annShareDetToggle.knobBgFor(annShareRealIdx());
            case "ann-share-rng-bg":      return annShareRngToggle.trackBgFor(annShareRealIdx());
            case "ann-share-rng-knob-bg": return annShareRngToggle.knobBgFor(annShareRealIdx());
            case "sd-color-bg":
            case "sd-color-picker-cur-bg":
                return be().getScreenDoorBandColorARGB();
            case "sd-status-indicator-bg":
                return isScreenDoorOnline() ? 0xFF66BB6A : 0xFF555555;
            case "sd-status-color":
                return isScreenDoorOnline() ? 0xFFFFFFFF : 0xFF888888;
            case "sd-highlight-toggle-bg": return sdHighlightToggle.trackBg();
            case "sd-highlight-knob-bg":   return sdHighlightToggle.knobBg();
        }
        // Owner face box border: Private = 赤、Public = 緑
        if ("owner-border".equals(key)) {
            return belugalab.tsu.api.OwnerAccess.ringColor(be().isPrivateMode());
        }
        return null;
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        switch (key) {
            case "header-sym-visible":
                return getAssignedLineSymbol() != null;
            case "face-flip-visible": {
                // Only show flip button when current group is double-sided
                var groups = be().getMonitorGroups();
                if (groups.isEmpty()) return false;
                int gi = currentGroupIndex();
                return groups.get(gi).doubleSided();
            }
            case "group-selector-visible":
                // Show only when batch=false AND multiple groups exist
                return !batchApply() && be().getMonitorGroups().size() > 1;
            case "sym-dropdown-visible":
                return showSymbolDropdown;
            case "announcement-btn-visible":
                return com.trainsystemutilities.compat.sas.SasIntegration.isLoaded();
            case "function-dd-visible-ann":
                // SAS 連携時のみ「アナウンス」 項目表示 + dropdown 展開中
                return showFunctionDropdown
                        && com.trainsystemutilities.compat.sas.SasIntegration.isLoaded();
            case "function-dd-visible-door":
                return showFunctionDropdown;
            case "sd-color-picker-visible":
                return showScreenDoor && showScreenDoorColorPicker;
            case "sd-cond-empty-visible":
                return be().getScreenDoorConditions().isEmpty();
            case "sd-cond-scroll-visible":
                return sdCondScroll.needsScrollbar();
            case "ann-share-scroll-visible":
                return showAnnouncement && showAnnouncementShareList && annShareScroll.needsScrollbar();
            case "ann-cond-dd-visible":
                return showAnnouncement && conditionDropdown.isOpen()
                        && !showAnnouncementShareList;
            case "ann-share-visible":
                return showAnnouncement && showAnnouncementShareList;
            case "ann-entries-visible":
                // share popup 中は背後の entry list / add/test ボタンを隠す
                return showAnnouncement && !showAnnouncementShareList;
            case "ann-playing-frame-visible":
                return showAnnouncement && !showAnnouncementShareList
                        && announcementPlayingEntryIndex() >= 0;
            case "ann-share-btn-visible":
                // 共有先になっている rmbe からは「共有」ボタンを隠す (二段階共有を防ぐ)
                return showAnnouncement && !showAnnouncementShareList
                        && be().getIncomingShareSources().isEmpty();
            case "ann-incoming-share-visible":
                // 共有元の駅名 banner: 共有先になっていて、かつ共有 popup を開いていない時のみ
                return showAnnouncement && !showAnnouncementShareList
                        && !be().getIncomingShareSources().isEmpty();
            // color-target-visible は colorPopup controller に委譲 (下で resolveBool 経由)
        }
        if (showColorSettings) {
            Boolean b = colorPopup.resolveBool(key);
            if (b != null) return b;
        }
        return null;
    }
}
