package com.trainsystemutilities.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.UUID;

/**
 * 電化詳細 popup の車両リスト (車体 + パンタアイコン + トグル) を描画する renderer。
 * god-class 分割 (v2) で {@link ManagementComputerScreenV2} から切り出した。
 * パンタ click hit-box ({@link #pantoHits()}) を自前保持し、 screen は drawCanvas("ed-car-list")
 * で {@link #draw} を呼んで populate した後、 onElementClick で hit-test に使う。
 * トグル knob / パンタアイコンの補間キャッシュも保持する。
 */
public final class ElectrificationCarListRenderer {

    /** 1 車両行の高さ。 */
    private static final int ED_ROW_H = 56;
    /** 車両ボディ (= 細い長方形) の幅 (= ライン小さく)。 */
    private static final int ED_BODY_W = 140;
    /** 車両ボディの高さ。 */
    private static final int ED_BODY_H = 10;
    /** トグルスイッチ寸法 (= hint-toggle と同じ 24x12)。 */
    private static final int ED_TOGGLE_W = 24;
    private static final int ED_TOGGLE_H = 12;
    private static final int ED_TOGGLE_KNOB = 8;
    /** ヒット領域マージン。 */
    private static final int ED_PANTO_HIT_R = 10;
    /** 縦区切り線の X 位置 (左セクション幅)。 */
    private static final int ED_LEFT_SECTION_W = 175;

    /** トグル knob X の補間用キャッシュ (= popup-relative pos → 現在 X)。 */
    private final java.util.Map<net.minecraft.core.BlockPos, Float> edToggleKnobX = new java.util.HashMap<>();
    /** パンタアイコン T の補間用キャッシュ (= popup-relative pos → 現在 T 0..1)。 */
    private final java.util.Map<net.minecraft.core.BlockPos, Float> edPantoIconT = new java.util.HashMap<>();

    /** draw で更新、 screen の onElementClick で参照される click hit-box キャッシュ。 */
    private final java.util.List<EdPantoHit> edPantoHits = new java.util.ArrayList<>();
    public record EdPantoHit(int x0, int y0, int x1, int y1,
                             int carriageIndex, net.minecraft.core.BlockPos pos) {}

    /** drawCanvas("ed-car-list") から呼ばれる毎フレーム描画。 helper が参照する font をここで保持。 */
    private Font font;

    /** drawCanvas("ed-car-list") の描画本体。 screen は this.font / selectedTrainId を引数で渡す。 */
    public void draw(GuiGraphics g, int x, int y, int w, int h, Font font, UUID selectedTrainId) {
        this.font = font;
        edPantoHits.clear();
        if (selectedTrainId == null) return;
        var v = com.trainsystemutilities.client.electrification
                .ClientTrainElectrificationCache.get(selectedTrainId);
        if (v == null) {
            g.drawString(this.font, net.minecraft.network.chat.Component.translatable("tsu.mc.not_synced_starting"),
                    x + 6, y + 6, 0xFFEF5350, false);
            return;
        }
        if (v.cars.isEmpty()) {
            g.drawString(this.font, net.minecraft.network.chat.Component.translatable("tsu.mc.no_cars"), x + 6, y + 6, 0xFFAAAAAA, false);
            return;
        }
        int rowY = y;
        for (var car : v.cars) {
            drawEdCarRow(g, x, rowY, w, ED_ROW_H - 2, car);
            rowY += ED_ROW_H;
            if (rowY + ED_ROW_H > y + h) break;
        }
    }

    /** パンタ click hit-box (draw で populate)。 screen の hit-test が参照する。 */
    public java.util.List<EdPantoHit> pantoHits() { return edPantoHits; }

    private void drawEdCarRow(GuiGraphics g, int x, int y, int w, int h,
                               com.trainsystemutilities.electrification.contraption.TrainElectrificationView.Car car) {
        boolean powered = car.hasInverter && car.storedEnergy > 0;

        // === 行背景 (電力ありなら濃く、エッジに発光感) ===
        int bg = car.hasInverter ? 0x402A4060 : 0x40404040;
        if (powered) bg = 0x602A6A8E; // 電力アクティブ時は濃いめのシアン背景
        g.fill(x, y, x + w, y + h, bg);

        // === 上段: ラベル + FE 残量 (1行に収めて長い数字も収まる) ===
        String label = "Car #" + car.carriageIndex;
        int labelColor = car.hasInverter ? 0xFF80DEEA : 0xFFAAAAAA;
        g.drawString(this.font, label, x + 6, y + 4, labelColor, false);
        if (car.hasInverter) {
            String feText = String.format("%,d / %,d FE", car.storedEnergy, car.capacity);
            int feColor = car.inContact ? 0xFFA5D6A7 : 0xFFFFD54F;
            g.drawString(this.font, feText, x + 52, y + 4, feColor, false);
        } else {
            g.drawString(this.font, net.minecraft.network.chat.Component.translatable("tsu.mc.not_electrified"), x + 52, y + 4, 0xFF888888, false);
        }

        // === 縦区切り線 ===
        int dividerX = x + ED_LEFT_SECTION_W;
        int dividerColor = powered ? 0xFF4FC3F7 : 0xFF555555;
        g.fill(dividerX, y + 4, dividerX + 1, y + h - 4, dividerColor);

        // === 左セクション: 車両ボディ + パンタアイコン (FE テキストと重ならないよう下に配置) ===
        int bodyTop = y + 32;                 // FE テキストの下、十分離す
        int bodyX = x + 12;
        int bodyW = ED_BODY_W;
        drawEdCarBody(g, bodyX, bodyTop, bodyW, ED_BODY_H, car, powered);

        // パンタアイコンは車体の上 (= bodyTop の上方向に伸びる)
        int pantoCount = car.pantographs.size();
        if (pantoCount > 0) {
            int pantoStride = bodyW / (pantoCount + 1);
            for (int i = 0; i < pantoCount; i++) {
                var p = car.pantographs.get(i);
                int pcx = bodyX + pantoStride * (i + 1);
                // === パンタ T を滑らかに補間 ===
                float targetT = p.deployed ? 1f : 0f;
                Float prev = edPantoIconT.get(p.pos);
                float curT = prev == null ? targetT : prev;
                if (Math.abs(curT - targetT) < 0.005f) curT = targetT;
                else curT += (targetT - curT) * 0.20f;
                edPantoIconT.put(p.pos, curT);

                drawEdPantographKuShape(g, pcx, bodyTop, curT, p.inContact);

                // パンタ click hit-box
                edPantoHits.add(new EdPantoHit(
                        pcx - ED_PANTO_HIT_R, bodyTop - 16,
                        pcx + ED_PANTO_HIT_R, bodyTop + 2,
                        car.carriageIndex, p.pos));
            }
        }

        // === 右セクション: 「パンタ N」ラベル + トグルスイッチ ===
        int rightX = dividerX + 8;
        int rightTopY = y + 16; // 上段テキスト下
        if (pantoCount == 0) {
            g.drawString(this.font, net.minecraft.network.chat.Component.translatable("tsu.mc.no_pantograph"), rightX, rightTopY, 0xFF888888, false);
        } else {
            int rowStride = 14;
            for (int i = 0; i < pantoCount; i++) {
                var p = car.pantographs.get(i);
                int ry = rightTopY + i * rowStride;
                // ラベル
                String lbl = net.minecraft.network.chat.Component.translatable("tsu.mc.pantograph_n", i + 1).getString();
                int lblColor = p.deployed ? 0xFFE0F7FA : 0xFFAAAAAA;
                g.drawString(this.font, lbl, rightX, ry + 2, lblColor, false);
                // トグル
                int tx = rightX + 40;
                int ty = ry;
                drawEdToggle(g, tx, ty, p.deployed, p.pos);
                edPantoHits.add(new EdPantoHit(
                        tx, ty, tx + ED_TOGGLE_W, ty + ED_TOGGLE_H,
                        car.carriageIndex, p.pos));
            }
        }
    }

    /** 車体: 短めの長方形 + 車輪 4 個。電力供給中 (powered) は枠を太く描く。 */
    private void drawEdCarBody(GuiGraphics g, int x, int y, int w, int h,
                                com.trainsystemutilities.electrification.contraption.TrainElectrificationView.Car car,
                                boolean powered) {
        int color = car.hasInverter ? 0xFF80DEEA : 0xFF888888;
        int top = y;
        int bot = y + h;
        int thick = powered ? 2 : 1;  // 電力ありは太線
        // 枠 (powered なら 2 px 太さ)
        g.fill(x, top, x + w, top + thick, color);
        g.fill(x, bot - thick + 1, x + w, bot + 1, color);
        g.fill(x, top, x + thick, bot + 1, color);
        g.fill(x + w - thick, top, x + w, bot + 1, color);
        // 車輪 4 個 (車体下)
        int wheelY = bot + 3;
        int wheelR = 2;
        int[] wheelXs = {x + w / 6, x + w / 3, x + 2 * w / 3, x + 5 * w / 6};
        for (int wx : wheelXs) drawEdWheel(g, wx, wheelY, wheelR, color, car.hasInverter);
    }

    /** 車輪。 */
    private static void drawEdWheel(GuiGraphics g, int cx, int cy, int r, int color, boolean filled) {
        if (filled) {
            g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, color);
            g.fill(cx, cy, cx + 1, cy + 1, 0xFF000000);
        } else {
            g.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, color);
            g.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, color);
            g.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, color);
            g.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, color);
        }
    }

    /** く字パンタ (シングルアーム) — T (0=折畳, 1=展開) で滑らかに補間。
     *  全アーム/集電バー/ベース円を MCSS の AA 付きベクター描画 (drawLine / fillRoundedRect /
     *  fillCircle) で構成 → サブピクセル精度で滑らかに表示、ガビガビなし。 */
    private static void drawEdPantographKuShape(GuiGraphics g, int cx, int bodyTopY, float T, boolean inContact) {
        int gray = 0xFF888888;
        int active = inContact ? 0xFFA5D6A7 : 0xFF4FC3F7;
        int color = lerpColor(gray, active, T);

        // === マウント基部 (細い丸キャップの台座) ===
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(
                g, cx - 3.5f, bodyTopY - 1.5f, 7f, 2.5f, 1.25f, color);

        // === 関節位置と集電バー位置を T で滑らかに補間 ===
        float jointX = cx + (-5f * T);
        float jointY = bodyTopY + (-8f * T);
        float barY   = bodyTopY + (-13f * T);

        // === 下アーム (細い棒) ===
        belugalab.mcss3.draw.SmoothRenderer.drawLine(
                g, cx, bodyTopY - 1f, jointX, jointY, 1.6f, color);
        // === 上アーム (細い棒) ===
        belugalab.mcss3.draw.SmoothRenderer.drawLine(
                g, jointX, jointY, cx, barY + 1f, 1.6f, color);
        // === 関節 (= ピボットの円) ===
        belugalab.mcss3.draw.SmoothRenderer.fillCircle(g, jointX, jointY, 1.2f, color);

        // === 集電バー (= 上端の水平バー、ベクター矩形) ===
        float barLen = 13f * (0.4f + 0.6f * T); // 折畳寄りでは短い
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(
                g, cx - barLen * 0.5f, barY, barLen, 2.2f, 1.1f, color);

        // === 接触スパーク (展開ほぼ完了時のみ) ===
        if (inContact && T > 0.8f) {
            int spark = 0xFFFFEB3B;
            belugalab.mcss3.draw.SmoothRenderer.fillCircle(g, cx, barY - 2f, 1.5f, spark);
            belugalab.mcss3.draw.SmoothRenderer.drawLine(
                    g, cx - 1.5f, barY - 3.5f, cx + 1.5f, barY - 0.5f, 0.8f, spark);
        }
    }

    /** ARGB 色を t (0..1) で線形補間。 */
    private static int lerpColor(int c0, int c1, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a0 = (c0 >>> 24) & 0xFF, r0 = (c0 >>> 16) & 0xFF, g0 = (c0 >>> 8) & 0xFF, b0 = c0 & 0xFF;
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int a = (int) (a0 + (a1 - a0) * t);
        int r = (int) (r0 + (r1 - r0) * t);
        int gg = (int) (g0 + (g1 - g0) * t);
        int b = (int) (b0 + (b1 - b0) * t);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    /** トグルスイッチ — MCSS の AA 付き fillRoundedRect で **真の角丸カプセル** + 円形 knob を描画。
     *  ヒントトグル (= JSON の hint-toggle) と完全に同じ視覚スタイル。 */
    private void drawEdToggle(GuiGraphics g, int x, int y, boolean on, net.minecraft.core.BlockPos key) {
        int trackBg = belugalab.tsu.api.ToggleColors.trackBg(on);
        int knobBg  = belugalab.tsu.api.ToggleColors.knobBg(on);

        // === トラック (= カプセル形、radius = h/2) ===
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(
                g, x, y, ED_TOGGLE_W, ED_TOGGLE_H, ED_TOGGLE_H * 0.5f, trackBg);

        // === knob X 補間 (= ぬるぬる) ===
        float targetKnobX = on ? 12f : 0f;
        Float cur = edToggleKnobX.get(key);
        float curKnobX = cur == null ? targetKnobX : cur;
        if (Math.abs(curKnobX - targetKnobX) < 0.05f) {
            curKnobX = targetKnobX;
        } else {
            curKnobX += (targetKnobX - curKnobX) * 0.30f;
        }
        edToggleKnobX.put(key, curKnobX);

        // === knob (= 円形、AA 付き) ===
        float knobCenterX = x + 2 + curKnobX + ED_TOGGLE_KNOB * 0.5f;
        float knobCenterY = y + ED_TOGGLE_H * 0.5f;
        belugalab.mcss3.draw.SmoothRenderer.fillCircle(
                g, knobCenterX, knobCenterY, ED_TOGGLE_KNOB * 0.5f, knobBg);
    }
}
