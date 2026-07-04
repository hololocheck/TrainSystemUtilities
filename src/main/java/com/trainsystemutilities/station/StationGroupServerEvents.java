package com.trainsystemutilities.station;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.StationGroupListResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;

/**
 * プレイヤーの世界参加時 + 定期 (1 秒) に駅グループ / 列車スケジュールを更新する hook。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public class StationGroupServerEvents {

    /** 列車スケジュールキャッシュの更新間隔 (server tick = 20/sec)。 */
    private static final int SCHEDULE_UPDATE_INTERVAL_TICKS = 20;
    private static int tickCounter = 0;
    /** 列車位置 broadcast 間隔: schedule 更新 (1Hz) と分離し高頻度化 = map アイコン/速度表示を滑らかに。 4 tick = 5Hz。 */
    private static final int POSITION_BROADCAST_INTERVAL_TICKS = 4;
    private static int posTickCounter = 0;

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var server = player.server;
        if (server == null) return;
        var all = new ArrayList<>(StationGroupSavedData.get(server).all());
        PacketDistributor.sendToPlayer(player, new StationGroupListResponsePayload(all));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 列車位置は schedule 更新と分離して高頻度 broadcast (= map アイコン/速度表示の滑らかさ)。
        if (++posTickCounter >= POSITION_BROADCAST_INTERVAL_TICKS) {
            posTickCounter = 0;
            try {
                com.trainsystemutilities.network.TrainPositionBroadcaster.broadcast(event.getServer());
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.debug("[TrainPos] broadcast threw: {}", t.getMessage());
            }
            try {
                // 路線種別 (折り返し/環状) の実挙動観測 (速度符号反転 tracker)
                com.trainsystemutilities.route.TrainRouteTracker.sample(event.getServer());
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.debug("[RouteTracker] sample threw: {}", t.getMessage());
            }
        }
        tickCounter++;
        if (tickCounter < SCHEDULE_UPDATE_INTERVAL_TICKS) return;
        tickCounter = 0;
        try {
            TrainScheduleCache.updateAll(event.getServer());
            // 列車の駅遷移を監視してEMA学習
            LegMeasurementListener.update(event.getServer());
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn("[ScheduleCache] update threw: {}", t.getMessage());
        }
    }

    /** プレイヤー切断時: 進行中のナビ計算をキャンセル。 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.trainsystemutilities.station.routing.NavPathDispatcher.cancelFor(player.getUUID());
            // SECURITY (TSU-06): 未完了の image upload session を破棄
            com.trainsystemutilities.network.ImageUploadChunkPayload.clearForPlayer(player.getUUID());
        }
    }

    /** サーバ停止時: ワーカープール終了 + キャッシュクリア。 */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        com.trainsystemutilities.station.routing.NavPathDispatcher.shutdown();
        com.trainsystemutilities.station.routing.PlatformCache.invalidateAll();
        // TSU-08: nav-field のバックグラウンド build を停止 (= 旧 ServerLevel を掴んだ worker の継続を止める。
        // executor は統合サーバ再 open に備え生かす)。 cache clear より前に cancel する。
        com.trainsystemutilities.station.routing.navfield.NavFieldBuildScheduler.cancelAll();
        // 統合サーバ再入で前ワールドの static 状態が残らないよう、 停止時に clear する
        // (= PlatformCache と対称化)。
        com.trainsystemutilities.station.routing.navfield.NavFieldCache.clear();
        com.trainsystemutilities.station.routing.navgraph.NavGraphCache.clear();
        com.trainsystemutilities.electrification.wire.EnergizedWireState.clearAll();
        com.trainsystemutilities.electrification.SubstationTickHandler.clearCache();
        // TSU-23: coupling signal の static state (override / station cache / coupling-wait 等) も
        // 統合サーバ再 open 間に残らないよう停止時に clear。
        com.trainsystemutilities.schedule.CouplingSignalController.clearAll();
    }
}
