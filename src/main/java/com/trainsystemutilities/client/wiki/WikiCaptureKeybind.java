package com.trainsystemutilities.client.wiki;

import com.mojang.blaze3d.platform.InputConstants;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Wiki capture キーバインド。default = F8 (vanilla 未使用)。
 *
 * <p>V2 screen を開いた状態で F8 を押すと
 * {@link WikiCapture#captureCurrentScreen()} が走る:
 *   - DynamicTexture を即時登録 → wiki の embed:screen で即反映
 *   - PNG を {@code <gamedir>/screenshots/wiki/<id>.png} に保存
 *
 * <p>Controls 画面でユーザがキー変更可能。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD,
        value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class WikiCaptureKeybind {

    public static final String CATEGORY = "key.categories.tsu.wiki";
    public static final String CAPTURE_KEY = "key.tsu.wiki_capture";

    public static KeyMapping CAPTURE = new KeyMapping(
            CAPTURE_KEY, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);

    private WikiCaptureKeybind() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CAPTURE);
        // Screen-level key event subscription (Forge EVENT_BUS)
        NeoForge.EVENT_BUS.addListener(WikiCaptureKeybind::onScreenKeyPressed);
    }

    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        // matches() は currently bound key と比較 (ユーザが変更しても追従)
        if (CAPTURE.matches(event.getKeyCode(), event.getScanCode())) {
            WikiCapture.captureCurrentScreen();
            event.setCanceled(true);
        }
    }
}
