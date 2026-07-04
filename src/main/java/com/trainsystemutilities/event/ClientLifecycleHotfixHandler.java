package com.trainsystemutilities.event;

import belugalab.mcss3.util.concurrent.ClientCacheRegistry;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.gui.MonitorClientCache;
import com.trainsystemutilities.client.gui.RailwayAnnouncementClientState;
import com.trainsystemutilities.client.gui.TrainPresetClientCache;
import com.trainsystemutilities.client.preview.TrainPreviewCache;
import com.trainsystemutilities.client.transit.TransitNavClientState;
import com.trainsystemutilities.client.transit.TransitTerminalClientCache;
import com.trainsystemutilities.client.electrification.ClientPantographContactState;
import com.trainsystemutilities.client.electrification.ClientTrainElectrificationCache;
import com.trainsystemutilities.client.electrification.ClientWireStore;
import com.trainsystemutilities.station.StationGroupClientCache;
import com.trainsystemutilities.station.TicketConfigClientCache;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * HOTFIX N+0.5 #6 / #8: client 側 static cache lifecycle 接続。
 *
 * <p>{@link ClientPlayerNetworkEvent.LoggingOut} で 4 つの client cache を一括 clear する。
 * 旧コードはこれら cache に lifecycle 接続が無く、 server A → server B に切替えると前
 * server の state (= 駅レイアウト / 案内設定 / 経路 / 時刻表) が新 server に流入する
 * (WF-G client-state-8/9/12 等)。
 *
 * <p>本格的な {@link com.trainsystemutilities.client.gui.MonitorClientCache} 系
 * lifecycle 統一は P0-12 で実施。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class ClientLifecycleHotfixHandler {

    private ClientLifecycleHotfixHandler() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MonitorClientCache.clear();
        RailwayAnnouncementClientState.clear();
        TransitNavClientState.clear();
        TransitTerminalClientCache.clearAll();
        // P0-1 #7 拡張: TrainPreview / TrainPresetClient cache の disconnect cleanup を追加
        TrainPreviewCache.clear();
        TrainPresetClientCache.clear();
        // TSU-07: server 由来 client cache の disconnect cleanup を追加 (= server 切替時の state 流入防止)
        StationGroupClientCache.clear();
        TicketConfigClientCache.clear();
        ClientWireStore.clear();
        ClientTrainElectrificationCache.clear();
        ClientPantographContactState.clear();
        // P0-5 #4: ClientCacheRegistry に register された全 callback も dispatch
        // (将来の ManagedCache 利用 cache は self-register する想定)
        ClientCacheRegistry.purgeAll();
        TrainSystemUtilities.LOGGER.info(
                "[Cleanup] cleared client caches on LoggingOut (manual 6 + registry {} cb)",
                ClientCacheRegistry.registeredCount());
    }
}
