package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.wire.WireSyncBroadcaster;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * FE 電化システムのサーバ側イベントフック。
 *
 * <p>プレイヤー login / ディメンション変更時に、現在いるレベルの架線接続を全送信。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class ElectrificationServerEvents {

    private ElectrificationServerEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            WireSyncBroadcaster.sendTo(sp);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            WireSyncBroadcaster.sendTo(sp);
        }
    }
}
