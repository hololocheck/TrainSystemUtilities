package com.trainsystemutilities.client.renderer;
import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.tsu.api.HudChrome;
import belugalab.tsu.api.HudConstants;
import belugalab.tsu.api.HudText;
import belugalab.tsu.api.TabHighlightAnimator;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPresetMaterials;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 列車プリセットツール Place モードの HUD オーバーレイ。
 *
 * - アイテム手持ち時に下からスライドアップ + フェードイン (300ms ease-out)
 * - 5 タブのモードバー、選択中タブはハイライトをアニメーションで移動 (150ms ease-out)
 * - ホットバー上に配置 (action bar とは被らない)
 * - 操作ヒントは shift+中クリックの cancel も含めて表示
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class PlaceModeHudRenderer {

    private static final int UNIFIED_W = 380;   // R2.2.1 例外: 列車プリセットのみ 380
    private static final int HOTBAR_TOP_OFFSET = HudConstants.HOTBAR_TOP_OFFSET;
    private static final long ENTRY_ANIM_NANOS = HudConstants.ENTRY_ANIM_NANOS;
    private static final long EXIT_ANIM_NANOS = HudConstants.EXIT_ANIM_NANOS;
    private static final long TAB_ANIM_NANOS = HudConstants.TAB_ANIM_NANOS;

    // 入場アニメ追跡
    private static long firstHeldNano = 0;
    private static boolean wasHeld = false;
    // タブハイライト追跡 (= 共通 animator に集約。 placeAnim は別物 = 入退場 HudAnimState)
    private static final TabHighlightAnimator subTabAnim = new TabHighlightAnimator(TAB_ANIM_NANOS);

    // 退場アニメ (PLACE → 他モード遷移時。データはサーバ側で消去されるためスナップショット保持)
    private static int lastToolMode = -1;
    /** PLACE モード中: visible=true、抜けたら visible=false で MCSS の HudAnimState が exit を駆動。 */
    private static final belugalab.tsu.api.HudAnimState placeAnim =
            new belugalab.tsu.api.HudAnimState(ENTRY_ANIM_NANOS, EXIT_ANIM_NANOS);
    private static int exitSub = -1;
    private static int exitRotY = 0;
    private static BlockPos exitOrigin = null;
    private static String exitPreset = null;

    // material status HUD のアニメ追跡
    private static long matStatusVisibleSinceNano = 0;
    private static long matStatusHiddenSinceNano = 0;
    private static boolean matStatusWasVisible = false;
    private static String matStatusLastTitle = "";
    private static java.util.List<TrainPresetMaterials.MaterialEntry> matStatusLastEntries = java.util.List.of();
    private static boolean matStatusLastReady = false;
    private static int matStatusLastTotalMissing = 0;
    private static final long MAT_STATUS_ENTRY_NANOS = 250_000_000L;
    private static final long MAT_STATUS_EXIT_NANOS = 200_000_000L;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (mc.screen != null) return;

        ItemStack stack = findHeldTool(mc);
        if (stack.isEmpty()) {
            if (wasHeld) {
                wasHeld = false;
                firstHeldNano = 0;
                subTabAnim.reset();
                placeAnim.reset();
                lastToolMode = -1;
            }
            return;
        }
        int toolMode = TrainPresetToolItem.getToolMode(stack);
        if (!wasHeld) {
            firstHeldNano = System.nanoTime();
            wasHeld = true;
        }

        // PLACE モード中だけ visible=true で HudAnimState を駆動 (entry/exit を一元管理)
        placeAnim.update(toolMode == TrainPresetToolItem.TOOL_MODE_PLACE);
        lastToolMode = toolMode;

        if (toolMode != TrainPresetToolItem.TOOL_MODE_PLACE) {
            // 退場アニメ進行中なら PLACE HUD を逆方向で描画 (HudAnimState が exit 進行を判定)
            GuiGraphics g = event.getGuiGraphics();
            // 「常にサイズ2相当」: ホットバー上端中央を pivot に cluster 全体を counter-scale (G=2 で無変更)。
            HudChrome.pushUiScale(g, g.guiWidth() / 2f, (float) (g.guiHeight() - HOTBAR_TOP_OFFSET));
            if (placeAnim.shouldRender()) {
                renderPlaceHudExiting(g, mc);
            } else {
                renderSimpleModeBadge(g, mc, stack, toolMode);
            }
            renderMaterialStatusHud(g, mc, stack, 1f);
            HudChrome.popUiScale(g);
            return;
        }

        long now = System.nanoTime();
        float entryProgress = Math.min(1f, (now - firstHeldNano) / (float) ENTRY_ANIM_NANOS);
        float entryEased = 1f - (float) Math.pow(1f - entryProgress, 3); // ease-out cubic

        GuiGraphics g = event.getGuiGraphics();
        int sub = TrainPresetToolItem.getPlaceSubMode(stack);
        int rotY = TrainPresetToolItem.getPlaceRotY(stack);
        BlockPos origin = TrainPresetToolItem.getPlaceOrigin(stack);
        String preset = stack.get(ModDataComponents.SELECTED_PRESET.get());

        // タブ変化検出 (= 共通 animator。 補間は renderModeBar 内の highlightX/W が担当)
        subTabAnim.update(sub);

        int sw = g.guiWidth();
        int sh = g.guiHeight();

        int rowH = HudConstants.ROW_H;
        int rowGap = HudConstants.ROW_GAP;
        int rows = 4;
        int totalH = rowH * rows + rowGap * (rows - 1);
        int x = (sw - UNIFIED_W) / 2;
        int yBase = sh - HOTBAR_TOP_OFFSET - totalH;
        // 入場時の slide up + fade
        int yOffset = (int) ((1f - entryEased) * 30f);
        int y = yBase + yOffset;

        // alpha は entryEased 倍率 (0xFF base * fade)
        float fade = entryEased;

        // 「常にサイズ2相当」: ホットバー上端中央を pivot に cluster 全体を counter-scale (G=2 で無変更)。
        HudChrome.pushUiScale(g, sw / 2f, (float) (sh - HOTBAR_TOP_OFFSET));
        // 行 1: モードバー
        renderModeBar(g, mc, x, y, UNIFIED_W, rowH, fade, subTabAnim.curTab(), rotY);

        int y2 = y + rowH + rowGap;
        HudChrome.renderInfoRow(g, mc.font, x, y2, UNIFIED_W, rowH, originText(origin), originColor(origin), fade);

        int y3 = y2 + rowH + rowGap;
        HudChrome.renderInfoRow(g, mc.font, x, y3, UNIFIED_W, rowH, presetText(preset), 0xFF4fc3f7, fade);

        int y4 = y3 + rowH + rowGap;
        HudChrome.renderInfoRow(g, mc.font, x, y4, UNIFIED_W, rowH, hintText(sub), 0xFFAAAAAA, fade);
        renderMaterialStatusHud(g, mc, stack, fade);
        HudChrome.popUiScale(g);

        // 退場アニメ用スナップショット (毎 frame 最新値で更新)
        exitSub = sub;
        exitRotY = rotY;
        exitOrigin = origin;
        exitPreset = preset;
    }

    /** 退場アニメ: PLACE HUD を入場の逆方向 (下へスライド + フェードアウト) で描画。 */
    private static void renderPlaceHudExiting(GuiGraphics g, Minecraft mc) {
        float fade = placeAnim.fade();         // 1 → 0
        float eased = placeAnim.exitEased();   // 0 → 1
        int yOffset = (int) (eased * 30f);

        int rowH = HudConstants.ROW_H;
        int rowGap = HudConstants.ROW_GAP;
        int rows = 4;
        int totalH = rowH * rows + rowGap * (rows - 1);
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = (sw - UNIFIED_W) / 2;
        int y = sh - HOTBAR_TOP_OFFSET - totalH + yOffset;

        int sub = exitSub >= 0 ? exitSub : TrainPresetToolItem.SUB_ORIGIN;
        // タブ位置は退場中も animator が保持する最後の PLACE 位置に固定される
        renderModeBar(g, mc, x, y, UNIFIED_W, rowH, fade, sub, exitRotY);

        int y2 = y + rowH + rowGap;
        HudChrome.renderInfoRow(g, mc.font, x, y2, UNIFIED_W, rowH, originText(exitOrigin), originColor(exitOrigin), fade);

        int y3 = y2 + rowH + rowGap;
        HudChrome.renderInfoRow(g, mc.font, x, y3, UNIFIED_W, rowH, presetText(exitPreset), 0xFF4fc3f7, fade);

        int y4 = y3 + rowH + rowGap;
        HudChrome.renderInfoRow(g, mc.font, x, y4, UNIFIED_W, rowH, hintText(sub), 0xFFAAAAAA, fade);
    }

    private static String originText(BlockPos origin) {
        if (origin == null) return net.minecraft.network.chat.Component.translatable("tsu.tool.hud_origin_unset_hint").getString();
        return net.minecraft.network.chat.Component.translatable(
                "tsu.tool.hud_origin_at_fmt", origin.getX(), origin.getY(), origin.getZ()).getString();
    }

    private static int originColor(BlockPos origin) {
        return origin == null ? 0xFFff8a80 : 0xFF80deea;
    }

    private static String presetText(String preset) {
        if (preset == null || preset.isEmpty())
            return net.minecraft.network.chat.Component.translatable("tsu.tool.hud_preset_unselected").getString();
        String name = preset.substring(preset.indexOf('/') + 1);
        if (name.endsWith(".tsupreset")) name = name.substring(0, name.length() - 10);
        return net.minecraft.network.chat.Component.translatable("tsu.tool.hud_preset_label_fmt", name).getString();
    }

    private static String hintText(int sub) {
        String key = switch (sub) {
            case TrainPresetToolItem.SUB_ORIGIN -> "tsu.tool.hud_hint_origin";
            case TrainPresetToolItem.SUB_ROT_Y -> "tsu.tool.hud_hint_rotate";
            case TrainPresetToolItem.SUB_PLACE -> "tsu.tool.hud_hint_place";
            default -> "tsu.tool.hud_hint_unimpl";
        };
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    private static void renderModeBar(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                        float fade, int curSub, int rotY) {
        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | 0x4fc3f7;
        HudChrome.drawRoundedRect(g, x, y, w, h, bg, border);

        int n = TrainPresetToolItem.SUB_COUNT;
        // タブ領域は枠の内側 1px から始まり、5 タブで等分。最後の余りは最終タブに足す。
        int tabAreaX = x + 1;
        int tabAreaY = y + 1;
        int tabAreaW = w - 2;
        int tabAreaH = h - 2;
        int tabW = tabAreaW / n;
        int lastTabW = tabAreaW - tabW * (n - 1); // 余り吸収

        // ハイライト位置 (= 共通 animator が prev→cur 補間 + 最終タブ余り吸収を担当, R2.7.1)
        int hlX = subTabAnim.highlightX(tabAreaX, tabW);
        int hlW = subTabAnim.highlightW(tabW, lastTabW, n);

        int hlBgA = (int) (0xFF * fade);
        int hlBg = (hlBgA << 24) | 0x4fc3f7;
        int hlBorderA = (int) (0xFF * fade);
        int hlBorder = (hlBorderA << 24) | 0x80deea;
        SmoothRenderer.fillRoundedRect(g, hlX, tabAreaY, hlW, tabAreaH, 5f, hlBorder);
        SmoothRenderer.fillRoundedRect(g, hlX + 1, tabAreaY + 1, hlW - 2, tabAreaH - 2, 4f, hlBg);

        for (int i = 0; i < n; i++) {
            int tx = tabAreaX + i * tabW;
            int tw = (i == n - 1) ? lastTabW : tabW;
            boolean active = i == curSub;
            int fgRgb = active ? 0x000000 : 0x80deea;
            int fgA = (int) (0xFF * fade);
            int fg = (fgA << 24) | fgRgb;
            String label = TrainPresetToolItem.subModeLabel(i);
            if (i == TrainPresetToolItem.SUB_ROT_Y && active) {
                label = label + " " + (rotY * 90) + "\u00b0";
            }
            HudChrome.drawCenteredLabel(g, mc.font, label, tx, tabAreaY, tw, tabAreaH, fg);
        }
    }

    /** GUI / Selection モード時の単行 badge。PLACE モードと位置を揃えてホットバー上に表示。 */
    private static void renderSimpleModeBadge(GuiGraphics g, Minecraft mc, net.minecraft.world.item.ItemStack stack, int toolMode) {
        long now = System.nanoTime();
        float entryProgress = Math.min(1f, (now - firstHeldNano) / (float) ENTRY_ANIM_NANOS);
        float entryEased = 1f - (float) Math.pow(1f - entryProgress, 3);
        float fade = entryEased;
        int yOffset = (int) ((1f - entryEased) * 20f);

        int badgeW = 220, badgeH = 20;
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = (sw - badgeW) / 2;
        int y = sh - HOTBAR_TOP_OFFSET - badgeH + yOffset;

        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | 0x4fc3f7;
        HudChrome.drawRoundedRect(g, x, y, badgeW, badgeH, bg, border);

        String label;
        if (toolMode == TrainPresetToolItem.TOOL_MODE_SELECTION) {
            int edit = TrainPresetToolItem.getEditMode(stack);
            String editStr = switch (edit) {
                case 1 -> net.minecraft.network.chat.Component.translatable("tsu.tool.hud_select_pos1").getString();
                case 2 -> net.minecraft.network.chat.Component.translatable("tsu.tool.hud_select_pos2").getString();
                default -> "";
            };
            label = net.minecraft.network.chat.Component.translatable("tsu.tool.hud_select_mode_fmt", editStr).getString();
        } else {
            label = net.minecraft.network.chat.Component.translatable("tsu.tool.hud_gui_mode").getString();
        }
        int fgA = (int) (0xFF * fade);
        int fg = (fgA << 24) | 0x4fc3f7;
        HudChrome.drawCenteredLabel(g, mc.font, label, x, y, badgeW, badgeH, fg);
    }


    private static void renderMaterialStatusHud(GuiGraphics g, Minecraft mc, ItemStack tool, float baseFade) {
        String status = TrainPresetToolItem.getPlacementStatusMessage(tool);
        var missingEntries = TrainPresetMaterials.decode(TrainPresetToolItem.getMissingItems(tool));
        boolean resumeReady = TrainPresetToolItem.isPlaceResumeReady(tool);
        boolean hasContent = (status != null && !status.isBlank()) || resumeReady;
        long now = System.nanoTime();

        // 表示状態遷移検知
        if (hasContent && !matStatusWasVisible) {
            matStatusVisibleSinceNano = now;
            matStatusHiddenSinceNano = 0;
        } else if (!hasContent && matStatusWasVisible) {
            matStatusHiddenSinceNano = now;
        }
        matStatusWasVisible = hasContent;

        // 表示中スナップショット更新
        if (hasContent) {
            int totalMissing = 0;
            for (var e : missingEntries) totalMissing += e.count();
            matStatusLastTitle = (status == null || status.isBlank())
                    ? net.minecraft.network.chat.Component.translatable("tsu.tool.hud_material_check").getString()
                    : status;
            matStatusLastEntries = missingEntries;
            matStatusLastReady = resumeReady;
            matStatusLastTotalMissing = totalMissing;
        }

        // 入退場 fade / yOffset 計算
        float animFade;
        int yOffset;
        if (hasContent) {
            float p = Math.min(1f, (now - matStatusVisibleSinceNano) / (float) MAT_STATUS_ENTRY_NANOS);
            float eased = 1f - (float) Math.pow(1f - p, 3); // ease-out cubic
            animFade = eased;
            yOffset = (int) ((1f - eased) * 18f);
        } else {
            if (matStatusHiddenSinceNano == 0) return;
            long elapsed = now - matStatusHiddenSinceNano;
            if (elapsed >= MAT_STATUS_EXIT_NANOS) { matStatusHiddenSinceNano = 0; return; }
            float p = elapsed / (float) MAT_STATUS_EXIT_NANOS;
            float eased = (float) Math.pow(p, 3); // ease-in cubic (退場)
            animFade = 1f - eased;
            yOffset = (int) (eased * 18f);
        }

        float fade = baseFade * animFade;
        if (fade <= 0.01f) return;

        var entries = matStatusLastEntries;
        boolean ready = matStatusLastReady;
        String title = ready ? net.minecraft.network.chat.Component.translatable("tsu.tool.hud_resume_ready").getString() : matStatusLastTitle;

        int maxShown = Math.min(6, entries.size());
        int cols = 2;
        int rows = maxShown == 0 ? 0 : (maxShown + cols - 1) / cols;
        int width = UNIFIED_W;
        int headerH = HudConstants.ROW_H;
        int rowH = 22;
        int footerH = entries.size() > maxShown ? 14 : 0;
        int height = 8 + headerH + rows * rowH + footerH;
        int x = (g.guiWidth() - width) / 2;
        int y = g.guiHeight() - HOTBAR_TOP_OFFSET - 92 - height + yOffset;

        // 枠色: ready なら緑、それ以外は赤
        int borderRgb = ready ? 0x66bb6a : 0xff8a80;
        HudChrome.drawRoundedRect(g, x, y, width, height,
                ((int) (0xE8 * fade) << 24) | 0x1a1a2e,
                ((int) (0xFF * fade) << 24) | borderRgb);

        int titleColor = ((int) (0xFF * fade) << 24) | (ready ? 0x80ffa0 : 0xFFF0F0);
        title = HudText.clip(mc.font, title, width - 16);
        g.drawString(mc.font, title, x + 8, y + 6, titleColor, false);

        for (int i = 0; i < maxShown; i++) {
            var entry = entries.get(i);
            int col = i % cols;
            int row = i / cols;
            int cellX = x + 8 + col * ((width - 16) / cols);
            int cellY = y + 24 + row * rowH;
            int cellW = (width - 24) / cols;
            // 各 row の枠色: 不足あり=赤、ready=緑、その他=灰
            int cellBorder = ready ? 0x66bb6a : 0x444a66;
            SmoothRenderer.fillRoundedRect(g, cellX, cellY, cellW, 20, 5f,
                    ((int) (0xFF * fade) << 24) | cellBorder);
            SmoothRenderer.fillRoundedRect(g, cellX + 1, cellY + 1, cellW - 2, 20 - 2, 4f,
                    ((int) (0xC0 * fade) << 24) | 0x25253a);

            ItemStack icon = entry.stack();
            if (!icon.isEmpty()) {
                g.renderItem(icon, cellX + 2, cellY + 2);
                if (ready) {
                    // 緑チェックマーク overlay (右下)
                    g.drawString(mc.font, "\u2714", cellX + 12, cellY + 9,
                            ((int) (0xFF * fade) << 24) | 0xFF66bb6a, false);
                }
            }
            String name = icon.isEmpty() ? "?" : icon.getHoverName().getString();
            name = HudText.clip(mc.font, name, cellW - 26);
            String count = ready
                    ? "✔ OK"
                    : net.minecraft.network.chat.Component.translatable("tsu.tool.hud_short_fmt", TrainPresetMaterials.formatCompactCount(entry.count())).getString();
            int countColor = ready ? 0xFF66bb6a : 0xFF80deea;
            int textColor = ((int) (0xFF * fade) << 24) | 0xFFF0F0;
            g.drawString(mc.font, name, cellX + 24, cellY + 3, textColor, false);
            g.drawString(mc.font, count, cellX + 24, cellY + 11,
                    ((int) (0xFF * fade) << 24) | countColor, false);
        }

        if (entries.size() > maxShown) {
            String more = "+" + (entries.size() - maxShown) + " more";
            g.drawString(mc.font, more, x + width - 8 - mc.font.width(more),
                    y + height - 10, ((int) (0xFF * fade) << 24) | 0xFFAAAAAA, false);
        }
    }

    private static ItemStack findHeldTool(Minecraft mc) {
        return belugalab.tsu.api.HeldTools.find(mc.player, ModItems.TRAIN_PRESET_TOOL.get());
    }

}
