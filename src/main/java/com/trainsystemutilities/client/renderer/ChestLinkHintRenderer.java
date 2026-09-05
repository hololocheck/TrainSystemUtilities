package com.trainsystemutilities.client.renderer;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.TrainPresetToolClientHandler;
import com.trainsystemutilities.item.TrainPresetToolItem;
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
 * 列車プリセットツール所持中、視線先がチェスト系 (Container) ブロックのとき、
 * 「shift+ホイール押し込みで登録」のヒントを画面中央下に小さく表示。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class ChestLinkHintRenderer {

    private static final int HOTBAR_TOP_OFFSET = 110;
    private static final int HINT_W = com.manta.api.hud.HudConstants.BADGE_W;
    private static final int HINT_H = 16;

    // MCSS 共通アニメ追跡
    private static final com.manta.api.hud.HudAnimState anim =
            new com.manta.api.hud.HudAnimState(220_000_000L, 160_000_000L);
    /** ラベル左に描く registry icon (空 = icon なし)。W7-1 で ✔ glyph から移行。 */
    private static String lastIconId = "";
    /** icon の実寸と text までの間隔 (px)。 */
    private static final int ICON_PX = 8;
    private static final int ICON_GAP = 3;
    private static String lastLabel = "";
    private static boolean lastAlreadyLinked = false;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            anim.reset();
            return;
        }

        ItemStack tool = findHeldTool(mc);
        boolean visibleNow = !tool.isEmpty()
                && TrainPresetToolItem.getToolMode(tool) != TrainPresetToolItem.TOOL_MODE_PLACE;
        BlockPos chestPos = visibleNow ? TrainPresetToolClientHandler.findLookedAtChest(mc) : null;
        visibleNow = visibleNow && chestPos != null;

        anim.update(visibleNow);

        if (visibleNow) {
            BlockPos linked = TrainPresetToolItem.getLinkedChestPos(tool);
            lastAlreadyLinked = linked != null && linked.equals(chestPos);
            // MANTA_5 Wave 7 / W7-1 (R4.23.1): \u5148\u982d\u306e \u2714 \u306f\u72b6\u614b iconography \u306a\u306e\u3067
            // registry icon (manta:check) \u3078\u5206\u96e2\u3057\u305f\u3002**escape \u8a18\u6cd5\u3060\u3063\u305f\u305f\u3081
            // control-glyph gate \u304b\u3089\u4e0d\u53ef\u8996**\u3060\u3063\u305f (gate \u306f 2026-07-26 \u306b escape \u5fa9\u53f7\u3092\u8ffd\u52a0)\u3002
            // \u26ab \u306f control \u3067\u306f\u306a\u304f\u884c\u982d bullet \u306e typography \u306a\u306e\u3067 text \u306e\u307e\u307e\u6b8b\u3059\u3002
            lastIconId = lastAlreadyLinked ? "manta:check" : "";
            lastLabel = lastAlreadyLinked
                    ? "\u30ea\u30f3\u30af\u6e08\u307f \u00b7 shift+\u30db\u30a4\u30fc\u30eb\u62bc\u3057\u8fbc\u307f\u3067\u518d\u767b\u9332"
                    : "\u26ab shift+\u30db\u30a4\u30fc\u30eb\u62bc\u3057\u8fbc\u307f\u3067\u8cc7\u6750\u30c1\u30a7\u30b9\u30c8\u3068\u3057\u3066\u767b\u9332";
        }

        if (!anim.shouldRender() || lastLabel.isEmpty()) return;

        float fade = anim.fade();
        int yOffset = visibleNow
                ? (int) ((1f - anim.entryEased()) * 14f)
                : (int) (anim.exitEased() * 14f);

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = (sw - HINT_W) / 2;
        int y = sh - HOTBAR_TOP_OFFSET - HINT_H + yOffset;

        int bgRgb = lastAlreadyLinked ? 0x143020 : 0x1a1a2e;
        int borderRgb = lastAlreadyLinked ? 0x66bb6a : 0x4fc3f7;
        int fgRgb = lastAlreadyLinked ? 0x66bb6a : 0x80deea;
        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        int fgA = (int) (0xFF * fade);
        // 「常にサイズ2相当」: 中央アンカーを pivot に counter-scale (G=2 で無変更)。
        com.manta.api.hud.HudChrome.pushUiScale(g, sw / 2f, y + HINT_H / 2f);
        com.manta.api.hud.HudChrome.drawRoundedRect(g, x, y, HINT_W, HINT_H,
                (bgA << 24) | bgRgb, (borderA << 24) | borderRgb);
        // icon がある状態では「icon + gap + text」を 1 グループとして中央寄せする
        // (text だけを中央に置くと icon 幅ぶん左右非対称になる)。
        int iconW = lastIconId.isEmpty() ? 0 : ICON_PX + ICON_GAP;
        int lw = mc.font.width(lastLabel);
        int gx = x + (HINT_W - (iconW + lw)) / 2;
        if (!lastIconId.isEmpty()) {
            com.manta.api.render.Icons.draw(g, lastIconId, gx,
                    y + (HINT_H - ICON_PX) / 2f, ICON_PX, ICON_PX, (fgA << 24) | fgRgb);
        }
        g.drawString(mc.font, lastLabel, gx + iconW, y + (HINT_H - 9) / 2,
                (fgA << 24) | fgRgb, false);
        com.manta.api.hud.HudChrome.popUiScale(g);
    }

    private static ItemStack findHeldTool(Minecraft mc) {
        return com.manta.api.hud.HeldTools.find(mc.player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
