package com.trainsystemutilities.event;

import belugalab.mcss3.util.concurrent.StaticCacheRegistry;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Server side world unload hook で {@link StaticCacheRegistry#purgeAll()} を発火する。
 *
 * <p>BlockEntity / 静的 holder が static init で {@link StaticCacheRegistry#register}
 * 経由で purge callback を登録すると、 本 handler から一括 dispatch される。
 *
 * <p>HOTFIX N+0.5 #2/#7 で導入し、 P0-1 #11 で StaticCacheRegistry pattern に升格。
 * dim 情報を含まない static measurement map (= WF-G blockentity-lifecycle-22) の
 * cross-world contamination を防ぐ。 本格的な per-dim purge は P0-5 で実施予定。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class WorldLifecycleHotfixHandler {

    private WorldLifecycleHotfixHandler() {}

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        Level level = (Level) event.getLevel();
        // server 側 ServerLevel のみ。 client 側 ClientLevel は別 lifecycle (= P0-12 で扱う)。
        if (!(level instanceof ServerLevel serverLevel)) return;
        // P0-5 #3: dim-aware purge (= 互換 callback + dim 指定 callback の双方を dispatch)。
        StaticCacheRegistry.purgeForDim(serverLevel.dimension());
    }
}
