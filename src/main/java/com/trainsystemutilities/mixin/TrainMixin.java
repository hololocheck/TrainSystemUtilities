package com.trainsystemutilities.mixin;

import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.profiler.TsuProfiler;
import com.trainsystemutilities.schedule.TrainCouplingManager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Create の Train クラスにフックして:
 * - 連結動作中の信号無視機能
 * - 適応型位置同期: 動いている列車は毎tick、停止中はデフォルト間隔
 * - TsuProfiler: Train.tick の所要時間計測 (Tier 5)
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {

    @Shadow
    public UUID id;

    @Shadow
    public double speed;

    @Shadow
    public double targetSpeed;

    /** TsuProfiler 計測の開始 nanoTime (per-thread)。Train.tick はサーバスレッドのみで動くが念のため ThreadLocal。 */
    private static final ThreadLocal<Long> trainsystemutilities$tickStart = ThreadLocal.withInitial(() -> 0L);

    /** DEBUG: TrainMixin の @Inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_TICK_INJECTS = false;

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void trainsystemutilities$onTrainTickHead(Level level, CallbackInfo ci) {
        if (DEBUG_DISABLE_TICK_INJECTS) return;
        Train self = (Train) (Object) this;
        TrainCouplingManager.onTrainTick(self);
        // 検知カードで登録された座標を train が通過したか毎 tick check
        if (level != null && !level.isClientSide()) {
            com.trainsystemutilities.detection.TrainDetectionManager.onTrainTick(self, level.dimension());
        }
        // Phase 24: 電化列車かつパンタ全折畳 → Train.tick 本体実行前に speed/targetSpeed を 0 に
        //   = この tick の加速計算が行われない (= 真に動かない)
        if (level != null && !level.isClientSide()) {
            if (com.trainsystemutilities.electrification.contraption.ContraptionElectrificationLock
                    .shouldLock(self)) {
                this.speed = 0.0;
                this.targetSpeed = 0.0;
            }
        }
        if (TsuProfiler.isEnabled()) {
            trainsystemutilities$tickStart.set(System.nanoTime());
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void trainsystemutilities$onTrainTickReturn(Level level, CallbackInfo ci) {
        if (DEBUG_DISABLE_TICK_INJECTS) return;
        Long start = trainsystemutilities$tickStart.get();
        if (start != null && start > 0) {
            TsuProfiler.end(TsuProfiler.Phase.TRAIN_TICK, start);
            trainsystemutilities$tickStart.set(0L);
        }
    }

    // Phase 16 で導入していた shouldCarriageSyncThisTick の @Overwrite を一時撤去。
    // プレイヤー搭乗中車両でモニター/ドアがフリッカーする問題の切り分けのため、
    // Create 純正の同期挙動に戻して挙動変化を検証する。
    // (移動中の列車を毎 tick 同期する最適化は、原因確定後に別アプローチで再導入する想定)
}
