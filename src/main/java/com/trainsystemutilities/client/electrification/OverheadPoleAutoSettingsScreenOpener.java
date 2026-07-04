package com.trainsystemutilities.client.electrification;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 架線柱自動配置ツールの設定スクリーン open 用 dispatcher。 Item クラスを
 * client API に依存させないため間接層を挟む ({@link com.trainsystemutilities.client.gui.StationGroupSaveScreenOpener}
 * と同じパターン)。
 */
@OnlyIn(Dist.CLIENT)
public final class OverheadPoleAutoSettingsScreenOpener {
    private OverheadPoleAutoSettingsScreenOpener() {}

    public static void open() {
        Minecraft.getInstance().setScreen(new OverheadPoleAutoSettingsScreen());
    }
}
