package com.trainsystemutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * {@code MemoryCardItem} からの client-side GUI open エントリーポイント。
 * {@link StationGroupSaveScreenOpener} と同じ規約 (R3.9.1: Item から
 * {@code Minecraft.getInstance().setScreen} を直呼びしない)。
 */
@OnlyIn(Dist.CLIENT)
public final class MemoryCardResetScreenOpener {
    private MemoryCardResetScreenOpener() {}

    public static void open(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new MemoryCardResetScreen(hand));
    }
}
