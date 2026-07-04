package com.trainsystemutilities.client.electrification;

import belugalab.tsu.api.HeldTools;
import belugalab.tsu.api.ModifierKeys;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.WireConnectorTypePayload;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 架線接続ツール所持時のクライアント入力処理。
 *
 * <p>Alt+ホイール: 選択中の架線デザインタイプを循環。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class WireConnectorClientHandler {

    private WireConnectorClientHandler() {}

    /** スクロール 2 重発火対策 (= BelugaExperience 標準 ScrollCooldown 180ms)。 */
    private static final belugalab.tsu.api.ScrollCooldown SCROLL_COOLDOWN =
            new belugalab.tsu.api.ScrollCooldown();

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        ItemStack stack = findHeldTool();
        if (stack.isEmpty()) return;

        long window = mc.getWindow().getWindow();
        if (!ModifierKeys.alt(window)) return;

        double dy = event.getScrollDeltaY();
        if (Math.abs(dy) < 0.0001) return;

        if (!SCROLL_COOLDOWN.tryAccept()) {
            event.setCanceled(true);
            return;
        }

        int delta = dy > 0 ? 1 : -1;
        PacketDistributor.sendToServer(
                new WireConnectorTypePayload(WireConnectorTypePayload.ACTION_MODE_CYCLE, delta));
        event.setCanceled(true);
    }

    private static ItemStack findHeldTool() {
        return HeldTools.find(Minecraft.getInstance().player, ModItems.WIRE_CONNECTOR.get());
    }
}
