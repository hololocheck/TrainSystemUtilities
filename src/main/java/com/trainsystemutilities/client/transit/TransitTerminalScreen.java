package com.trainsystemutilities.client.transit;
import belugalab.mcss3.draw.VectorRenderer;
import belugalab.mcss3.anim.Animation;

import belugalab.experience.render.OverlayPopIn;
import belugalab.mcss3.screen.JsonLayoutEngine;
import belugalab.mcss3.screen.JsonLayoutHandler;
import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.tsu.api.ToggleColors;
import belugalab.mcss3.anim.Transition;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import com.trainsystemutilities.station.routing.TrainRouter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import belugalab.experience.controller.TextInputController;
import belugalab.experience.controller.ToggleSwitchController;
import belugalab.experience.render.TextCaretRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
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
public class TransitTerminalScreen extends belugalab.mcss3.screen.JsonLayoutPlainScreen {

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 340;
    private static final int RIGHT_MARGIN = 12;
    private static final int BOTTOM_MARGIN = 12;
    private static final int HEADER_H = 22;
    private static final int NAV_H = 28;
    private static final int CONTENT_PAD = 6;

    // Layout: panel coordinates relative to screen
    private int px;
    private int py;

    // BelugaExperience: vanilla EditBox の代わりに TextInputController (§4.10)。
    // focus は acField (= FROM/TO/SCHEDULE/null) で管理。テキスト + caret は自前描画。
    private final TextInputController fromCtrl = new TextInputController(32,
            Component.translatable("tsu.transit_terminal.field_from").getString());
    private final TextInputController toCtrl = new TextInputController(32,
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
        if (belugalab.mcss3.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE) return 0f;
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

    private void rebuildEditBoxes() {
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
                    ? belugalab.tsu.api.HudText.tailFit(this.font, val, availW)
                    : belugalab.tsu.api.HudText.ellipsize(this.font, val, availW);
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
            String icon = navIcon(tabs[i]);
            String label = navLabel(tabs[i]);
            int iw = this.font.width(icon);
            g.drawString(this.font, icon, cx + (cw - iw) / 2, cy + 4, color, false);
            int lw = this.font.width(label);
            g.drawString(this.font, label, cx + (cw - lw) / 2, cy + 14, color, false);
        }
    }

    private String navIcon(TransitTerminalState.Tab t) {
        return switch (t) {
            case TOP -> "🔍";
            case SCHEDULE -> "🕒";
            case MAP -> "🗺";
            case SETTINGS -> "⚙";
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
    private int topBoxY;
    private int topBoxX;
    private int topBoxW;

    private void renderTopTab(GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        int innerX = px + CONTENT_PAD;
        int innerW = PANEL_W - CONTENT_PAD * 2;
        int boxX = innerX + 18;
        int boxW = innerW - 36;
        int boxY = y + 8;
        topBoxY = boxY; topBoxX = boxX; topBoxW = boxW;

        // 出発駅 bullet (●)
        g.drawString(this.font, "●", innerX + 4, boxY + 3, 0xFF4FC3F7, false);
        // 到着駅 bullet (■)
        g.drawString(this.font, "■", innerX + 4, boxY + 25, 0xFFFF8A65, false);

        // Swap ボタン (↕): 右側に縦長の小ボタン、2 つの input の中間に
        int swapX = innerX + innerW - 16;
        int swapY = boxY + 7;
        int swapW = 14, swapH = 22;
        boolean swapHover = mouseX >= swapX && mouseX < swapX + swapW
                && mouseY >= swapY && mouseY < swapY + swapH;
        int swapBg = swapHover ? 0xFF1f5e7e : 0xFF1f3a50;
        SmoothRenderer.fillRoundedRect(g, swapX, swapY, swapW, swapH, 5f, 0xFF4FC3F7);
        g.fill(swapX + 1, swapY + 1, swapX + swapW - 1, swapY + swapH - 1, swapBg);
        int sw = this.font.width("⇅");
        g.drawString(this.font, "⇅", swapX + (swapW - sw) / 2, swapY + 7, 0xFFFFFFFF, false);

        // EditBoxes は super.render() で描画される (boxX, boxY)。

        // 検索ボタン
        int btnY = boxY + 38;
        int btnH = 16;
        boolean canSearch = TransitTerminalState.fromGroupId() != null && TransitTerminalState.toGroupId() != null;
        boolean btnHover = mouseX >= innerX && mouseX < innerX + innerW && mouseY >= btnY && mouseY < btnY + btnH;
        int btnBg = !canSearch ? 0xFF1f3030 : (btnHover ? 0xFF1f5e7e : 0xFF2da856);
        int btnBorder = !canSearch ? 0xFF445566 : 0xFF66BB6A;
        SmoothRenderer.fillRoundedRect(g, innerX, btnY, innerW, btnH, 5f, btnBorder);
        g.fill(innerX + 1, btnY + 1, innerX + innerW - 1, btnY + btnH - 1, btnBg);
        String btnLabel = Component.translatable("tsu.transit_terminal.btn_search").getString();
        int bw = this.font.width(btnLabel);
        int btnTextColor = !canSearch ? 0xFF666666 : 0xFFFFFFFF;
        g.drawString(this.font, btnLabel, innerX + (innerW - bw) / 2, btnY + 4, btnTextColor, false);

        // 結果領域 (or 履歴 if no result)
        int resY = btnY + btnH + 6;
        int resH = (y + h) - resY - 4;

        // 結果クリアボタン (検索済の時のみ): 候補タブとは別行にして重なりを防ぐ
        ComposedRouteFinder.ComposedRoute r = TransitTerminalState.lastResult();
        if (r != null) {
            String clearLabel = "✕ " + Component.translatable("tsu.transit_terminal.clear_results").getString();
            int clW = this.font.width(clearLabel);
            boolean clHover = mouseX >= innerX + innerW - clW - 6
                    && mouseX < innerX + innerW
                    && mouseY >= resY && mouseY < resY + 11;
            g.fill(innerX + innerW - clW - 6, resY - 1,
                   innerX + innerW, resY + 11,
                   clHover ? 0xFFAA1F1F : 0xFF333344);
            g.drawString(this.font, clearLabel, innerX + innerW - clW - 3, resY,
                    clHover ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            resY += 14;
            resH -= 14;
        }

        // ソートタブ (結果がある時のみ): 早 / 楽 / 安
        if (r != null && r.found()) {
            int tabsH = 14;
            renderSortTabs(g, innerX, resY, innerW, tabsH, mouseX, mouseY);
            resY += tabsH + 4;
            resH -= tabsH + 4;
        }
        renderTopResults(g, innerX, resY, innerW, resH, mouseX, mouseY);
    }

    /**
     * Phase D: ルート候補タブ (上位 K 候補から選ぶ)。
     * 候補数に応じて N 個 (最大 3) のタブを表示。各タブには合計時間を簡易表示。
     */
    private void renderSortTabs(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        var routes = TransitTerminalState.lastResults();
        int n = Math.min(3, routes.size());
        if (n <= 1) {
            // 1 候補なら従来の早/楽/安タブ (UI として残す)
            int cellW = w / 3;
            String[] keys = {"tsu.transit_terminal.sort_fast", "tsu.transit_terminal.sort_easy", "tsu.transit_terminal.sort_cheap"};
            int active = TransitTerminalState.sortMode();
            for (int i = 0; i < 3; i++) {
                int cx = x + cellW * i;
                boolean hover = mouseX >= cx && mouseX < cx + cellW && mouseY >= y && mouseY < y + h;
                boolean act = i == active;
                int bg = act ? 0xFF1f5e7e : (hover ? 0xFF1f3a50 : 0xFF111928);
                int border = act ? 0xFF4FC3F7 : 0xFF2a4a60;
                g.fill(cx, y, cx + cellW, y + h, border);
                g.fill(cx + 1, y + 1, cx + cellW - 1, y + h - 1, bg);
                String label = Component.translatable(keys[i]).getString();
                int lw = this.font.width(label);
                g.drawString(this.font, label, cx + (cellW - lw) / 2, y + 3, act ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            }
            return;
        }
        int cellW = w / n;
        int active = TransitTerminalState.selectedRouteIdx();
        for (int i = 0; i < n; i++) {
            int cx = x + cellW * i;
            boolean hover = mouseX >= cx && mouseX < cx + cellW && mouseY >= y && mouseY < y + h;
            boolean act = i == active;
            int bg = act ? 0xFF1f5e7e : (hover ? 0xFF1f3a50 : 0xFF111928);
            int border = act ? 0xFF4FC3F7 : 0xFF2a4a60;
            g.fill(cx, y, cx + cellW, y + h, border);
            g.fill(cx + 1, y + 1, cx + cellW - 1, y + h - 1, bg);
            // 候補番号 + 合計時間
            var route = routes.get(i);
            int legsCount = route.trainLegs().size();
            int firstDep = legsCount == 0 ? 0 : route.trainLegs().get(0).departureTicksFromNow();
            int durSec = Math.max(0, (route.totalTicks() - firstDep) / 20);
            String label = (i + 1) + "  " + (durSec / 60) + "分";
            int lw = this.font.width(label);
            g.drawString(this.font, label, cx + (cellW - lw) / 2, y + 3, act ? 0xFFFFFFFF : 0xFFAAAAAA, false);
        }
    }

    private void renderTopResults(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
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
        var history = TransitTerminalState.history();
        if (history.isEmpty()) {
            String hint = Component.translatable("tsu.transit_terminal.results_hint").getString();
            drawWrapped(g, hint, x, y + 4, w, 0xFF80808F);
            return;
        }
        g.drawString(this.font, Component.translatable("tsu.transit_terminal.history_title").getString(),
                x, y, 0xFFAAAAAA, false);
        String clearAll = Component.translatable("tsu.transit_terminal.history_clear_all").getString();
        int caW = this.font.width(clearAll);
        boolean clearHover = mouseX >= x + w - caW - 4 && mouseX < x + w
                && mouseY >= y - 1 && mouseY < y + 9;
        g.drawString(this.font, clearAll, x + w - caW - 2, y, clearHover ? 0xFFFF8A65 : 0xFF80808F, false);

        int rowY = y + 12;
        int rowH = 22; // 18 → 22 で削除 × ボタンが見やすく
        int delBtnW = 20;
        int max = Math.min(history.size(), Math.max(1, (h - 16) / (rowH + 2)));
        for (int i = 0; i < max; i++) {
            var e = history.get(i);
            // 行本体 (タイル領域)
            boolean hover = mouseX >= x && mouseX < x + w - delBtnW - 2
                    && mouseY >= rowY && mouseY < rowY + rowH;
            int bg = hover ? 0xFF1f3a50 : 0xFF111928;
            SmoothRenderer.fillRoundedRect(g, x, rowY, w - delBtnW - 2, rowH, 5f, 0xFF2a4a60);
            g.fill(x + 1, rowY + 1, x + w - delBtnW - 3, rowY + rowH - 1, bg);
            g.drawString(this.font, "🕒", x + 4, rowY + 7, 0xFF80808F, false);
            String txt = truncate(e.fromName() + " → " + e.toName(), w - delBtnW - 22);
            g.drawString(this.font, txt, x + 16, rowY + 7, 0xFFE0E0E0, false);
            // 削除ボタン (× ボックス、20×rowH の独立タイル)
            int delX = x + w - delBtnW;
            boolean delHover = mouseX >= delX && mouseX < delX + delBtnW
                    && mouseY >= rowY && mouseY < rowY + rowH;
            SmoothRenderer.fillRoundedRect(g, delX, rowY, delBtnW, rowH, 5f,
                    delHover ? 0xFFFF6655 : 0xFF555566);
            int delBg = delHover ? 0xFFAA1F1F : 0xFF333344;
            g.fill(delX + 1, rowY + 1, delX + delBtnW - 1, rowY + rowH - 1, delBg);
            String xMark = "✕";
            int xW = this.font.width(xMark);
            g.drawString(this.font, xMark, delX + (delBtnW - xW) / 2, rowY + 7,
                    delHover ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            rowY += rowH + 2;
        }
    }

    /** ルート結果サマリ画面 (Yahoo!乗換案内風 1 タイル / 候補)。 */
    private void renderResultSummary(GuiGraphics g, ComposedRouteFinder.ComposedRoute r,
                                     int x, int y, int w, int h, int mouseX, int mouseY) {
        // ヘッダ行: 全体の発時刻 → 着時刻 + 所要時間
        List<TrainRouter.Leg> legs = r.trainLegs();
        int firstDep = legs.isEmpty() ? 0 : legs.get(0).departureTicksFromNow();
        int totalDuration = r.totalTicks(); // = 最終 leg arrival from now
        String depAbs = absoluteClockOffset(firstDep);
        String arrAbs = absoluteClockOffset(totalDuration);
        int totalSec = Math.max(0, (totalDuration - firstDep) / 20);
        int totalMin = totalSec / 60;

        // ヘッダ枠
        int headerH = 22;
        SmoothRenderer.fillRoundedRect(g, x, y, w, headerH, 5f, 0xFF4FC3F7);
        g.fill(x + 1, y + 1, x + w - 1, y + headerH - 1, 0xFF1a3040);
        // 大きい時刻 (発 → 着)
        g.drawString(this.font, depAbs, x + 4, y + 2, 0xFFFFFFFF, false);
        int dw = this.font.width(depAbs);
        g.drawString(this.font, "→", x + 4 + dw + 3, y + 2, 0xFF80DEEA, false);
        g.drawString(this.font, arrAbs, x + 4 + dw + 12, y + 2, 0xFFFFFFFF, false);
        // 所要時間 (右上)
        String dur = Component.translatable("tsu.transit_terminal.duration_fmt",
                totalMin, totalSec % 60).getString();
        int durW = this.font.width(dur);
        g.drawString(this.font, dur, x + w - durW - 4, y + 2, 0xFFFFD54F, false);
        // メタ行: 乗換 N 回 + 距離
        String meta = Component.translatable("tsu.transit_terminal.transfers_fmt", legs.size() - 1).getString();
        g.drawString(this.font, meta, x + 4, y + 13, 0xFFAAAAAA, false);

        int rowY = y + headerH + 4;

        // 徒歩レッグ (もしあれば短く一行で)
        if (r.walkToFrom() != null && r.walkToFrom().approxTicks() > 0) {
            int walkSec = r.walkToFrom().approxTicks() / 20;
            String walkText = Component.translatable("tsu.transit_terminal.walk_fmt",
                    r.fromGroupName(), walkSec).getString();
            g.drawString(this.font, "🚶 " + truncate(walkText, w - 14), x, rowY, 0xFF80DEEA, false);
            rowY += 12;
        }

        // 路線記号バー (各 leg の路線色を横並びで)
        if (!legs.isEmpty()) {
            int barH = 8;
            int barX = x;
            int totalLegTicks = 0;
            for (var leg : legs) totalLegTicks += leg.travelTicks();
            for (int i = 0; i < legs.size(); i++) {
                TrainRouter.Leg leg = legs.get(i);
                int color = lineColorForLeg(leg);
                int segW = totalLegTicks <= 0 ? w / legs.size()
                        : (w * leg.travelTicks() / totalLegTicks);
                if (i == legs.size() - 1) segW = (x + w) - barX;
                g.fill(barX, rowY, barX + segW, rowY + barH, 0xFF000000);
                g.fill(barX + 1, rowY + 1, barX + segW - 1, rowY + barH - 1, color);
                barX += segW;
            }
            rowY += barH + 4;
        }

        // 列車レッグタイル群
        int rowHTile = 44;
        for (int i = 0; i < legs.size() && rowY + rowHTile < y + h; i++) {
            TrainRouter.Leg leg = legs.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + rowHTile;
            int bg = hover ? 0xFF1f3a50 : 0xFF111928;
            int border = 0xFF2a4a60;
            int lineColor = lineColorForLeg(leg);
            SmoothRenderer.fillRoundedRect(g, x, rowY, w, rowHTile, 5f, border);
            g.fill(x + 1, rowY + 1, x + w - 1, rowY + rowHTile - 1, bg);
            // 左端に路線色帯 (3px)
            g.fill(x + 1, rowY + 1, x + 4, rowY + rowHTile - 1, lineColor);

            // 路線記号バッジ (タイル右、垂直中央)。サイズ 24×24
            int badgeW = 0;
            if (leg.symbolNumber() >= 0 || (leg.symbolLetters() != null && !leg.symbolLetters().isEmpty())) {
                int symBadgeY = rowY + (rowHTile - 24) / 2;
                badgeW = drawSymbolBadge(g, x + w - 28, symBadgeY, leg);
            }
            int rightTextEdge = x + w - (badgeW > 0 ? badgeW + 8 : 12);

            // 1 行目: 駅名 from → to
            String fromName = nameOf(leg.fromGroupId());
            String toName = nameOf(leg.toGroupId());
            String l1 = truncate(fromName + " → " + toName, rightTextEdge - (x + 8));
            g.drawString(this.font, l1, x + 8, rowY + 4, 0xFFFFFFFF, false);

            // 2 行目: 発車時刻 + あと N 分
            int delaySec = Math.max(0, leg.delayTicks() / 20);
            String absDep = absoluteClockOffset(leg.departureTicksFromNow() + leg.delayTicks());
            int liveTicks = liveCountdownTicks(leg.departureTicksFromNow() + leg.delayTicks());
            int depSec = liveTicks / 20;
            String relDep = Component.translatable("tsu.transit_terminal.dep_in_fmt",
                    depSec / 60, depSec % 60).getString();
            g.drawString(this.font, Component.translatable("tsu.transit_terminal.abs_dep_fmt", absDep), x + 8, rowY + 15, 0xFFFFD54F, false);
            int adw = this.font.width("発 " + absDep);
            g.drawString(this.font, " (" + relDep + ")", x + 8 + adw, rowY + 15, 0xFFAAAAAA, false);

            // 3 行目: 走行時間 + 番線 + 遅延バッジ
            int legSec = leg.travelTicks() / 20;
            String travel = "🕐 " + Component.translatable("tsu.transit_terminal.leg_fmt_short",
                    legSec / 60, legSec % 60).getString();
            g.drawString(this.font, travel, x + 8, rowY + 26, 0xFF80DEEA, false);
            int travelW = this.font.width(travel);

            int rightBadgeX = rightTextEdge;
            // 番線バッジ (記号バッジの左に置く)
            if (leg.boardPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.boardPlatform()).getString();
                int pw = this.font.width(plat);
                int pbX = rightBadgeX - pw - 6;
                g.fill(pbX, rowY + 25, rightBadgeX, rowY + 35, 0xFF1f4f3e);
                g.drawString(this.font, plat, pbX + 3, rowY + 26, 0xFF80FFAA, false);
                rightBadgeX = pbX - 4;
            }
            // 遅延バッジ
            if (delaySec > 0) {
                String dlb = Component.translatable("tsu.transit_terminal.delay_fmt",
                        delaySec / 60, delaySec % 60).getString();
                int dlw = this.font.width(dlb);
                int dlx = rightBadgeX - dlw - 6;
                g.fill(dlx, rowY + 14, rightBadgeX, rowY + 24, 0xFFAA1F1F);
                g.drawString(this.font, dlb, dlx + 3, rowY + 15, 0xFFFFFFFF, false);
            }
            // 信頼度
            String confLabel;
            int confColor;
            if (leg.sampleCount() >= 5) {
                confLabel = "● " + Component.translatable("tsu.transit_terminal.conf_certain").getString();
                confColor = 0xFF66BB6A;
            } else if (leg.sampleCount() >= 1) {
                confLabel = "○ " + Component.translatable("tsu.transit_terminal.conf_estimate").getString();
                confColor = 0xFFFFD54F;
            } else {
                confLabel = "△ " + Component.translatable("tsu.transit_terminal.conf_unknown").getString();
                confColor = 0xFF80808F;
            }
            int cw = this.font.width(confLabel);
            // 信頼度は travel の右隣 (記号バッジ前まで)
            int confX = x + 8 + travelW + 6;
            if (confX + cw < rightTextEdge) {
                g.drawString(this.font, confLabel, confX, rowY + 26, confColor, false);
            }

            rowY += rowHTile + 2;
        }
    }

    /** Leg の路線色を返す: 路線記号があればその border color、なければ列車 ID hash。 */
    private static int lineColorForLeg(TrainRouter.Leg leg) {
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
    private int drawSymbolBadge(GuiGraphics g, int x, int y, TrainRouter.Leg leg) {
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
        // 戻るボタン (左上)
        boolean backHover = mouseX >= x && mouseX < x + 40 && mouseY >= y && mouseY < y + 12;
        g.drawString(this.font, "← " + Component.translatable("tsu.transit_terminal.back").getString(),
                x, y, backHover ? 0xFFFFD54F : 0xFF80DEEA, false);

        // 右上に 2 ボタンを横並びで配置 (重ならないよう 8px 空ける)
        boolean hudOn = TransitTerminalState.showDetailHud();
        String hudLabel = hudOn
                ? "🪟 " + Component.translatable("tsu.transit_terminal.hud_hide").getString()
                : "🪟 " + Component.translatable("tsu.transit_terminal.hud_show").getString();
        String navLabel;
        if (TransitNavClientState.active()) {
            navLabel = "🧭 " + Component.translatable("tsu.transit_terminal.nav_cancel").getString();
        } else if (TransitNavClientState.isPending()) {
            navLabel = "🧭 " + Component.translatable("tsu.transit_terminal.nav_pending").getString();
        } else {
            navLabel = "🧭 " + Component.translatable("tsu.transit_terminal.nav_start").getString();
        }
        int hudW = this.font.width(hudLabel);
        int navW = this.font.width(navLabel);
        int btnGap = 8;
        // HUD button (一番右)
        int hudBoxX = x + w - hudW - 6;
        boolean hudHover = mouseX >= hudBoxX && mouseX < hudBoxX + hudW + 6
                && mouseY >= y - 1 && mouseY < y + 10;
        g.fill(hudBoxX, y - 1, hudBoxX + hudW + 6, y + 10, hudOn ? 0xFF1f5e7e : 0xFF2a4a60);
        g.drawString(this.font, hudLabel, hudBoxX + 3, y,
                hudHover ? 0xFFFFD54F : 0xFFFFFFFF, false);

        // Nav button (HUD button の左、btnGap 空けて)
        int navBoxX = hudBoxX - navW - 6 - btnGap;
        boolean navHover = mouseX >= navBoxX && mouseX < navBoxX + navW + 6
                && mouseY >= y - 1 && mouseY < y + 10;
        g.fill(navBoxX, y - 1, navBoxX + navW + 6, y + 10,
                TransitNavClientState.active() ? 0xFFAA3F1F : 0xFF2a4a60);
        g.drawString(this.font, navLabel, navBoxX + 3, y,
                navHover ? 0xFFFFD54F : 0xFFFFFFFF, false);

        if (hudOn) {
            TransitTerminalState.setHudRouteIdx(TransitTerminalState.selectedRouteIdx());
        }

        int detailTextY = y + 12;

        // ナビゲーションエラー表示 (server から失敗が返ってきたとき)
        String navErr = TransitNavClientState.lastError();
        if (!navErr.isEmpty()) {
            String errMsg = "🧭 " + Component.translatable("tsu.transit_terminal.nav_error_fmt", navErr).getString();
            int eW = this.font.width(errMsg);
            g.fill(x, y + 11, x + Math.min(w, eW + 6), y + 21, 0xCC8B0000);
            g.drawString(this.font, truncate(errMsg, w - 6), x + 3, y + 12, 0xFFFFE0E0, false);
            detailTextY += 12;
        }

        // サマリ
        List<TrainRouter.Leg> legs = r.trainLegs();
        int firstDep = legs.isEmpty() ? 0 : legs.get(0).departureTicksFromNow();
        String dr = absoluteClockOffset(firstDep) + " → " + absoluteClockOffset(r.totalTicks());
        int totalSec = Math.max(0, (r.totalTicks() - firstDep) / 20);
        String summary = dr + " (" + (totalSec / 60) + "m " + (totalSec % 60) + "s)";
        g.drawString(this.font, truncate(summary, w - 4), x, detailTextY, 0xFFFFFFFF, false);
        String trans = Component.translatable("tsu.transit_terminal.transfers_fmt", legs.size() - 1).getString();
        g.drawString(this.font, trans, x, detailTextY + 10, 0xFFAAAAAA, false);

        // タイムライン
        int dy = detailTextY + 24;
        // 列のレイアウト:
        //   x .. x+24   : 時刻 (発・着)
        //   x+26 .. +34 : 駅マーク (●) + 縦線 (║)
        //   x+38 ..     : 駅名 / 番線 / 列車名
        int timeColW = 28;
        int barColX = x + timeColW;

        for (int i = 0; i < legs.size(); i++) {
            TrainRouter.Leg leg = legs.get(i);
            int color = lineColorForLeg(leg);
            int depTicks = leg.departureTicksFromNow();
            int arrTicks = depTicks + leg.travelTicks();
            String depAbs = absoluteClockOffset(depTicks);
            String arrAbs = absoluteClockOffset(arrTicks);
            StationGroup fromG = findGroup(leg.fromGroupId());
            StationGroup toG = findGroup(leg.toGroupId());

            // === 出発駅マーク ===
            // 時刻
            g.drawString(this.font, depAbs, x, dy, 0xFFFFD54F, false);
            // ●
            g.fill(barColX + 2, dy + 2, barColX + 9, dy + 9, 0xFF000000);
            g.fill(barColX + 3, dy + 3, barColX + 8, dy + 8, color);
            // 駅名 (路線記号バッジ用にスペース確保)
            String fromName = fromG != null ? fromG.name() : nameOf(leg.fromGroupId());
            int reserveR = 50; // 番線分
            if (leg.symbolNumber() >= 0 || (leg.symbolLetters() != null && !leg.symbolLetters().isEmpty())) reserveR += 18;
            g.drawString(this.font, truncate(fromName, w - timeColW - reserveR), barColX + 14, dy + 1, 0xFFFFFFFF, false);
            // 番線 (右側に小さく)
            if (leg.boardPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.boardPlatform()).getString();
                int pw = this.font.width(plat);
                g.fill(x + w - pw - 8, dy, x + w - 2, dy + 10, 0xFF1f4f3e);
                g.drawString(this.font, plat, x + w - pw - 5, dy + 1, 0xFF80FFAA, false);
            }
            dy += 12;

            // === 区間 (路線色塗り縦線) ===
            int legSec = leg.travelTicks() / 20;
            int barTopY = dy;
            int legHeight = 28; // 区間表示の高さ
            // 縦線 (路線色、太さ 5px)
            g.fill(barColX + 4, barTopY, barColX + 9, barTopY + legHeight, color);
            // 路線記号バッジ (区間の右側)
            int symBadgeW = 0;
            if (leg.symbolNumber() >= 0 || (leg.symbolLetters() != null && !leg.symbolLetters().isEmpty())) {
                symBadgeW = drawSymbolBadge(g, x + w - 30, barTopY + 4, leg);
            }
            // 列車種別 / 行き先
            String trainName = "🚆 " + Component.translatable("tsu.transit_terminal.detail_train_short").getString();
            if (leg.trainId() != null) {
                var snap = TransitTerminalClientCache.allSchedules().get(leg.trainId());
                if (snap != null && snap.trainName() != null && !snap.trainName().isEmpty()) {
                    trainName = "🚆 " + snap.trainName();
                }
            }
            g.drawString(this.font, truncate(trainName, w - timeColW - 16 - (symBadgeW > 0 ? symBadgeW + 4 : 0)),
                    barColX + 14, barTopY + 4, 0xFFE0E0E0, false);
            // 走行時間 + 駅数
            String legInfo = Component.translatable("tsu.transit_terminal.leg_fmt_short",
                    legSec / 60, legSec % 60).getString();
            g.drawString(this.font, legInfo, barColX + 14, barTopY + 14, 0xFF80DEEA, false);
            dy += legHeight;

            // === 到着駅マーク ===
            g.drawString(this.font, arrAbs, x, dy, 0xFFFF8A65, false);
            g.fill(barColX + 2, dy + 2, barColX + 9, dy + 9, 0xFF000000);
            g.fill(barColX + 3, dy + 3, barColX + 8, dy + 8, color);
            String toName = toG != null ? toG.name() : nameOf(leg.toGroupId());
            g.drawString(this.font, truncate(toName, w - timeColW - 50), barColX + 14, dy + 1, 0xFFFFFFFF, false);
            if (leg.alightPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.alightPlatform()).getString();
                int pw = this.font.width(plat);
                g.fill(x + w - pw - 8, dy, x + w - 2, dy + 10, 0xFF4f3a1f);
                g.drawString(this.font, plat, x + w - pw - 5, dy + 1, 0xFFFFD54F, false);
            }
            dy += 12;

            // 次の leg がある場合は乗換ブロック
            if (i + 1 < legs.size()) {
                TrainRouter.Leg next = legs.get(i + 1);
                int wait = next.departureTicksFromNow() - arrTicks;
                if (wait > 0) {
                    int waitSec = wait / 20;
                    String waitText = Component.translatable("tsu.transit_terminal.transfer_wait_fmt",
                            waitSec / 60, waitSec % 60).getString();
                    g.fill(barColX + 5, dy, barColX + 7, dy + 14, 0xFF606080);
                    g.drawString(this.font, "🔄 " + waitText, barColX + 14, dy + 2, 0xFFAAAAAA, false);
                    dy += 14;
                }
            }
            dy += 2;

            // 画面下端を超えたら停止
            if (dy > y + h - 8) break;
        }
    }

    /**
     * 検索結果到着時の dayTime + offsetTicks をゲーム内 HH:MM で返す。
     * 結果が届いた瞬間を t=0 とするため、現在 dayTime が進んでも表示は固定される。
     */
    private String absoluteClockOffset(int offsetTicks) {
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
    private int liveCountdownTicks(int originalOffsetTicks) {
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
        int innerX = px + CONTENT_PAD;
        int innerW = PANEL_W - CONTENT_PAD * 2;
        // search box already drawn by EditBox child
        g.drawString(this.font, "🔍", innerX + 2, y + 11, 0xFF4FC3F7, false);

        int listY = y + 28;
        int listH = h - 30;

        var snapshots = TransitTerminalClientCache.allSchedules();
        if (snapshots.isEmpty()) {
            g.drawString(this.font, Component.translatable("tsu.transit_terminal.schedule_empty").getString(),
                    innerX, listY + 4, 0xFF80808F, false);
            g.drawString(this.font, Component.translatable("tsu.transit_terminal.schedule_hint").getString(),
                    innerX, listY + 16, 0xFF606080, false);
            return;
        }
        String key = TransitTerminalState.scheduleQuery().toLowerCase(java.util.Locale.ROOT);
        int rowH = 22;
        // フィルタ済み件数 を先に数えて scrollbar 用に保持
        int filteredCount = 0;
        for (var snap : snapshots.values()) {
            if (!key.isEmpty() && !snap.trainName().toLowerCase(java.util.Locale.ROOT).contains(key)) continue;
            filteredCount++;
        }
        // スクロールバー領域確保
        int sbW = 4;
        int listInnerW = innerW - sbW - 4;
        int rowsVisible = listH / (rowH + 2);
        int maxScroll = Math.max(0, filteredCount - rowsVisible);
        int scrollY = Math.min(TransitTerminalState.scheduleScrollY(), maxScroll);
        if (scrollY != TransitTerminalState.scheduleScrollY()) {
            TransitTerminalState.setScheduleScrollY(scrollY);
        }

        int idx = 0;
        int drawn = 0;
        for (var snap : snapshots.values()) {
            if (!key.isEmpty() && !snap.trainName().toLowerCase(java.util.Locale.ROOT).contains(key)) continue;
            if (idx++ < scrollY) continue;
            if (drawn >= rowsVisible) break;
            int rowY = listY + drawn * (rowH + 2);
            g.fill(innerX, rowY, innerX + listInnerW, rowY + rowH, 0xFF111928);
            g.fill(innerX, rowY, innerX + listInnerW, rowY + 1, 0xFF2a4a60);
            g.drawString(this.font, "🚆 " + truncate(snap.trainName(), listInnerW - 16),
                    innerX + 2, rowY + 2, 0xFFFFFFFF, false);
            String next = snap.nextGroupId() == null ? "—" : nameOf(snap.nextGroupId());
            int eta = snap.etaTicksToNext() / 20;
            String etaText = Component.translatable("tsu.transit_terminal.eta_fmt", eta).getString();
            g.drawString(this.font, "→ " + truncate(next, listInnerW - 60),
                    innerX + 2, rowY + 12, 0xFF80DEEA, false);
            int ew = this.font.width(etaText);
            g.drawString(this.font, etaText, innerX + listInnerW - ew - 2, rowY + 12, 0xFFFFD54F, false);
            drawn++;
        }

        // スクロールバー描画 (右端)
        if (filteredCount > rowsVisible) {
            int sbX = innerX + innerW - sbW;
            int sbY = listY;
            int sbHeight = listH;
            // track
            g.fill(sbX, sbY, sbX + sbW, sbY + sbHeight, 0xFF2a2a3a);
            // thumb
            int thumbH = Math.max(12, sbHeight * rowsVisible / filteredCount);
            int thumbY = sbY + (maxScroll == 0 ? 0 : (sbHeight - thumbH) * scrollY / maxScroll);
            g.fill(sbX, thumbY, sbX + sbW, thumbY + thumbH, 0xFF4FC3F7);
        }
    }

    // -------- MAP tab --------
    /** MAP タブ: 管理用コンピューターと同じ vector 形式 (mapZoom * (world + pan) で 2px 線)。 */
    private void renderMapTab(GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        int innerX = px + CONTENT_PAD;
        int innerW = PANEL_W - CONTENT_PAD * 2;
        // 背景
        g.fill(innerX, y + 2, innerX + innerW, y + h - 4, 0xFF0a0a18);

        var groups = TransitTerminalClientCache.allMapGroups();
        var segments = TransitTerminalClientCache.mapSegments();

        // データ無い場合の早期 return + 初期 fit
        if (segments.isEmpty() && groups.isEmpty()) {
            String msg = Component.translatable("tsu.transit_terminal.map_empty").getString();
            int mw = this.font.width(msg);
            g.drawString(this.font, msg, innerX + (innerW - mw) / 2, y + h / 2 - 4, 0xFF80808F, false);
            return;
        }

        // 初回 fit: pan = -centerPos, zoom は range に合わせて自動。
        // mapZoom == 0 を「未初期化フラグ」として使う。
        if (TransitTerminalState.mapZoomD() <= 0.0001) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (var grp : groups) {
                double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
                double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                if (cz < minZ) minZ = cz; if (cz > maxZ) maxZ = cz;
            }
            for (int[] s : segments) {
                if (s[0] < minX) minX = s[0]; if (s[0] > maxX) maxX = s[0];
                if (s[2] < minX) minX = s[2]; if (s[2] > maxX) maxX = s[2];
                if (s[1] < minZ) minZ = s[1]; if (s[1] > maxZ) maxZ = s[1];
                if (s[3] < minZ) minZ = s[3]; if (s[3] > maxZ) maxZ = s[3];
            }
            if (minX != Double.MAX_VALUE) {
                double cx = (minX + maxX) / 2.0;
                double cz = (minZ + maxZ) / 2.0;
                double rangeX = Math.max(20, (maxX - minX) + 20);
                double rangeZ = Math.max(20, (maxZ - minZ) + 20);
                double zoom = Math.min((innerW - 8) / rangeX, (h - 24) / rangeZ);
                zoom = Math.max(0.05, Math.min(5.0, zoom));
                TransitTerminalState.setMapZoomD(zoom);
                TransitTerminalState.setMapPan(-cx, -cz);
            }
        }

        double mapZoom = TransitTerminalState.mapZoomD();
        double mapPanX = TransitTerminalState.mapPanXD();
        double mapPanZ = TransitTerminalState.mapPanZD();
        double centerSX = innerX + innerW / 2.0;
        double centerSY = y + h / 2.0;

        // Scissor: パネル領域内にクリップ
        var msc = g.pose().last().pose();
        float mscX = msc.m00(), mscY = msc.m11();
        int mscTx = (int) msc.m30(), mscTy = (int) msc.m31();
        g.enableScissor((int) (innerX * mscX) + mscTx, (int) ((y + 2) * mscY) + mscTy,
                        (int) ((innerX + innerW) * mscX) + mscTx, (int) ((y + h - 4) * mscY) + mscTy);

        // 線路セグメント (vector)
        var vc = belugalab.mcss3.draw.VectorRenderer.getGuiBuffer(g.bufferSource());
        var matrix = g.pose().last().pose();
        for (int[] s : segments) {
            float x1 = (float) (centerSX + (s[0] + mapPanX) * mapZoom);
            float y1 = (float) (centerSY + (s[1] + mapPanZ) * mapZoom);
            float x2 = (float) (centerSX + (s[2] + mapPanX) * mapZoom);
            float y2 = (float) (centerSY + (s[3] + mapPanZ) * mapZoom);
            belugalab.mcss3.draw.VectorRenderer.drawLine(vc, matrix, x1, y1, x2, y2, 0xFF6688AA, 2.0f);
        }
        g.bufferSource().endBatch();

        // 列車のリアルタイム位置 (オレンジドット、線速度方向に短い線も)
        var trainPositions = TransitTerminalClientCache.trainPositions();
        if (!trainPositions.isEmpty()) {
            for (var pos : trainPositions.values()) {
                int tx = (int) (centerSX + (pos.x() + mapPanX) * mapZoom);
                int tz = (int) (centerSY + (pos.z() + mapPanZ) * mapZoom);
                g.fill(tx - 3, tz - 3, tx + 4, tz + 4, 0xFF000000);
                g.fill(tx - 2, tz - 2, tx + 3, tz + 3, 0xFFFF9800);
            }
        }

        // プレイヤー位置 (黄色ドット)
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            int dotX = (int) (centerSX + (mc.player.getX() + mapPanX) * mapZoom);
            int dotZ = (int) (centerSY + (mc.player.getZ() + mapPanZ) * mapZoom);
            g.fill(dotX - 3, dotZ - 3, dotX + 4, dotZ + 4, 0xFF000000);
            g.fill(dotX - 2, dotZ - 2, dotX + 3, dotZ + 3, 0xFFFFD54F);
        }

        // 駅ドット (ホバーで駅名)
        StringBuilder hoverName = null;
        int hoverX = 0, hoverY = 0;
        for (var grp : groups) {
            double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
            double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
            int dx = (int) (centerSX + (cx + mapPanX) * mapZoom);
            int dz = (int) (centerSY + (cz + mapPanZ) * mapZoom);
            g.fill(dx - 3, dz - 3, dx + 4, dz + 4, 0xFF1a1a2e);
            g.fill(dx - 2, dz - 2, dx + 3, dz + 3, 0xFF4FC3F7);
            if (mapZoom > 0.5) {
                g.drawString(this.font, truncate(grp.name(), 60), dx + 5, dz - 4, 0xFF4fc3f7, true);
            }
            if (mouseX >= dx - 4 && mouseX <= dx + 4 && mouseY >= dz - 4 && mouseY <= dz + 4) {
                hoverName = new StringBuilder(grp.name());
                hoverX = dx; hoverY = dz;
            }
        }

        g.disableScissor();

        // Hover tooltip (scissor 外でも見えるよう scissor 終了後)
        if (hoverName != null) {
            String n = truncate(hoverName.toString(), 100);
            int w = this.font.width(n);
            g.fill(hoverX + 4, hoverY - 6, hoverX + 8 + w, hoverY + 4, 0xFF000000);
            g.drawString(this.font, n, hoverX + 6, hoverY - 4, 0xFFFFFFFF, false);
        }

        // ズーム表示 + ヒント
        String zoom = String.format("x%.2f", mapZoom);
        g.drawString(this.font, zoom, innerX + 4, y + h - 14, 0xFF80808F, false);
        if (!segments.isEmpty()) {
            String segInfo = segments.size() + " seg";
            int sw = this.font.width(segInfo);
            g.drawString(this.font, segInfo, innerX + innerW - sw - 4, y + h - 14, 0xFF80808F, false);
        }
        g.drawString(this.font, Component.translatable("tsu.transit_terminal.map_hint").getString(),
                innerX + 4, y + 4, 0xFF606080, false);
    }

    // -------- SETTINGS tab --------
    private void renderSettingsTab(GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        int innerX = px + CONTENT_PAD;
        int innerW = PANEL_W - CONTENT_PAD * 2;
        int rowY = y + 8;
        int rowH = 18;

        rowY = renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_clock24",
                TransitTerminalState.clock24h());
        rowY += 4;
        rowY = renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_walk_gate",
                TransitTerminalState.walkGateEnabled());
        rowY += 4;
        rowY = renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_layout_adjust",
                TransitTerminalState.layoutAdjustMode());
        rowY += 4;
        rowY = renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_show_hud",
                TransitTerminalState.showDetailHud());

        rowY += 8;
        // 位置リセットボタン
        boolean rstHover = mouseX >= innerX && mouseX < innerX + innerW
                && mouseY >= rowY && mouseY < rowY + 14;
        g.fill(innerX, rowY, innerX + innerW, rowY + 14, 0xFF333344);
        g.fill(innerX + 1, rowY + 1, innerX + innerW - 1, rowY + 13, rstHover ? 0xFF1f3a50 : 0xFF111928);
        String rstLabel = Component.translatable("tsu.transit_terminal.setting_reset_layout").getString();
        int rstW = this.font.width(rstLabel);
        g.drawString(this.font, rstLabel, innerX + (innerW - rstW) / 2, rowY + 3, 0xFFAAAAAA, false);
        rowY += 18;

        g.drawString(this.font, Component.translatable("tsu.transit_terminal.setting_about").getString(),
                innerX, rowY, 0xFF80808F, false);
    }

    /**
     * 鉄道管理ブロック / 管理用コンピュータの hint-toggle / monitor-toggle と同じ寸法+配色。
     * track 24×12 px (6px 角丸)、knob 8×8 px (4px 角丸)、knob は track 内で OFF=2px / ON=14px。
     * アニメーション: 状態変化時に knob 位置を 150ms で lerp。
     */
    private int renderSettingRow(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY,
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
            case TOP -> { return mouseClickedTop(mouseX, mouseY, button); }
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

    private boolean mouseClickedTop(double mouseX, double mouseY, int button) {
        int contentY = py + HEADER_H + 2;
        int innerX = px + CONTENT_PAD;
        int innerW = PANEL_W - CONTENT_PAD * 2;
        int boxY = contentY + 8;

        // Swap ボタン
        int swapX = innerX + innerW - 16;
        int swapY = boxY + 7;
        if (mouseX >= swapX && mouseX < swapX + 14 && mouseY >= swapY && mouseY < swapY + 22) {
            TransitTerminalState.swapFromTo();
            rebuildEditBoxes();
            return true;
        }

        // 検索ボタン
        int btnY = boxY + 38;
        int btnH = 16;
        if (mouseX >= innerX && mouseX < innerX + innerW && mouseY >= btnY && mouseY < btnY + btnH) {
            TransitTerminalState.onSearchSubmit();
            return true;
        }

        var r = TransitTerminalState.lastResult();
        int resY = btnY + btnH + 6;
        // 結果クリアボタンのクリック判定 (結果あり時のみ)
        if (r != null) {
            String clearLabel = "✕ " + Component.translatable("tsu.transit_terminal.clear_results").getString();
            int clW = this.font.width(clearLabel);
            if (mouseX >= innerX + innerW - clW - 6 && mouseX < innerX + innerW
                    && mouseY >= resY - 1 && mouseY < resY + 11) {
                TransitTerminalState.clearResults();
                return true;
            }
            resY += 14;
        }

        // ソート/ルートタブ (結果あり時のみ)
        if (r != null && r.found()) {
            int tabsH = 14;
            if (mouseY >= resY && mouseY < resY + tabsH) {
                var routes = TransitTerminalState.lastResults();
                int n = Math.min(3, routes.size());
                if (n >= 2) {
                    // ルート候補タブ
                    int cellW = innerW / n;
                    int relX = (int)(mouseX - innerX);
                    if (relX >= 0 && relX < cellW * n) {
                        int idx = relX / cellW;
                        TransitTerminalState.setSelectedRouteIdx(idx);
                        return true;
                    }
                } else {
                    // 早/楽/安タブ
                    int cellW = innerW / 3;
                    int relX = (int)(mouseX - innerX);
                    if (relX >= 0 && relX < cellW * 3) {
                        int idx = relX / cellW;
                        TransitTerminalState.setSortMode(idx);
                        return true;
                    }
                }
            }
            resY += tabsH + 4;
        }

        // 結果がある場合
        if (r != null && r.found()) {
            if (TransitTerminalState.expandedLegIdx() >= 0) {
                // 詳細画面: 戻るボタン
                if (mouseX >= innerX && mouseX < innerX + 40 && mouseY >= resY && mouseY < resY + 12) {
                    TransitTerminalState.setExpandedLegIdx(-1);
                    return true;
                }
                // HUD 展開ボタン / Nav ボタン (横並び、render と同じ計算)
                String hudLabel = TransitTerminalState.showDetailHud()
                        ? "🪟 " + Component.translatable("tsu.transit_terminal.hud_hide").getString()
                        : "🪟 " + Component.translatable("tsu.transit_terminal.hud_show").getString();
                String navLabel = TransitNavClientState.active()
                        ? "🧭 " + Component.translatable("tsu.transit_terminal.nav_cancel").getString()
                        : "🧭 " + Component.translatable("tsu.transit_terminal.nav_start").getString();
                int hudW = this.font.width(hudLabel);
                int navW = this.font.width(navLabel);
                int btnGap = 8;
                int hudBoxX = innerX + innerW - hudW - 6;
                int navBoxX = hudBoxX - navW - 6 - btnGap;
                // HUD クリック
                if (mouseX >= hudBoxX && mouseX < hudBoxX + hudW + 6
                        && mouseY >= resY - 1 && mouseY < resY + 10) {
                    TransitTerminalState.setShowDetailHud(!TransitTerminalState.showDetailHud());
                    if (TransitTerminalState.showDetailHud()) {
                        TransitTerminalState.setHudRouteIdx(TransitTerminalState.selectedRouteIdx());
                    }
                    return true;
                }
                // Nav クリック
                if (mouseX >= navBoxX && mouseX < navBoxX + navW + 6
                        && mouseY >= resY - 1 && mouseY < resY + 10) {
                    if (TransitNavClientState.active()) {
                        TransitNavClientState.clear();
                    } else if (TransitNavClientState.isPending()) {
                        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                                "[NavPath] click ignored (request still pending)");
                    } else {
                        var legs = r.trainLegs();
                        UUID target = legs.isEmpty() ? r.fromGroupId() : legs.get(0).fromGroupId();
                        int platform = legs.isEmpty() ? 0 : legs.get(0).boardPlatform();
                        // チェイン: 第 1 leg の出発駅 (= 今からナビ) 以降、各乗換駅を queue へ
                        TransitNavClientState.clearChain();
                        for (int i = 1; i < legs.size(); i++) {
                            var leg = legs.get(i);
                            TransitNavClientState.enqueueChain(
                                    new TransitNavClientState.ChainedTarget(
                                            leg.fromGroupId(), leg.boardPlatform(), ""));
                        }
                        // 最終目的地 (最後の leg の到着駅) も queue 末尾に追加
                        if (!legs.isEmpty()) {
                            var lastLeg = legs.get(legs.size() - 1);
                            TransitNavClientState.enqueueChain(
                                    new TransitNavClientState.ChainedTarget(
                                            lastLeg.toGroupId(), lastLeg.alightPlatform(), ""));
                        }
                        TransitNavClientState.markPending();
                        TransitNavClientState.setRequestedPlatform(platform);
                        com.trainsystemutilities.network.NavPathRequestPayload.send(target, platform);
                        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                                "[NavPath] sent request, target={} platform={} chain={}",
                                target, platform, TransitNavClientState.chainSize());
                    }
                    return true;
                }
            } else {
                // タイル一覧 (新レイアウト: header 22 + 路線色帯 8 + 4 + tile 44+2)
                int rowY = resY + 22 + 4;
                if (r.walkToFrom() != null && r.walkToFrom().approxTicks() > 0) rowY += 12;
                rowY += 8 + 4; // 路線色バー
                int rowH = 44;
                List<TrainRouter.Leg> legs = r.trainLegs();
                for (int i = 0; i < legs.size(); i++) {
                    if (mouseX >= innerX && mouseX < innerX + innerW && mouseY >= rowY && mouseY < rowY + rowH) {
                        TransitTerminalState.setExpandedLegIdx(i);
                        return true;
                    }
                    rowY += rowH + 2;
                }
            }
        } else {
            // 履歴クリック (検索前)
            var history = TransitTerminalState.history();
            if (!history.isEmpty()) {
                // ヘッダ「全消去」ボタン
                String clearAll = Component.translatable("tsu.transit_terminal.history_clear_all").getString();
                int caW = this.font.width(clearAll);
                if (mouseX >= innerX + innerW - caW - 4 && mouseX < innerX + innerW
                        && mouseY >= resY - 1 && mouseY < resY + 9) {
                    TransitTerminalState.clearHistory();
                    return true;
                }
                int rowY = resY + 12;
                int rowH = 22;
                int delBtnW = 20;
                for (int i = 0; i < history.size(); i++) {
                    // 削除 (×) ボタン
                    int delX = innerX + innerW - delBtnW;
                    if (mouseX >= delX && mouseX < delX + delBtnW
                            && mouseY >= rowY && mouseY < rowY + rowH) {
                        TransitTerminalState.removeHistoryAt(i);
                        return true;
                    }
                    // 行本体クリック
                    if (mouseX >= innerX && mouseX < innerX + innerW - delBtnW - 2
                            && mouseY >= rowY && mouseY < rowY + rowH) {
                        var e = history.get(i);
                        fromCtrl.setValue(e.fromName());   // onChange が setFromQuery
                        toCtrl.setValue(e.toName());       // onChange が setToQuery
                        TransitTerminalState.onSearchSubmit();
                        return true;
                    }
                    rowY += rowH + 2;
                }
            }
        }
        return false;
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

    private static String truncate(String s, int maxWidth) {
        return belugalab.tsu.api.HudText.ellipsize(Minecraft.getInstance().font, s, maxWidth);
    }

    private static StationGroup findGroup(UUID id) {
        if (id == null) return null;
        for (var g : StationGroupClientCache.all()) {
            if (g.id().equals(id)) return g;
        }
        return null;
    }

    private static String nameOf(UUID id) {
        var g = findGroup(id);
        return g != null ? g.name() : (id == null ? "?" : id.toString().substring(0, 6));
    }

    private static String stationSymbol(StationGroup g) {
        // StationGroup には路線記号文字 + 数字が個別に保存されている (取得 API は別 cache)。
        // 簡易表示として name に手動付与された記号があれば使う。なければ空文字。
        if (g == null) return "";
        return "";
    }

    private void drawWrapped(GuiGraphics g, String text, int x, int y, int w, int color) {
        for (var line : this.font.split(Component.literal(text), w)) {
            g.drawString(this.font, line, x, y, color, false);
            y += 10;
        }
    }

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
    private int scheduleFilteredCount() {
        return TransitTerminalState.scheduleFilteredRowCount();
    }

    /** SCHEDULE tab canvas h=256, row h=22+2 gap → 256/24 ≈ 10 行可視。 */
    private static final int SCHEDULE_ROW_H = 22;
    private static final int SCHEDULE_ROW_STRIDE = SCHEDULE_ROW_H + 2;
    private static final int SCHEDULE_ROWS_VISIBLE = 256 / SCHEDULE_ROW_STRIDE;

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if (key == null) return defaultValue;
        // SETTINGS knob x position: BelugaExperience 標準 ToggleSwitchController.KNOB_TRAVEL_PX
        int travel = ToggleSwitchController.KNOB_TRAVEL_PX;
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
        var groups = TransitTerminalClientCache.allMapGroups();
        var segments = TransitTerminalClientCache.mapSegments();
        // データなし
        if (segments.isEmpty() && groups.isEmpty()) {
            String msg = Component.translatable("tsu.transit_terminal.map_empty").getString();
            int mw = this.font.width(msg);
            g.drawString(this.font, msg, x + (w - mw) / 2, y + h / 2 - 4, 0xFF80808F, false);
            return;
        }
        // 初回 fit
        if (TransitTerminalState.mapZoomD() <= 0.0001) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (var grp : groups) {
                double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
                double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                if (cz < minZ) minZ = cz; if (cz > maxZ) maxZ = cz;
            }
            for (int[] s : segments) {
                if (s[0] < minX) minX = s[0]; if (s[0] > maxX) maxX = s[0];
                if (s[2] < minX) minX = s[2]; if (s[2] > maxX) maxX = s[2];
                if (s[1] < minZ) minZ = s[1]; if (s[1] > maxZ) maxZ = s[1];
                if (s[3] < minZ) minZ = s[3]; if (s[3] > maxZ) maxZ = s[3];
            }
            if (minX != Double.MAX_VALUE) {
                double cx = (minX + maxX) / 2.0;
                double cz = (minZ + maxZ) / 2.0;
                double rangeX = Math.max(20, (maxX - minX) + 20);
                double rangeZ = Math.max(20, (maxZ - minZ) + 20);
                double zoom = Math.min((w - 8) / rangeX, (h - 24) / rangeZ);
                zoom = Math.max(0.05, Math.min(5.0, zoom));
                TransitTerminalState.setMapZoomD(zoom);
                TransitTerminalState.setMapPan(-cx, -cz);
            }
        }
        double mapZoom = TransitTerminalState.mapZoomD();
        double mapPanX = TransitTerminalState.mapPanXD();
        double mapPanZ = TransitTerminalState.mapPanZD();
        double centerSX = x + w / 2.0;
        double centerSY = y + h / 2.0;
        // bg
        g.fill(x, y, x + w, y + h, 0xFF0a0a18);
        // scissor は screen 座標で渡す必要あり (pose translate を加算)
        var sm = g.pose().last().pose();
        float smX = sm.m00(), smY = sm.m11();
        int sx = (int) sm.m30();
        int sy = (int) sm.m31();
        g.flush();
        g.enableScissor((int) (x * smX) + sx, (int) (y * smY) + sy,
                        (int) ((x + w) * smX) + sx, (int) ((y + h) * smY) + sy);
        // 線路セグメント
        var vc = belugalab.mcss3.draw.VectorRenderer.getGuiBuffer(g.bufferSource());
        var matrix = g.pose().last().pose();
        for (int[] s : segments) {
            float x1 = (float) (centerSX + (s[0] + mapPanX) * mapZoom);
            float y1 = (float) (centerSY + (s[1] + mapPanZ) * mapZoom);
            float x2 = (float) (centerSX + (s[2] + mapPanX) * mapZoom);
            float y2 = (float) (centerSY + (s[3] + mapPanZ) * mapZoom);
            belugalab.mcss3.draw.VectorRenderer.drawLine(vc, matrix, x1, y1, x2, y2, 0xFF6688AA, 2.0f);
        }
        g.bufferSource().endBatch();
        // 列車
        var trainPositions = TransitTerminalClientCache.trainPositions();
        for (var pos : trainPositions.values()) {
            int tx = (int) (centerSX + (pos.x() + mapPanX) * mapZoom);
            int tz = (int) (centerSY + (pos.z() + mapPanZ) * mapZoom);
            g.fill(tx - 3, tz - 3, tx + 4, tz + 4, 0xFF000000);
            g.fill(tx - 2, tz - 2, tx + 3, tz + 3, 0xFFFF9800);
        }
        // プレイヤー
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            int dotX = (int) (centerSX + (mc.player.getX() + mapPanX) * mapZoom);
            int dotZ = (int) (centerSY + (mc.player.getZ() + mapPanZ) * mapZoom);
            g.fill(dotX - 3, dotZ - 3, dotX + 4, dotZ + 4, 0xFF000000);
            g.fill(dotX - 2, dotZ - 2, dotX + 3, dotZ + 3, 0xFFFFD54F);
        }
        // 駅 + ホバー
        StringBuilder hoverName = null;
        int hoverX = 0, hoverY = 0;
        for (var grp : groups) {
            double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
            double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
            int dx = (int) (centerSX + (cx + mapPanX) * mapZoom);
            int dz = (int) (centerSY + (cz + mapPanZ) * mapZoom);
            g.fill(dx - 3, dz - 3, dx + 4, dz + 4, 0xFF1a1a2e);
            g.fill(dx - 2, dz - 2, dx + 3, dz + 3, 0xFF4FC3F7);
            if (mapZoom > 0.5) {
                g.drawString(this.font, truncate(grp.name(), 60), dx + 5, dz - 4, 0xFF4fc3f7, true);
            }
            if (mouseX >= dx - 4 && mouseX <= dx + 4 && mouseY >= dz - 4 && mouseY <= dz + 4) {
                hoverName = new StringBuilder(grp.name());
                hoverX = dx; hoverY = dz;
            }
        }
        g.flush();
        g.disableScissor();
        // hover tooltip
        if (hoverName != null) {
            String n = truncate(hoverName.toString(), 100);
            int tw = this.font.width(n);
            g.fill(hoverX + 4, hoverY - 6, hoverX + 8 + tw, hoverY + 4, 0xFF000000);
            g.drawString(this.font, n, hoverX + 6, hoverY - 4, 0xFFFFFFFF, false);
        }
    }

    /** SCHEDULE tab の列車リスト canvas drawer。 */
    private void drawScheduleListCanvas(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        var snapshots = TransitTerminalClientCache.allSchedules();
        if (snapshots.isEmpty()) {
            g.drawString(this.font, Component.translatable("tsu.transit_terminal.schedule_empty").getString(),
                    x, y + 4, 0xFF80808F, false);
            g.drawString(this.font, Component.translatable("tsu.transit_terminal.schedule_hint").getString(),
                    x, y + 16, 0xFF606080, false);
            return;
        }
        String key = TransitTerminalState.scheduleQuery().toLowerCase(java.util.Locale.ROOT);
        int filteredCount = scheduleFilteredCount();
        int maxScroll = Math.max(0, filteredCount - SCHEDULE_ROWS_VISIBLE);
        int scrollY = Math.min(TransitTerminalState.scheduleScrollY(), maxScroll);
        if (scrollY != TransitTerminalState.scheduleScrollY()) {
            TransitTerminalState.setScheduleScrollY(scrollY);
        }
        int listInnerW = w;  // canvas full width (scrollbar は隣接 canvas)
        int idx = 0, drawn = 0;
        for (var snap : snapshots.values()) {
            if (!key.isEmpty() && !snap.trainName().toLowerCase(java.util.Locale.ROOT).contains(key)) continue;
            if (idx++ < scrollY) continue;
            if (drawn >= SCHEDULE_ROWS_VISIBLE) break;
            int rowY = y + drawn * SCHEDULE_ROW_STRIDE;
            g.fill(x, rowY, x + listInnerW, rowY + SCHEDULE_ROW_H, 0xFF111928);
            g.fill(x, rowY, x + listInnerW, rowY + 1, 0xFF2a4a60);
            g.drawString(this.font, "🚆 " + truncate(snap.trainName(), listInnerW - 16),
                    x + 2, rowY + 2, 0xFFFFFFFF, false);
            String next = snap.nextGroupId() == null ? "—" : nameOf(snap.nextGroupId());
            int eta = snap.etaTicksToNext() / 20;
            String etaText = Component.translatable("tsu.transit_terminal.eta_fmt", eta).getString();
            g.drawString(this.font, "→ " + truncate(next, listInnerW - 60),
                    x + 2, rowY + 12, 0xFF80DEEA, false);
            int ew = this.font.width(etaText);
            g.drawString(this.font, etaText, x + listInnerW - ew - 2, rowY + 12, 0xFFFFD54F, false);
            drawn++;
        }
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
            String clearLabel = "✕ " + Component.translatable("tsu.transit_terminal.clear_results").getString();
            int clW = this.font.width(clearLabel);
            boolean clHover = mouseX >= x + w - clW - 6 && mouseX < x + w
                    && mouseY >= resY - 1 && mouseY < resY + 11;
            g.fill(x + w - clW - 6, resY - 1, x + w, resY + 11,
                    clHover ? 0xFFAA1F1F : 0xFF333344);
            g.drawString(this.font, clearLabel, x + w - clW - 3, resY,
                    clHover ? 0xFFFFFFFF : 0xFFAAAAAA, false);
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
                    mouseClickedTop(mouseX + px, mouseY + py, button);
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
