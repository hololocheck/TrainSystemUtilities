package com.trainsystemutilities.client.gui;

import belugalab.experience.controller.ScrollViewport;
import belugalab.experience.controller.TextInputController;
import belugalab.experience.render.TextCaretRenderer;
import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.mcss3.screen.JsonLayoutEngine;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 時刻表エディタ popup (entries の追加/並べ替え/削除 + 条件追加/削除 + cyclic 切替) の
 * state + dynamic 値 + wheel + click + body 描画を保持する controller。
 * {@code ManagementComputerScreenV2} から切り出し (god-class 分割 v2、4 つ目の抽出)。
 *
 * <p><b>v2 刷新 (時刻表 UI):</b> 表示名称は Create 純正の翻訳キー
 * ({@code create.schedule.instruction.*} / {@code create.schedule.condition.*} /
 * {@code create.gui.schedule.*}) を直接参照し、純正と「一字一句同じ」を保証する。
 * body 描画は {@link SmoothRenderer} の角丸二層 + 色分けミニボタン + hover で
 * アナウンス設定 UI と同系統の見た目に統一。throttle 値 / redstone on-off は
 * hover+wheel / クリックで編集、rename タイトルは inline text input で編集する。
 * 連結 (coupling) / 切離 (decoupling) は純正に無い TSU 拡張として残置 (TSU 名称)。
 */
public final class ScheduleEditorController {

    /** 編集中の 1 条件 (cyclic でない単純な値モデル)。screen の NBT 化から read される。 */
    public static final class EditCondData {
        public String type; public int value; public int timeUnit;
        public EditCondData(String type, int value, int timeUnit) { this.type = type; this.value = value; this.timeUnit = timeUnit; }
    }

    /** 編集中の 1 エントリ。screen の openScheduleEditor が construct、applyScheduleEdit が read する。 */
    public static final class EditEntryData {
        public String type; public String text; public int value;
        public List<EditCondData> conditions;
        public EditEntryData(String type, String text, int value, List<EditCondData> conds) {
            this.type = type; this.text = text; this.value = value; this.conditions = conds;
        }
    }

    private boolean showScheduleEditor = false;
    private final List<EditEntryData> editEntries = new ArrayList<>();
    private boolean editCyclic = true;
    private static final int EDIT_MAX_VISIBLE = 6;
    /** スケジュールエディタの entry scroll (= §4.19 ScrollViewport)。 */
    private final ScrollViewport editScroll = new ScrollViewport(() -> editEntries.size(), EDIT_MAX_VISIBLE);
    private boolean showAddEntryDropdown = false;
    private int showAddConditionForEntry = -1;
    private boolean showStationDropdown = false;
    private String stationDropdownType = "destination";
    /** 駅選択 dropdown の候補 (= 既設駅を除いた snapshot)。 開いた時点で確定。 */
    private final List<String> stationPickList = new ArrayList<>();
    private static final int STATION_VISIBLE = 8;
    private static final int STATION_TRACK_H = STATION_VISIBLE * 13;
    private static final int STATION_THUMB_H = 18;
    private final ScrollViewport stationScroll = new ScrollViewport(() -> stationPickList.size(), STATION_VISIBLE);

    /** rename タイトルの inline 編集対象 entry index (-1 = 非編集)。 */
    private int renameFocusEntry = -1;
    private final TextInputController renameInput = new TextInputController(48, "");

    // Per-canvas hit areas (recomputed each frame in drawBody),
    // dispatched in handleClick("sched-edit-body") after JSON routes the click here.
    private final List<int[]> entryUpBtns = new ArrayList<>();   // [x,y,w,h,idx]
    private final List<int[]> entryDownBtns = new ArrayList<>();
    private final List<int[]> entryDelBtns = new ArrayList<>();
    private final List<int[]> entryAddCondBtns = new ArrayList<>();
    private final List<int[]> entryCondDelBtns = new ArrayList<>(); // [x,y,w,h,entryIdx,condIdx]
    private final List<int[]> entryCondHoverBounds = new ArrayList<>(); // [x,y,w,h,entryIdx,condIdx]
    private final List<int[]> entryValueHoverBounds = new ArrayList<>(); // [x,y,w,h,entryIdx] (throttle wheel)
    private final List<int[]> entryRenameBounds = new ArrayList<>();      // [x,y,w,h,entryIdx] (rename click)

    /** 命令 (動作) type → Create 純正の翻訳キー。 連結/切離は純正に無いため TSU キー。 */
    private static final String[][] ADD_ENTRY_TYPES = {
            {"destination", "create.schedule.instruction.destination"},      // 駅へ移動
            {"deliver",     "create.schedule.instruction.package_delivery"}, // 小包を配送
            {"fetch",       "create.schedule.instruction.package_retrieval"},// 小包を回収
            {"rename",      "create.schedule.instruction.rename"},           // 時刻表名の変更
            {"throttle",    "create.schedule.instruction.throttle"},         // 最大速度を制限する
    };
    /** 条件 type → Create 純正の翻訳キー。 末尾 2 件は TSU 拡張。 */
    private static final String[][] ADD_COND_TYPES = {
            {"delay",            "create.schedule.condition.delay"},           // 待機する
            {"time_of_day",      "create.schedule.condition.time_of_day"},     // 指定時刻
            {"passenger",        "create.schedule.condition.player_count"},    // 座っているプレイヤー数
            {"idle",             "create.schedule.condition.idle"},            // 貨物のやりとりが停止しているなら
            {"redstone_link",    "create.schedule.condition.redstone_link"},   // レッドストーンリンク
            {"powered",          "create.schedule.condition.powered"},         // 駅が赤石信号を受けたら
            {"unloaded",         "create.schedule.condition.unloaded"},        // チャンクロードが解除されたら
            {"item_threshold",   "create.schedule.condition.item_threshold"},  // アイテム貨物の状態
            {"fluid_threshold",  "create.schedule.condition.fluid_threshold"}, // 液体貨物の状態
            {"coupling",         "tsu.mc.sched_cond_coupling"},                // 連結 (TSU 拡張)
            {"decoupling",       "tsu.mc.sched_cond_decoupling"},              // 切離 (TSU 拡張)
    };

    /** 「+ 条件追加」ボタンクリック時に記録する screen 座標。
     *  sub-dropdown を直下に配置するため screen の overlayDefaultPosition2 で参照。 */
    private int addCondBtnScreenX = 0;
    private int addCondBtnScreenY = 0;

    /** drawBody で更新、helper が参照する font をここで保持。 */
    private Font font;

    // === 色 palette (アナウンス設定 UI と同系統。 host が cyan のため cyan 基調) ===
    private static final int ROW_BG        = 0xFF1c2735;
    private static final int ROW_BG_HOVER  = 0xFF24344a;
    private static final int ROW_BORDER    = 0xFF35506a;
    private static final int COND_BG       = 0xFF141b26;
    private static final int COND_BORDER   = 0xFF2a3a4e;
    private static final int BTN_CYAN      = 0xFF1a3a4e;
    private static final int BTN_CYAN_HV   = 0xFF2a5a7e;
    private static final int BTN_RED       = 0xFF5e1e1e;
    private static final int BTN_RED_HV    = 0xFF7e2e2e;
    private static final int TEXT_MAIN     = 0xFFe0f7fa;
    private static final int TEXT_SUB      = 0xFFaaccdd;
    private static final int TEXT_VALUE    = 0xFFffcc02;
    private static final int TEXT_GREEN    = 0xFF80ffaa;
    private static final int TEXT_CYAN     = 0xFF80deea;
    private static final int TEXT_RED      = 0xFFff8888;
    private static final int TEXT_DIM      = 0xFF667788;

    /** Lang リソースから 1 つの翻訳キーを解決するヘルパ。 */
    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** 既に時刻表に設定済みの駅 (destination/deliver/fetch の text) を除いた駅名一覧。 */
    private List<String> filteredStationNames(Supplier<List<String>> stationNames) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (EditEntryData e : editEntries) {
            if (("destination".equals(e.type) || "deliver".equals(e.type) || "fetch".equals(e.type))
                    && e.text != null && !e.text.isEmpty()) {
                used.add(e.text);
            }
        }
        List<String> result = new ArrayList<>();
        for (String n : stationNames.get()) if (!used.contains(n)) result.add(n);
        return result;
    }

    // === 1:1 フラグ accessor (screen 配線が機械的な token swap になるよう、test 順序を保つ) ===
    public boolean isOpen() { return showScheduleEditor; }
    public boolean isAddEntryOpen() { return showAddEntryDropdown; }
    public int addCondForEntry() { return showAddConditionForEntry; }
    public boolean isStationDropdownOpen() { return showStationDropdown; }
    public String stationDropdownType() { return stationDropdownType; }

    // === sub-dropdown 配置用の screen 座標 (overlayDefaultPosition2 が参照) ===
    public int addCondBtnScreenX() { return addCondBtnScreenX; }
    public int addCondBtnScreenY() { return addCondBtnScreenY; }

    // === screen の server IO 用モデル access ===
    /** editor を開く: entries/cyclic を受け取り、 sub-popup + editScroll をリセットする。 */
    public void open(List<EditEntryData> entries, boolean cyclic) {
        editEntries.clear();
        editEntries.addAll(entries);
        editCyclic = cyclic;
        editScroll.setOffset(0);
        showAddEntryDropdown = false;
        showAddConditionForEntry = -1;
        showStationDropdown = false;
        renameFocusEntry = -1;
        showScheduleEditor = true;
    }

    public List<EditEntryData> getEntries() { return editEntries; }
    public boolean isCyclic() { return editCyclic; }

    /** apply/close 後のフルリセット (applyScheduleEdit の close シーケンスを踏襲)。 */
    public void close() {
        showScheduleEditor = false;
        editEntries.clear();
        showAddEntryDropdown = false;
        showAddConditionForEntry = -1;
        showStationDropdown = false;
        renameFocusEntry = -1;
    }

    // === Dynamic 値 resolver (該当しなければ null/sentinel で screen 側 fall through) ===

    /** sched-edit-* / sched-*-item の表示テキスト。該当 class でなければ null。 */
    public String resolveText(String[] classes, int ri, Supplier<List<String>> stationNames, Font font) {
        // Schedule editor cyclic toggle (Create: 無限ループ)
        if (showScheduleEditor) {
            for (String c : classes) {
                if ("sched-edit-cyclic".equals(c))
                    return editCyclic ? "↻ " + tr("create.schedule.loop") : tr("tsu.mc.sched_cyclic_oneway");
            }
        }
        // Schedule editor sub-dropdowns
        if (showScheduleEditor && showAddEntryDropdown && ri >= 0 && ri < ADD_ENTRY_TYPES.length) {
            for (String c : classes) {
                if ("sched-add-entry-item".equals(c)) return tr(ADD_ENTRY_TYPES[ri][1]);
            }
        }
        if (showScheduleEditor && showStationDropdown && ri >= 0) {
            for (String c : classes) {
                if ("sched-station-pick-item".equals(c)) {
                    int realIdx = ri + stationScroll.offset();
                    if (realIdx < stationPickList.size()) {
                        String n = stationPickList.get(realIdx);
                        int maxW = 106;
                        if (font.width(n) <= maxW) return n;
                        while (n.length() > 0 && font.width(n + "…") > maxW)
                            n = n.substring(0, n.length() - 1);
                        return n + "…";
                    }
                    return "";
                }
            }
        }
        if (showScheduleEditor && showAddConditionForEntry >= 0
                && ri >= 0 && ri < ADD_COND_TYPES.length) {
            for (String c : classes) {
                if ("sched-add-cond-item".equals(c)) return tr(ADD_COND_TYPES[ri][1]);
            }
        }
        return null;
    }

    /** sched-* の dynamic number。該当 key でなければ null。 */
    public Integer resolveNumber(String key, Supplier<List<String>> stationNames) {
        if ("sched-add-entry-count".equals(key)) return ADD_ENTRY_TYPES.length;
        if ("sched-add-cond-count".equals(key)) return ADD_COND_TYPES.length;
        if ("sched-station-pick-count".equals(key)) return stationScroll.rowCount();
        if ("sched-station-pick-h".equals(key)) return 22 + Math.max(1, Math.min(stationPickList.size(), STATION_VISIBLE)) * 13;
        return null;
    }

    /** sched-* の dynamic bool。該当 key でなければ null。 */
    public Boolean resolveBool(String key, Supplier<List<String>> stationNames) {
        // dropdown 表示中は下段ボタン (適用/キャンセル) を gate して hit-test 横取りを防ぐ
        if ("sched-edit-bottom-visible".equals(key)) {
            return showScheduleEditor && !showAddEntryDropdown && !showStationDropdown;
        }
        if ("sched-add-entry-inline-visible".equals(key)) {
            return showScheduleEditor && showAddEntryDropdown;
        }
        if ("sched-station-pick-empty".equals(key)) return stationPickList.isEmpty();
        if ("sched-station-scrollbar-visible".equals(key)) return stationScroll.needsScrollbar();
        return null;
    }

    /** sched-edit-* の dynamic color。該当 key でなければ null。 */
    public Integer resolveColor(String key) {
        if ("sched-edit-cyclic-bg".equals(key)) return editCyclic ? 0xFF1e4e2e : 0xFF3e2e1a;
        if ("sched-edit-cyclic-color".equals(key)) return editCyclic ? 0xFF80ffaa : 0xFFffcc02;
        if ("sched-edit-cyclic-border".equals(key)) return editCyclic ? 0xFF66cc66 : 0xFFffcc02;
        return null;
    }

    // === 駅選択 dropdown scroll ===
    public boolean handleStationWheel(int delta) { stationScroll.scroll(delta); return true; }
    public int stationThumbY(int defaultY) { return stationScroll.thumbY(defaultY, STATION_TRACK_H - 2, STATION_THUMB_H); }
    public int stationThumbH() { return STATION_THUMB_H; }

    // === rename inline text input (screen.charTyped / keyPressed から呼ぶ) ===

    /** rename 編集中なら文字入力を消費して true。 */
    public boolean charTyped(char c) {
        if (renameFocusEntry < 0 || renameFocusEntry >= editEntries.size()) return false;
        if (renameInput.charTyped(c)) {
            editEntries.get(renameFocusEntry).text = renameInput.value();
            return true;
        }
        return false;
    }

    /** rename 編集中ならキーを消費して true。 ENTER/ESC で確定 (focus を外す)。 */
    public boolean keyPressed(int keyCode) {
        if (renameFocusEntry < 0 || renameFocusEntry >= editEntries.size()) return false;
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            renameFocusEntry = -1;
            return true;
        }
        if (renameInput.keyPressed(keyCode)) {
            editEntries.get(renameFocusEntry).text = renameInput.value();
        }
        return true; // focus 中は他キーも消費
    }

    // === wheel / click / esc / transient ===

    /** "sched-edit-body-scroll" の wheel。hover 中の編集可能な値を wheel-edit、
     *  そうでなければ entry list を scroll。処理したら true。 */
    public boolean handleWheel(int mouseX, int mouseY, int delta) {
        // Hovered editable condition → wheel-edit the value
        for (int[] b : entryCondHoverBounds) {
            if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
                int eIdx = b[4], cIdx = b[5];
                if (eIdx >= 0 && eIdx < editEntries.size()) {
                    var e = editEntries.get(eIdx);
                    if (cIdx >= 0 && cIdx < e.conditions.size()) {
                        var cond = e.conditions.get(cIdx);
                        int dlt = -delta; // wheel up should increase
                        switch (cond.type) {
                            case "delay", "idle", "passenger", "item_threshold", "fluid_threshold"
                                    -> cond.value = Math.max(0, cond.value + dlt);
                            case "time_of_day" -> cond.value = (cond.value + dlt + 24) % 24;
                        }
                    }
                }
                return true;
            }
        }
        // Hovered throttle entry value → wheel-edit (0..100, 5% step)
        for (int[] b : entryValueHoverBounds) {
            if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
                int eIdx = b[4];
                if (eIdx >= 0 && eIdx < editEntries.size()) {
                    var e = editEntries.get(eIdx);
                    e.value = Math.max(0, Math.min(100, e.value + (-delta) * 5));
                }
                return true;
            }
        }
        // Otherwise scroll the entry list
        editScroll.scroll(delta);
        return true;
    }

    /**
     * 時刻表エディタ全域の click を処理。sub-dropdown 3 ブロック → frame ブロックの順に評価し、
     * 消費したら true、エディタが閉じている / 何も該当しなければ false (= main GUI へ fall through)。
     */
    public boolean handleClick(String[] classes, int mouseX, int mouseY, int overlayX, int overlayY,
                               Supplier<List<String>> stationNames, Runnable onApply, Consumer<String> clearAnim) {
        // === Schedule editor sub-dropdowns (top of overlay chain) ===
        if (showScheduleEditor && showAddEntryDropdown) {
            int rii = JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("sched-add-entry-item".equals(c) && rii >= 0 && rii < ADD_ENTRY_TYPES.length) {
                    String type = ADD_ENTRY_TYPES[rii][0];
                    switch (type) {
                        case "destination", "deliver", "fetch" -> {
                            stationDropdownType = type;
                            showAddEntryDropdown = false;
                            stationPickList.clear();
                            stationPickList.addAll(filteredStationNames(stationNames));
                            stationScroll.setOffset(0);
                            showStationDropdown = true;
                        }
                        case "rename" -> {
                            editEntries.add(new EditEntryData("rename", tr("tsu.mc.rename_default_text"), 0, new ArrayList<>()));
                            showAddEntryDropdown = false;
                        }
                        case "throttle" -> {
                            editEntries.add(new EditEntryData("throttle", "", 100, new ArrayList<>()));
                            showAddEntryDropdown = false;
                        }
                    }
                    return true;
                }
            }
        }
        if (showScheduleEditor && showStationDropdown) {
            int rii = JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("sched-station-pick-item".equals(c) && rii >= 0) {
                    int realIdx = rii + stationScroll.offset();
                    if (realIdx < stationPickList.size()) {
                        editEntries.add(new EditEntryData(stationDropdownType, stationPickList.get(realIdx), 0, new ArrayList<>()));
                    }
                    showStationDropdown = false;
                    return true;
                }
            }
        }
        if (showScheduleEditor && showAddConditionForEntry >= 0) {
            int rii = JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("sched-add-cond-item".equals(c) && rii >= 0 && rii < ADD_COND_TYPES.length) {
                    String ct = ADD_COND_TYPES[rii][0];
                    int defVal = switch (ct) {
                        case "delay", "idle" -> 5;
                        case "passenger" -> 1;
                        case "time_of_day" -> 6;
                        case "item_threshold", "fluid_threshold" -> 1;
                        default -> 0;
                    };
                    int defUnit = "delay".equals(ct) || "idle".equals(ct) ? 1 : 0;
                    int eIdx = showAddConditionForEntry;
                    if (eIdx >= 0 && eIdx < editEntries.size()) {
                        editEntries.get(eIdx).conditions.add(new EditCondData(ct, defVal, defUnit));
                    }
                    showAddConditionForEntry = -1;
                    return true;
                }
            }
        }
        // === Schedule editor frame ===
        if (showScheduleEditor) {
            for (String c : classes) {
                if ("sched-edit-close".equals(c) || "mc-popup-close".equals(c)) {
                    showScheduleEditor = false; editEntries.clear(); renameFocusEntry = -1; return true;
                }
                if ("sched-edit-cancel".equals(c)) {
                    showScheduleEditor = false; editEntries.clear(); renameFocusEntry = -1; return true;
                }
                if ("sched-edit-apply".equals(c)) { onApply.run(); return true; }
                if ("sched-edit-cyclic".equals(c)) { editCyclic = !editCyclic; return true; }
                if ("sched-edit-add-entry".equals(c)) {
                    // overlay2 (management-computer-sched-add-entry.json) として開く。
                    // インライン配置だと背後の canvas (sched-edit-body) に hit-test を横取りされる。
                    showAddEntryDropdown = !showAddEntryDropdown;
                    return true;
                }
                if ("sched-edit-body".equals(c)) {
                    // クリックでまず rename focus を解除 (rename text を直押しした場合のみ再設定)
                    renameFocusEntry = -1;
                    // rename タイトルの inline 編集 focus
                    for (int[] b : entryRenameBounds) {
                        if (hit(mouseX, mouseY, b)) {
                            int idx = b[4];
                            if (idx >= 0 && idx < editEntries.size()) {
                                renameFocusEntry = idx;
                                renameInput.setValue(editEntries.get(idx).text);
                            }
                            return true;
                        }
                    }
                    for (int[] b : entryUpBtns) {
                        if (hit(mouseX, mouseY, b)) {
                            int idx = b[4];
                            if (idx > 0 && idx < editEntries.size()) {
                                var e = editEntries.remove(idx); editEntries.add(idx - 1, e);
                            }
                            return true;
                        }
                    }
                    for (int[] b : entryDownBtns) {
                        if (hit(mouseX, mouseY, b)) {
                            int idx = b[4];
                            if (idx >= 0 && idx < editEntries.size() - 1) {
                                var e = editEntries.remove(idx); editEntries.add(idx + 1, e);
                            }
                            return true;
                        }
                    }
                    for (int[] b : entryDelBtns) {
                        if (hit(mouseX, mouseY, b)) {
                            int idx = b[4];
                            if (idx >= 0 && idx < editEntries.size()) editEntries.remove(idx);
                            showAddConditionForEntry = -1;
                            return true;
                        }
                    }
                    for (int[] b : entryCondDelBtns) {
                        if (hit(mouseX, mouseY, b)) {
                            int eIdx = b[4], cIdx = b[5];
                            if (eIdx >= 0 && eIdx < editEntries.size()) {
                                var e = editEntries.get(eIdx);
                                if (cIdx >= 0 && cIdx < e.conditions.size()) e.conditions.remove(cIdx);
                            }
                            return true;
                        }
                    }
                    // redstone リンク条件: chip 本体クリックで on/off トグル
                    for (int[] b : entryCondHoverBounds) {
                        if (hit(mouseX, mouseY, b)) {
                            int eIdx = b[4], cIdx = b[5];
                            if (eIdx >= 0 && eIdx < editEntries.size()) {
                                var e = editEntries.get(eIdx);
                                if (cIdx >= 0 && cIdx < e.conditions.size()
                                        && "redstone_link".equals(e.conditions.get(cIdx).type)) {
                                    var cond = e.conditions.get(cIdx);
                                    cond.value = cond.value == 0 ? 1 : 0; // 0=オン / 1=オフ
                                    return true;
                                }
                            }
                        }
                    }
                    for (int[] b : entryAddCondBtns) {
                        if (hit(mouseX, mouseY, b)) {
                            if (showAddConditionForEntry == b[4]) {
                                showAddConditionForEntry = -1;
                            } else {
                                showAddConditionForEntry = b[4];
                                addCondBtnScreenX = overlayX + b[0];
                                addCondBtnScreenY = overlayY + b[1] + b[3];
                            }
                            return true;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hit(int mx, int my, int[] b) {
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    /** ESC 1 段階閉じ。sub-dropdown が開いていればそれを 1 つ閉じ、無ければ editor 自体を閉じる。 */
    public boolean handleEscape() {
        if (showScheduleEditor) {
            if (renameFocusEntry >= 0) { renameFocusEntry = -1; return true; }
            if (showAddEntryDropdown) { showAddEntryDropdown = false; return true; }
            if (showStationDropdown) { showStationDropdown = false; return true; }
            if (showAddConditionForEntry >= 0) { showAddConditionForEntry = -1; return true; }
            showScheduleEditor = false; editEntries.clear();
            return true;
        }
        return false;
    }

    /** transient な sub-dropdown のみを 1 つ閉じる (closeTransientOverlays の schedule branch)。 */
    public boolean closeTransientSubPopups() {
        if (showScheduleEditor && showAddEntryDropdown) {
            showAddEntryDropdown = false; return true;
        }
        if (showScheduleEditor && showStationDropdown) {
            showStationDropdown = false; return true;
        }
        if (showScheduleEditor && showAddConditionForEntry >= 0) {
            showAddConditionForEntry = -1; return true;
        }
        return false;
    }

    // === body 描画 (drawCanvas("sched-edit-body") から委譲) ===

    private static boolean isCondWheelEditable(String type) {
        return "delay".equals(type) || "passenger".equals(type) || "idle".equals(type)
                || "time_of_day".equals(type) || "item_threshold".equals(type) || "fluid_threshold".equals(type);
    }

    /** 命令 (動作) 行のラベル = Create 純正名 + フィルタ/値。 */
    private String entryLabel(EditEntryData entry) {
        String name = tr(entryNameKey(entry.type));
        return switch (entry.type) {
            case "destination" -> name + ": " + (entry.text.isEmpty() ? "—" : entry.text);
            case "deliver", "fetch" -> entry.text.isEmpty() ? name : name + ": " + entry.text;
            case "rename" -> name + ": " + entry.text;
            case "throttle" -> name + ": " + entry.value + "% ↕";
            default -> name;
        };
    }

    private static String entryNameKey(String type) {
        for (String[] t : ADD_ENTRY_TYPES) if (t[0].equals(type)) return t[1];
        return "create.schedule.instruction.destination";
    }

    private static String condNameKey(String type) {
        for (String[] t : ADD_COND_TYPES) if (t[0].equals(type)) return t[1];
        return type;
    }

    /** 条件 chip の値部 (= wheel/click で編集する部分のみ)。 値が無い条件は ""。 */
    private String condValueText(EditCondData cond) {
        String timeStr = switch (cond.timeUnit) {
            case 0 -> cond.value + "t";
            case 2 -> Component.translatable("tsu.mc.time_unit_min_fmt", cond.value).getString();
            default -> Component.translatable("tsu.mc.time_unit_sec_fmt", cond.value).getString();
        };
        return switch (cond.type) {
            case "delay", "idle" -> timeStr;
            case "time_of_day" -> cond.value + ":00";
            case "passenger" -> String.valueOf(cond.value);
            case "item_threshold", "fluid_threshold" -> "≥" + cond.value;
            case "redstone_link" -> cond.value == 0 ? "●オン" : "○オフ";
            default -> "";
        };
    }

    /**
     * Schedule-editor body painter (アナウンス設定 UI 同系統): 角丸の動作行 + 条件 chip を
     * 色分けミニボタン付きで描画。click hit-area は instance lists に記録し handleClick が dispatch。
     *
     * <p>canvas の pose は overlay 原点に translate 済 → x/y は popup-local。scissor は SCREEN
     * 座標を要求するので overlayX/Y を受け取り加算する。
     */
    public void drawBody(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY,
                         int overlayX, int overlayY, Font font) {
        this.font = font;
        entryUpBtns.clear(); entryDownBtns.clear(); entryDelBtns.clear();
        entryAddCondBtns.clear(); entryCondDelBtns.clear(); entryCondHoverBounds.clear();
        entryValueHoverBounds.clear(); entryRenameBounds.clear();

        // scissor は pose 行列 (overlayScale 含む) から実 screen rect を算出する。
        // overlayX+x の手動加算は dialogScale 非乗算で高 GUI スケール時に切れる。
        var pm = g.pose().last().pose();
        int sx = Math.round(x * pm.m00() + pm.m30());
        int sy = Math.round(y * pm.m11() + pm.m31());
        g.enableScissor(sx, sy,
                Math.round((x + w) * pm.m00() + pm.m30()),
                Math.round((y + h) * pm.m11() + pm.m31()));

        int rowH = 18;
        int condRowH = 12;
        int total = editEntries.size();
        editScroll.clamp();
        int contentW = editScroll.needsScrollbar() ? w - 7 : w;

        int yy = y;
        if (total == 0) {
            g.drawString(this.font, tr("tsu.mc.entries_empty"), x, yy + 2, TEXT_DIM, false);
            g.disableScissor();
            return;
        }
        if (editScroll.offset() > 0) {
            g.drawString(this.font,
                    Component.translatable("tsu.mc.scroll_up_count_fmt", editScroll.offset()).getString(),
                    x, yy, TEXT_DIM, false);
            yy += 11;
        }

        int showEnd = Math.min(total, editScroll.offset() + EDIT_MAX_VISIBLE);
        for (int i = editScroll.offset(); i < showEnd && yy + rowH <= y + h - 11; i++) {
            var entry = editEntries.get(i);

            // --- 動作行 (角丸二層) ---
            boolean rowHover = mouseX >= x && mouseX < x + contentW && mouseY >= yy && mouseY < yy + rowH;
            roundedRow(g, x, yy, contentW, rowH, rowHover ? ROW_BG_HOVER : ROW_BG, ROW_BORDER);

            // 右端: ▲ ▼ × ミニボタン
            int btnW = 13, btnH = 13, btnY = yy + 2;
            int delX = x + contentW - btnW - 3;
            boolean hDel = inBtn(mouseX, mouseY, delX, btnY, btnW, btnH);
            roundedBtn(g, delX, btnY, btnW, btnH, hDel ? BTN_RED_HV : BTN_RED);
            g.drawString(this.font, "×", delX + 4, btnY + 3, TEXT_RED, false);
            entryDelBtns.add(new int[]{delX, btnY, btnW, btnH, i});

            int downX = delX - btnW - 2;
            if (i < total - 1) {
                boolean hh = inBtn(mouseX, mouseY, downX, btnY, btnW, btnH);
                roundedBtn(g, downX, btnY, btnW, btnH, hh ? BTN_CYAN_HV : BTN_CYAN);
                g.drawString(this.font, "▼", downX + 3, btnY + 3, TEXT_CYAN, false);
                entryDownBtns.add(new int[]{downX, btnY, btnW, btnH, i});
            }
            int upX = downX - btnW - 2;
            if (i > 0) {
                boolean hh = inBtn(mouseX, mouseY, upX, btnY, btnW, btnH);
                roundedBtn(g, upX, btnY, btnW, btnH, hh ? BTN_CYAN_HV : BTN_CYAN);
                g.drawString(this.font, "▲", upX + 3, btnY + 3, TEXT_CYAN, false);
                entryUpBtns.add(new int[]{upX, btnY, btnW, btnH, i});
            }

            // ラベル (右ボタン群の手前まで truncate)
            int labelMaxW = (i > 0 ? upX : (i < total - 1 ? downX : delX)) - (x + 6) - 2;
            String label = entryLabel(entry);
            String labelTrunc = truncate(label, labelMaxW);
            int textColor = "throttle".equals(entry.type) ? TEXT_VALUE : TEXT_MAIN;
            // rename 編集中はハイライト
            if ("rename".equals(entry.type) && renameFocusEntry == i) textColor = TEXT_CYAN;
            g.drawString(this.font, labelTrunc, x + 6, yy + 5, textColor, false);

            // throttle: 値部を wheel-edit hover 対象に
            if ("throttle".equals(entry.type)) {
                entryValueHoverBounds.add(new int[]{x + 4, yy, labelMaxW, rowH, i});
            }
            // rename: text 部を click-focus 対象に + caret
            if ("rename".equals(entry.type)) {
                entryRenameBounds.add(new int[]{x + 4, yy, labelMaxW, rowH, i});
                if (renameFocusEntry == i) {
                    TextCaretRenderer.draw(g, this.font, label, x + 6, yy + 4, labelMaxW + 40, 10, 0xFF4FC3F7);
                }
            }
            yy += rowH + 1;

            // --- 条件 chips ---
            for (int ci = 0; ci < entry.conditions.size() && yy + condRowH <= y + h - 11; ci++) {
                var cond = entry.conditions.get(ci);
                int condX = x + 12;
                int condW = contentW - 12;
                roundedRow(g, condX, yy, condW, condRowH, COND_BG, COND_BORDER);

                String name = tr(condNameKey(cond.type));
                String valueText = condValueText(cond);
                boolean editable = isCondWheelEditable(cond.type);

                // × 削除 (右端、赤)
                int cdW = 11, cdX = condX + condW - cdW - 2, cdY = yy + 1;
                boolean hcd = inBtn(mouseX, mouseY, cdX, cdY, cdW, condRowH - 2);
                roundedBtn(g, cdX, cdY, cdW, condRowH - 2, hcd ? BTN_RED_HV : 0xFF3a1a1a);
                g.drawString(this.font, "×", cdX + 3, cdY + 1, TEXT_RED, false);
                entryCondDelBtns.add(new int[]{cdX, cdY, cdW, condRowH - 2, i, ci});

                if (!valueText.isEmpty()) {
                    // 値ボックス (右寄せ)。 wheel/click 対象は「ここだけ」= 数字の上でのみ変更可。
                    // chip 全体だと一覧スクロール時に誤って値が変わるため。
                    String vt = editable ? valueText + " ↕" : valueText;
                    int vw = this.font.width(vt) + 6;
                    int vx = cdX - vw - 3;
                    boolean vHover = mouseX >= vx && mouseX < vx + vw && mouseY >= yy + 1 && mouseY < yy + condRowH - 1;
                    SmoothRenderer.fillRoundedRect(g, vx, yy + 1, vw, condRowH - 2, 3f, vHover ? 0xFF2a4a5e : 0xFF14202c);
                    g.drawString(this.font, vt, vx + 3, yy + 2, editable ? TEXT_VALUE : TEXT_CYAN, false);
                    entryCondHoverBounds.add(new int[]{vx, yy, vw, condRowH, i, ci});
                    g.drawString(this.font, truncate(name, vx - condX - 8), condX + 5, yy + 2, TEXT_SUB, false);
                } else {
                    // 値なし (powered/unloaded/coupling/decoupling): 名前のみ
                    g.drawString(this.font, truncate(name, condW - cdW - 8), condX + 5, yy + 2, TEXT_DIM, false);
                }
                yy += condRowH + 1;
            }

            // --- 「+ 条件を追加」(駅へ移動 / 小包系のみ。 Create と同じく destination 系に条件付与) ---
            if (("destination".equals(entry.type) || "deliver".equals(entry.type)
                    || "fetch".equals(entry.type)) && yy + condRowH <= y + h - 11) {
                String addLbl = "+ " + tr("create.gui.schedule.add_condition");
                int aw = this.font.width(addLbl) + 8;
                boolean hac = inBtn(mouseX, mouseY, x + 12, yy, aw, condRowH);
                g.drawString(this.font, addLbl, x + 14, yy + 2, hac ? TEXT_GREEN : 0xFF5fae7a, false);
                entryAddCondBtns.add(new int[]{x + 12, yy, aw, condRowH, i});
                yy += condRowH + 2;
            }
            yy += 1;
        }
        if (showEnd < total) {
            g.drawString(this.font,
                    Component.translatable("tsu.mc.scroll_down_count_fmt", total - showEnd).getString(),
                    x, Math.min(yy, y + h - 11), TEXT_DIM, false);
        }
        // スクロールバー (右端)。 editScroll (entry 数ベース) の thumb を描く。
        if (editScroll.needsScrollbar()) {
            int sbX = x + w - 4;
            int trackH = h;
            SmoothRenderer.fillRoundedRect(g, sbX, y, 3, trackH, 1.5f, 0x33000000);
            int thumbH = Math.max(14, trackH * editScroll.visible() / Math.max(1, editScroll.total()));
            int thumbYpos = editScroll.thumbY(y, trackH, thumbH);
            SmoothRenderer.fillRoundedRect(g, sbX, thumbYpos, 3, thumbH, 1.5f, 0xFF4fc3f7);
        }
        g.disableScissor();
    }

    // === 描画ヘルパ (SmoothRenderer 二層 = BelugaExperience R2.4) ===

    private static void roundedRow(GuiGraphics g, int x, int y, int w, int h, int bg, int border) {
        SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, border);
        SmoothRenderer.fillRoundedRect(g, x + 1, y + 1, w - 2, h - 2, 4f, bg);
    }

    private static void roundedBtn(GuiGraphics g, int x, int y, int w, int h, int bg) {
        SmoothRenderer.fillRoundedRect(g, x, y, w, h, 4f, bg);
    }

    private static boolean inBtn(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String truncate(String s, int maxW) {
        if (this.font.width(s) <= maxW) return s;
        String t = s;
        while (t.length() > 0 && this.font.width(t + "…") > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
