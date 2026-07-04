package com.trainsystemutilities.client.transit;

import belugalab.tsu.api.HudAnimState;
import belugalab.tsu.api.HudChrome;
import belugalab.tsu.api.HudConstants;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * ナビゲーション中に画面上部に「目的駅 / 残り距離」を表示する小さい HUD。
 * Screen が開いていなくても常時描画される。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class TransitNavStatusHud {

    private static final HudAnimState anim =
            new HudAnimState(HudConstants.ENTRY_ANIM_NANOS, HudConstants.EXIT_ANIM_NANOS);

    // 退場アニメ中も描画できるよう、最後に表示した内容を保持する (= TransitDetailHudRenderer と同型)。
    private static String lastLine1 = "";
    private static String lastLine2 = "";
    private static int lastBoxW = 0;

    private TransitNavStatusHud() {}

    @SubscribeEvent
    public static void onRender(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (mc.screen != null) return;   // R2.3.2: 開いている screen 上には描画しない

        var path = TransitNavClientState.path();
        boolean show = TransitNavClientState.active() && !path.isEmpty();

        if (show) {
            // 残り距離 (プレイヤー位置 → 終点)
            var goal = path.get(path.size() - 1);
            double dx = goal.getX() + 0.5 - mc.player.getX();
            double dz = goal.getZ() + 0.5 - mc.player.getZ();
            int remaining = (int) Math.sqrt(dx * dx + dz * dz);

            String name = TransitNavClientState.targetName();
            lastLine1 = "🧭 " + Component.translatable("tsu.transit_terminal.nav_to_fmt", name).getString();
            lastLine2 = Component.translatable("tsu.transit_terminal.nav_remaining_fmt", remaining).getString();
            lastBoxW = Math.max(mc.font.width(lastLine1), mc.font.width(lastLine2)) + 16;
        }

        anim.update(show);
        if (!anim.shouldRender() || lastBoxW == 0) return;

        float fade = anim.fade();

        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int boxW = lastBoxW;
        int boxH = 28;
        int x = (sw - boxW) / 2;
        int y = 8;

        // 「常にサイズ2相当」: 上中央アンカーを pivot に counter-scale (G=2 で無変更)。
        HudChrome.pushUiScale(g, sw / 2f, (float) y);
        HudChrome.drawRoundedRect(g, x, y, boxW, boxH,
                HudChrome.fadeAlpha(0xE612122A, fade), HudChrome.fadeAlpha(0xFF4FC3F7, fade));
        g.drawString(mc.font, lastLine1, x + 8, y + 4, HudChrome.fadeAlpha(0xFFFFFFFF, fade), false);
        g.drawString(mc.font, lastLine2, x + 8, y + 16, HudChrome.fadeAlpha(0xFF80DEEA, fade), false);
        HudChrome.popUiScale(g);
    }
}
