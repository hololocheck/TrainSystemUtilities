package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutEngine;
import com.trainsystemutilities.blockentity.LineSymbol;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 路線記号エディタ popup の state + dynamic text + wheel + click を保持する controller。
 * {@code ManagementComputerScreenV2} から切り出し (god-class deep #2、最大の popup)。
 *
 * <p>編集中の 8 値と preset 色配列を所有する。HSV color picker は別 popup
 * ({@code showColorPicker}) のまま screen 側に残り、確定色は {@link #setColorCustom} で書き戻す。
 * save / delete / color-picker を開く操作は screen helper 結合ゆえ screen 側で処理し、
 * 本 controller の accessor を参照する。custom 色パレット ({@code customColors}) は
 * HSV picker と共有のため screen が {@link Supplier} で渡す。
 */
public final class SymbolEditorController {

    public static final String[] SYMBOL_COLOR_PRESETS = {
            "#9acd32", "#00b2e5", "#e21f26", "#f15a22", "#00a651", "#8b4513",
            "#ffc107", "#ab47bc", "#4fc3f7", "#ff69b4", "#555555", "#333333",
    };

    private boolean open = false;
    private int index = -1; // -1 = new, 0+ = existing
    private String letters = "JA";
    private int number = 1;
    private String color = "#4fc3f7";
    private String name = "";
    private int radius = 12;
    private int colorIdx = 0;

    public boolean isOpen() { return open; }
    /** 編集値を保持したまま表示だけ立てる (wiki capture 用、openNew/openExisting と区別)。 */
    public void open() { open = true; }
    public void close() { open = false; }

    public int getIndex() { return index; }
    public String getColor() { return color; }
    public int getColorIdx() { return colorIdx; }

    public void openNew() {
        index = -1;
        letters = "JA";
        number = 1;
        color = SYMBOL_COLOR_PRESETS[0];
        colorIdx = 0;
        name = "";
        radius = 12;
        open = true;
    }

    public void openExisting(int idx, LineSymbol s) {
        index = idx;
        letters = s.getLetters();
        if (letters.length() < 2) letters = (letters + "AA").substring(0, 2);
        number = s.getNumber();
        color = s.getBorderColor();
        colorIdx = -1;
        for (int i = 0; i < SYMBOL_COLOR_PRESETS.length; i++) {
            if (SYMBOL_COLOR_PRESETS[i].equalsIgnoreCase(color)) { colorIdx = i; break; }
        }
        if (colorIdx < 0) colorIdx = 0;
        name = s.getName();
        radius = s.getBorderRadius();
        open = true;
    }

    /** HSV picker / custom chip から色を上書き (preset 以外なので colorIdx = -1)。 */
    public void setColorCustom(String hex) {
        color = hex;
        colorIdx = -1;
    }

    /** 現在の編集内容から LineSymbol を生成 (preview / save 用)。 */
    public LineSymbol buildSymbol() {
        return new LineSymbol(letters, number, color, name, radius);
    }

    /** sym-edit-* の表示テキスト。該当 class でなければ null (= 他 dynamic text へ fall through)。 */
    public String resolveText(String[] classes) {
        for (String c : classes) {
            switch (c) {
                case "sym-edit-title":
                    return Component.translatable(index < 0 ? "tsu.mc.sym_create" : "tsu.mc.sym_edit_title").getString();
                case "sym-edit-letter1":
                    return String.valueOf(letters.charAt(0));
                case "sym-edit-letter2":
                    return String.valueOf(letters.charAt(1));
                case "sym-edit-number":
                    return String.format("%02d", number);
                case "sym-edit-radius":
                    return radius + "px";
                case "sym-edit-color":
                    return color;
                case "sym-edit-delete":
                    // Hide the delete button when creating new
                    return index < 0 ? "" : Component.translatable("tsu.mc.sym_delete").getString();
            }
        }
        return null;
    }

    /** sym-edit-* の wheel 編集 (wheel-up = increase)。処理したら true。 */
    public boolean handleWheel(String key, double scrollY) {
        int upDelta = scrollY > 0 ? 1 : -1;
        switch (key) {
            case "sym-edit-letter1" -> {
                char c1 = letters.charAt(0);
                c1 = (char) ((c1 - 'A' + upDelta + 26) % 26 + 'A');
                letters = "" + c1 + letters.charAt(1);
                return true;
            }
            case "sym-edit-letter2" -> {
                char c2 = letters.charAt(1);
                c2 = (char) ((c2 - 'A' + upDelta + 26) % 26 + 'A');
                letters = "" + letters.charAt(0) + c2;
                return true;
            }
            case "sym-edit-number" -> {
                number = (number + upDelta + 100) % 100;
                return true;
            }
            case "sym-edit-radius" -> {
                radius = Math.max(5, Math.min(25, radius + upDelta));
                return true;
            }
            case "sym-edit-color" -> {
                colorIdx = (colorIdx + upDelta + SYMBOL_COLOR_PRESETS.length) % SYMBOL_COLOR_PRESETS.length;
                color = SYMBOL_COLOR_PRESETS[colorIdx];
                return true;
            }
        }
        return false;
    }

    /**
     * close / cancel / chip(preset 色)/ custom-chip(custom 色) の click を処理。処理したら true。
     * save / delete / cp-btn は screen helper 結合ゆえ screen 側で処理する。
     */
    public boolean handleClick(String[] classes, Supplier<List<String>> customColors) {
        for (String c : classes) {
            if ("sym-edit-close".equals(c) || "sym-edit-cancel".equals(c) || "mc-popup-close".equals(c)) {
                close();
                return true;
            }
            if ("sym-edit-chip".equals(c)) {
                int idx = JsonLayoutEngine.currentRepeatIndex();
                if (idx >= 0 && idx < SYMBOL_COLOR_PRESETS.length) {
                    colorIdx = idx;
                    color = SYMBOL_COLOR_PRESETS[idx];
                }
                return true;
            }
            if ("sym-edit-custom-chip".equals(c)) {
                int idx = JsonLayoutEngine.currentRepeatIndex();
                List<String> cc = customColors.get();
                if (idx >= 0 && idx < cc.size()) {
                    color = cc.get(idx);
                    colorIdx = -1;  // preset 以外
                }
                return true;
            }
        }
        return false;
    }
}
