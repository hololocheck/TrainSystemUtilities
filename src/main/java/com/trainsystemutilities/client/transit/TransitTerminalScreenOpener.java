package com.trainsystemutilities.client.transit;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * TransitTerminalItem からの client-side GUI open エントリーポイント。
 * Item クラスを client API (Minecraft / Screen) に直接依存させないための間接層
 * (= dedicated server で Screen が force-load されてクラッシュするのを防ぐ)。
 * {@link com.trainsystemutilities.client.gui.StationGroupSaveScreenOpener} と同じ規約。
 */
@OnlyIn(Dist.CLIENT)
public final class TransitTerminalScreenOpener {
    private TransitTerminalScreenOpener() {}

    public static void open() {
        Minecraft.getInstance().setScreen(new TransitTerminalScreen());
    }
}
