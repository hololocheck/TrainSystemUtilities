package com.trainsystemutilities.client.wiki;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Wiki capture (既定 F8) で TSU の画面を撮る登録。
 *
 * <p><b>キー自体は Manta が持つ</b> ({@link com.manta.api.wiki.WikiCaptureKey})。
 * 2026-09-13 まではこのクラスが {@code KeyMapping} を所有しており、ゲームのキー設定に
 * <b>「TSU Wiki」</b>というカテゴリで出ていた —— しかし wiki キャプチャは Manta wiki の
 * 機能で、consumer が増えるたびに同じキーが別カテゴリで並ぶことになる（ユーザー指摘）。
 *
 * <p>ここに残るのは「TSU の画面なら撮る」という 1 点だけ。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class WikiCaptureKeybind {

    private WikiCaptureKeybind() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> com.manta.api.wiki.WikiCaptureKey.register(() -> {
            // 開いている画面が無ければ Manta の他の handler に譲る。
            if (Minecraft.getInstance().screen == null) return false;
            WikiCapture.captureCurrentScreen();
            return true;
        }));
    }
}
