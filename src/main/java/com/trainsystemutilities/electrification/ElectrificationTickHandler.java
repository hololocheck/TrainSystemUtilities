package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.wire.EnergizedWireState;
import com.trainsystemutilities.electrification.wire.WireSyncBroadcaster;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 給電状態の tick ライフサイクル管理。
 *
 * <p>{@link ServerTickEvent.Pre} で各 level の {@link EnergizedWireState#beginTick()} を呼んで
 * pending クリア。tick 中に各 substation BE が pending に OR していく。
 * {@link ServerTickEvent.Post} で commitTick を呼び、変化があれば全プレイヤーへ broadcast。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class ElectrificationTickHandler {

    private ElectrificationTickHandler() {}

    @SubscribeEvent
    public static void onPre(ServerTickEvent.Pre event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            EnergizedWireState.get(level).beginTick();
        }
    }

    /** 電化列車 sync 配信用 tick カウンタ (= 1 秒 = 20 tick 毎)。 */
    private static int trainSyncCountdown = 0;

    @SubscribeEvent
    public static void onPost(ServerTickEvent.Post event) {
        // 変電所の給電 tick: SavedData ベースで全変電所を走査し markEnergized() を実行。
        // チャンクロード非依存。BE.serverTick は廃止 (= 変電所チャンクが非ロードでも給電継続)。
        SubstationTickHandler.tickAll(event.getServer());

        for (ServerLevel level : event.getServer().getAllLevels()) {
            EnergizedWireState state = EnergizedWireState.get(level);
            if (state.commitTick()) {
                // 通電状態が変化したのでクライアントへ再配信
                WireSyncBroadcaster.broadcast(level);
            }
        }

        // 列車が停車中でも給電が走るよう、毎 tick で全電化列車をスキャン。
        // ついでに「電化列車でパンタ全折畳」なら速度を 0 に強制してロックする。
        com.trainsystemutilities.electrification.contraption.ContraptionElectrificationTicker
                .tickAll(event.getServer());

        // 電化列車スナップショット配信: 1 秒間隔
        if (--trainSyncCountdown <= 0) {
            trainSyncCountdown = 20;
            com.trainsystemutilities.network.TrainElectrificationSyncPayload.broadcast(event.getServer());
        }
    }
}
