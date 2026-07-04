package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.blockentity.LineSymbol;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * 路線記号の削除確認 popup の state + dynamic text + click を保持する controller。
 * {@code ManagementComputerScreenV2} から切り出し (god-class deep #2、monitorColorPopup と同方針)。
 *
 * <p>記号データの供給と実削除は screen が {@link Supplier} / {@link IntConsumer} で渡す
 * (controller は screen の field を直接参照せず、挙動は抽出元と一致)。
 */
public final class SymbolDeleteController {

    private boolean open = false;
    private int index = -1;

    public boolean isOpen() { return open; }

    public void open(int symbolIndex) { this.open = true; this.index = symbolIndex; }

    public void close() { this.open = false; this.index = -1; }

    /**
     * "sym-del-target" の表示テキスト (記号名)。{@code symbols} は screen が供給する。
     * 該当 class でなければ null を返し、screen 側の他 dynamic text 解決へ fall through させる。
     */
    public String resolveText(String[] classes, Supplier<List<LineSymbol>> symbols) {
        for (String c : classes) {
            if ("sym-del-target".equals(c)) {
                List<LineSymbol> syms = symbols.get();
                if (index >= 0 && index < syms.size()) {
                    LineSymbol s = syms.get(index);
                    return s.getLetters() + " " + s.getNumberStr()
                            + (s.getName().isEmpty() ? "" : " " + s.getName());
                }
                return "?";
            }
        }
        return null;
    }

    /**
     * click 処理。confirm → {@code onConfirm.accept(index)} 実行後に close、
     * cancel / close → close。処理したら true (= 呼び元で return)。
     */
    public boolean handleClick(String[] classes, IntConsumer onConfirm) {
        for (String c : classes) {
            if ("sym-del-confirm".equals(c)) {
                onConfirm.accept(index);
                close();
                return true;
            }
            if ("sym-del-cancel".equals(c) || "sym-del-close".equals(c) || "mc-popup-close".equals(c)) {
                close();
                return true;
            }
        }
        return false;
    }
}
