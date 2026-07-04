package com.trainsystemutilities.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@link RailwayManagementBlockEntity} のアナウンス機能を保持するヘルパ。
 * god-class 分割 (v2) で BE から切り出した。 アナウンス設定 (config) + 検知カードの
 * detection listener lifecycle + 共有元 (検知 / 範囲指定) 探索を担う。 検知カード /
 * 範囲ボード / 媒体スロット自体は BE が {@link net.minecraft.world.Container} として保持し、
 * 本クラスは所有 BE を引数に受けて読む ({@link ScreenDoorController} と同型)。
 * NBT key (AnnouncementCfg) は抽出前と完全一致。
 */
public final class AnnouncementManager {

    private com.trainsystemutilities.announce.AnnouncementConfig config =
            new com.trainsystemutilities.announce.AnnouncementConfig();
    /** 検知カードに対して登録した listener (位置変更時に unregister する用)。 */
    private com.trainsystemutilities.detection.TrainDetectionManager.DetectionListener registeredListener;
    private GlobalPos registeredPos;

    public com.trainsystemutilities.announce.AnnouncementConfig getConfig() { return config; }

    public void setConfig(com.trainsystemutilities.announce.AnnouncementConfig cfg) {
        this.config = cfg != null ? cfg : new com.trainsystemutilities.announce.AnnouncementConfig();
    }

    /** 検知カードの bind 状態に応じて TrainDetectionManager の listener を更新。 server-side のみ。 */
    public void refreshListener(RailwayManagementBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return;
        GlobalPos newPos = com.trainsystemutilities.item.TrainDetectionCardItem
                .getBoundPosition(be.getDetectionCard());
        // Same as before? skip
        if (Objects.equals(newPos, registeredPos)) return;
        // Unregister old
        if (registeredPos != null && registeredListener != null) {
            com.trainsystemutilities.detection.TrainDetectionManager.unregister(registeredPos, registeredListener);
            registeredPos = null;
            registeredListener = null;
        }
        // Register new (onTrainEnter + onTrainStopped 両方をハンドル)
        if (newPos != null) {
            final BlockPos bePos = be.getBlockPos();
            final ResourceKey<Level> beDim = level.dimension();
            registeredListener =
                    new com.trainsystemutilities.detection.TrainDetectionManager.DetectionListener() {
                        @Override
                        public void onTrainEnter(com.simibubi.create.content.trains.entity.Train train,
                                                 GlobalPos gp) {
                            com.trainsystemutilities.announce.AnnouncementScheduler.onTrainPass(train, gp, beDim, bePos);
                        }
                        @Override
                        public void onTrainStopped(com.simibubi.create.content.trains.entity.Train train,
                                                   GlobalPos gp) {
                            com.trainsystemutilities.announce.AnnouncementScheduler.onTrainStopped(train, gp, beDim, bePos);
                        }
                    };
            com.trainsystemutilities.detection.TrainDetectionManager.register(newPos, registeredListener);
            registeredPos = newPos;
        }
    }

    /** BE 削除時 (chunk unload 含む) 等に detection listener を解除。 */
    public void unregisterListener() {
        if (registeredPos != null && registeredListener != null) {
            com.trainsystemutilities.detection.TrainDetectionManager.unregister(registeredPos, registeredListener);
            registeredPos = null;
            registeredListener = null;
        }
    }

    /** 検知カードを共有されている (= 検知スロットがロックされる)。 */
    public boolean isSharedDetectionTarget(RailwayManagementBlockEntity be) {
        for (var info : getIncomingShareSources(be)) {
            if (info.detection) return true;
        }
        return false;
    }

    /** 範囲指定ボードを共有されている (= 範囲指定スロットがロックされる)。 */
    public boolean isSharedRangeTarget(RailwayManagementBlockEntity be) {
        for (var info : getIncomingShareSources(be)) {
            if (info.range) return true;
        }
        return false;
    }

    /**
     * この be を共有先に指定している source 一覧 (検知 / 範囲指定 のどちらか以上を共有している駅)。
     * アナウンス GUI の「○○から〜を共有」表示と、 スロットロックの両方で使う。 client/server 両対応。
     */
    public List<RailwayManagementBlockEntity.IncomingShareInfo> getIncomingShareSources(
            RailwayManagementBlockEntity be) {
        Level level = be.getLevel();
        if (level == null) return Collections.emptyList();
        String linkedStationName = be.getLinkedStationName();
        if (linkedStationName == null || linkedStationName.isEmpty()) return Collections.emptyList();
        BlockPos linkedComputerPos = be.getLinkedComputerPos();
        if (linkedComputerPos == null) return Collections.emptyList();
        var cmpBe = level.getBlockEntity(linkedComputerPos);
        if (!(cmpBe instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity mcbe)) {
            return Collections.emptyList();
        }
        var stations = mcbe.getCachedStations();
        if (stations == null || stations.isEmpty()) return Collections.emptyList();
        List<RailwayManagementBlockEntity.IncomingShareInfo> result = new ArrayList<>();
        for (var station : stations) {
            if (station == null) continue;
            if (linkedStationName.equals(station.name())) continue;
            BlockPos otherPos = mcbe.getManagerPosForStation(station.name(), station.position());
            if (otherPos == null || otherPos.equals(be.getBlockPos())) continue;
            var otherBe = level.getBlockEntity(otherPos);
            if (!(otherBe instanceof RailwayManagementBlockEntity other)) continue;
            var otherCfg = other.getAnnouncementConfig();
            boolean det = otherCfg.isDetectionSharedTo(linkedStationName);
            boolean rng = otherCfg.isRangeSharedTo(linkedStationName);
            if (det || rng) {
                result.add(new RailwayManagementBlockEntity.IncomingShareInfo(station.name(), det, rng));
            }
        }
        return result;
    }

    /**
     * 範囲指定ボード共有元の rmbe を返す (sharedRangeTo にこの rmbe の station が登録されている source)。
     * 複数あれば最初の一つ。 なければ null。 スケジューラの音声再生で source の range board を使うために使う。
     */
    public RailwayManagementBlockEntity findRangeShareSource(RailwayManagementBlockEntity be) {
        Level level = be.getLevel();
        if (level == null) return null;
        String linkedStationName = be.getLinkedStationName();
        if (linkedStationName == null || linkedStationName.isEmpty()) return null;
        BlockPos linkedComputerPos = be.getLinkedComputerPos();
        if (linkedComputerPos == null) return null;
        var cmpBe = level.getBlockEntity(linkedComputerPos);
        if (!(cmpBe instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity mcbe)) return null;
        var stations = mcbe.getCachedStations();
        if (stations == null) return null;
        for (var station : stations) {
            if (station == null) continue;
            if (linkedStationName.equals(station.name())) continue;
            BlockPos otherPos = mcbe.getManagerPosForStation(station.name(), station.position());
            if (otherPos == null || otherPos.equals(be.getBlockPos())) continue;
            var otherBe = level.getBlockEntity(otherPos);
            if (!(otherBe instanceof RailwayManagementBlockEntity other)) continue;
            if (other.getAnnouncementConfig().isRangeSharedTo(linkedStationName)) return other;
        }
        return null;
    }

    public void writeNbt(CompoundTag tag) {
        tag.put("AnnouncementCfg", config.save());
    }

    public void readNbt(CompoundTag tag) {
        if (tag.contains("AnnouncementCfg", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            config = com.trainsystemutilities.announce.AnnouncementConfig.load(tag.getCompound("AnnouncementCfg"));
        }
    }
}
