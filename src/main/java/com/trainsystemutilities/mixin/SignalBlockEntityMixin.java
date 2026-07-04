package com.trainsystemutilities.mixin;

import com.simibubi.create.content.trains.signal.SignalBlockEntity;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.Create;
import com.trainsystemutilities.schedule.CouplingSignalController;
import com.trainsystemutilities.schedule.TrainCouplingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create の信号ブロックエンティティにフックして、
 * 連結待ち駅の近くの信号に赤青点滅オーバーライドを設定する。
 */
@Mixin(value = SignalBlockEntity.class, remap = false)
public abstract class SignalBlockEntityMixin {

    /** DEBUG: SignalBlockEntityMixin の inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_SIGNAL_BE_INJECT = false;

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void trainsystemutilities$afterTick(CallbackInfo ci) {
        if (DEBUG_DISABLE_SIGNAL_BE_INJECT) return;
        SignalBlockEntity self = (SignalBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide()) return;

        // 5tickごとにチェック（毎tick不要）
        if (self.getLevel().getGameTime() % 5 != 0) return;

        boolean foundCoupling = false;
        boolean foundDecoupling = false;

        // この信号が属するグラフ内で、近くの連結/切り離し待ち駅を探す
        try {
            var edgePoint = self.edgePoint;
            if (edgePoint != null) {
                var boundary = edgePoint.getEdgePoint();
                if (boundary instanceof SignalBoundary) {
                    net.minecraft.core.BlockPos signalPos = self.getBlockPos();

                    for (var graph : Create.RAILWAYS.trackNetworks.values()) {
                        for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                            net.minecraft.core.BlockPos stationPos = station.getBlockEntityPos();
                            if (stationPos == null || signalPos.distSqr(stationPos) >= 32 * 32)
                                continue;

                            if (TrainCouplingManager.isStationWaitingForCoupling(station.name)) {
                                foundCoupling = true;
                            }
                            if (TrainCouplingManager.isStationDecoupling(station.name)) {
                                foundDecoupling = true;
                            }
                        }
                        if (foundCoupling || foundDecoupling) break;
                    }
                }
            }
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[SignalMixin] coupling station scan failed", e); /* ignore */ }

        var level = self.getLevel();
        var pos = self.getBlockPos();
        CouplingSignalController.SignalOverrideState desired = foundCoupling
                ? CouplingSignalController.SignalOverrideState.RED_BLUE_BLINK
                : foundDecoupling
                        ? CouplingSignalController.SignalOverrideState.RED_WHITE_SIMULTANEOUS
                        : CouplingSignalController.SignalOverrideState.NONE;
        CouplingSignalController.SignalOverrideState prev =
                CouplingSignalController.getSignalOverride(level, pos);
        if (desired != CouplingSignalController.SignalOverrideState.NONE) {
            CouplingSignalController.setSignalOverride(level, pos, desired);
            // #8 MP fix: client は server static map を読めないので 5tick 毎に re-assert 配信。
            // 後から近づいた player にも届き、 coupling 完了で配信が止まれば client TTL で自然消灯する。
            CouplingSignalController.broadcastOverride(level, pos, desired);
        } else if (prev != CouplingSignalController.SignalOverrideState.NONE) {
            CouplingSignalController.clearSignalOverride(level, pos);
            CouplingSignalController.broadcastOverride(level, pos,
                    CouplingSignalController.SignalOverrideState.NONE);
        }
    }
}
