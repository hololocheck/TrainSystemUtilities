package com.trainsystemutilities.client.renderer;

import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * プリセット保存成功 / 失敗の HUD: 共通 {@link com.manta.api.hud.HudToast} に委譲する thin wrapper。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class SaveResultHudRenderer {

    private SaveResultHudRenderer() {}

    /** 保存結果 HUD を共通 HudToast に委譲。 */
    public static void show(boolean success, String name, String detail) {
        String title = name == null || name.isEmpty()
                ? (success ? "\u30d7\u30ea\u30bb\u30c3\u30c8\u3092\u4fdd\u5b58\u3057\u307e\u3057\u305f"
                           : "\u4fdd\u5b58\u306b\u5931\u6557\u3057\u307e\u3057\u305f")
                : name;
        if (success) {
            com.manta.api.hud.HudToast.success(title, detail).show();
        } else {
            com.manta.api.hud.HudToast.error(title, detail).show();
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        com.manta.api.hud.HudToast.render(event.getGuiGraphics());
    }
}
