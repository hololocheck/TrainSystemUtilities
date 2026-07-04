package com.trainsystemutilities.mixin;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.trainsystemutilities.schedule.CouplingSignalController;
import com.trainsystemutilities.schedule.TrainCouplingManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;

/**
 * Create の ScheduleRuntime に連結・切り離し条件を注入する。
 * 列車時刻表の実行時に、連結/切り離しのカスタム動作を処理する。
 */
@Mixin(value = ScheduleRuntime.class, remap = false)
public abstract class ScheduleRuntimeMixin {

    @Shadow
    public Train train;

    @Shadow
    public int currentEntry;

    @Shadow
    public ScheduleRuntime.State state;

    /** DEBUG: ScheduleRuntimeMixin の @Inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_SCHEDULE_INJECTS = false;

    /**
     * tick のたびに連結・切り離しの状態を確認し、処理する。
     * currentEntryが不整合な場合はスケジュールを解除してクラッシュを防ぐ。
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void trainsystemutilities$onTick(CallbackInfo ci) {
        if (DEBUG_DISABLE_SCHEDULE_INJECTS) return;
        if (train == null)
            return;

        // スケジュールの不整合チェック（連結後の破損対策）
        ScheduleRuntime self = (ScheduleRuntime) (Object) this;
        var schedule = self.getSchedule();
        if (schedule != null) {
            if (schedule.entries.isEmpty()) {
                // 空のスケジュール → 解除してクラッシュ回避
                self.discardSchedule();
                ci.cancel();
                return;
            }
            if (currentEntry >= schedule.entries.size()) {
                currentEntry = 0;
            }
        }

        TrainCouplingManager.processScheduleTick(train);
    }

    /**
     * 駅到着時に連結・切り離し指示を確認する。
     */
    @Inject(method = "destinationReached", at = @At("HEAD"), require = 0)
    private void trainsystemutilities$onDestinationReached(CallbackInfo ci) {
        if (DEBUG_DISABLE_SCHEDULE_INJECTS) return;
        if (train == null)
            return;
        TrainCouplingManager.onStationReached(train);
    }

    /**
     * 電子式時刻表列車では、 運転士右クリックによる「時刻表アイテム取り出し」を阻止する。
     * {@code returnSchedule} は schedule を ScheduleItem 化して返し {@code discardSchedule()} する取り出し本体。
     * HEAD で {@code ItemStack.EMPTY} を返せばアイテム化もスケジュール削除も発生しない。
     * 代わりに {@code paused = true} とし、 Create の resume(1回目)→stop(2回目) を「再開/停止トグル」化する。
     */
    @Inject(method = "returnSchedule", at = @At("HEAD"), cancellable = true, require = 0)
    private void trainsystemutilities$blockElectronicReturn(HolderLookup.Provider provider,
                                                            CallbackInfoReturnable<ItemStack> cir) {
        if (train == null || train.id == null) return;
        if (!ManagementComputerBlockEntity.isTrainElectronicAnywhere(train.id)) return;
        // 電子式時刻表はアイテム化させない (= 取り出し阻止)。 トグルは ConductorInteractionMixin が担う。
        // これは defense-in-depth (運転士経路以外で取り出しが起きても阻止)。
        cir.setReturnValue(ItemStack.EMPTY);
    }
}
