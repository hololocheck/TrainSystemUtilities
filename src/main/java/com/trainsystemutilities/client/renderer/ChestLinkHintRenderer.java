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
    private static final int HINT_W = belugalab.tsu.api.HudConstants.BADGE_W;
    private static final int HINT_H = 16;

    // MCSS 共通アニメ追跡
    private static final belugalab.tsu.api.HudAnimState anim =
            new belugalab.tsu.api.HudAnimState(220_000_000L, 160_000_000L);
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
            lastLabel = lastAlreadyLinked
                    ? "\u2714 \u30ea\u30f3\u30af\u6e08\u307f \u00b7 shift+\u30db\u30a4\u30fc\u30eb\u62bc\u3057\u8fbc\u307f\u3067\u518d\u767b\u9332"
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
        belugalab.tsu.api.HudChrome.pushUiScale(g, sw / 2f, y + HINT_H / 2f);
        belugalab.tsu.api.HudChrome.drawRoundedRect(g, x, y, HINT_W, HINT_H,
                (bgA << 24) | bgRgb, (borderA << 24) | borderRgb);
        int lw = mc.font.width(lastLabel);
        g.drawString(mc.font, lastLabel, x + (HINT_W - lw) / 2, y + (HINT_H - 9) / 2,
                (fgA << 24) | fgRgb, false);
        belugalab.tsu.api.HudChrome.popUiScale(g);
    }

    private static ItemStack findHeldTool(Minecraft mc) {
        return belugalab.tsu.api.HeldTools.find(mc.player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
