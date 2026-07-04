package com.trainsystemutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * WireConnectorItem からの client-side GUI open エントリーポイント。
 * Item クラスを client API (Minecraft / Screen) に直接依存させないための間接層
 * (= dedicated server で Screen が force-load されてクラッシュするのを防ぐ)。
 * {@link StationGroupSaveScreenOpener} と同じ規約。
 */
@OnlyIn(Dist.CLIENT)
public final class WireConnectorScreenOpener {
    private WireConnectorScreenOpener() {}

    public static void open() {
        Minecraft.getInstance().setScreen(new WireConnectorScreen());
    }
}
