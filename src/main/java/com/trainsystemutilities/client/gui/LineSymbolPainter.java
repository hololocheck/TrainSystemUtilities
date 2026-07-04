package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.blockentity.LineSymbol;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 路線記号バッジを canvas 上に描画する共通ペインター。
 * 角丸白背景 + 縁色枠 + letters / number を 2 行 + 中央 divider line。
 *
 * <p>MgmtComputer の sym-edit-preview / sym-tile-badge / station-row-badge /
 * station-detail-badge / assign-item-badge と RM の header-sym (本来は) で
 * 共通の描画を行う。
 *
 * <p>呼び出し側は {@link belugalab.mcss3.screen.JsonLayoutHandler#drawCanvas} 内から
 * {@code LineSymbolPainter.draw(g, x, y, size, sym, font)} の 1 行で使う。
 */
public final class LineSymbolPainter {
    private LineSymbolPainter() {}

    /**
     * 路線記号 1 つを (x, y) を左上に size×size px で描画。
     *
     * @param g  GUI graphics
     * @param x  描画左上 X (canvas-local)
     * @param y  描画左上 Y (canvas-local)
     * @param size 一辺の長さ (推奨 14〜36 px)
     * @param sym 描画対象の路線記号
     * @param font テキスト描画用 (caller の {@code this.font})
     */
    public static void draw(GuiGraphics g, int x, int y, int size, LineSymbol sym, Font font) {
        // ロジックは MCSS の生値版 (belugalab.experience.render.LineSymbolPainter) に集約。
        // ここは LineSymbol 型を受ける TSU 向け薄いラッパー。
        belugalab.experience.render.LineSymbolPainter.draw(g, x, y, size,
                sym.getBorderColor(), sym.getBorderRadius(),
                sym.getLetters(), sym.getNumberStr(), font);
    }
}
