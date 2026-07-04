package com.trainsystemutilities.schedule;

import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.TrackNetworkScanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * 「目的の列車が到着するまで待機」の条件。
 * 連結動作で使用 - 列車Aが駅で待機し、列車Bの到着を待つ。
 */
public class WaitForTrainCondition {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "wait_for_train");

    private String targetTrainName = "";
    private UUID targetTrainId = null;
    private String stationName = "";

    public WaitForTrainCondition() {}

    public WaitForTrainCondition(String targetTrainName, String stationName) {
        this.targetTrainName = targetTrainName;
        this.stationName = stationName;
        resolveTrainId();
    }

    private void resolveTrainId() {
        Optional<Train> train = TrackNetworkScanner.getTrainByName(targetTrainName);
        train.ifPresent(t -> this.targetTrainId = t.id);
    }

    /**
     * 目的の列車が指定された駅に到着しているか判定する。
     */
    public boolean isConditionMet() {
        if (targetTrainId == null) {
            resolveTrainId();
            if (targetTrainId == null) return false;
        }

        Optional<Train> trainOpt = TrackNetworkScanner.getTrainById(targetTrainId);
        if (trainOpt.isEmpty()) return false;

        Train targetTrain = trainOpt.get();

        // Check if the target train has arrived at the specified station
        if (targetTrain.getCurrentStation() != null) {
            return targetTrain.getCurrentStation().name.equals(stationName);
        }

        return false;
    }

    public String getTargetTrainName() {
        return targetTrainName;
    }

    public void setTargetTrainName(String name) {
        this.targetTrainName = name;
        resolveTrainId();
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String name) {
        this.stationName = name;
    }

    public MutableComponent getDescription() {
        return Component.translatable(
                "schedule.trainsystemutilities.wait_for_train",
                targetTrainName,
                stationName
        );
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("TargetTrain", targetTrainName);
        if (targetTrainId != null) {
            tag.putUUID("TargetTrainId", targetTrainId);
        }
        tag.putString("Station", stationName);
        return tag;
    }

    public void read(CompoundTag tag) {
        targetTrainName = tag.getString("TargetTrain");
        if (tag.hasUUID("TargetTrainId")) {
            targetTrainId = tag.getUUID("TargetTrainId");
        }
        stationName = tag.getString("Station");
    }
}
