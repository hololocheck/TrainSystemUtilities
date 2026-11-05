package com.trainsystemutilities.client.electrification;

import com.manta.api.hud.HeldTools;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.WireConnectorTypeAction;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.client.ToolsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * 架線接続ツール所持時のクライアント入力処理。
 *
 * <p>Alt+ホイール: 選択中の架線デザインタイプを循環。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class WireConnectorClientHandler {

    private WireConnectorClientHandler() {}

    /** スクロール 2 重発火対策 (= BelugaExperience 標準 ScrollCooldown 180ms)。 */
    private static final com.manta.api.hud.ScrollCooldown SCROLL_COOLDOWN =
            new com.manta.api.hud.ScrollCooldown();

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        ItemStack stack = findHeldTool();
        if (stack.isEmpty()) return;

        if (!com.manta.api.hud.WheelInput.mods(mc.getWindow().getWindow()).alt()) return;

        double dy = event.getScrollDeltaY();
        if (com.manta.api.hud.WheelInput.isDead(dy)) return;

        if (!SCROLL_COOLDOWN.tryAccept()) {
            event.setCanceled(true);
            return;
        }

        int delta = com.manta.api.hud.WheelInput.direction(dy);
        ToolsClient.wireType(WireConnectorTypeAction.ACTION_MODE_CYCLE, delta);
        event.setCanceled(true);
    }

    private static ItemStack findHeldTool() {
        return HeldTools.find(Minecraft.getInstance().player, ModItems.WIRE_CONNECTOR.get());
    }
}
