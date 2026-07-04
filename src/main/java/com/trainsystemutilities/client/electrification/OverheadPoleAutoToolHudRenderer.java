package com.trainsystemutilities.client.electrification;

import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.tsu.api.HudAnimState;
import belugalab.tsu.api.HudChrome;
import belugalab.tsu.api.HudConstants;
import belugalab.tsu.api.TabHighlightAnimator;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.preset.TrainPresetMaterials;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 架線柱自動配置ツールの HUD オーバーレイ。
 *
 * <p>{@link StationRangeToolHudRenderer} と同じビジュアルスタイル: ホットバー上 80px、
 * 中央寄せ、SmoothRenderer による角丸枠 + 入退場 fade/slide。
 *
 * <p>表示行 (= tool mode により可変):
 * <ol>
 *   <li>モード badge (GUI / 選択) — alt+wheel で循環</li>
 *   <li>選択 mode のみ: 編集対象タブ (高さ / クリアランス / スパン)</li>
 *   <li>選択 mode のみ: 起点 status / GUI mode: 操作ヒント</li>
 * </ol>
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class OverheadPoleAutoToolHudRenderer {

    private static final int BADGE_W = HudConstants.BADGE_W;
    private static final int ROW_H = HudConstants.ROW_H;
    private static final int ROW_GAP = HudConstants.ROW_GAP;
    private static final int HOTBAR_TOP_OFFSET = HudConstants.HOTBAR_TOP_OFFSET;
    private static final long ENTRY_ANIM_NANOS = HudConstants.ENTRY_ANIM_NANOS;
    private static final long EXIT_ANIM_NANOS = HudConstants.EXIT_ANIM_NANOS;
    private static final long TAB_ANIM_NANOS = HudConstants.TAB_ANIM_NANOS;

    private static final HudAnimState anim = new HudAnimState(ENTRY_ANIM_NANOS, EXIT_ANIM_NANOS);

    // サブモードタブハイライトの追跡 (= 共通 animator に集約)
    private static final TabHighlightAnimator subModeAnim = new TabHighlightAnimator(TAB_ANIM_NANOS);

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (mc.screen != null) return;

        ItemStack stack = findHeldTool(mc);
        boolean held = !stack.isEmpty();
        anim.update(held);
        if (!anim.shouldRender()) return;

        float fade = anim.fade();
        float yOffset = held
                ? (1f - fade) * 20f
                : anim.exitEased() * 20f;

        int toolMode = held ? AutoPlaceConfig.getToolMode(stack) : AutoPlaceConfig.TOOL_MODE_GUI;
        int subMode = held ? AutoPlaceConfig.getSubMode(stack) : AutoPlaceConfig.SUB_MODE_SELECT;
        // 設置 (PLACE) サブモードでは資材在庫行を 1 行追加で表示する。
        boolean placeMode = held && toolMode == AutoPlaceConfig.TOOL_MODE_SELECTION
                && subMode == AutoPlaceConfig.SUB_MODE_PLACE;
        // GUI mode: 2 行、 SELECTION mode: 3 行 (badge + タブ + status)、 設置モード: 4 行 (+ 資材)
        int rows = toolMode == AutoPlaceConfig.TOOL_MODE_SELECTION ? (placeMode ? 4 : 3) : 2;
        int totalH = ROW_H * rows + ROW_GAP * (rows - 1);

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = (sw - BADGE_W) / 2;
        int y = sh - HOTBAR_TOP_OFFSET - totalH + (int) yOffset;

        HudChrome.pushUiScale(g, sw, sh);   // 「常にサイズ2相当」counter-scale
        // 行 1: ツールモード badge (GUI / 選択)
        renderToolModeBadge(g, mc, x, y, BADGE_W, ROW_H, fade, toolMode);

        int yCursor = y + ROW_H + ROW_GAP;
        if (toolMode == AutoPlaceConfig.TOOL_MODE_GUI) {
            String hint = Component.translatable("tsu.opa_tool.hud_gui_hint").getString();
            HudChrome.renderInfoRow(g, mc.font, x, yCursor, BADGE_W, ROW_H, hint, 0xFFBA68C8, fade);
        } else {
            // SELECTION mode 行 2: サブモードタブ (GUIへ戻る / 配置する)
            if (held) {
                renderSubModeTabs(g, mc, x, yCursor, BADGE_W, ROW_H, fade, subMode);
            } else {
                subModeAnim.reset();
            }
            yCursor += ROW_H + ROW_GAP;

            // 行 3: status (= 現在のサブモードに応じたヒント)
            int multiTrack = held ? AutoPlaceConfig.getMultiTrackCount(stack) : 1;
            String status;
            int statusColor;
            switch (subMode) {
                case AutoPlaceConfig.SUB_MODE_GUI_RETURN -> {
                    status = Component.translatable("tsu.opa_tool.hud_gui_return").getString();
                    statusColor = 0xFFBA68C8;
                }
                default -> {  // SUB_MODE_PLACE
                    int rot = held ? AutoPlaceConfig.getManualRotation(stack) : 0;
                    status = Component.translatable("tsu.opa_tool.hud_place_status_fmt",
                            multiTrack, rot * 45).getString();
                    statusColor = 0xFF66BB6A;
                }
            }
            HudChrome.renderInfoRow(g, mc.font, x, yCursor, BADGE_W, ROW_H, status, statusColor, fade);

            // 行 4 (設置モードのみ): リンク済み倉庫の資材在庫 + 設置可能ユニット数 (リアルタイム)
            if (placeMode) {
                yCursor += ROW_H + ROW_GAP;
                renderMaterialRow(g, mc, x, yCursor, BADGE_W, ROW_H, fade, stack);
            }
        }
        HudChrome.popUiScale(g);
    }

    /** 設置モードの資材行: 架線柱/トラス/碍子 の在庫数 (= AUTO_POLE_STOCK) と、 現在設定での
     *  設置可能ユニット数 (= 各資材の所持 ÷ 1 ユニット必要数の最小) をリアルタイム表示。 */
    private static void renderMaterialRow(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                           float fade, ItemStack stack) {
        boolean creative = mc.player != null && mc.player.getAbilities().instabuild;
        String text;
        int color;
        if (creative) {
            text = Component.translatable("tsu.opa_tool.hud_creative").getString();
            color = 0xFF4FC3F7;
        } else if (!hasLinkedStorage(stack)) {
            text = Component.translatable("tsu.opa_tool.hud_no_storage").getString();
            color = 0xFFFF8A80;
        } else {
            int[] stock = readStock(stack);
            int units = estimateUnits(stack, stock);
            text = Component.translatable("tsu.opa_tool.hud_material_fmt",
                    stock[0], stock[1], stock[2], units).getString();
            color = units > 0 ? 0xFF66BB6A : 0xFFEF5350;
        }
        HudChrome.renderInfoRow(g, mc.font, x, y, w, h, text, color, fade);
    }

    /** chest / ME のいずれかがリンクされているか (= client 同期済み component を読む)。 */
    private static boolean hasLinkedStorage(ItemStack stack) {
        if (stack.get(ModDataComponents.PLACE_LINKED_CHEST_POS.get()) != null) return true;
        return ModList.get().isLoaded("ae2")
                && stack.get(ModDataComponents.PLACE_LINKED_WAP_POS.get()) != null;
    }

    /** AUTO_POLE_STOCK を decode して {架線柱, トラス, 碍子} の在庫数を返す。 */
    private static int[] readStock(ItemStack stack) {
        int pole = 0, truss = 0, ins = 0;
        String enc = stack.get(ModDataComponents.AUTO_POLE_STOCK.get());
        if (enc != null && !enc.isBlank()) {
            for (var e : TrainPresetMaterials.decode(enc)) {
                if (e.item() == ModItems.OVERHEAD_POLE.get()) pole = e.count();
                else if (e.item() == ModItems.OVERHEAD_TRUSS.get()) truss = e.count();
                else if (e.item() == ModItems.INSULATOR.get()) ins = e.count();
            }
        }
        return new int[]{pole, truss, ins};
    }

    /** 現在設定での 1 ユニット (= 1 ポータル) あたり必要数から、 設置可能ユニット数を概算。
     *  truss 数は地形依存のため概算 (= 平坦地での見込み)。 */
    private static int estimateUnits(ItemStack stack, int[] stock) {
        int height = AutoPlaceConfig.getHeight(stack);
        int clearance = AutoPlaceConfig.getClearance(stack);
        boolean cantilever = AutoPlaceConfig.getCantilever(stack);
        boolean placeTruss = AutoPlaceConfig.getPlaceTruss(stack);
        boolean placeIns = AutoPlaceConfig.getPlaceInsulator(stack);
        int multi = AutoPlaceConfig.getMultiTrackCount(stack);

        int intDist = Math.round(1.5f + clearance);
        int polesPerUnit = (cantilever ? 1 : 2) * (height + 1);
        int trussPerUnit = placeTruss
                ? Math.max(1, (cantilever ? intDist : 2 * intDist) + (multi - 1) * 3 - 1) : 0;
        int insPerUnit = placeIns ? (cantilever ? 1 : Math.max(1, multi)) : 0;

        int units = Integer.MAX_VALUE;
        if (polesPerUnit > 0) units = Math.min(units, stock[0] / polesPerUnit);
        if (trussPerUnit > 0) units = Math.min(units, stock[1] / trussPerUnit);
        if (insPerUnit > 0) units = Math.min(units, stock[2] / insPerUnit);
        return units == Integer.MAX_VALUE ? 0 : units;
    }

    /** サブモードタブ bar (GUIへ戻る / 起点を選択 / 配置する)。 駅範囲指定ツールの番線方向タブと同じ補間アニメ。 */
    private static void renderSubModeTabs(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                            float fade, int subMode) {
        subModeAnim.update(subMode);

        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | 0x4FC3F7;
        HudChrome.drawRoundedRect(g, x, y, w, h, bg, border);

        int n = AutoPlaceConfig.SUB_MODE_COUNT;
        int tabAreaX = x + 1;
        int tabAreaY = y + 1;
        int tabAreaW = w - 2;
        int tabAreaH = h - 2;
        int tabW = tabAreaW / n;
        int lastTabW = tabAreaW - tabW * (n - 1);

        int hlX = subModeAnim.highlightX(tabAreaX, tabW);
        int hlW = subModeAnim.highlightW(tabW, lastTabW, n);

        int hlBgA = (int) (0xFF * fade);
        int hlBg = (hlBgA << 24) | 0x4FC3F7;
        int hlBorderA = (int) (0xFF * fade);
        int hlBorder = (hlBorderA << 24) | 0x80deea;
        SmoothRenderer.fillRoundedRect(g, hlX, tabAreaY, hlW, tabAreaH, 5f, hlBorder);
        SmoothRenderer.fillRoundedRect(g, hlX + 1, tabAreaY + 1, hlW - 2, tabAreaH - 2, 4f, hlBg);

        for (int i = 0; i < n; i++) {
            int tx = tabAreaX + i * tabW;
            int tw = (i == n - 1) ? lastTabW : tabW;
            boolean active = i == subModeAnim.curTab();
            int fgRgb = active ? 0x000000 : 0x80deea;
            int fgA = (int) (0xFF * fade);
            int fg = (fgA << 24) | fgRgb;
            String label = AutoPlaceConfig.subModeName(i);
            HudChrome.drawCenteredLabel(g, mc.font, label, tx, tabAreaY, tw, tabAreaH, fg);
        }
    }

    /** モード badge (1 行目)。アクセント色は tool mode 別: GUI=紫、選択=水色。 */
    private static void renderToolModeBadge(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                              float fade, int toolMode) {
        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        int borderRgb = toolMode == AutoPlaceConfig.TOOL_MODE_GUI ? 0xBA68C8 : 0x4FC3F7;
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | borderRgb;
        HudChrome.drawRoundedRect(g, x, y, w, h, bg, border);

        String label = Component.translatable("tsu.opa_tool.hud_badge_fmt", AutoPlaceConfig.toolModeName(toolMode)).getString();
        int fgA = (int) (0xFF * fade);
        int fg = (fgA << 24) | borderRgb;
        HudChrome.drawCenteredLabel(g, mc.font, label, x, y, w, h, fg);
    }

    // (旧 renderEditModeTabs / buildStatusText は新サブモードタブに置換済、 削除)


    private static ItemStack findHeldTool(Minecraft mc) {
        return belugalab.tsu.api.HeldTools.find(mc.player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
    }

}
