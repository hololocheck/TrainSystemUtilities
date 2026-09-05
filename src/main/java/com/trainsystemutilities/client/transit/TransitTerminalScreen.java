package com.trainsystemutilities.client.transit;
import com.manta.api.draw.VectorRenderer;
import com.manta.api.anim.Animation;

import com.manta.api.render.OverlayPopIn;
import com.manta.api.screen.JsonLayoutEngine;
import com.manta.api.screen.JsonLayoutHandler;
import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.draw.SmoothRenderer;
import com.manta.api.hud.ToggleColors;
import com.manta.api.anim.Transition;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
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
    private final OverlayPopIn acAnim = new OverlayPopIn();

    // ドラッグ状態 (レイアウト調整モード時のスクリーン移動)
    private boolean draggingScreen = false;
    private double dragAnchorX = 0, dragAnchorY = 0;
    private int dragStartPx = 0, dragStartPy = 0;
    private boolean draggingHud = false;
    private double dragHudAnchorX = 0, dragHudAnchorY = 0;
    private int dragHudStartX = 0, dragHudStartY = 0;

    public String wikiCaptureState() {
        return switch (TransitTerminalState.tab()) {
            case TOP -> "top";
            case SCHEDULE -> "schedule";
            case MAP -> "map";
            case SETTINGS -> "settings";
        };
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
        super.init();   // layout を parse + dialogX/Y を dialogAnchor から設定
        rebuildEditBoxes();
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.SCHEDULE) requestScheduleSync();
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.MAP) requestMapSync();
    }

    // === スマホ型 PiP slide: 開く=下端外→resting、 閉じる=resting→下端外。 base の scale+fade は
    //     onClose を直接 override しているため走らない (= 自前の縦 slide が優先)。 ===
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float offY = slideOffsetY();
        float s = panelScale();
        int pvx = px + PANEL_W, pvy = py + PANEL_H;  // 右下 pivot を固定して flush 右下を保つ
        g.pose().pushPose();
        if (offY != 0f) g.pose().translate(0, offY, 0);
        if (s != 1f) {
            g.pose().translate(pvx, pvy, 0);
            g.pose().scale(s, s, 1f);
            g.pose().translate(-pvx, -pvy, 0);
        }
        // V3 ツリー / afterDialogRender の hover を scale 後の panel 座標に合わせる
        super.render(g, (int) Math.round(sMx(mouseX)), (int) Math.round(sMy(mouseY)), partialTick);
        g.pose().popPose();
        if (closing && closeProgress() >= 1f) finishClose();
    }

    /** 「常にサイズ2相当」+ 画面に収める scale。 GUI スケール2では 1.0 (無変更)。 */
    private float panelScale() {
        double gs = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
        float target = gs > 0 ? (float) (2.0 / gs) : 1f;
        float fitW = (this.width  - 4) / (float) PANEL_W;
        float fitH = (this.height - 4) / (float) PANEL_H;
        return Math.min(target, Math.min(fitW, fitH));
    }
    /** screen mouse → scale 前の panel 座標系 (右下 pivot)。 hit-test は全て px/py 基準なのでこれで一致。 */
    private double sMx(double mx) { float s = panelScale(); int p = px + PANEL_W; return p + (mx - p) / s; }
    private double sMy(double my) { float s = panelScale(); int p = py + PANEL_H; return p + (my - p) / s; }

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

    private static float easeOut(float t) {
        float inv = 1f - t;
        return 1f - inv * inv;
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

    private void requestScheduleSync() {
        long now = System.nanoTime();
        if (now - lastScheduleRequestNanos < 2_000_000_000L) return; // 2 秒に 1 回まで
        lastScheduleRequestNanos = now;
        com.trainsystemutilities.network.TransitScheduleRequestPayload.send();
    }

    private void requestMapSync() {
        long now = System.nanoTime();
        if (now - lastMapRequestNanos < 5_000_000_000L) return; // 5 秒に 1 回 (重い)
        lastMapRequestNanos = now;
        com.trainsystemutilities.network.TransitMapRequestPayload.send();
    }

    @Override
    public void tick() {
        super.tick();
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.SCHEDULE) requestScheduleSync();
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.MAP) requestMapSync();
    }

    void rebuildEditBoxes() {
        // tab 切替 / init 時: controller の値を state から復元し、focus を解除する。
        // (vanilla EditBox の再生成は廃止 — TextInputController は永続 instance)
        acField = null;
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

    private AcField hitTestBox(double mx, double my) {
        var tab = TransitTerminalState.tab();
        if (tab == TransitTerminalState.Tab.TOP) {
            if (inRect(mx, my, boxRect(AcField.FROM))) return AcField.FROM;
            if (inRect(mx, my, boxRect(AcField.TO))) return AcField.TO;
        } else if (tab == TransitTerminalState.Tab.SCHEDULE) {
            if (inRect(mx, my, boxRect(AcField.SCHEDULE))) return AcField.SCHEDULE;
        }
        return null;
    }

    private static boolean inRect(double mx, double my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
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
        var tab = TransitTerminalState.tab();
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
            g.drawString(this.font, c.display(), tx, ty, 0xFF707070, false); // placeholder
        } else {
            // 長い駅名が枠外へはみ出さないよう収める: 編集中は末尾(caret 側)、非編集は head + "…"。
            int availW = r[2] - 6;
            String shown = (acField == f)
                    ? com.manta.api.hud.HudText.tailFit(this.font, val, availW)
                    : com.manta.api.hud.HudText.ellipsize(this.font, val, availW);
            g.drawString(this.font, shown, tx, ty, 0xFFFFFFFF, false);
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

    private String acQuery() {
        if (acField == null) return "";
        return switch (acField) {
            case FROM -> TransitTerminalState.fromQuery();
            case TO -> TransitTerminalState.toQuery();
            case SCHEDULE -> TransitTerminalState.scheduleQuery();
        };
    }

    private java.util.List<com.trainsystemutilities.station.StationGroup> acCandidates() {
        if (acField == null) return java.util.List.of();
        return TransitTerminalState.autocomplete(acQuery(), AC_MAX_ROWS);
    }

    private int acDropdownX() {
        if (acField == null) return 0;
        // JSON box の左端 (px+24) に揃える
        return px + 24;
    }

    private int acDropdownY() {
        if (acField == null) return 0;
        // JSON box (h=14) の下端に揃える
        return switch (acField) {
            case FROM -> py + 32 + 14;
            case TO -> py + 54 + 14;
            case SCHEDULE -> py + 32 + 14;
        };
    }

    private int acDropdownW() {
        if (acField == null) return 0;
        // JSON box の幅に揃える: FROM/TO=150, SCHEDULE=168
        return switch (acField) {
            case FROM, TO -> 150;
            case SCHEDULE -> 168;
        };
    }

    private void renderAutocomplete(GuiGraphics g, int mouseX, int mouseY) {
        if (acField == null) return;
        var cands = acCandidates();
        if (cands.isEmpty()) return;
        int x = acDropdownX();
        int y = acDropdownY();
        int w = acDropdownW();
        int h = cands.size() * AC_ROW_H + 2;
        // BelugaExperience 標準 pop-in (= JSON Animation.popIn 同等)
        acAnim.push(g, x + w / 2f, y);
        // 背景 + 枠 (R2.4.1 二層: border 5f + bg 4f inset)
        SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, 0xFF4FC3F7);
        SmoothRenderer.fillRoundedRect(g, x + 1, y + 1, w - 2, h - 2, 4f, 0xF0101820);

        for (int i = 0; i < cands.size(); i++) {
            int rowY = y + 1 + i * AC_ROW_H;
            boolean hover = mouseX >= x + 1 && mouseX < x + w - 1
                    && mouseY >= rowY && mouseY < rowY + AC_ROW_H;
            boolean active = i == acSelected || hover;
            if (active) SmoothRenderer.fillRoundedRect(g, x + 1, rowY, w - 2, AC_ROW_H, 5f, 0xFF1f4a5e);
            String name = truncate(cands.get(i).name(), w - 12);
            g.drawString(this.font, name, x + 6, rowY + 2,
                    active ? 0xFFFFFFFF : 0xFFE0E0E0, false);
        }
        acAnim.pop(g);
    }

    private void applyAcSelection(int idx) {
        var cands = acCandidates();
        if (idx < 0 || idx >= cands.size()) return;
        String name = cands.get(idx).name();
        ctrlOf(acField).setValue(name);   // onChange が state を更新
        acField = null;                   // defocus
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
        switch (TransitTerminalState.tab()) {
            case TOP -> title = Component.translatable("tsu.transit_terminal.title").getString();
            case SCHEDULE -> title = Component.translatable("tsu.transit_terminal.tab_schedule").getString();
            case MAP -> title = Component.translatable("tsu.transit_terminal.tab_map").getString();
            case SETTINGS -> title = Component.translatable("tsu.transit_terminal.tab_settings").getString();
            default -> title = "";
        }
        g.drawString(this.font, title, px + 10, py + 8, 0xFF4FC3F7, false);

        // 右上時計
        String clock = formatClock();
        int cw = this.font.width(clock);
        g.drawString(this.font, clock, px + PANEL_W - 10 - cw, py + 8, 0xFFFFD54F, false);

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
            boolean active = TransitTerminalState.tab() == tabs[i];
            boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + ch;
            int color = active ? 0xFF4FC3F7 : (hover ? 0xFF80DEEA : 0xFF80808F);
            // W7-1: タブの glyph を manta registry icon へ (中央寄せは icon 幅で計算)。
            String icon = navIcon(tabs[i]);
            String label = navLabel(tabs[i]);
            int iw = NAV_ICON_SIZE;
            com.manta.api.render.Icons.draw(g, icon, cx + (cw - iw) / 2, cy + 3, iw, color);
            int lw = this.font.width(label);
            g.drawString(this.font, label, cx + (cw - lw) / 2, cy + 14, color, false);
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
            g.drawString(this.font, msg, x, y + 4, since > 5000 ? 0xFFFF8A65 : 0xFFFFD54F, false);
            return;
        }
        if (!r.found()) {
            g.drawString(this.font, Component.translatable("tsu.transit_terminal.no_route").getString(),
                    x, y + 4, 0xFFFF8A80, false);
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
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        if (hover) g.fill(x, y, x + w, y + h, 0xFF1f3a50);
        g.drawString(this.font, Component.translatable(labelKey).getString(), x + 4, y + 5, 0xFFE0E0E0, false);
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
        if (closing) return true; // スライドアウト中は入力無視
        mouseX = sMx(mouseX); mouseY = sMy(mouseY);  // size2 scale の逆変換 (= panel 座標へ)
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
                    && mouseX >= hudX && mouseX < hudX + hudW
                    && mouseY >= hudY && mouseY < hudY + hudH) {
                TransitTerminalState.setShowDetailHud(false);
                return true;
            }
            // HUD ドラッグ開始
            if (TransitTerminalState.showDetailHud()
                    && mouseX >= hudX && mouseX < hudX + hudW
                    && mouseY >= hudY && mouseY < hudY + hudH) {
                draggingHud = true;
                dragHudAnchorX = mouseX; dragHudAnchorY = mouseY;
                dragHudStartX = TransitTerminalState.hudOffsetX();
                dragHudStartY = TransitTerminalState.hudOffsetY();
                return true;
            }
            // Screen ヘッダクリックで Screen drag 開始
            if (mouseX >= px && mouseX < px + PANEL_W
                    && mouseY >= py && mouseY < py + HEADER_H) {
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
            if (mouseX >= hudX && mouseX < hudX + hudW
                    && mouseY >= hudY && mouseY < hudY + hudH) {
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
                if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
                    int idx = ((int) (mouseY - y - 1)) / AC_ROW_H;
                    applyAcSelection(idx);
                    return true;
                }
            }
        }
        // 入力欄クリック → focus。box/autocomplete 以外のクリックは defocus する。
        if (button == 0) {
            AcField clicked = hitTestBox(mouseX, mouseY);
            if (clicked != null) {
                if (acField != clicked) { acField = clicked; acSelected = -1; acAnim.start(); }
                return true;
            }
            acField = null;
            acSelected = -1;
        }
        // base (JsonLayoutPlainScreen) が JSON click を V3 EventGraph 経由で onElementClick へ
        // dispatch する (nav / settings rows / buttons / tt-top-content canvas → mouseClickedTop)。
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        // 以下は base が消費しなかった (= JSON 要素に当たらなかった) 場合のみ到達。
        // Fallback: JSON 無効時の従来 hit-test
        int navY = py + PANEL_H - NAV_H;
        if (mouseY >= navY && mouseY < navY + NAV_H && mouseX >= px && mouseX < px + PANEL_W) {
            int cellW = (PANEL_W - 8) / 4;
            int relX = (int)(mouseX - (px + 4));
            if (relX >= 0 && relX < cellW * 4) {
                int idx = relX / cellW;
                TransitTerminalState.Tab[] tabs = TransitTerminalState.Tab.values();
                if (idx >= 0 && idx < tabs.length) {
                    TransitTerminalState.setTab(tabs[idx]);
                    rebuildEditBoxes();
                    return true;
                }
            }
        }

        switch (TransitTerminalState.tab()) {
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
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 入力欄 focus 中は文字を controller に流す
        if (acField != null && ctrlOf(acField).charTyped(codePoint)) {
            acSelected = -1;
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
                if (TransitTerminalState.tab() == TransitTerminalState.Tab.TOP) {
                    TransitTerminalState.onSearchSubmit();
                    acField = null;
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
            int next = (TransitTerminalState.tab().ordinal() + 1) % tabs.length;
            TransitTerminalState.setTab(tabs[next]);
            rebuildEditBoxes();
            return true;
        }
        // MAP タブのズーム / リセット
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.MAP) {
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
        mouseX = sMx(mouseX); mouseY = sMy(mouseY);  // size2 scale の逆変換
        if (draggingScreen || draggingHud) {
            draggingScreen = false;
            draggingHud = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing) return true; // スライドアウト中は入力無視
        mouseX = sMx(mouseX); mouseY = sMy(mouseY);  // size2 scale の逆変換
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.MAP) {
            // マウス位置を中心にズーム (画面座標を維持してズームイン/アウト)
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
            return true;
        }
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.SCHEDULE) {
            TransitTerminalState.scheduleScroll().scroll(-(int) scrollY);  // ScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        mouseX = sMx(mouseX); mouseY = sMy(mouseY);  // size2 scale の逆変換
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
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (TransitTerminalState.tab() == TransitTerminalState.Tab.MAP && button == 0) {
            int innerX = px + CONTENT_PAD;
            int innerW = PANEL_W - CONTENT_PAD * 2;
            int contentY = py + HEADER_H + 2;
            int contentH = (py + PANEL_H - NAV_H) - contentY;
            // ドラッグ範囲をマップ領域に限定
            if (mouseX >= innerX && mouseX < innerX + innerW
                    && mouseY >= contentY + 2 && mouseY < contentY + contentH - 4) {
                double zoom = TransitTerminalState.mapZoomD();
                if (zoom <= 0.0001) zoom = 1.0;
                TransitTerminalState.mapPanBy(dragX / zoom, dragY / zoom);
                return true;
            }
        }
        return false;
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
        for (var g : StationGroupClientCache.all()) {
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
        for (var line : this.font.split(Component.literal(text), w)) {
            g.drawString(this.font, line, x, y, color, false);
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

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        if (classes == null) return defaultText;
        for (String c : classes) {
            switch (c) {
                case "tt-title" -> { return headerTitleText(); }
                case "tt-clock" -> { return formatClock(); }
                // 入力欄テキスト + caret は renderInputs() が描画するので JSON 側は背景のみ (空文字)。
                case "tt-from-box", "tt-to-box", "tt-sch-box" -> { return ""; }
                case "tt-map-zoom" -> { return String.format("x%.2f", TransitTerminalState.mapZoomD()); }
                case "tt-map-segcount" -> {
                    int n = TransitTerminalClientCache.mapSegments().size();
                    return n + " seg";
                }
            }
        }
        return defaultText;
    }

    private String headerTitleText() {
        return Component.translatable(switch (TransitTerminalState.tab()) {
            case TOP -> "tsu.transit_terminal.title";
            case SCHEDULE -> "tsu.transit_terminal.tab_schedule";
            case MAP -> "tsu.transit_terminal.tab_map";
            case SETTINGS -> "tsu.transit_terminal.tab_settings";
        }).getString();
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        // Phase 3-6: 全 tab content を JSON layout 駆動に。
        if (key != null) {
            switch (key) {
                case "tt-tab-top" -> {
                    return TransitTerminalState.tab() == TransitTerminalState.Tab.TOP;
                }
                case "tt-tab-schedule" -> {
                    return TransitTerminalState.tab() == TransitTerminalState.Tab.SCHEDULE;
                }
                case "tt-tab-map" -> {
                    return TransitTerminalState.tab() == TransitTerminalState.Tab.MAP;
                }
                case "tt-tab-settings" -> {
                    return TransitTerminalState.tab() == TransitTerminalState.Tab.SETTINGS;
                }
                case "tt-sch-scrollbar-visible" -> {
                    return scheduleFilteredCount() > SCHEDULE_ROWS_VISIBLE;
                }
            }
        }
        return defaultValue;
    }

    /** SCHEDULE tab で表示中の (フィルタ後) snapshot 件数 (= scheduleScroll の total に委譲)。 */
    int scheduleFilteredCount() {
        return TransitTerminalState.scheduleFilteredRowCount();
    }

    /** SCHEDULE tab canvas h=256, row h=22+2 gap → 256/24 ≈ 10 行可視。 */
    static final int SCHEDULE_ROW_H = 22;
    static final int SCHEDULE_ROW_STRIDE = SCHEDULE_ROW_H + 2;
    static final int SCHEDULE_ROWS_VISIBLE = 256 / SCHEDULE_ROW_STRIDE;

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if (key == null) return defaultValue;
        // SETTINGS knob x position: BelugaExperience 標準 ToggleSwitchController.KNOB_TRAVEL_PX
        int travel = ToggleSwitchController.KNOB_TRAVEL_PX;
        // R4.23.1 で glyph を外して icon + label にした検索ボタンのラベル幅。
        if ("btn-search-label-w".equals(key)) {
            return com.trainsystemutilities.client.gui.LabelWidth.of(
                    "tsu.transit_terminal.btn_search");
        }
        return switch (key) {
            case "tt-set-knob-24h-x"    -> TransitTerminalState.clock24h() ? defaultValue + travel : defaultValue;
            case "tt-set-knob-walk-x"   -> TransitTerminalState.walkGateEnabled() ? defaultValue + travel : defaultValue;
            case "tt-set-knob-layout-x" -> TransitTerminalState.layoutAdjustMode() ? defaultValue + travel : defaultValue;
            case "tt-set-knob-hud-x"    -> TransitTerminalState.showDetailHud() ? defaultValue + travel : defaultValue;
            default -> defaultValue;
        };
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        if (key == null) return null;
        return switch (key) {
            // nav cell color: active tab = bright cyan (TRACK_ON), others = gray
            case "tt-nav-top-color"      -> tabColor(TransitTerminalState.Tab.TOP);
            case "tt-nav-schedule-color" -> tabColor(TransitTerminalState.Tab.SCHEDULE);
            case "tt-nav-map-color"      -> tabColor(TransitTerminalState.Tab.MAP);
            case "tt-nav-settings-color" -> tabColor(TransitTerminalState.Tab.SETTINGS);
            // SETTINGS toggle track bg (BelugaExperience 標準 ToggleColors)
            case "tt-set-track-24h-bg"    -> ToggleColors.trackBg(TransitTerminalState.clock24h());
            case "tt-set-track-walk-bg"   -> ToggleColors.trackBg(TransitTerminalState.walkGateEnabled());
            case "tt-set-track-layout-bg" -> ToggleColors.trackBg(TransitTerminalState.layoutAdjustMode());
            case "tt-set-track-hud-bg"    -> ToggleColors.trackBg(TransitTerminalState.showDetailHud());
            // SETTINGS toggle knob bg (BelugaExperience 標準 ToggleColors)
            case "tt-set-knob-24h-bg"    -> ToggleColors.knobBg(TransitTerminalState.clock24h());
            case "tt-set-knob-walk-bg"   -> ToggleColors.knobBg(TransitTerminalState.walkGateEnabled());
            case "tt-set-knob-layout-bg" -> ToggleColors.knobBg(TransitTerminalState.layoutAdjustMode());
            case "tt-set-knob-hud-bg"    -> ToggleColors.knobBg(TransitTerminalState.showDetailHud());
            default -> null;
        };
    }

    private int tabColor(TransitTerminalState.Tab t) {
        return TransitTerminalState.tab() == t ? ToggleColors.TRACK_ON : 0xFF80808F;
    }

    @Override
    public ImageRef getDynamicImage(String[] classes, String key) { return null; }

    @Override
    public Transition getDynamicTransition(String[] classes, String key) {
        if (key == null) return null;
        if ("toggle-bg".equals(key))   return Transition.toggleBg();
        if ("toggle-knob".equals(key)) return Transition.toggleKnob();
        return null;
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
        int thumbH = Math.max(12, h * SCHEDULE_ROWS_VISIBLE / Math.max(1, filteredCount));
        int thumbY = TransitTerminalState.scheduleScroll().thumbY(y, h, thumbH);
        SmoothRenderer.fillRoundedRect(g, x, thumbY, w, thumbH, 5f, 0xFF4FC3F7);
    }

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
            int clW = clIco + TransitTerminalRender.CLEAR_ICON_GAP + this.font.width(clearText);
            boolean clHover = mouseX >= x + w - clW - 6 && mouseX < x + w
                    && mouseY >= resY - 1 && mouseY < resY + 11;
            g.fill(x + w - clW - 6, resY - 1, x + w, resY + 11,
                    clHover ? 0xFFAA1F1F : 0xFF333344);
            int clX = x + w - clW - 3;
            int clC = clHover ? 0xFFFFFFFF : 0xFFAAAAAA;
            com.manta.api.render.Icons.draw(g, "manta:x", clX, resY + 1, clIco, clC);
            g.drawString(this.font, clearText,
                    clX + clIco + TransitTerminalRender.CLEAR_ICON_GAP, resY, clC, false);
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

    private void switchTab(TransitTerminalState.Tab t) {
        TransitTerminalState.setTab(t);
        rebuildEditBoxes();
        if (t == TransitTerminalState.Tab.SCHEDULE) requestScheduleSync();
        if (t == TransitTerminalState.Tab.MAP) requestMapSync();
    }
}
