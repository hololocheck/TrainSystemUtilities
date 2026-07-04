package com.trainsystemutilities.client;

import belugalab.tsu.api.HeldTools;
import belugalab.tsu.api.ModifierKeys;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.StationRangeToolModePayload;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 駅範囲指定ツール所持時のクライアント入力処理。
 *
 * <p>列車プリセットツール ({@link TrainPresetToolClientHandler}) と同じく
 * <b>alt + ホイール</b> で edit mode (0 = 両方確定 / 1 = pos1 編集 / 2 = pos2 編集)
 * を循環。GUI が開いている間は無効。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class StationRangeToolClientHandler {

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
        boolean alt = ModifierKeys.alt(window);
        boolean ctrl = ModifierKeys.ctrl(window);
        if (!alt && !ctrl) return;

        double dy = event.getScrollDeltaY();
        if (Math.abs(dy) < 0.0001) return;

        if (!SCROLL_COOLDOWN.tryAccept()) {
            event.setCanceled(true);
            return;
        }

        int delta = dy > 0 ? 1 : -1;
        // ctrl + ホイール: action 1 = edit mode 循環
        // alt + ホイール: action 0 = tool mode 循環 (GUI/SELECTION/VIEW)
        int action = ctrl ? 1 : 0;
        PacketDistributor.sendToServer(new StationRangeToolModePayload(action, delta));
        event.setCanceled(true);
    }

    /** ctrl + マウスホイール押し込み (= ctrl + 中ボタン) で番線連番方向 (auto/left/right) を循環。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        ItemStack stack = findHeldTool();
        if (stack.isEmpty()) return;
        long window = mc.getWindow().getWindow();
        if (!ModifierKeys.ctrl(window)) return;
        // SELECTION モード以外では無効 (auto/left/right の意味があるのは範囲選択のみ)
        if (com.trainsystemutilities.item.StationRangeToolItem.getToolMode(stack)
                != com.trainsystemutilities.item.StationRangeToolItem.TOOL_MODE_SELECTION) return;
        // action 2 = numbering dir 循環
        PacketDistributor.sendToServer(new StationRangeToolModePayload(2, 1));
        event.setCanceled(true);
    }

    private static ItemStack findHeldTool() {
        return HeldTools.find(Minecraft.getInstance().player, ModItems.STATION_RANGE_TOOL.get());
    }
}
