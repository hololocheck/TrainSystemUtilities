package com.trainsystemutilities.client.renderer;

import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.tsu.api.HudAnimState;
import belugalab.tsu.api.HudChrome;
import belugalab.tsu.api.HudConstants;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.electrification.wire.WireType;
import com.trainsystemutilities.registry.ModItems;
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
 * 架線接続ツールの HUD オーバーレイ。
 *
 * <p>列車プリセットツール / 駅範囲指定ツールと同じビジュアル仕様:
 * ホットバー上 80px、中央寄せ、{@link SmoothRenderer} 角丸枠 + entry/exit fade slide。
 *
 * <p>表示行:
 * <ol>
 *   <li>モード badge (GUI / 選択)</li>
 *   <li>選択中デザイン名</li>
 *   <li>SELECTION モードのみ: 接続元状態 (未選択 / 選択済み + 座標)</li>
 * </ol>
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class WireConnectorHudRenderer {

    private static final int BADGE_W = HudConstants.BADGE_W;
    private static final int ROW_H = HudConstants.ROW_H;
    private static final int ROW_GAP = HudConstants.ROW_GAP;
    private static final int HOTBAR_TOP_OFFSET = HudConstants.HOTBAR_TOP_OFFSET;
    private static final long ENTRY_ANIM_NANOS = HudConstants.ENTRY_ANIM_NANOS;
    private static final long EXIT_ANIM_NANOS = HudConstants.EXIT_ANIM_NANOS;

    private static final HudAnimState anim = new HudAnimState(ENTRY_ANIM_NANOS, EXIT_ANIM_NANOS);

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

        int toolMode = held ? WireConnectorItem.readToolMode(stack)
                : WireConnectorItem.TOOL_MODE_SELECTION;

        // GUI モード = 2 行、設置モード = 4 行 (+ 接続元状態 + 架線残量)
        int rows = (toolMode == WireConnectorItem.TOOL_MODE_GUI) ? 2 : 4;
        int totalH = ROW_H * rows + ROW_GAP * (rows - 1);

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = (sw - BADGE_W) / 2;
        int y = sh - HOTBAR_TOP_OFFSET - totalH + (int) yOffset;

        HudChrome.pushUiScale(g, sw, sh);   // 「常にサイズ2相当」counter-scale
        // 行 1: モード badge
        renderModeBadge(g, mc, x, y, BADGE_W, ROW_H, fade, toolMode);
        int yCursor = y + ROW_H + ROW_GAP;

        // 行 2: 選択中デザイン名
        WireType selectedType = held ? WireConnectorItem.readWireType(stack) : WireType.TWO_TIER;
        renderDesignRow(g, mc, x, yCursor, BADGE_W, ROW_H, fade, selectedType);
        yCursor += ROW_H + ROW_GAP;

        // 行 3 / 4 (設置モードのみ): 接続元状態 + 架線残量
        if (toolMode == WireConnectorItem.TOOL_MODE_SELECTION && held) {
            renderPendingRow(g, mc, x, yCursor, BADGE_W, ROW_H, fade, stack);
            yCursor += ROW_H + ROW_GAP;
            renderWireRow(g, mc, x, yCursor, BADGE_W, ROW_H, fade, stack);
        }
        HudChrome.popUiScale(g);
    }

    private static void renderModeBadge(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                         float fade, int toolMode) {
        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        int borderRgb = (toolMode == WireConnectorItem.TOOL_MODE_GUI)
                ? 0xBA68C8     // GUI = 紫
                : 0x66BB6A;    // SELECTION = 緑
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | borderRgb;
        HudChrome.drawRoundedRect(g, x, y, w, h, bg, border);

        String label = (toolMode == WireConnectorItem.TOOL_MODE_GUI)
                ? Component.translatable("tsu.wire_connector.hud_mode_gui").getString()
                : Component.translatable("tsu.wire_connector.hud_mode_selection").getString();

        int fgA = (int) (0xFF * fade);
        int fg = (fgA << 24) | borderRgb;
        HudChrome.drawCenteredLabel(g, mc.font, label, x, y, w, h, fg);
    }

    private static void renderDesignRow(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                         float fade, WireType type) {
        int bgA = (int) (0xC0 * fade);
        int borderA = (int) (0xFF * fade);
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | 0x333344;
        SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, border);
        SmoothRenderer.fillRoundedRect(g, x + 1, y + 1, w - 2, h - 2, 4f, bg);

        String prefix = Component.translatable("tsu.wire_connector.hud_design_label").getString();
        String designName = type.displayName().getString();
        String full = prefix + " " + designName;

        int fgA = (int) (0xFF * fade);
        int fg = (fgA << 24) | 0x80DEEA;
        HudChrome.drawCenteredLabel(g, mc.font, full, x, y, w, h, fg);
    }

    private static void renderPendingRow(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                          float fade, ItemStack stack) {
        int bgA = (int) (0xC0 * fade);
        int borderA = (int) (0xFF * fade);
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | 0x333344;
        SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, border);
        SmoothRenderer.fillRoundedRect(g, x + 1, y + 1, w - 2, h - 2, 4f, bg);

        BlockPos pending = readPendingFromStack(stack);
        String text;
        int rgb;
        if (pending == null) {
            text = Component.translatable("tsu.wire_connector.hud_pending_none").getString();
            rgb = 0xFFD54F; // 黄 = 待機中
        } else {
            text = Component.translatable("tsu.wire_connector.hud_pending_set",
                    pending.getX(), pending.getY(), pending.getZ()).getString();
            rgb = 0x66BB6A; // 緑 = 接続元決定済み
        }

        int fgA = (int) (0xFF * fade);
        int fg = (fgA << 24) | rgb;
        HudChrome.drawCenteredLabel(g, mc.font, text, x, y, w, h, fg);
    }

    /** 設置モードの架線残量行: タンク残量 (= あと何ブロック設置できるか) をリアルタイム表示。
     *  クリエイティブは無制限表示。 */
    private static void renderWireRow(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                                       float fade, ItemStack stack) {
        int bgA = (int) (0xC0 * fade);
        int borderA = (int) (0xFF * fade);
        int bg = (bgA << 24) | 0x1a1a2e;
        int border = (borderA << 24) | 0x333344;
        SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, border);
        SmoothRenderer.fillRoundedRect(g, x + 1, y + 1, w - 2, h - 2, 4f, bg);

        boolean creative = mc.player != null && mc.player.getAbilities().instabuild;
        String text;
        int rgb;
        if (creative) {
            text = Component.translatable("tsu.wire_connector.hud_wire_creative").getString();
            rgb = 0x4FC3F7; // cyan
        } else {
            int remain = WireConnectorItem.readWireTank(stack);
            text = Component.translatable("tsu.wire_connector.hud_wire_remaining", remain).getString();
            rgb = remain > 0 ? 0x66BB6A : 0xEF5350; // 緑 / 残量 0 は赤
        }
        int fgA = (int) (0xFF * fade);
        int fg = (fgA << 24) | rgb;
        HudChrome.drawCenteredLabel(g, mc.font, text, x, y, w, h, fg);
    }

    /** ItemStack の CustomData から pending pos を読む (= WireConnectorItem の private logic と同等)。 */
    private static BlockPos readPendingFromStack(ItemStack stack) {
        var cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        var tag = cd.copyTag();
        if (!tag.contains("pendingPos")) return null;
        return BlockPos.of(tag.getLong("pendingPos"));
    }

    private static ItemStack findHeldTool(Minecraft mc) {
        return belugalab.tsu.api.HeldTools.find(mc.player, ModItems.WIRE_CONNECTOR.get());
    }

}
