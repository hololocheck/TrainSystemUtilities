package com.trainsystemutilities.electrification.autoplace;

import com.trainsystemutilities.registry.ModDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-place tool の設定値 (= 高さ / クリアランス / スパン)。 ItemStack の
 * DataComponents に永続化される。
 */
public final class AutoPlaceConfig {

    public static final int DEFAULT_HEIGHT = 3;
    public static final int DEFAULT_CLEARANCE = 1;
    public static final int DEFAULT_SPAN = 4;
    public static final int DEFAULT_SCAN_RANGE = 8;
    public static final boolean DEFAULT_SKIP_STATION = true;
    public static final int DEFAULT_MULTI_TRACK_COUNT = 1;
    public static final int MIN_MULTI_TRACK_COUNT = 1;
    public static final int MAX_MULTI_TRACK_COUNT = 8;
    public static final boolean DEFAULT_CANTILEVER = false;
    public static final boolean DEFAULT_PLACE_TRUSS = true;
    public static final boolean DEFAULT_PLACE_INSULATOR = true;

    public static final int MIN_HEIGHT = 1;
    public static final int MAX_HEIGHT = 16;
    public static final int MIN_CLEARANCE = 0;
    public static final int MAX_CLEARANCE = 8;
    public static final int MIN_SPAN = 1;
    public static final int MAX_SPAN = 32;
    public static final int MIN_SCAN_RANGE = 1;
    public static final int MAX_SCAN_RANGE = 32;

    // ===== Tool mode (= alt+wheel で循環、 GUI / SELECTION) =====
    public static final int TOOL_MODE_GUI = 0;
    public static final int TOOL_MODE_SELECTION = 1;
    public static final int TOOL_MODE_COUNT = 2;

    // ===== SELECTION 内のサブモード (= ctrl+wheel で循環、 2 タブ) =====
    //   配置するモード時は hover プレビュー表示 + 右クリックで実配置
    public static final int SUB_MODE_GUI_RETURN = 0;
    public static final int SUB_MODE_PLACE = 1;
    public static final int SUB_MODE_COUNT = 2;
    // 旧 SUB_MODE_SELECT (=1) は削除。 互換 alias として PLACE と同じ値に。
    public static final int SUB_MODE_SELECT = SUB_MODE_PLACE;

    private AutoPlaceConfig() {}

    public static int getHeight(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_HEIGHT.get());
        return v == null ? DEFAULT_HEIGHT : Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, v));
    }

    public static int getClearance(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_CLEARANCE.get());
        return v == null ? DEFAULT_CLEARANCE : Math.max(MIN_CLEARANCE, Math.min(MAX_CLEARANCE, v));
    }

    public static int getSpan(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_SPAN.get());
        return v == null ? DEFAULT_SPAN : Math.max(MIN_SPAN, Math.min(MAX_SPAN, v));
    }

    public static void setHeight(ItemStack stack, int height) {
        stack.set(ModDataComponents.AUTO_POLE_HEIGHT.get(),
                Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, height)));
    }

    public static void setClearance(ItemStack stack, int clearance) {
        stack.set(ModDataComponents.AUTO_POLE_CLEARANCE.get(),
                Math.max(MIN_CLEARANCE, Math.min(MAX_CLEARANCE, clearance)));
    }

    public static void setSpan(ItemStack stack, int span) {
        stack.set(ModDataComponents.AUTO_POLE_SPAN.get(),
                Math.max(MIN_SPAN, Math.min(MAX_SPAN, span)));
    }

    // ===== Edit mode (= ctrl+wheel で切替、 alt+wheel でその mode の値を増減) =====

    public static final int MODE_HEIGHT = 0;
    public static final int MODE_CLEARANCE = 1;
    public static final int MODE_SPAN = 2;
    public static final int MODE_COUNT = 3;

    public static int getEditMode(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_EDIT_MODE.get());
        return v == null ? MODE_HEIGHT : ((v % MODE_COUNT) + MODE_COUNT) % MODE_COUNT;
    }

    public static void setEditMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.AUTO_POLE_EDIT_MODE.get(),
                ((mode % MODE_COUNT) + MODE_COUNT) % MODE_COUNT);
    }

    /** mode 名 (HUD 表示用)。 */
    public static String modeName(int mode) {
        return switch (mode) {
            case MODE_HEIGHT -> "高さ";
            case MODE_CLEARANCE -> "クリアランス";
            case MODE_SPAN -> "スパン";
            default -> "?";
        };
    }

    /** 現在 edit mode の値を delta だけ増減 (= 範囲 clamp 付き)。 */
    public static void adjustCurrentMode(ItemStack stack, int delta) {
        int mode = getEditMode(stack);
        switch (mode) {
            case MODE_HEIGHT -> setHeight(stack, getHeight(stack) + delta);
            case MODE_CLEARANCE -> setClearance(stack, getClearance(stack) + delta);
            case MODE_SPAN -> setSpan(stack, getSpan(stack) + delta);
        }
    }

    // ===== Tool mode (GUI / SELECTION) =====

    public static int getToolMode(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_TOOL_MODE.get());
        // default = GUI (= 持ち替えた直後は設定画面が開く UX)。 「設定にする」 で SELECTION に移行
        return v == null ? TOOL_MODE_GUI
                : ((v % TOOL_MODE_COUNT) + TOOL_MODE_COUNT) % TOOL_MODE_COUNT;
    }

    public static void setToolMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.AUTO_POLE_TOOL_MODE.get(),
                ((mode % TOOL_MODE_COUNT) + TOOL_MODE_COUNT) % TOOL_MODE_COUNT);
    }

    /** Tool-mode name for HUD display. Resolved via the client language
     *  (only the client HUD calls this). */
    public static String toolModeName(int mode) {
        return switch (mode) {
            case TOOL_MODE_GUI -> Component.translatable("tsu.opa_tool.mode_gui").getString();
            case TOOL_MODE_SELECTION -> Component.translatable("tsu.opa_tool.mode_selection").getString();
            default -> "?";
        };
    }

    // ===== SUB mode getter/setter =====

    public static int getSubMode(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_SUBMODE.get());
        return v == null ? SUB_MODE_SELECT
                : ((v % SUB_MODE_COUNT) + SUB_MODE_COUNT) % SUB_MODE_COUNT;
    }

    public static void setSubMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.AUTO_POLE_SUBMODE.get(),
                ((mode % SUB_MODE_COUNT) + SUB_MODE_COUNT) % SUB_MODE_COUNT);
    }

    public static String subModeName(int mode) {
        return switch (mode) {
            case SUB_MODE_GUI_RETURN -> Component.translatable("tsu.opa_tool.submode_gui_return").getString();
            case SUB_MODE_PLACE      -> Component.translatable("tsu.opa_tool.submode_place").getString();
            default -> "?";
        };
    }

    // ===== Skip station range option =====
    // 駅範囲 (StationGroupSavedData で指定された範囲) は **常に** skip する。
    // 駅の架線はホームから出るのが現実的、 自動 pole は不要。 toggle 設定は廃止。

    public static boolean getSkipStation(ItemStack stack) {
        return true;
    }

    public static void setSkipStation(ItemStack stack, boolean skip) {
        // no-op (= 強制 ON)
    }

    // ===== 複線化数 (= multi-track count) =====
    // 1 = 単線、 2 = 複線、 ... 配置時に N 本分の並走線路を選択する必要がある。

    public static int getMultiTrackCount(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_MULTI_TRACK_COUNT.get());
        return v == null ? DEFAULT_MULTI_TRACK_COUNT
                : Math.max(MIN_MULTI_TRACK_COUNT, Math.min(MAX_MULTI_TRACK_COUNT, v));
    }

    public static void setMultiTrackCount(ItemStack stack, int count) {
        stack.set(ModDataComponents.AUTO_POLE_MULTI_TRACK_COUNT.get(),
                Math.max(MIN_MULTI_TRACK_COUNT, Math.min(MAX_MULTI_TRACK_COUNT, count)));
    }

    // ===== Parallel scan range =====

    public static int getScanRange(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_SCAN_RANGE.get());
        return v == null ? DEFAULT_SCAN_RANGE
                : Math.max(MIN_SCAN_RANGE, Math.min(MAX_SCAN_RANGE, v));
    }

    public static void setScanRange(ItemStack stack, int range) {
        stack.set(ModDataComponents.AUTO_POLE_SCAN_RANGE.get(),
                Math.max(MIN_SCAN_RANGE, Math.min(MAX_SCAN_RANGE, range)));
    }

    // ===== Cantilever style (= 片持ち) =====

    public static boolean getCantilever(ItemStack stack) {
        Boolean v = stack.get(ModDataComponents.AUTO_POLE_CANTILEVER.get());
        return v == null ? DEFAULT_CANTILEVER : v;
    }

    public static void setCantilever(ItemStack stack, boolean v) {
        stack.set(ModDataComponents.AUTO_POLE_CANTILEVER.get(), v);
    }

    // ===== Place truss option =====

    public static boolean getPlaceTruss(ItemStack stack) {
        Boolean v = stack.get(ModDataComponents.AUTO_POLE_PLACE_TRUSS.get());
        return v == null ? DEFAULT_PLACE_TRUSS : v;
    }

    public static void setPlaceTruss(ItemStack stack, boolean v) {
        stack.set(ModDataComponents.AUTO_POLE_PLACE_TRUSS.get(), v);
    }

    // ===== Place insulator option =====

    public static boolean getPlaceInsulator(ItemStack stack) {
        Boolean v = stack.get(ModDataComponents.AUTO_POLE_PLACE_INSULATOR.get());
        return v == null ? DEFAULT_PLACE_INSULATOR : v;
    }

    public static void setPlaceInsulator(ItemStack stack, boolean v) {
        stack.set(ModDataComponents.AUTO_POLE_PLACE_INSULATOR.get(), v);
    }

    // ===== 手動回転 (= 8 方向 ANGLE_8 index、 alt+wheel で増減) =====

    public static int getManualRotation(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.AUTO_POLE_MANUAL_ROTATION.get());
        return v == null ? 0 : ((v % 8) + 8) % 8;
    }

    public static void setManualRotation(ItemStack stack, int value) {
        stack.set(ModDataComponents.AUTO_POLE_MANUAL_ROTATION.get(),
                ((value % 8) + 8) % 8);
    }
}
