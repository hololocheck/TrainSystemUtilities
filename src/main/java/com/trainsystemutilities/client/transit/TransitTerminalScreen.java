package com.trainsystemutilities.client.transit;
import com.manta.api.draw.VectorRenderer;
import com.manta.api.anim.Animation;

import com.manta.api.screen.JsonLayoutEngine;
import com.manta.api.screen.JsonLayoutHandler;
import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PanelTransform;
import com.manta.api.draw.SmoothRenderer;
import com.manta.api.hud.ToggleColors;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import com.trainsystemutilities.station.routing.TrainRouter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.manta.api.controller.TextInputController;
import com.manta.api.controller.ToggleSwitchController;
import com.manta.api.render.TextCaretRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * 乗り換え案内端末のスマホ風 Screen (Phase 19)。
 *
 * <p>右下に固定された 200×340 縦長パネルとして描画される (Screen 全体は透過、
 * 端末以外の領域はゲーム描画が見えたまま)。
 *
 * <p>4 タブ構成:
 * <ul>
 *   <li>TOP: 出発/到着駅入力 + 検索 + 結果タイル + タイル展開で詳細</li>
 *   <li>SCHEDULE: 全列車時刻表 (将来サーバ sync 完成時)</li>
 *   <li>MAP: 全世界線路ネットワーク (将来実装)</li>
 *   <li>SETTINGS: 24h/12h、徒歩到達ゲート 等</li>
 * </ul>
 *
 * <p>Screen として開くため、自動的に:
 * <ul>
 *   <li>マウスカーソル解放 → タップ操作可能</li>
 *   <li>プレイヤー移動キー (W/A/S/D) は vanilla により消費されない (= 移動しない)</li>
 *   <li>EditBox にフォーカスがあるときだけ文字キーが入力に反映</li>
 * </ul>
 * これで「歩きながら検索ボックスに W が入る」問題が根本解決する。
 */
@OnlyIn(Dist.CLIENT)
public class TransitTerminalScreen extends com.manta.api.screen.JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    static final int PANEL_W = 200;
    private static final int PANEL_H = 340;
    private static final int RIGHT_MARGIN = 12;
    private static final int BOTTOM_MARGIN = 12;
    static final int HEADER_H = 22;
    private static final int NAV_H = 28;
    /** W7-1: 下部タブの icon 寸法 (旧: font glyph の実測幅)。 */
    private static final int NAV_ICON_SIZE = 10;
    static final int CONTENT_PAD = 6;

    // Layout: panel coordinates relative to screen
    int px;
    int py;

    // BelugaExperience: vanilla EditBox の代わりに TextInputController (§4.10)。
    // focus は acField (= FROM/TO/SCHEDULE/null) で管理。テキスト + caret は自前描画。
    final TextInputController fromCtrl = new TextInputController(32,
            Component.translatable("tsu.transit_terminal.field_from").getString());
    final TextInputController toCtrl = new TextInputController(32,
            Component.translatable("tsu.transit_terminal.field_to").getString());
    private final TextInputController scheduleCtrl = new TextInputController(32,
            Component.translatable("tsu.transit_terminal.search_placeholder").getString());

    /** どの入力欄に対する autocomplete を出すか (null=非表示)。 */
    private enum AcField { FROM, TO, SCHEDULE }
    private AcField acField = null;
    private int acSelected = -1;
    private static final int AC_MAX_ROWS = 5;
    private static final int AC_ROW_H = 12;
    /** BelugaExperience 標準 pop-in 演出 (= Animation.popIn 同等の 180ms/0.85→1.0/ease-out)。 */
    /**
     * How the autocomplete list unfolds under its field and folds when the field loses focus - the one dropdown motion
     * (Manta DropdownMotion, 2026-10-07; it was OverlayPopIn's scale-in, with no close). {@code acShown} is the field
     * whose list is drawn: the focused one, or the one whose list is still folding.
     */
    private com.manta.api.anim.DropdownMotion acMotion = new com.manta.api.anim.DropdownMotion();
    private AcField acShown = null;

    // ドラッグ状態 (レイアウト調整モード時のスクリーン移動)
    private boolean draggingScreen = false;
    private double dragAnchorX = 0, dragAnchorY = 0;
    private int dragStartPx = 0, dragStartPy = 0;
    private boolean draggingHud = false;
    private double dragHudAnchorX = 0, dragHudAnchorY = 0;
    private int dragHudStartX = 0, dragHudStartY = 0;

    /**
     * The wiki's stand-in has a terminal of its own - its state, its stations, trains and track (TransitTerminalWikiDemo) -
     * installed on this thread for every call into it (scoped). Null for the player's terminal. A wiki page shows several
     * tabs of this screen at once (tools/transit-terminal.md) and plays a search on one (改善1, 2026-10-07): a stand-in on
     * the player's state would put every picture and the player's own terminal on the last tab built, its search and its
     * map view.
     */
    private TransitTerminalState.Data wikiState = null;
    private TransitTerminalClientCache.WikiData wikiData = null;

    /** The wiki's stand-in (WikiLiveCapture): its own terminal from the start, so init reads that one. */
    public static TransitTerminalScreen wikiCreate() {
        TransitTerminalScreen s = new TransitTerminalScreen();
        s.wikiState = TransitTerminalWikiDemo.state();
        s.wikiData = TransitTerminalWikiDemo.cache();
        return s;
    }

    /** Runs {@code body} on this screen's terminal: the stand-in's own installed, or the player's as it is. */
    private <T> T scoped(java.util.function.Supplier<T> body) {
        if (wikiState == null) return body.get();
        return TransitTerminalState.underWiki(wikiState, () -> TransitTerminalClientCache.underWiki(wikiData, body));
    }

    private void scopedRun(Runnable body) {
        scoped(() -> { body.run(); return null; });
    }

    /** The tab this screen shows: its terminal's. */
    private TransitTerminalState.Tab tab() {
        return TransitTerminalState.tab();
    }

    private void setTab(TransitTerminalState.Tab t) {
        TransitTerminalState.setTab(t);
    }

    /** The wiki's states (WikiLiveCapture): "top", "schedule", "map", "settings" - this screen's own tab. */
    public void wikiApplyState(String state) {
        scopedRun(() -> {
            setTab(switch (state) {
                case "schedule" -> TransitTerminalState.Tab.SCHEDULE;
                case "map" -> TransitTerminalState.Tab.MAP;
                case "settings" -> TransitTerminalState.Tab.SETTINGS;
                default -> TransitTerminalState.Tab.TOP;
            });
            rebuildEditBoxes();
        });
    }

    public String wikiCaptureState() {
        return scoped(() -> switch (tab()) {
            case TOP -> "top";
            case SCHEDULE -> "schedule";
            case MAP -> "map";
            case SETTINGS -> "settings";
        });
    }

    public TransitTerminalScreen() {
        super(Component.translatable("tsu.transit_terminal.title"));
        fromCtrl.onChange(() -> { TransitTerminalState.setFromQuery(fromCtrl.value()); acSelected = -1; });
        toCtrl.onChange(() -> { TransitTerminalState.setToQuery(toCtrl.value()); acSelected = -1; });
        scheduleCtrl.onChange(() -> TransitTerminalState.setScheduleQuery(scheduleCtrl.value()));
    }

    private long lastScheduleRequestNanos = 0;
    private long lastMapRequestNanos = 0;

    // === スマホ型 PiP slide (開く=下から上スライドイン、 閉じる=上から下スライドアウト) ===
    private static final long SLIDE_NANOS = 220_000_000L;
    private final long openedAtNano = System.nanoTime();
    private boolean closing = false;
    private long closingAtNano = 0L;

    // === JsonLayoutPlainScreen 連携 ===
    @Override
    protected String layoutJson() {
        return JsonLayoutPlainScreen.loadModResourceJson(
                TrainSystemUtilities.MOD_ID, "layouts/transit-terminal.json");
    }

    /** 右下アンカー + ユーザオフセット (= 中央配置ではなく movable panel)。毎フレーム呼ばれる。 */
    @Override
    protected int[] dialogAnchor(int displayW, int displayH) {
        px = this.width - PANEL_W - RIGHT_MARGIN + TransitTerminalState.screenOffsetX();
        py = this.height - PANEL_H - BOTTOM_MARGIN + TransitTerminalState.screenOffsetY();
        return new int[]{px, py};
    }

    /** panel は固定 200×340 の小型 UI なので auto-scale しない (= px/py と dialogX/Y を 1:1 に保つ)。 */
    @Override
    protected boolean autoScaleEnabled() { return false; }

    @Override
    protected void init() {
        scopedRun(() -> {
            super.init();   // layout を parse + dialogX/Y を dialogAnchor から設定
            rebuildEditBoxes();
            if (tab() == TransitTerminalState.Tab.SCHEDULE) requestScheduleSync();
            if (tab() == TransitTerminalState.Tab.MAP) requestMapSync();
        });
    }

    // ===== Manta 7 push (Phase 4, TSU batch 3, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws (the clock moves every
    // second, the tab on a click), and once when the page opens: the screen overrides no getDynamic*.
    // The toggle transitions are the base's. The three input boxes draw their text and caret in
    // renderInputs(), so the layout's boxes stay empty - they are class-only nodes nothing asks.
    private com.manta.api.state.MantaState pushed;
    private com.manta.api.state.TextSlot tTitle, tClock, tZoom, tSegments;
    private com.manta.api.state.BoolSlot bTop, bSchedule, bMap, bSettings, bScrollbar;
    private com.manta.api.state.NumberSlot nSearchW, nKnob24h, nKnobWalk, nKnobLayout, nKnobHud;
    private com.manta.api.state.ColorSlot cNavTop, cNavSchedule, cNavMap, cNavSettings;
    private com.manta.api.state.ColorSlot cTrack24h, cTrackWalk, cTrackLayout, cTrackHud;
    private com.manta.api.state.ColorSlot cKnob24h, cKnobWalk, cKnobLayout, cKnobHud;

    @Override
    protected void pageOpened(Object page, com.manta.api.screen.PageLayer layer) {
        if (layer != com.manta.api.screen.PageLayer.PRIMARY) return;
        pushed = com.manta.api.state.MantaState.of(page);
        tTitle = pushed.text("tt-title");
        tClock = pushed.text("tt-clock");
        tZoom = pushed.text("tt-map-zoom");
        tSegments = pushed.text("tt-map-segcount");
        bTop = pushed.bool("tt-tab-top");
        bSchedule = pushed.bool("tt-tab-schedule");
        bMap = pushed.bool("tt-tab-map");
        bSettings = pushed.bool("tt-tab-settings");
        bScrollbar = pushed.bool("tt-sch-scrollbar-visible");
        nSearchW = pushed.number("btn-search-label-w");
        nKnob24h = pushed.number("tt-set-knob-24h-x");
        nKnobWalk = pushed.number("tt-set-knob-walk-x");
        nKnobLayout = pushed.number("tt-set-knob-layout-x");
        nKnobHud = pushed.number("tt-set-knob-hud-x");
        cNavTop = pushed.color("tt-nav-top-color");
        cNavSchedule = pushed.color("tt-nav-schedule-color");
        cNavMap = pushed.color("tt-nav-map-color");
        cNavSettings = pushed.color("tt-nav-settings-color");
        cTrack24h = pushed.color("tt-set-track-24h-bg");
        cTrackWalk = pushed.color("tt-set-track-walk-bg");
        cTrackLayout = pushed.color("tt-set-track-layout-bg");
        cTrackHud = pushed.color("tt-set-track-hud-bg");
        cKnob24h = pushed.color("tt-set-knob-24h-bg");
        cKnobWalk = pushed.color("tt-set-knob-walk-bg");
        cKnobLayout = pushed.color("tt-set-knob-layout-bg");
        cKnobHud = pushed.color("tt-set-knob-hud-bg");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushAll() {
        if (pushed == null || !pushed.isOpen()) return;
        var tab = tab();
        pushed.set(tTitle, headerTitleText());
        pushed.set(tClock, formatClock());
        pushed.set(tZoom, String.format("x%.2f", TransitTerminalState.mapZoomD()));
        pushed.set(tSegments, TransitTerminalClientCache.mapSegments().size() + " seg");
        // Phase 3-6: every tab's content is layout-driven, shown by its tab.
        pushed.set(bTop, tab == TransitTerminalState.Tab.TOP);
        pushed.set(bSchedule, tab == TransitTerminalState.Tab.SCHEDULE);
        pushed.set(bMap, tab == TransitTerminalState.Tab.MAP);
        pushed.set(bSettings, tab == TransitTerminalState.Tab.SETTINGS);
        pushed.set(bScrollbar, scheduleFilteredCount() > SCHEDULE_ROWS_VISIBLE);
        // R4.23.1: the search button's glyph became icon + label; the label's width is LabelWidth's.
        pushed.set(nSearchW, com.trainsystemutilities.client.gui.LabelWidth.of("tsu.transit_terminal.btn_search"));
        // SETTINGS knobs: BelugaExperience's standard travel (an offset from each knob's own x).
        int travel = ToggleSwitchController.KNOB_TRAVEL_PX;
        pushed.setOffset(nKnob24h, TransitTerminalState.clock24h() ? travel : 0);
        pushed.setOffset(nKnobWalk, TransitTerminalState.walkGateEnabled() ? travel : 0);
        pushed.setOffset(nKnobLayout, TransitTerminalState.layoutAdjustMode() ? travel : 0);
        pushed.setOffset(nKnobHud, TransitTerminalState.showDetailHud() ? travel : 0);
        // The nav cells: the active tab bright cyan (TRACK_ON), the others grey.
        pushed.set(cNavTop, tabColor(TransitTerminalState.Tab.TOP));
        pushed.set(cNavSchedule, tabColor(TransitTerminalState.Tab.SCHEDULE));
        pushed.set(cNavMap, tabColor(TransitTerminalState.Tab.MAP));
        pushed.set(cNavSettings, tabColor(TransitTerminalState.Tab.SETTINGS));
        // SETTINGS toggles: BelugaExperience's standard ToggleColors.
        pushed.set(cTrack24h, ToggleColors.trackBg(TransitTerminalState.clock24h()));
        pushed.set(cTrackWalk, ToggleColors.trackBg(TransitTerminalState.walkGateEnabled()));
        pushed.set(cTrackLayout, ToggleColors.trackBg(TransitTerminalState.layoutAdjustMode()));
        pushed.set(cTrackHud, ToggleColors.trackBg(TransitTerminalState.showDetailHud()));
        pushed.set(cKnob24h, ToggleColors.knobBg(TransitTerminalState.clock24h()));
        pushed.set(cKnobWalk, ToggleColors.knobBg(TransitTerminalState.walkGateEnabled()));
        pushed.set(cKnobLayout, ToggleColors.knobBg(TransitTerminalState.layoutAdjustMode()));
        pushed.set(cKnobHud, ToggleColors.knobBg(TransitTerminalState.showDetailHud()));
    }

    // === スマホ型 PiP slide: 開く=下端外→resting、 閉じる=resting→下端外。 base の scale+fade は
    //     onClose を直接 override しているため走らない (= 自前の縦 slide が優先)。 ===
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        scopedRun(() -> {
            pushAll();
            super.render(g, mouseX, mouseY, partialTick);   // base が panelTransform() の下で panel を描く
            if (closing && closeProgress() >= 1f) finishClose();
        });
    }

    /**
     * 下端からの slide と画面に収める縮尺。右下 pivot を固定して flush 右下を保つ。
     *
     * <p>base がこの変換で描き、ポインタを逆に写し、hitmap もこれを通して報告する。2026-09-30 まではこの画面が
     * 前の 2 つを自前で行い、hitmap はどちらも知らなかった (wiki の再生が schedule タブを狙って top タブに届いた)。
     */
    @Override
    protected PanelTransform panelTransform() {
        return new PanelTransform(px + PANEL_W, py + PANEL_H, panelScale(), 0f, slideOffsetY());
    }

    /**
     * GUI スケールに素直に追従し (R4.22.4)、入らないときだけ縮める。
     *
     * <p>旧実装は {@code 2.0 / guiScale} を掛けていた = v1.22 で全廃したはずの
     * 「常にサイズ 2 相当」正規化。GUI スケール 2 では完全に同じ値。
     */
    private float panelScale() {
        return com.manta.api.screen.PanelFit.scale(this.width, this.height, PANEL_W, PANEL_H, 4);
    }

    /** 開く: 画面下端の外から resting へスライドアップ。 閉じる: resting から下端外へスライドダウン。 */
    private float slideOffsetY() {
        // wiki capture: init 直後の 1 フレームを撮るため、 スライド途中 (openedAtNano≈now →
        // パネルが画面下端外) を撮ると空白になる。 capture 中は resting (offY=0) で撮る。
        if (com.manta.api.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE) return 0f;
        float dist = this.height - getDialogScreenY(); // パネルを画面下端の完全外まで押し下げる距離
        if (closing) return dist * easeOut(closeProgress());
        long elapsed = System.nanoTime() - openedAtNano;
        if (elapsed >= SLIDE_NANOS) return 0f;
        float t = elapsed / (float) SLIDE_NANOS;
        return dist * (1f - easeOut(t));
    }

    private float closeProgress() {
        if (!closing) return 0f;
        return Math.min(1f, (System.nanoTime() - closingAtNano) / (float) SLIDE_NANOS);
    }

    /** 二次 ease-out。 展開した多項式を置かない (動きは変えない)。 */
    private static float easeOut(float t) {
        return com.manta.api.anim.Easing.EASE_OUT.apply(t);
    }

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        closingAtNano = System.nanoTime();
    }

    private void finishClose() {
        closing = false;
        super.performClose();
    }

    /**
     * 接続が無いときは送らない。{@code PacketDistributor.sendToServer} は接続を
     * {@code Objects.requireNonNull} するので、接続の無い状態で送ると **クライアントが落ちる**
     * (2026-09-22 実測、crash-2026-09-22_15.59.53-client.txt: Ticking screen -> requestScheduleSync)。
     *
     * <p>この画面はタイトル画面からでも開ける (wiki キャプチャ・カタログ経路) ので、そこには接続が無い。
     * これまで出なかったのは、開いてから閉じるまでが tick より短く tick が一度も回らなかったから。
     * 画面を 900ms 保持する計測を入れた初回に落ちた。
     *
     * <p><b>guard は呼び出し元ではなく、ここに置く。</b>最初は {@code tick()} の先頭に置いたが、
     * この 2 つを呼ぶのは tick だけではない —— {@code init()} (初期タブが SCHEDULE / MAP のとき) と
     * {@code switchTab()} (タブをクリックしたとき) も呼ぶ。「tick の中の送信」を探して
     * 「送信する関数の呼び出し元」を探さなかった、という掃き方の誤り (二次読みが指摘)。
     * 絞り込み点は送る側にしかない。
     */
    private boolean connected() {
        // The wiki's stand-in asks no server: its data is its own (TransitTerminalWikiDemo).
        if (TransitTerminalState.wiki()) return false;
        return net.minecraft.client.Minecraft.getInstance().getConnection() != null;
    }

    private void requestScheduleSync() {
        if (!connected()) return;
        long now = System.nanoTime();
        if (now - lastScheduleRequestNanos < 2_000_000_000L) return; // 2 秒に 1 回まで
        lastScheduleRequestNanos = now;
        com.trainsystemutilities.client.TransitClient.schedule();
    }

    private void requestMapSync() {
        if (!connected()) return;
        long now = System.nanoTime();
        if (now - lastMapRequestNanos < 5_000_000_000L) return; // 5 秒に 1 回 (重い)
        lastMapRequestNanos = now;
        com.trainsystemutilities.client.TransitClient.map();
    }

    @Override
    public void tick() {
        scopedRun(() -> {
            super.tick();
            if (tab() == TransitTerminalState.Tab.SCHEDULE) requestScheduleSync();
            if (tab() == TransitTerminalState.Tab.MAP) requestMapSync();
        });
    }

    void rebuildEditBoxes() {
        // tab 切替 / init 時: controller の値を state から復元し、focus を解除する。
        // (vanilla EditBox の再生成は廃止 — TextInputController は永続 instance)
        setAcField(null);
        acSelected = -1;
        fromCtrl.setValue(TransitTerminalState.fromQuery());
        toCtrl.setValue(TransitTerminalState.toQuery());
        scheduleCtrl.setValue(TransitTerminalState.scheduleQuery());
    }

    /** 入力欄の矩形 (screen 座標 {x, y, w, h})。JSON box (tt-*-box) と一致させる。 */
    private int[] boxRect(AcField f) {
        return switch (f) {
            case FROM     -> new int[]{px + 24, py + 32, 150, 14};
            case TO       -> new int[]{px + 24, py + 54, 150, 14};
            case SCHEDULE -> new int[]{px + 24, py + 32, 168, 14};
        };
    }

    private TextInputController ctrlOf(AcField f) {
        return switch (f) {
            case FROM -> fromCtrl;
            case TO -> toCtrl;
            case SCHEDULE -> scheduleCtrl;
        };
    }

    /** 現在 tab の入力欄テキスト + caret を描画 (JSON が box 背景を、ここが文字を担当)。 */
    private void renderInputs(GuiGraphics g) {
        var tab = tab();
        if (tab == TransitTerminalState.Tab.TOP) {
            drawInput(g, AcField.FROM, fromCtrl);
            drawInput(g, AcField.TO, toCtrl);
        } else if (tab == TransitTerminalState.Tab.SCHEDULE) {
            drawInput(g, AcField.SCHEDULE, scheduleCtrl);
        }
    }

    private void drawInput(GuiGraphics g, AcField f, TextInputController c) {
        int[] r = boxRect(f);
        int tx = r[0] + 4;                       // TextCaretRenderer の LEFT_PAD と一致
        int ty = r[1] + (r[3] - 8) / 2;          // vertical center
        String val = c.value();
        if (val.isEmpty()) {
            com.manta.api.text.MantaText.draw(g, this.font, c.display(), tx, ty, 0xFF707070); // placeholder
        } else {
            // 長い駅名が枠外へはみ出さないよう収める: 編集中は末尾(caret 側)、非編集は head + "…"。
            int availW = r[2] - 6;
            String shown = (acField == f)
                    ? com.manta.api.hud.HudText.tailFit(this.font, val, availW)
                    : com.manta.api.hud.HudText.ellipsize(this.font, val, availW);
            com.manta.api.text.MantaText.draw(g, this.font, shown, tx, ty, 0xFFFFFFFF);
        }
        if (acField == f) {
            TextCaretRenderer.draw(g, this.font, val, r[0], r[1], r[2], r[3], 0xFF4FC3F7);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void afterDialogRender(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // base が panel 背景 + tab 内容 (JSON V3 + canvas) を描画済み。 その上に screen 座標で:
        //   入力欄テキスト + caret、 autocomplete dropdown を重ねる。
        renderInputs(g);
        renderAutocomplete(g, mouseX, mouseY);
    }

    /**
     * Moves the focus between the fields (null: none). A field that takes the focus unfolds its list from the start;
     * losing it folds the list where it is (2026-10-07).
     */
    private void setAcField(AcField f) {
        if (f == acField) return;
        if (f == null) {
            acMotion.close();
        } else if (f == acShown && acMotion.isShown()) {
            // The same field's list, still folding: it turns round where it is, as every dropdown does (second reading,
            // 2026-10-07 - it restarted from folded).
            acMotion.open();
        } else {
            acMotion = new com.manta.api.anim.DropdownMotion();
            acMotion.open();
            acShown = f;
        }
        acField = f;
    }

    private String acQuery() {
        return acQuery(acField);
    }

    private String acQuery(AcField f) {
        if (f == null) return "";
        return switch (f) {
            case FROM -> TransitTerminalState.fromQuery();
            case TO -> TransitTerminalState.toQuery();
            case SCHEDULE -> TransitTerminalState.scheduleQuery();
        };
    }

    private java.util.List<com.trainsystemutilities.station.StationGroup> acCandidates() {
        return acCandidates(acField);
    }

    private java.util.List<com.trainsystemutilities.station.StationGroup> acCandidates(AcField f) {
        if (f == null) return java.util.List.of();
        return TransitTerminalState.autocomplete(acQuery(f), AC_MAX_ROWS);
    }

    private int acDropdownX() {
        return acDropdownX(acField);
    }

    private int acDropdownX(AcField f) {
        if (f == null) return 0;
        // JSON box の左端 (px+24) に揃える
        return px + 24;
    }

    private int acDropdownY() {
        return acDropdownY(acField);
    }

    private int acDropdownY(AcField f) {
        if (f == null) return 0;
        // JSON box (h=14) の下端に揃える
        return switch (f) {
            case FROM -> py + 32 + 14;
            case TO -> py + 54 + 14;
            case SCHEDULE -> py + 32 + 14;
        };
    }

    private int acDropdownW() {
        return acDropdownW(acField);
    }

    private int acDropdownW(AcField f) {
        if (f == null) return 0;
        // JSON box の幅に揃える: FROM/TO=150, SCHEDULE=168
        return switch (f) {
            case FROM, TO -> 150;
            case SCHEDULE -> 168;
        };
    }

    private void renderAutocomplete(GuiGraphics g, int mouseX, int mouseY) {
        // the focused field's list, or the one still folding after the focus left
        AcField f = acField != null ? acField : (acMotion.isShown() ? acShown : null);
        if (f == null) return;
        var cands = acCandidates(f);
        if (cands.isEmpty()) return;
        int x = acDropdownX(f);
        int y = acDropdownY(f);
        int w = acDropdownW(f);
        int h = cands.size() * AC_ROW_H + 2;
        // The dropdown's unfold from its top edge (Manta DropdownMotion), above the screen (z 500, as the pop-in was).
        g.pose().pushPose();
        g.pose().translate(x, y, 500f);
        g.pose().scale(1f, acMotion.scaleY(), 1f);
        g.pose().translate(-x, -y, 0f);
        // 背景 + 枠 (R2.4.1 二層: border 5f + bg 4f inset)
        com.manta.api.hud.HudChrome.innerRect(g, x, y, w, h, 0xF0101820, 0xFF4FC3F7);

        for (int i = 0; i < cands.size(); i++) {
            int rowY = y + 1 + i * AC_ROW_H;
            boolean hover = mouseX >= x + 1 && mouseX < x + w - 1
                    && mouseY >= rowY && mouseY < rowY + AC_ROW_H;
            boolean active = i == acSelected || hover;
            if (active) SmoothRenderer.fillRoundedRect(g, x + 1, rowY, w - 2, AC_ROW_H, 5f, 0xFF1f4a5e);
            String name = truncate(cands.get(i).name(), w - 12);
            com.manta.api.text.MantaText.draw(g, this.font, name, x + 6, rowY + 2, active ? 0xFFFFFFFF : 0xFFE0E0E0);
        }
        g.pose().popPose();
    }

    private void applyAcSelection(int idx) {
        var cands = acCandidates();
        if (idx < 0 || idx >= cands.size()) return;
        String name = cands.get(idx).name();
        ctrlOf(acField).setValue(name);   // onChange が state を更新
        setAcField(null);                 // defocus: the list folds
        acSelected = -1;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // ゲーム描画を見せたいので半透過にせず、何もしない。
    }

    // renderPanel は廃止: base (JsonLayoutPlainScreen) が JSON layout (panel + nav + tab content)
    // を V3 経由で描画する。 入力欄 / autocomplete は afterDialogRender が overlay する。
    // 旧 Java fallback (renderTopTab 等) は jsonLayout==null 専用だったため呼ばれない (= dead)。

    private void renderHeader(GuiGraphics g) {
        String title;
        switch (tab()) {
            case TOP -> title = Component.translatable("tsu.transit_terminal.title").getString();
            case SCHEDULE -> title = Component.translatable("tsu.transit_terminal.tab_schedule").getString();
            case MAP -> title = Component.translatable("tsu.transit_terminal.tab_map").getString();
            case SETTINGS -> title = Component.translatable("tsu.transit_terminal.tab_settings").getString();
            default -> title = "";
        }
        com.manta.api.text.MantaText.draw(g, this.font, title, px + 10, py + 8, 0xFF4FC3F7);

        // 右上時計
        String clock = formatClock();
        int cw = com.manta.api.text.MantaText.uiWidth(this.font, clock);
        com.manta.api.text.MantaText.draw(g, this.font, clock, px + PANEL_W - 10 - cw, py + 8, 0xFFFFD54F);

        g.fill(px + 8, py + HEADER_H, px + PANEL_W - 8, py + HEADER_H + 1, 0xFF4FC3F7);
    }

    // -------- Bottom navigation --------
    private void renderNav(GuiGraphics g, int navY, int mouseX, int mouseY) {
        g.fill(px + 4, navY, px + PANEL_W - 4, navY + 1, 0xFF4FC3F7);
        // Tab cells
        int cellW = (PANEL_W - 8) / 4;
        TransitTerminalState.Tab[] tabs = TransitTerminalState.Tab.values();
        for (int i = 0; i < 4; i++) {
            int cx = px + 4 + cellW * i;
            int cy = navY + 2;
            int cw = cellW;
            int ch = NAV_H - 4;
            boolean active = tab() == tabs[i];
            boolean hover = com.manta.api.hud.Rects.contains(mouseX, mouseY, cx, cy, cw, ch);
            int color = active ? 0xFF4FC3F7 : (hover ? 0xFF80DEEA : 0xFF80808F);
            // W7-1: タブの glyph を manta registry icon へ (中央寄せは icon 幅で計算)。
            String icon = navIcon(tabs[i]);
            String label = navLabel(tabs[i]);
            int iw = NAV_ICON_SIZE;
            com.manta.api.render.Icons.draw(g, icon, cx + (cw - iw) / 2, cy + 3, iw, color);
            int lw = com.manta.api.text.MantaText.uiWidth(this.font, label);
            com.manta.api.text.MantaText.draw(g, this.font, label, cx + (cw - lw) / 2, cy + 14, color);
        }
    }

    /** W7-1: タブの manta registry icon ID (旧: 生 glyph)。 */
    private String navIcon(TransitTerminalState.Tab t) {
        return switch (t) {
            case TOP -> "manta:search";
            case SCHEDULE -> "manta:clock";
            case MAP -> "manta:map";
            case SETTINGS -> "manta:settings";
        };
    }

    private String navLabel(TransitTerminalState.Tab t) {
        return Component.translatable(switch (t) {
            case TOP -> "tsu.transit_terminal.tab_top";
            case SCHEDULE -> "tsu.transit_terminal.tab_schedule";
            case MAP -> "tsu.transit_terminal.tab_map";
            case SETTINGS -> "tsu.transit_terminal.tab_settings";
        }).getString();
    }

    // -------- TOP tab --------
    /** TOP タブのレイアウト座標 (mouseClicked と render で共有)。 */
    int topBoxY;
    int topBoxX;
    int topBoxW;

    private void renderTopTab(GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        TransitTerminalRender.renderTopTab(this, g, mouseX, mouseY, y, h);
    }

    /**
     * Phase D: ルート候補タブ (上位 K 候補から選ぶ)。
     * 候補数に応じて N 個 (最大 3) のタブを表示。各タブには合計時間を簡易表示。
     */
    private void renderSortTabs(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        TransitTerminalRender.renderSortTabs(this, g, x, y, w, h, mouseX, mouseY);
    }

    void renderTopResults(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        ComposedRouteFinder.ComposedRoute r = TransitTerminalState.lastResult();
        long since = TransitTerminalState.sinceLastResultRequestMs();
        if (r == null) {
            if (since == 0) {
                // 検索前: 履歴 / お気に入りを表示
                renderHistorySection(g, x, y, w, h, mouseX, mouseY);
                return;
            }
            String msg = since > 5000
                    ? Component.translatable("tsu.transit_terminal.timeout").getString()
                    : Component.translatable("tsu.transit_terminal.searching").getString();
            com.manta.api.text.MantaText.draw(g, this.font, msg, x, y + 4, since > 5000 ? 0xFFFF8A65 : 0xFFFFD54F);
            return;
        }
        if (!r.found()) {
            com.manta.api.text.MantaText.draw(g, this.font, Component.translatable("tsu.transit_terminal.no_route").getString(), x, y + 4, 0xFFFF8A80);
            String reason = r.reason() == null ? "" : r.reason();
            if (!reason.isEmpty()) drawWrapped(g, reason, x, y + 16, w, 0xFFAAAAAA);
            return;
        }
        if (TransitTerminalState.expandedLegIdx() >= 0) {
            renderResultDetail(g, r, x, y, w, h, mouseX, mouseY);
            return;
        }
        renderResultSummary(g, r, x, y, w, h, mouseX, mouseY);
    }

    /** TOP タブで未検索時に表示する履歴セクション + 各行に削除ボタン (見やすい大きさ)。 */
    private void renderHistorySection(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        TransitTerminalRender.renderHistorySection(this, g, x, y, w, h, mouseX, mouseY);
    }

    /** ルート結果サマリ画面 (Yahoo!乗換案内風 1 タイル / 候補)。 */
    private void renderResultSummary(GuiGraphics g, ComposedRouteFinder.ComposedRoute r,
                                     int x, int y, int w, int h, int mouseX, int mouseY) {
        TransitTerminalRender.renderResultSummary(this, g, r, x, y, w, h, mouseX, mouseY);
    }

    /** Leg の路線色を返す: 路線記号があればその border color、なければ列車 ID hash。 */
    static int lineColorForLeg(TrainRouter.Leg leg) {
        if (leg == null) return 0xFF4FC3F7;
        String symColor = leg.symbolColor();
        if (symColor != null && symColor.startsWith("#") && symColor.length() == 7) {
            try {
                int rgb = Integer.parseInt(symColor.substring(1), 16);
                return 0xFF000000 | rgb;
            } catch (NumberFormatException ignored) {}
        }
        return lineColorForTrain(leg.trainId());
    }

    /** 列車 ID から安定的な路線色を生成 (色相環をハッシュで分散)。 */
    private static int lineColorForTrain(java.util.UUID trainId) {
        if (trainId == null) return 0xFF4FC3F7;
        long h = trainId.getMostSignificantBits() ^ trainId.getLeastSignificantBits();
        // 0..360 度の色相、彩度 0.65、明度 0.85
        float hue = (Math.abs(h) % 360) / 360f;
        return 0xFF000000 | hsvToRgb(hue, 0.65f, 0.85f);
    }

    /**
     * 路線記号バッジを描画 (色背景 + アルファベット + 数字)。
     * 12x14 サイズで letters と number を縦並びに。
     * @return 描画した幅 (描画不要なら 0)
     */
    /**
     * 路線記号バッジを描画。鉄道管理ブロック / 管理用コンピューターと同じ {@link com.trainsystemutilities.client.gui.LineSymbolPainter}
     * を使ってデザインを統一 (角丸白背景 + 色枠 + 中央 divider + 上下に letters/number)。
     *
     * <p>サイズは 24 px。LineSymbolPainter は letters Y = midY-9 で配置するため、
     * size < 22 ではテキストが badge の上にはみ出す。24 でちょうど中に収まる。
     */
    int drawSymbolBadge(GuiGraphics g, int x, int y, TrainRouter.Leg leg) {
        if (leg == null) return 0;
        String letters = leg.symbolLetters();
        int num = leg.symbolNumber();
        if ((letters == null || letters.isEmpty()) && num < 0) return 0;
        var sym = new com.trainsystemutilities.blockentity.LineSymbol(
                letters == null ? "" : letters,
                Math.max(0, num),
                (leg.symbolColor() == null || leg.symbolColor().isEmpty()) ? "#4fc3f7" : leg.symbolColor(),
                "");
        int size = 24;
        com.trainsystemutilities.client.gui.LineSymbolPainter.draw(g, x, y, size, sym, this.font);
        return size;
    }

    private static int hsvToRgb(float h, float s, float v) {
        float r = 0, g = 0, b = 0;
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            case 5 -> { r = v; g = p; b = q; }
        }
        return ((int)(r * 255) << 16) | ((int)(g * 255) << 8) | (int)(b * 255);
    }

    /**
     * Yahoo!乗換案内風の縦タイムライン詳細ビュー。
     * 全レッグを通しで表示する (1 leg だけでなく、route 全体)。
     */
    private void renderResultDetail(GuiGraphics g, ComposedRouteFinder.ComposedRoute r,
                                    int x, int y, int w, int h, int mouseX, int mouseY) {
        TransitTerminalRender.renderResultDetail(this, g, r, x, y, w, h, mouseX, mouseY);
    }

    /**
     * 検索結果到着時の dayTime + offsetTicks をゲーム内 HH:MM で返す。
     * 結果が届いた瞬間を t=0 とするため、現在 dayTime が進んでも表示は固定される。
     */
    String absoluteClockOffset(int offsetTicks) {
        long base = TransitTerminalState.lastResultBaseDayTime();
        if (base == 0) {
            // フォールバック: 現在時刻基準
            var mc = Minecraft.getInstance();
            if (mc.level == null) return "--:--";
            base = mc.level.getDayTime();
        }
        long t = (base + offsetTicks) % 24000L;
        if (t < 0) t += 24000L;
        long minutesInDay = (long) ((t / 24000.0) * 24 * 60);
        long mcMinutes = (minutesInDay + 6 * 60) % (24 * 60);
        long hours = mcMinutes / 60;
        long mins = mcMinutes % 60;
        if (TransitTerminalState.clock24h()) {
            return String.format("%02d:%02d", hours, mins);
        } else {
            String suf = hours >= 12 ? "PM" : "AM";
            long h12 = hours % 12; if (h12 == 0) h12 = 12;
            return String.format("%d:%02d %s", h12, mins, suf);
        }
    }

    /**
     * ライブの「あと N分S秒」(現在 dayTime と base からの経過分を引く)。
     * 列車の現在位置情報があれば、それで補正する (位置ベース外挿)。
     */
    int liveCountdownTicks(int originalOffsetTicks) {
        long base = TransitTerminalState.lastResultBaseDayTime();
        if (base == 0) return Math.max(0, originalOffsetTicks);
        var mc = Minecraft.getInstance();
        if (mc.level == null) return Math.max(0, originalOffsetTicks);
        long elapsed = mc.level.getDayTime() - base;
        return Math.max(0, originalOffsetTicks - (int) elapsed);
    }

    /**
     * 特定列車のライブ ETA (位置ペイロードベース) を返す。位置情報がなければ -1。
     * @param trainId 列車 ID
     * @return その列車が次駅に到着するまでの ticks (現在 dayTime 基準)
     */
    private int liveTrainEta(java.util.UUID trainId) {
        if (trainId == null) return -1;
        var pos = TransitTerminalClientCache.trainPositions().get(trainId);
        if (pos == null) return -1;
        long posDayTime = TransitTerminalClientCache.trainPositionsDayTime();
        var mc = Minecraft.getInstance();
        if (mc.level == null) return Math.max(0, pos.etaToNextTicks());
        long elapsed = Math.max(0, mc.level.getDayTime() - posDayTime);
        return Math.max(0, pos.etaToNextTicks() - (int) elapsed);
    }

    // -------- SCHEDULE tab --------
    private void renderScheduleTab(GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        TransitTerminalRender.renderScheduleTab(this, g, mouseX, mouseY, y, h);
    }

    // -------- MAP tab --------
    /** MAP タブ: 管理用コンピューターと同じ vector 形式 (mapZoom * (world + pan) で 2px 線)。 */
    private void renderMapTab(GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        TransitTerminalRender.renderMapTab(this, g, mouseX, mouseY, y, h);
    }

    // -------- SETTINGS tab --------
    private void renderSettingsTab(GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        TransitTerminalRender.renderSettingsTab(this, g, mouseX, mouseY, y, h);
    }

    /**
     * 鉄道管理ブロック / 管理用コンピュータの hint-toggle / monitor-toggle と同じ寸法+配色。
     * track 24×12 px (6px 角丸)、knob 8×8 px (4px 角丸)、knob は track 内で OFF=2px / ON=14px。
     * アニメーション: 状態変化時に knob 位置を 150ms で lerp。
     */
    int renderSettingRow(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY,
                                 String labelKey, boolean value) {
        boolean hover = com.manta.api.hud.Rects.contains(mouseX, mouseY, x, y, w, h);
        if (hover) g.fill(x, y, x + w, y + h, 0xFF1f3a50);
        com.manta.api.text.MantaText.draw(g, this.font, Component.translatable(labelKey).getString(), x + 4, y + 5, 0xFFE0E0E0);
        // TSU 標準トグル: track 24×12, knob 8×8
        int tw = 24, th = 12;
        int tx = x + w - tw - 2;
        int ty = y + (h - th) / 2;
        // アニメーション位置 (0.0 = OFF, 1.0 = ON)
        float prog = animatedToggleProgress(labelKey, value);
        // track 色も補間 (gray → green)
        int trackColor = blendColor(0xFF555555, 0xFF4CAF50, prog);
        SmoothRenderer.fillRoundedRect(g, tx, ty, tw, th, 6f, trackColor);
        // knob: x = tx+2 (OFF) → tx+14 (ON)、track の中央 (ty+2 の位置で 8×8)
        int knobX = (int) (tx + 2 + 12 * prog);
        int knobColor = blendColor(0xFFAAAAAA, 0xFFFFFFFF, prog);
        SmoothRenderer.fillRoundedRect(g, knobX, ty + 2, 8, 8, 4f, knobColor);
        return y + h;
    }

    /** トグル毎のアニメーション進捗を保持。state 変化時に target 切替。 */
    private final java.util.Map<String, ToggleAnim> toggleAnims = new java.util.HashMap<>();
    private static final class ToggleAnim {
        float current;     // 現在の進捗 0..1
        boolean targetOn;
        long lastUpdateNanos;
    }

    private float animatedToggleProgress(String key, boolean targetOn) {
        ToggleAnim a = toggleAnims.computeIfAbsent(key, k -> {
            ToggleAnim t = new ToggleAnim();
            t.current = targetOn ? 1f : 0f;
            t.targetOn = targetOn;
            t.lastUpdateNanos = System.nanoTime();
            return t;
        });
        long now = System.nanoTime();
        float dt = (now - a.lastUpdateNanos) / 1_000_000_000f;
        a.lastUpdateNanos = now;
        a.targetOn = targetOn;
        float target = targetOn ? 1f : 0f;
        // 150ms で完了するように lerp 速度 = 1/0.15
        float speed = dt / 0.15f;
        if (a.current < target) a.current = Math.min(target, a.current + speed);
        else if (a.current > target) a.current = Math.max(target, a.current - speed);
        // ease-out quad
        float t = a.current;
        return 1f - (1f - t) * (1f - t);
    }

    private static int blendColor(int a, int b, float t) {
        int aA = (a >> 24) & 0xFF, aR = (a >> 16) & 0xFF, aG = (a >> 8) & 0xFF, aB = a & 0xFF;
        int bA = (b >> 24) & 0xFF, bR = (b >> 16) & 0xFF, bG = (b >> 8) & 0xFF, bB = b & 0xFF;
        int rA = (int) (aA + (bA - aA) * t);
        int rR = (int) (aR + (bR - aR) * t);
        int rG = (int) (aG + (bG - aG) * t);
        int rB = (int) (aB + (bB - aB) * t);
        return (rA << 24) | (rR << 16) | (rG << 8) | rB;
    }

    // -------- Mouse / Keyboard handling --------
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return scoped(() -> clicked(mouseX, mouseY, button));
    }

    private boolean clicked(double mouseX, double mouseY, int button) {
        if (closing) return true; // スライドアウト中は入力無視
        // 以下のこの画面自身の判定は panel の座標 (px/py 基準) で行う。base には受け取った画面の点を
        // そのまま渡す — base が panelTransform() で自分で写す (写した点を渡すと二重に写る)。
        double screenX = mouseX, screenY = mouseY;
        PanelTransform panel = panelTransform();
        mouseX = panel.toPanelX(screenX); mouseY = panel.toPanelY(screenY);
        // Phase 2: BelugaExperience JSON shell の nav 領域クリックを優先処理。
        // (tab 切替は handler.onElementClick が処理)。
        // layoutAdjustMode 時の header drag は JSON click より優先するので
        // この block より前に書く。
        // ↓ 続く autocomplete / drag handling より後に JSON click を試す。

        // レイアウト調整: ヘッダクリックでスクリーン drag を開始
        if (TransitTerminalState.layoutAdjustMode() && button == 0) {
            int hudW = 200, hudH = 220;
            int hudX = 8 + TransitTerminalState.hudOffsetX();
            int hudY = this.height - hudH - 8 + TransitTerminalState.hudOffsetY();
            // Shift+click で HUD を非表示に
            if (TransitTerminalState.showDetailHud()
                    && hasShiftDown()
                    && com.manta.api.hud.Rects.contains(mouseX, mouseY, hudX, hudY, hudW, hudH)) {
                TransitTerminalState.setShowDetailHud(false);
                return true;
            }
            // HUD ドラッグ開始
            if (TransitTerminalState.showDetailHud()
                    && com.manta.api.hud.Rects.contains(mouseX, mouseY, hudX, hudY, hudW, hudH)) {
                draggingHud = true;
                dragHudAnchorX = mouseX; dragHudAnchorY = mouseY;
                dragHudStartX = TransitTerminalState.hudOffsetX();
                dragHudStartY = TransitTerminalState.hudOffsetY();
                return true;
            }
            // Screen ヘッダクリックで Screen drag 開始
            if (com.manta.api.hud.Rects.contains(mouseX, mouseY, px, py, PANEL_W, HEADER_H)) {
                draggingScreen = true;
                dragAnchorX = mouseX; dragAnchorY = mouseY;
                dragStartPx = px; dragStartPy = py;
                return true;
            }
        }
        // Shift+click でも HUD 非表示 (レイアウト OFF 時の便利機能)
        if (button == 0 && hasShiftDown() && TransitTerminalState.showDetailHud()) {
            int hudW = 200, hudH = 220;
            int hudX = 8 + TransitTerminalState.hudOffsetX();
            int hudY = this.height - hudH - 8 + TransitTerminalState.hudOffsetY();
            if (com.manta.api.hud.Rects.contains(mouseX, mouseY, hudX, hudY, hudW, hudH)) {
                TransitTerminalState.setShowDetailHud(false);
                return true;
            }
        }
        // Autocomplete dropdown click は最優先で消費。
        if (acField != null && button == 0) {
            var cands = acCandidates();
            if (!cands.isEmpty()) {
                int x = acDropdownX();
                int y = acDropdownY();
                int w = acDropdownW();
                int h = cands.size() * AC_ROW_H + 2;
                if (com.manta.api.hud.Rects.contains(mouseX, mouseY, x, y, w, h)) {
                    int idx = ((int) (mouseY - y - 1)) / AC_ROW_H;
                    applyAcSelection(idx);
                    return true;
                }
            }
        }
        // 入力欄クリック → focus: the field's own press, through the engine (onElementClick on tt-from-box / tt-to-box /
        // tt-sch-box - 2026-10-07: a wiki playback's press is aimed at its node, and the screen's own hit test answered
        // before the engine saw it). box/autocomplete 以外のクリックは defocus する。
        // base (JsonLayoutPlainScreen) が JSON click を V3 EventGraph 経由で onElementClick へ
        // dispatch する (nav / settings rows / buttons / tt-top-content canvas → mouseClickedTop)。
        fieldPressed = null;
        boolean taken = super.mouseClicked(screenX, screenY, button);
        if (button == 0 && fieldPressed == null) {
            setAcField(null);
            acSelected = -1;
        }
        if (taken) return true;
        // 以下は base が消費しなかった (= JSON 要素に当たらなかった) 場合のみ到達。
        // Fallback: JSON 無効時の従来 hit-test
        int navY = py + PANEL_H - NAV_H;
        if (com.manta.api.hud.Rects.contains(mouseX, mouseY, px, navY, PANEL_W, NAV_H)) {
            int cellW = (PANEL_W - 8) / 4;
            int relX = (int)(mouseX - (px + 4));
            if (relX >= 0 && relX < cellW * 4) {
                int idx = relX / cellW;
                TransitTerminalState.Tab[] tabs = TransitTerminalState.Tab.values();
                if (idx >= 0 && idx < tabs.length) {
                    setTab(tabs[idx]);
                    rebuildEditBoxes();
                    return true;
                }
            }
        }

        switch (tab()) {
            case TOP -> { return TransitTerminalRender.mouseClickedTop(this, mouseX, mouseY, button); }
            case SETTINGS -> {
                // SETTINGS click は base が onElementClick 経由で処理済み。
                return false;
            }
            case MAP -> {
                if (button == 0) {
                    // クリックで駅にフォーカス? (簡易: 何もしない)
                }
                return false;
            }
            default -> { return false; }
        }
    }

    private boolean mouseClickedSettings(double mouseX, double mouseY, int button) {
        int contentY = py + HEADER_H + 2;
        int innerX = px + CONTENT_PAD;
        int innerW = PANEL_W - CONTENT_PAD * 2;
        int rowY = contentY + 8;
        int rowH = 18;
        // 24h toggle
        if (inRect(mouseX, mouseY, innerX, rowY, innerW, rowH)) {
            TransitTerminalState.setClock24h(!TransitTerminalState.clock24h());
            return true;
        }
        rowY += rowH + 4;
        // walk gate
        if (inRect(mouseX, mouseY, innerX, rowY, innerW, rowH)) {
            TransitTerminalState.setWalkGateEnabled(!TransitTerminalState.walkGateEnabled());
            return true;
        }
        rowY += rowH + 4;
        // layout adjust mode
        if (inRect(mouseX, mouseY, innerX, rowY, innerW, rowH)) {
            TransitTerminalState.setLayoutAdjustMode(!TransitTerminalState.layoutAdjustMode());
            return true;
        }
        rowY += rowH + 4;
        // show HUD
        if (inRect(mouseX, mouseY, innerX, rowY, innerW, rowH)) {
            TransitTerminalState.setShowDetailHud(!TransitTerminalState.showDetailHud());
            return true;
        }
        rowY += rowH + 8;
        // reset layout
        if (inRect(mouseX, mouseY, innerX, rowY, innerW, 14)) {
            TransitTerminalState.setScreenOffset(0, 0);
            TransitTerminalState.setHudOffset(0, 0);
            return true;
        }
        return false;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return com.manta.api.hud.Rects.contains(mx, my, x, y, w, h);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return scoped(() -> {
            // 入力欄 focus 中は文字を controller に流す
            if (acField != null && ctrlOf(acField).charTyped(codePoint)) {
                acSelected = -1;
                return true;
            }
            return super.charTyped(codePoint, modifiers);
        });
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return scoped(() -> keyed(keyCode, scanCode, modifiers));
    }

    private boolean keyed(int keyCode, int scanCode, int modifiers) {
        if (closing) return true; // スライドアウト中は入力無視
        // 入力欄 focus 中は delegate (autocomplete navigation 優先)
        if (acField != null) {
            var cands = acCandidates();
            if (!cands.isEmpty()) {
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                    acSelected = (acSelected + 1) % cands.size();
                    return true;
                }
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                    acSelected = acSelected <= 0 ? cands.size() - 1 : acSelected - 1;
                    return true;
                }
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
                    applyAcSelection(acSelected >= 0 ? acSelected : 0);
                    return true;
                }
            }
            // Enter: autocomplete 候補があれば選択、なければ検索発火 (TOP タブのみ)
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                if (acSelected >= 0) {
                    applyAcSelection(acSelected);
                    return true;
                }
                if (tab() == TransitTerminalState.Tab.TOP) {
                    TransitTerminalState.onSearchSubmit();
                    setAcField(null);
                    return true;
                }
            }
            // R4.10.2: ESC は controller に渡す前に screen close を優先
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                this.onClose();
                return true;
            }
            ctrlOf(acField).keyPressed(keyCode);  // Backspace 等
            return true;                          // focus 中はキーを消費 (game key 漏れ防止)
        }
        // Esc で閉じる
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        // Tab 切替: Tab キーで次タブ
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            TransitTerminalState.Tab[] tabs = TransitTerminalState.Tab.values();
            int next = (tab().ordinal() + 1) % tabs.length;
            setTab(tabs[next]);
            rebuildEditBoxes();
            return true;
        }
        // MAP タブのズーム / リセット
        if (tab() == TransitTerminalState.Tab.MAP) {
            switch (keyCode) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD ->
                    { TransitTerminalState.setMapZoomD(TransitTerminalState.mapZoomD() * 1.25); return true; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT ->
                    { TransitTerminalState.setMapZoomD(TransitTerminalState.mapZoomD() / 1.25); return true; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_R ->
                    { TransitTerminalState.mapResetView(); return true; }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return scoped(() -> {
            if (draggingScreen || draggingHud) {
                draggingScreen = false;
                draggingHud = false;
                return true;
            }
            return super.mouseReleased(mouseX, mouseY, button);
        });
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // The map's zoom and the timetable's scroll are the engine's (tt-map-canvas / tt-sch-list wheelKey,
        // onElementWheel - 2026-10-07, so a wiki playback's wheel is aimed at its node as a press is).
        return scoped(() -> {
            if (closing) return true;
            if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
            // As before 2026-10-07: on the timetable a wheel anywhere scrolls the list - over its scrollbar or the
            // search box too, not only over the list's node (second reading); on the map a wheel anywhere zooms.
            if (tab() == TransitTerminalState.Tab.SCHEDULE) {
                TransitTerminalState.scheduleScroll().scroll(-(int) scrollY);  // ScrollViewport が clamp 内包 (§4.19)
                return true;
            }
            if (tab() == TransitTerminalState.Tab.MAP) {
                PanelTransform panel = panelTransform();
                zoomMapAt(panel.toPanelX(mouseX), panel.toPanelY(mouseY), scrollY);
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean onElementWheel(String[] classes, String key, int mouseX, int mouseY, double scrollY) {
        if ("tt-sch-wheel".equals(key) && tab() == TransitTerminalState.Tab.SCHEDULE) {
            TransitTerminalState.scheduleScroll().scroll(-(int) scrollY);  // ScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        if (!"tt-map-wheel".equals(key) || tab() != TransitTerminalState.Tab.MAP) return false;
        // The point is the panel's local one: + px, py is this screen's own panel space, as onElementClick's tt-top-content.
        zoomMapAt(mouseX + px, mouseY + py, scrollY);
        return true;
    }

    /** The map's zoom about a point of this screen's panel space - the point's world position stays under it. */
    private void zoomMapAt(double mouseX, double mouseY, double scrollY) {
        // マウス位置を中心にズーム (画面座標を維持してズームイン/アウト)。
        int innerX = px + CONTENT_PAD;
        int innerW = PANEL_W - CONTENT_PAD * 2;
        int contentY = py + HEADER_H + 2;
        int contentH = (py + PANEL_H - NAV_H) - contentY;
        double centerSX = innerX + innerW / 2.0;
        double centerSY = contentY + contentH / 2.0;
        double oldZoom = TransitTerminalState.mapZoomD();
        if (oldZoom <= 0.0001) oldZoom = 1.0; // 未初期化なら 1 から
        double factor = scrollY > 0 ? 1.25 : 1.0 / 1.25;
        double newZoom = Math.max(0.05, Math.min(8.0, oldZoom * factor));
        // ワールド座標 = (mouseScreen - centerS) / zoom - pan を維持。
        double oldPanX = TransitTerminalState.mapPanXD();
        double oldPanZ = TransitTerminalState.mapPanZD();
        double worldX = (mouseX - centerSX) / oldZoom - oldPanX;
        double worldZ = (mouseY - centerSY) / oldZoom - oldPanZ;
        // 新しい pan = (mouseScreen - centerS) / newZoom - worldX
        double newPanX = (mouseX - centerSX) / newZoom - worldX;
        double newPanZ = (mouseY - centerSY) / newZoom - worldZ;
        TransitTerminalState.setMapZoomD(newZoom);
        TransitTerminalState.setMapPan(newPanX, newPanZ);
    }

    /** Where the map's drag last stood (screen), for the next step's delta. */
    private double mapDragLastX, mapDragLastY;

    /** The map's pan: the engine's drag on tt-map-canvas (its dragKey tt-map-pan). */
    @Override
    public boolean onElementDrag(String[] classes, String key, int mouseX, int mouseY,
                                 int elementX, int elementY, int elementW, int elementH, boolean pressed) {
        if ("tt-sch-thumb-drag".equals(key)) {
            // The schedule list's thumb, drawn on its scrollbar canvas, grabbed and dragged (Manta 2.87.0's
            // dragThumb, R4.19.5): a press on the thumb grabs it, one on the rest of the track is refused.
            if (pressed) {
                int[] t = scheduleThumb;
                scheduleThumbHeld = t != null && mouseY >= t[1] && mouseY < t[1] + t[3]
                        && TransitTerminalState.scheduleScroll().dragThumb(mouseY, true);
                return scheduleThumbHeld;
            }
            return scheduleThumbHeld && TransitTerminalState.scheduleScroll().dragThumb(mouseY, false);
        }
        if (!"tt-map-pan".equals(key) || tab() != TransitTerminalState.Tab.MAP) return false;
        if (!pressed) {
            double zoom = TransitTerminalState.mapZoomD();
            if (zoom <= 0.0001) zoom = 1.0;
            TransitTerminalState.mapPanBy((mouseX - mapDragLastX) / zoom, (mouseY - mapDragLastY) / zoom);
        }
        mapDragLastX = mouseX;
        mapDragLastY = mouseY;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return scoped(() -> dragged(mouseX, mouseY, button, dragX, dragY));
    }

    private boolean dragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double screenX = mouseX, screenY = mouseY;   // base へはこのまま (base が自分で写す)
        PanelTransform panel = panelTransform();
        mouseX = panel.toPanelX(screenX); mouseY = panel.toPanelY(screenY);
        // レイアウト調整: アンカーベース smooth drag
        if (draggingScreen && button == 0) {
            int newPx = (int)(dragStartPx + (mouseX - dragAnchorX));
            int newPy = (int)(dragStartPy + (mouseY - dragAnchorY));
            int defaultPx = this.width - PANEL_W - RIGHT_MARGIN;
            int defaultPy = this.height - PANEL_H - BOTTOM_MARGIN;
            TransitTerminalState.setScreenOffset(newPx - defaultPx, newPy - defaultPy);
            px = newPx; py = newPy;
            rebuildEditBoxes();
            return true;
        }
        if (draggingHud && button == 0) {
            int newOffX = (int)(dragHudStartX + (mouseX - dragHudAnchorX));
            int newOffY = (int)(dragHudStartY + (mouseY - dragHudAnchorY));
            TransitTerminalState.setHudOffset(newOffX, newOffY);
            return true;
        }
        // The map's pan is the engine's drag (onElementDrag).
        return super.mouseDragged(screenX, screenY, button, dragX, dragY);
    }

    // -------- helpers --------
    private String formatClock() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return "--:--";
        long t = mc.level.getDayTime() % 24000L;
        long minutesInDay = (long) ((t / 24000.0) * 24 * 60);
        long mcMinutes = (minutesInDay + 6 * 60) % (24 * 60);
        long hours = mcMinutes / 60;
        long mins = mcMinutes % 60;
        if (TransitTerminalState.clock24h()) {
            return String.format("%02d:%02d", hours, mins);
        } else {
            String suffix = hours >= 12 ? "PM" : "AM";
            long h12 = hours % 12; if (h12 == 0) h12 = 12;
            return String.format("%d:%02d %s", h12, mins, suffix);
        }
    }

    static String truncate(String s, int maxWidth) {
        return com.manta.api.hud.HudText.ellipsize(Minecraft.getInstance().font, s, maxWidth);
    }

    static StationGroup findGroup(UUID id) {
        if (id == null) return null;
        for (var g : TransitTerminalState.groups()) {
            if (g.id().equals(id)) return g;
        }
        return null;
    }

    static String nameOf(UUID id) {
        var g = findGroup(id);
        return g != null ? g.name() : (id == null ? "?" : id.toString().substring(0, 6));
    }

    private static String stationSymbol(StationGroup g) {
        // StationGroup には路線記号文字 + 数字が個別に保存されている (取得 API は別 cache)。
        // 簡易表示として name に手動付与された記号があれば使う。なければ空文字。
        if (g == null) return "";
        return "";
    }

    void drawWrapped(GuiGraphics g, String text, int x, int y, int w, int color) {
        for (var line : com.manta.api.text.MantaText.split(this.font, Component.literal(text), w)) {
            com.manta.api.text.MantaText.draw(g, this.font, line, x, y, color);
            y += 10;
        }
    }

    /** god-class 分割 (TransitTerminalRender) 用: 他パッケージ protected 継承メンバーへの package-private seam。 */
    net.minecraft.client.gui.Font fontAccess() { return this.font; }

    // ==================== JsonLayoutHandler (BelugaExperience JSON shell) ====================
    // Phase 2: shell (panel frame + header + nav bar) を JSON layout 駆動に。
    // tab content (TOP/SCHEDULE/MAP/SETTINGS) は引き続き Java rendering で
    // overlay する。Phase 3+ で段階的に JSON 化予定。

    private static final String NAV_COLOR_ACTIVE = "#4FC3F7";
    private static final String NAV_COLOR_INACTIVE = "#80808F";

    private String headerTitleText() {
        return Component.translatable(switch (tab()) {
            case TOP -> "tsu.transit_terminal.title";
            case SCHEDULE -> "tsu.transit_terminal.tab_schedule";
            case MAP -> "tsu.transit_terminal.tab_map";
            case SETTINGS -> "tsu.transit_terminal.tab_settings";
        }).getString();
    }

    /** SCHEDULE tab で表示中の (フィルタ後) snapshot 件数 (= scheduleScroll の total に委譲)。 */
    int scheduleFilteredCount() {
        return TransitTerminalState.scheduleFilteredRowCount();
    }

    /** SCHEDULE tab canvas h=256, row h=22+2 gap → 256/24 ≈ 10 行可視。 */
    static final int SCHEDULE_ROW_H = 22;
    static final int SCHEDULE_ROW_STRIDE = SCHEDULE_ROW_H + 2;
    static final int SCHEDULE_ROWS_VISIBLE = 256 / SCHEDULE_ROW_STRIDE;

    private int tabColor(TransitTerminalState.Tab t) {
        return tab() == t ? ToggleColors.TRACK_ON : 0xFF80808F;
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                            int x, int y, int w, int h, int mouseX, int mouseY) {
        if (key == null) return;
        switch (key) {
            case "tt-top-content" -> {
                // 結果あり: clear button + sortTabs + result content
                // 結果なし: history rows (空時はヒント)
                drawTopContentCanvas(g, x, y, w, h, mouseX, mouseY);
            }
            case "tt-sch-list" -> drawScheduleListCanvas(g, x, y, w, h, mouseX, mouseY);
            case "tt-sch-scrollbar" -> drawScheduleScrollbarCanvas(g, x, y, w, h);
            case "tt-map-canvas" -> drawMapCanvas(g, x, y, w, h, mouseX, mouseY);
        }
    }

    /** MAP tab の vector map canvas drawer (元 renderMapTab のコア部分)。
     *  x, y, w, h はキャンバス絶対座標 (screen coords)。 */
    private void drawMapCanvas(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        TransitTerminalRender.drawMapCanvas(this, g, x, y, w, h, mouseX, mouseY);
    }

    /** SCHEDULE tab の列車リスト canvas drawer。 */
    private void drawScheduleListCanvas(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        TransitTerminalRender.drawScheduleListCanvas(this, g, x, y, w, h, mouseX, mouseY);
    }

    /** SCHEDULE tab の scrollbar canvas drawer。track + thumb を描画。 */
    private void drawScheduleScrollbarCanvas(GuiGraphics g, int x, int y, int w, int h) {
        int filteredCount = scheduleFilteredCount();
        // track
        SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, 0xFF2a2a3a);
        // thumb (= ScrollViewport.thumbY で位置算出、formula は従来同等)
        int thumbH = TransitTerminalState.scheduleScroll().thumbH(h, 12);
        int thumbY = TransitTerminalState.scheduleScroll().thumbY(y, h, thumbH);
        SmoothRenderer.fillRoundedRect(g, x, thumbY, w, thumbH, 5f, 0xFF4FC3F7);
        scheduleThumb = new int[] {x, thumbY, w, thumbH};
    }

    /** The schedule list's thumb as its canvas last drew it (x, y, w, h), and whether a drag holds it. */
    private int[] scheduleThumb;
    private boolean scheduleThumbHeld;

    /** TOP tab の results / history area を描画 (canvas drawer)。 */
    private void drawTopContentCanvas(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        ComposedRouteFinder.ComposedRoute r = TransitTerminalState.lastResult();
        long since = TransitTerminalState.sinceLastResultRequestMs();
        if (r == null && since == 0) {
            // 履歴セクション (旧 renderHistorySection 同等)
            renderHistorySection(g, x, y, w, h, mouseX, mouseY);
            return;
        }
        // 結果あり (or 検索中)
        int resY = y;
        int resH = h;
        if (r != null) {
            // クリアボタン
            // W7-1: 先頭の ✕ を manta:x icon へ (幅は Render 側と同じ式)。
            String clearText = Component.translatable("tsu.transit_terminal.clear_results").getString();
            int clIco = TransitTerminalRender.CLEAR_ICON_SIZE;
            int clW = clIco + TransitTerminalRender.CLEAR_ICON_GAP + com.manta.api.text.MantaText.uiWidth(this.font, clearText);
            boolean clHover = mouseX >= x + w - clW - 6 && mouseX < x + w
                    && mouseY >= resY - 1 && mouseY < resY + 11;
            g.fill(x + w - clW - 6, resY - 1, x + w, resY + 11,
                    clHover ? 0xFFAA1F1F : 0xFF333344);
            int clX = x + w - clW - 3;
            int clC = clHover ? 0xFFFFFFFF : 0xFFAAAAAA;
            com.manta.api.render.Icons.draw(g, "manta:x", clX, resY + 1, clIco, clC);
            com.manta.api.text.MantaText.draw(g, this.font, clearText, clX + clIco + TransitTerminalRender.CLEAR_ICON_GAP, resY, clC);
            resY += 14;
            resH -= 14;
        }
        if (r != null && r.found()) {
            int tabsH = 14;
            renderSortTabs(g, x, resY, w, tabsH, mouseX, mouseY);
            resY += tabsH + 4;
            resH -= tabsH + 4;
        }
        renderTopResults(g, x, resY, w, resH, mouseX, mouseY);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        onElementClick(classes, mouseX, mouseY, 0);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        if (classes == null) return;
        for (String c : classes) {
            switch (c) {
                case "mc-popup-close" -> { onClose(); return; }  // R4.17.1 標準 × (狭ヘッダのため × のみ)
                case "tt-nav-top" -> { switchTab(TransitTerminalState.Tab.TOP); return; }
                case "tt-nav-schedule" -> { switchTab(TransitTerminalState.Tab.SCHEDULE); return; }
                case "tt-nav-map" -> { switchTab(TransitTerminalState.Tab.MAP); return; }
                case "tt-nav-settings" -> { switchTab(TransitTerminalState.Tab.SETTINGS); return; }
                // SETTINGS rows
                case "tt-set-row-24h" -> { TransitTerminalState.setClock24h(!TransitTerminalState.clock24h()); return; }
                case "tt-set-row-walk" -> { TransitTerminalState.setWalkGateEnabled(!TransitTerminalState.walkGateEnabled()); return; }
                case "tt-set-row-layout" -> { TransitTerminalState.setLayoutAdjustMode(!TransitTerminalState.layoutAdjustMode()); return; }
                case "tt-set-row-hud" -> { TransitTerminalState.setShowDetailHud(!TransitTerminalState.showDetailHud()); return; }
                case "tt-set-reset" -> {
                    TransitTerminalState.setScreenOffset(0, 0);
                    TransitTerminalState.setHudOffset(0, 0);
                    px = this.width - PANEL_W - RIGHT_MARGIN;
                    py = this.height - PANEL_H - BOTTOM_MARGIN;
                    rebuildEditBoxes();
                    return;
                }
                // The fields: a press focuses its own (the engine's - 2026-10-07; mouseClicked defocused every field first).
                case "tt-from-box" -> { focusField(AcField.FROM); return; }
                case "tt-to-box" -> { focusField(AcField.TO); return; }
                case "tt-sch-box" -> { focusField(AcField.SCHEDULE); return; }
                // TOP buttons
                case "tt-swap-btn" -> {
                    TransitTerminalState.swapFromTo();
                    rebuildEditBoxes();
                    return;
                }
                case "tt-search-btn" -> {
                    TransitTerminalState.onSearchSubmit();
                    return;
                }
                // TOP content canvas click: 既存 mouseClickedTop の result/history click 判定に委譲
                // mouseX/mouseY は panel ローカル座標なので screen 座標に変換 (+ px, py)
                case "tt-top-content" -> {
                    TransitTerminalRender.mouseClickedTop(this, mouseX + px, mouseY + py, button);
                    return;
                }
            }
        }
    }

    /** The field this press landed on (onElementClick), or null: mouseClicked defocuses the fields after any other. */
    private AcField fieldPressed = null;

    private void focusField(AcField f) {
        fieldPressed = f;
        if (acField != f) { setAcField(f); acSelected = -1; }
    }

    private void switchTab(TransitTerminalState.Tab t) {
        setTab(t);
        rebuildEditBoxes();
        if (t == TransitTerminalState.Tab.SCHEDULE) requestScheduleSync();
        if (t == TransitTerminalState.Tab.MAP) requestMapSync();
    }
}
