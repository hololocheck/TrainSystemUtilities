package com.trainsystemutilities.mixin;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.schedule.CouplingApproachTracker;
import com.trainsystemutilities.schedule.CouplingApproachTracker.Phase;
import com.trainsystemutilities.schedule.TrainCouplingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.createmod.catnip.data.Pair;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 連結モード列車制御:
 * HEAD: 停車距離に達したら速度0にして移動を阻止
 * TAIL: 状態遷移 + 徐行速度制限
 */
@Mixin(value = Navigation.class, remap = false)
public abstract class NavigationMixin {

    @Shadow public Train train;
    @Shadow public Pair<UUID, Boolean> waitingForSignal;
    @Shadow public double distanceToSignal;
    @Shadow public double distanceToDestination;

    private static final double COUPLING_MAX_SPEED = 0.08;
    /** DEBUG: NavigationMixin の inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_NAV_INJECTS = false;

    /**
     * HEAD: Createのtick前に、停車距離以内なら速度を0にして
     * Createが列車を動かすのを阻止する。
     */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void trainsystemutilities$beforeTick(Level level, CallbackInfo ci) {
        if (DEBUG_DISABLE_NAV_INJECTS) return;
        if (train == null) return;
        if (!TrainCouplingManager.canBypassSignal(train.id)) return;

        CouplingApproachTracker.TrackState state = CouplingApproachTracker.getOrCreate(train.id);
        if (state.phase != Phase.CRAWLING) return;

        double stopDist = getStopDistance();

        // 停車位置に到達 → Createが動かす前に速度0
        if (distanceToDestination <= stopDist) {
            train.speed = 0;
            train.targetSpeed = 0;
        }
    }

    /**
     * TAIL: Createのtick後に状態遷移と速度制御。
     */
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void trainsystemutilities$afterTick(Level level, CallbackInfo ci) {
        if (DEBUG_DISABLE_NAV_INJECTS) return;
        if (train == null) return;

        boolean canBypass = TrainCouplingManager.canBypassSignal(train.id);
        if (!canBypass) {
            CouplingApproachTracker.remove(train.id);
            return;
        }

        CouplingApproachTracker.TrackState state = CouplingApproachTracker.getOrCreate(train.id);

        switch (state.phase) {
            case WAITING_SIGNAL -> {
                // 本物の信号で停止したらPAUSINGに移行
                if (waitingForSignal != null && Math.abs(train.speed) < 0.01) {
                    state.phase = Phase.PAUSING;
                    state.pauseTicksRemaining = CouplingApproachTracker.getPauseTicks();
                }
            }
            case PAUSING -> {
                // 一時停止中
                train.speed = 0;
                train.targetSpeed = 0;
                state.pauseTicksRemaining--;
                if (state.pauseTicksRemaining <= 0) {
                    // 信号解除して徐行開始
                    waitingForSignal = null;
                    distanceToSignal = 0;
                    state.phase = Phase.CRAWLING;
                }
            }
            case CRAWLING -> {
                // 信号が再設定されたら解除
                if (waitingForSignal != null) {
                    waitingForSignal = null;
                    distanceToSignal = 0;
                }

                double crawlStopDist = getStopDistance();

                // 停車位置で完全停止
                if (distanceToDestination <= crawlStopDist) {
                    train.speed = 0;
                    train.targetSpeed = 0;
                } else {
                    // 徐行速度に制限
                    double maxSpeed = COUPLING_MAX_SPEED;

                    // 停車位置に近づいたら減速
                    double remaining = distanceToDestination - crawlStopDist;
                    if (remaining < 3) {
                        maxSpeed = COUPLING_MAX_SPEED * (remaining / 3.0);
                        maxSpeed = Math.max(maxSpeed, 0.01);
                    }

                    if (Math.abs(train.speed) > maxSpeed) {
                        train.speed = Math.signum(train.speed) * maxSpeed;
                    }
                    if (Math.abs(train.targetSpeed) > maxSpeed) {
                        train.targetSpeed = Math.signum(train.targetSpeed) * maxSpeed;
                    }
                }
            }
        }
    }

    private double getStopDistance() {
        String destStation = null;
        try {
            if (train.navigation != null && train.navigation.destination != null)
                destStation = train.navigation.destination.name;
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[NavMixin] read destination failed", e); /* ignore */ }

        if (destStation != null) {
            return TrainCouplingManager.calculateStopDistance(destStation, train);
        }
        return 8; // フォールバック
    }
}
