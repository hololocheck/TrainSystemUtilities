package com.trainsystemutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * StationRangeToolItem からの client-side GUI open エントリーポイント。
 * 起動 logic を {@link StationGroupSaveScreen} に集約し、Item クラスを
 * client API に依存させないため間接層を挟む。
 */
@OnlyIn(Dist.CLIENT)
public final class StationGroupSaveScreenOpener {
    private StationGroupSaveScreenOpener() {}
    public static void open(BlockPos p1, BlockPos p2) {
        Minecraft.getInstance().setScreen(new StationGroupSaveScreen(p1, p2));
    }
    /** 駅グループ管理画面を開く (TOOL_MODE_GUI から)。 */
    public static void openManageScreen() {
        StationGroupManageScreen.open();
    }
}
