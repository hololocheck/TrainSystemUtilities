package com.trainsystemutilities.mixin;

import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.trainsystemutilities.schedule.CouplingSignalController;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create の駅ブロックエンティティにフックして、
 * 連結・切り離し動作 + 駅グループ自動命名を処理する。
 *
 * 連結: 列車Aが待機中に列車Bが到着 → 連結実行
 * 切り離し: 連結された列車が駅に到着 → 切り離し実行 → 前方車両発車 → 後方車両発車
 *
 * 切り離し後の後方車両は駅ブロックを踏まなくても動作開始可能。
 *
 * 駅グループ命名: {@link com.trainsystemutilities.station.StationGroup} 範囲内に
 * ある駅ブロックの名前を rename する際、ユーザー入力名を破棄して
 * "{@code <group-name> <番線番号>}" に強制する。
 */
@Mixin(value = StationBlockEntity.class, remap = false)
public abstract class StationBlockEntityMixin {

    /** DEBUG: StationBlockEntityMixin の tick inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_STATION_INJECT = false;

    /**
     * 駅の tick 処理にフックして、連結・切り離し状態を管理。
     */
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void trainsystemutilities$afterStationTick(CallbackInfo ci) {
        if (DEBUG_DISABLE_STATION_INJECT) return;
        StationBlockEntity self = (StationBlockEntity) (Object) this;

        if (self.getLevel() == null || self.getLevel().isClientSide()) return;

        // 信号コントローラーのtick処理
        CouplingSignalController.tick(self.getLevel());
    }

    /**
     * Create 駅ブロックの rename を、駅グループ範囲内なら
     * "{@code <group-name> <platform-num>}" に書き換える。
     *
     * <p>{@link ModifyVariable} で argsOnly=true により updateName(String) の
     * 第 1 引数 (newName) を直接書き換える。group 範囲外なら original を
     * そのまま返して既存挙動を保つ。
     */
    @ModifyVariable(method = "updateName", at = @At("HEAD"), argsOnly = true, require = 0)
    private String trainsystemutilities$autoRenameInStationGroup(String original) {
        StationBlockEntity self = (StationBlockEntity) (Object) this;
        var level = self.getLevel();
        if (level == null) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "[StationGroup-Mixin] updateName('{}') skipped: level=null", original);
            return original;
        }
        if (level.isClientSide()) {
            // client 側でも mixin は走るが、judgement は server-side で行う
            return original;
        }
        var server = level.getServer();
        if (server == null) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "[StationGroup-Mixin] updateName('{}') skipped: server=null", original);
            return original;
        }

        String dim = level.dimension().location().toString();
        var pos = self.getBlockPos();

        StationGroup group;
        try {
            group = StationGroupSavedData.get(server).findContaining(dim, pos);
        } catch (Throwable t) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                    "[StationGroup-Mixin] updateName('{}') exception while looking up group: {}",
                    original, t.getMessage());
            return original;
        }
        if (group == null) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "[StationGroup-Mixin] updateName('{}') at dim={} pos=({},{},{}): no group contains this position",
                    original, dim, pos.getX(), pos.getY(), pos.getZ());
            return original;
        }

        int platformNum = group.platformNumberFor(pos);
        if (platformNum == 0) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "[StationGroup-Mixin] updateName('{}') at pos=({},{},{}): in group '{}' but not in stationBlockPositions list (group created before this station was added)",
                    original, pos.getX(), pos.getY(), pos.getZ(), group.name());
            return original;
        }
        String formatted = group.formatStationName(platformNum);
        com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                "[StationGroup-Mixin] updateName('{}') at pos=({},{},{}): group='{}' platform={} → '{}'",
                original, pos.getX(), pos.getY(), pos.getZ(), group.name(), platformNum, formatted);
        return formatted;
    }
}
