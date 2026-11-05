package com.trainsystemutilities.client.transit;

import com.manta.api.hud.HudAnimState;
import com.manta.api.hud.HudChrome;
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
        HudChrome.panel(g, x, y, W, H, fade, 0x12122A, 0x4FC3F7);

        int titleC = com.manta.api.hud.HudChrome.fadeAlpha(0xFF4FC3F7, fade);
        com.manta.api.text.MantaText.draw(g, mc.font, Component.translatable("tsu.transit_terminal.title").getString(), x + 8, y + 5, titleC);

        ComposedRouteFinder.ComposedRoute r = TransitTerminalState.lastResult();
        int infoC = com.manta.api.hud.HudChrome.fadeAlpha(0xFFE0E0E0, fade);
        if (r != null && r.found()) {
            int totalSec = r.totalTicks() / 20;
            String summary = Component.translatable("tsu.transit_terminal.minihud_route_fmt",
                    truncate(mc, r.fromGroupName(), 40),
                    truncate(mc, r.toGroupName(), 40),
                    totalSec / 60, totalSec % 60).getString();
            com.manta.api.text.MantaText.draw(g, mc.font, summary, x + 8, y + 17, infoC);
        } else {
            int hintC = com.manta.api.hud.HudChrome.fadeAlpha(0xC080808F, fade);
            com.manta.api.text.MantaText.draw(g, mc.font, Component.translatable("tsu.transit_terminal.minihud_open_hint").getString(), x + 8, y + 17, hintC);
        }
        // 右下に「開く」アイコン — W7-1: raw ▶ を manta:play icon へ (fade の alpha はそのまま乗る)。
        int rightArrowC = com.manta.api.hud.HudChrome.fadeAlpha(0xFFFFD54F, fade);
        com.manta.api.render.Icons.draw(g, "manta:play", x + W - 13, y + 16, 9, rightArrowC);
        HudChrome.popUiScale(g);
    }

    private static String truncate(Minecraft mc, String s, int w) {
        return com.manta.api.hud.HudText.clip(mc.font, s, w);
    }

    private static ItemStack findHeld(Minecraft mc) {
        return com.manta.api.hud.HeldTools.find(mc.player, ModItems.TRANSIT_TERMINAL.get());
    }
}
