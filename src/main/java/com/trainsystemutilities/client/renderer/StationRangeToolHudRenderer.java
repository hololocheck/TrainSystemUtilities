package com.trainsystemutilities.client.renderer;

import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.tsu.api.HudAnimState;
import belugalab.tsu.api.HudChrome;
import belugalab.tsu.api.HudConstants;
import belugalab.tsu.api.TabHighlightAnimator;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.StationRangeToolItem;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.station.StationGroupClientCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 駅範囲指定ツールの HUD オーバーレイ。
 *
 * <p>列車プリセットツール ({@link PlaceModeHudRenderer}) の renderSimpleModeBadge と
 * 同じビジュアルスタイル: ホットバー上 80px、中央寄せ、SmoothRenderer による角丸枠 +
 * 入退場 fade/slide。
 *
 * <p>表示行:
 * <ol>
 *   <li>モード badge (GUI / 範囲選択 / 閲覧)</li>
 *   <li>SELECTION mode のみ: Pos1 / Pos2 状態 + edit mode インジケータ</li>
 *   <li>VIEW mode のみ: 表示中グループ数</li>
 * </ol>
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class StationRangeToolHudRenderer {

    private static final int BADGE_W = HudConstants.BADGE_W;
    private static final int ROW_H = HudConstants.ROW_H;
    private static final int ROW_GAP = HudConstants.ROW_GAP;
    private static final int HOTBAR_TOP_OFFSET = HudConstants.HOTBAR_TOP_OFFSET;
    private static final long ENTRY_ANIM_NANOS = HudConstants.ENTRY_ANIM_NANOS;
    private static final long EXIT_ANIM_NANOS = HudConstants.EXIT_ANIM_NANOS;
    private static final long TAB_ANIM_NANOS = HudConstants.TAB_ANIM_NANOS;

    private static final HudAnimState anim = new HudAnimState(ENTRY_ANIM_NANOS, EXIT_ANIM_NANOS);

    // 番線方向タブハイライトの追跡 (= 共通 animator に集約)
    private static final TabHighlightAnimator numDirAnim = new TabHighlightAnimator(TAB_ANIM_NANOS);

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (mc.screen != null) return;

        ItemStack stack = findHeldTool(mc);
        boolean held = !stack.isEmpty();
        anim.update(held);
        if (!anim.shouldRender()) return;

        float fade = anim.fade();           // 0 → 1 (entry) / 1 → 0 (exit)
        // 入場時: 下から slide up; 退場時: 下へ slide down
        float yOffset = held
                ? (1f - fade) * 20f
                : anim.exitEased() * 20f;

        int toolMode = held ? StationRangeToolItem.getToolMode(stack) : StationRangeToolItem.TOOL_MODE_SELECTION;
        // SELECTION モードでは「番線方向」モードバーが追加される (3 行構成)、それ以外は元の 2 行 / 1 行。
        boolean showNumDirBar = (toolMode == StationRangeToolItem.TOOL_MODE_SELECTION);
        int rows = (toolMode == StationRangeToolItem.TOOL_MODE_GUI)
                ? 1 : (showNumDirBar ? 3 : 2);
        int totalH = ROW_H * rows + ROW_GAP * (rows - 1);

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = (sw - BADGE_W) / 2;
        int y = sh - HOTBAR_TOP_OFFSET - totalH + (int) yOffset;

        HudChrome.pushUiScale(g, sw, sh);   // 「常にサイズ2相当」counter-scale
        // 行 1: モード badge (GUI / 範囲選択 / 閲覧)
        renderModeBadge(g, mc, x, y, BADGE_W, ROW_H, fade, toolMode);

        int yCursor = y + ROW_H + ROW_GAP;

        // 行 2: SELECTION モードのみ — 番線方向タブ (Auto / Left / Right)
        if (showNumDirBar && held) {
            int numDir = StationRangeToolItem.getNumberingDir(stack);
            renderNumberingDirBar(g, mc, x, yCursor, BADGE_W, ROW_H, fade, numDir);
            yCursor += ROW_H + ROW_GAP;
        }

        // 行 3 (or 2): モード別補助情報
        if (rows >= 2) {
            String detail = held ? buildDetailText(stack, toolMode) : "";
            int color = held ? detailColor(stack, toolMode) : 0xFFAAAAAA;
            HudChrome.renderInfoRow(g, mc.font, x, yCursor, BADGE_W, ROW_H, detail, color, fade);
        }
        HudChrome.popUiScale(g);
    }

    /** 番線方向タブ (Auto / Left / Right)。プリセットツールの mode bar と同じスタイル: */
    private static void renderNumberingDirBar(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                                float fade, int numDir) {
        // タブ変化を検知してハイライト位置を補間 (= 共通 animator)
        numDirAnim.update(numDir);

        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | 0x4fc3f7;
        HudChrome.drawRoundedRect(g, x, y, w, h, bg, border);

        int n = StationRangeToolItem.NUM_DIR_COUNT;
        int tabAreaX = x + 1;
        int tabAreaY = y + 1;
        int tabAreaW = w - 2;
        int tabAreaH = h - 2;
        int tabW = tabAreaW / n;
        int lastTabW = tabAreaW - tabW * (n - 1);

        int hlX = numDirAnim.highlightX(tabAreaX, tabW);
        int hlW = numDirAnim.highlightW(tabW, lastTabW, n);

        int hlBgA = (int) (0xFF * fade);
        int hlBg = (hlBgA << 24) | 0x4fc3f7;
        int hlBorderA = (int) (0xFF * fade);
        int hlBorder = (hlBorderA << 24) | 0x80deea;
        SmoothRenderer.fillRoundedRect(g, hlX, tabAreaY, hlW, tabAreaH, 5f, hlBorder);
        SmoothRenderer.fillRoundedRect(g, hlX + 1, tabAreaY + 1, hlW - 2, tabAreaH - 2, 4f, hlBg);

        for (int i = 0; i < n; i++) {
            int tx = tabAreaX + i * tabW;
            int tw = (i == n - 1) ? lastTabW : tabW;
            boolean active = i == numDirAnim.curTab();
            int fgRgb = active ? 0x000000 : 0x80deea;
            int fgA = (int) (0xFF * fade);
            int fg = (fgA << 24) | fgRgb;
            String label = numDirLabel(i);
            HudChrome.drawCenteredLabel(g, mc.font, label, tx, tabAreaY, tw, tabAreaH, fg);
        }
    }

    private static String numDirLabel(int dir) {
        return Component.translatable(switch (dir) {
            case StationRangeToolItem.NUM_DIR_LEFT  -> "tsu.station_tool.hud_numdir_left";
            case StationRangeToolItem.NUM_DIR_RIGHT -> "tsu.station_tool.hud_numdir_right";
            default                                  -> "tsu.station_tool.hud_numdir_auto";
        }).getString();
    }

    private static String buildDetailText(ItemStack stack, int toolMode) {
        if (toolMode == StationRangeToolItem.TOOL_MODE_VIEW) {
            int count = StationGroupClientCache.all().size();
            return Component.translatable("tsu.station_tool.hud_view_count_fmt", count).getString();
        }
        // SELECTION mode
        BlockPos p1 = StationRangeToolItem.getPos1(stack);
        BlockPos p2 = StationRangeToolItem.getPos2(stack);
        int editMode = StationRangeToolItem.getEditMode(stack);
        String posState;
        if (p1 == null && p2 == null) {
            posState = Component.translatable("tsu.station_tool.hud_pos_none").getString();
        } else if (p1 != null && p2 == null) {
            posState = Component.translatable("tsu.station_tool.hud_pos1_only").getString();
        } else if (p1 == null) {
            posState = Component.translatable("tsu.station_tool.hud_pos2_only").getString();
        } else {
            posState = Component.translatable("tsu.station_tool.hud_pos_both").getString();
        }
        String editStr = switch (editMode) {
            case 1 -> " " + Component.translatable("tsu.station_tool.hud_edit_pos1").getString();
            case 2 -> " " + Component.translatable("tsu.station_tool.hud_edit_pos2").getString();
            default -> "";
        };
        return posState + editStr;
    }

    private static int detailColor(ItemStack stack, int toolMode) {
        if (toolMode == StationRangeToolItem.TOOL_MODE_VIEW) return 0xFF80DEEA;
        BlockPos p1 = StationRangeToolItem.getPos1(stack);
        BlockPos p2 = StationRangeToolItem.getPos2(stack);
        if (p1 != null && p2 != null) return 0xFF66BB6A; // 緑 = 確定
        if (p1 != null || p2 != null) return 0xFFFFD54F; // 黄 = 進行中
        return 0xFFFF8A80; // 赤 = 未設定
    }

    private static void renderModeBadge(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                        float fade, int toolMode) {
        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        // モード別アクセント色
        int borderRgb = switch (toolMode) {
            case StationRangeToolItem.TOOL_MODE_GUI -> 0xBA68C8;       // 紫
            case StationRangeToolItem.TOOL_MODE_VIEW -> 0x4FC3F7;      // 水色
            default -> 0x66BB6A;                                       // 緑 (selection)
        };
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | borderRgb;
        HudChrome.drawRoundedRect(g, x, y, w, h, bg, border);

        String key = switch (toolMode) {
            case StationRangeToolItem.TOOL_MODE_GUI -> "tsu.station_tool.hud_mode_gui";
            case StationRangeToolItem.TOOL_MODE_VIEW -> "tsu.station_tool.hud_mode_view";
            default -> "tsu.station_tool.hud_mode_selection";
        };
        String label = Component.translatable(key).getString();
        int fgA = (int) (0xFF * fade);
        int fg = (fgA << 24) | borderRgb;
        HudChrome.drawCenteredLabel(g, mc.font, label, x, y, w, h, fg);
    }


    private static ItemStack findHeldTool(Minecraft mc) {
        return belugalab.tsu.api.HeldTools.find(mc.player, ModItems.STATION_RANGE_TOOL.get());
    }

}
