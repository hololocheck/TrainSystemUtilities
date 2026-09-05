package com.trainsystemutilities.client.renderer;

import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 列車プリセット設置結果 HUD: 共通 {@link com.manta.api.hud.HudToast} に委譲する thin wrapper。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class TrainPresetPlaceResultHudRenderer {

    private TrainPresetPlaceResultHudRenderer() {}

    public static void show(String title, String detail) {
        com.manta.api.hud.HudToast.success(title, detail).show();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        com.manta.api.hud.HudToast.render(event.getGuiGraphics());
    }
}
