package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.blockentity.LineSymbol;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 駅への路線記号 assign dropdown の state + dynamic text + click を保持する controller。
 * {@code ManagementComputerScreenV2} から切り出し (god-class deep #2、symbolDelete と同方針)。
 *
 * <p>記号一覧は screen が {@link Supplier} で供給し、実 assign は {@link AssignAction} で渡す
 * (controller は screen の field を直接参照しない)。dropdown の overlay 座標
 * (assignBtnScreenX/Y) は配置責務ゆえ screen 側に残す。
 */
public final class StationAssignController {

    /** assign 実行コールバック。{@code symbol} が null なら「記号なし」を割り当てる。 */
    @FunctionalInterface
    public interface AssignAction {
        void assign(String stationName, BlockPos pos, LineSymbol symbol);
    }

    private boolean open = false;
    private String name = "";
    private BlockPos pos = null;

    public boolean isOpen() { return open; }

    public void open(String stationName, BlockPos stationPos) {
        this.open = true;
        this.name = stationName;
        this.pos = stationPos;
    }

    public void close() { this.open = false; }

    /** "assign-title" の表示テキスト (駅名、未設定なら既定名)。該当 class でなければ null。 */
    public String resolveTitleText(String[] classes) {
        for (String c : classes) {
            if ("assign-title".equals(c)) {
                String shown = name.isEmpty()
                        ? Component.translatable("tsu.mc.station_default").getString()
                        : name;
                return Component.translatable("tsu.mc.station_with_name_fmt", shown).getString();
            }
        }
        return null;
    }

    /**
     * "assign-item-name" / "assign-item" の表示テキスト (repeat index {@code ri} の記号ラベル)。
     * 呼び元で {@code ri >= 0} を保証する。該当 class または範囲外なら null。
     */
    public String resolveItemText(String[] classes, int ri, Supplier<List<LineSymbol>> symbols) {
        for (String c : classes) {
            if ("assign-item-name".equals(c) || "assign-item".equals(c)) {
                List<LineSymbol> syms = symbols.get();
                if (ri < syms.size()) {
                    LineSymbol sym = syms.get(ri);
                    String label = sym.getLetters() + sym.getNumberStr();
                    if (!sym.getName().isEmpty()) label += " " + sym.getName();
                    return label;
                }
            }
        }
        return null;
    }

    /**
     * click 処理。"assign-item-none" → 記号なしを assign、"assign-item(-badge/-name)" →
     * repeat index {@code ri} の記号を assign。いずれも処理後に close。処理したら true。
     */
    public boolean handleClick(String[] classes, int ri, Supplier<List<LineSymbol>> symbols, AssignAction onAssign) {
        for (String c : classes) {
            if ("assign-item-none".equals(c)) {
                onAssign.assign(name, pos, null);
                close();
                return true;
            }
            if (("assign-item".equals(c) || "assign-item-badge".equals(c)
                    || "assign-item-name".equals(c)) && ri >= 0) {
                List<LineSymbol> syms = symbols.get();
                if (ri < syms.size()) {
                    onAssign.assign(name, pos, syms.get(ri));
                }
                close();
                return true;
            }
        }
        return false;
    }
}
