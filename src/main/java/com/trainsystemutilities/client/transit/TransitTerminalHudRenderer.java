package com.trainsystemutilities.client.transit;

import belugalab.tsu.api.HudAnimState;
import belugalab.tsu.api.HudChrome;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 乗り換え案内端末を手に持っているときに右下に出る passive な minimized 表示。
 *
 * <p>Phase 19 で interactive 部分は {@link TransitTerminalScreen} に移行した。
 * この HUD はもはや操作可能ではなく、最新の検索結果 / 「右クリックで開く」ヒントを
 * 小さく表示するだけ。実際の操作は item を右クリックして Screen を開く。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class TransitTerminalHudRenderer {

    private static final int W = 132;
    private static final int H = 36;
    private static final int RIGHT_MARGIN = 8;
    private static final int BOTTOM_MARGIN = 60;
    private static final long ENTRY_NANOS = 250_000_000L;
    private static final long EXIT_NANOS = 200_000_000L;
    private static final HudAnimState anim = new HudAnimState(ENTRY_NANOS, EXIT_NANOS);

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (mc.screen != null) return;

        ItemStack stack = findHeld(mc);
        boolean held = !stack.isEmpty();
        anim.update(held);
        if (!anim.shouldRender()) return;

        float fade = anim.fade();
        float yOffset = held ? (1f - fade) * 30f : anim.exitEased() * 30f;

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x = sw - W - RIGHT_MARGIN;
        int y = sh - BOTTOM_MARGIN - H + (int) yOffset;

        HudChrome.pushUiScale(g, (float) (x + W), (float) (y + H));  // 右下 pivot で size2 固定
        int bgA = (int) (0xE0 * fade);
        int borderA = (int) (0xFF * fade);
        HudChrome.drawRoundedRect(g, x, y, W, H, (bgA << 24) | 0x12122A, (borderA << 24) | 0x4FC3F7);

        int titleC = ((int) (0xFF * fade) << 24) | 0x4FC3F7;
        g.drawString(mc.font, Component.translatable("tsu.transit_terminal.title").getString(),
                x + 8, y + 5, titleC, false);

        ComposedRouteFinder.ComposedRoute r = TransitTerminalState.lastResult();
        int infoC = ((int) (0xFF * fade) << 24) | 0xE0E0E0;
        if (r != null && r.found()) {
            int totalSec = r.totalTicks() / 20;
            String summary = Component.translatable("tsu.transit_terminal.minihud_route_fmt",
                    truncate(mc, r.fromGroupName(), 40),
                    truncate(mc, r.toGroupName(), 40),
                    totalSec / 60, totalSec % 60).getString();
            g.drawString(mc.font, summary, x + 8, y + 17, infoC, false);
        } else {
            int hintC = ((int) (0xC0 * fade) << 24) | 0x80808F;
            g.drawString(mc.font, Component.translatable("tsu.transit_terminal.minihud_open_hint").getString(),
                    x + 8, y + 17, hintC, false);
        }
        // 右下に "▶" 開くアイコン
        int rightArrowC = ((int) (0xFF * fade) << 24) | 0xFFD54F;
        g.drawString(mc.font, "▶", x + W - 12, y + 17, rightArrowC, false);
        HudChrome.popUiScale(g);
    }

    private static String truncate(Minecraft mc, String s, int w) {
        return belugalab.tsu.api.HudText.clip(mc.font, s, w);
    }

    private static ItemStack findHeld(Minecraft mc) {
        return belugalab.tsu.api.HeldTools.find(mc.player, ModItems.TRANSIT_TERMINAL.get());
    }
}
