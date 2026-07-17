package com.trainsystemutilities.schedule;

import com.simibubi.create.content.trains.schedule.Schedule;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.*;

/**
 * 連結/切り離しデータをワールドに永続化する。
 * savedSchedules等のデータがワールド再読み込みで消えない。
 */
public class CouplingPersistentData extends SavedData {

    private final Map<UUID, SavedScheduleData> savedSchedules = new HashMap<>();

    // TSU-22: mergedTrainId で front/rear のペアを scope する (= 同時に複数 merge があっても取り違えない)。
    public record SavedScheduleData(UUID originalTrainId, UUID mergedTrainId, CompoundTag scheduleNbt,
                                     int currentEntry, int carriageCount,
                                     boolean isFront, String trainName,
                                     int waitTimeSeconds) {}

    public CouplingPersistentData() {}

    public static CouplingPersistentData get() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return new CouplingPersistentData();
        return server.overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(CouplingPersistentData::new, CouplingPersistentData::load),
                        "trainsystemutilities_coupling");
    }

    public void saveSchedule(UUID trainId, UUID originalTrainId, UUID mergedTrainId, Schedule schedule,
                              int currentEntry, int carriageCount, boolean isFront, Component trainName,
                              int waitTimeSeconds, HolderLookup.Provider registries) {
        CompoundTag scheduleNbt = schedule.write(registries);
        savedSchedules.put(trainId, new SavedScheduleData(
                originalTrainId, mergedTrainId, scheduleNbt, currentEntry, carriageCount, isFront,
                trainName.getString(), waitTimeSeconds));
        setDirty();
        TrainSystemUtilities.LOGGER.info("[CouplingData] Saved schedule: train={} role={} name='{}' carriages={}",
                originalTrainId.toString().substring(0, 8), isFront ? "FRONT" : "REAR",
                trainName.getString(), carriageCount);
    }

    /** TSU-22: 指定 merged train に属する front/rear のみを返す (= global first-match による取り違えを防ぐ)。 */
    public SavedScheduleData getFrontSchedule(UUID mergedTrainId) {
        return savedSchedules.values().stream()
                .filter(s -> s.isFront() && mergedTrainId.equals(s.mergedTrainId()))
                .findFirst().orElse(null);
    }

    public SavedScheduleData getRearSchedule(UUID mergedTrainId) {
        return savedSchedules.values().stream()
                .filter(s -> !s.isFront() && mergedTrainId.equals(s.mergedTrainId()))
                .findFirst().orElse(null);
    }

    public SavedScheduleData getByTrainId(UUID trainId) {
        return savedSchedules.get(trainId);
    }

    public void remove(UUID trainId) {
        savedSchedules.remove(trainId);
        setDirty();
    }

    public void put(UUID trainId, SavedScheduleData data) {
        savedSchedules.put(trainId, data);
        setDirty();
    }

    public boolean isEmpty() {
        return savedSchedules.isEmpty();
    }

    public void clear() {
        savedSchedules.clear();
        setDirty();
    }

    // --- NBT永続化 ---

    /** P0-5 #3: NBT schema version。 0 = legacy (= schemaVersion tag 無し)。 */
    private static final int SCHEMA_VERSION = 2;

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (var entry : savedSchedules.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("Key", entry.getKey());
            item.putUUID("OriginalId", entry.getValue().originalTrainId());
            if (entry.getValue().mergedTrainId() != null) item.putUUID("MergedId", entry.getValue().mergedTrainId());
            item.put("Schedule", entry.getValue().scheduleNbt());
            item.putInt("CurrentEntry", entry.getValue().currentEntry());
            item.putInt("CarriageCount", entry.getValue().carriageCount());
            item.putBoolean("IsFront", entry.getValue().isFront());
            item.putString("TrainName", entry.getValue().trainName());
            item.putInt("WaitTimeSeconds", entry.getValue().waitTimeSeconds());
            list.add(item);
        }
        tag.put("SavedSchedules", list);
        return tag;
    }

    public static CouplingPersistentData load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.contains("schemaVersion") ? tag.getInt("schemaVersion") : 0;
        if (version > SCHEMA_VERSION) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                    "[CouplingPersistentData] future schemaVersion={} (current={}); best-effort load",
                    version, SCHEMA_VERSION);
        }
        CouplingPersistentData data = new CouplingPersistentData();
        ListTag list = tag.getList("SavedSchedules", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            // SECURITY/robustness (TSU-SAVED-001): 破損した 1 entry で全 coupling schedule を
            // 失わないよう per-entry で隔離する (StationGroup/Wire/NavField と同方針)。
            try {
                CompoundTag item = list.getCompound(i);
                UUID key = item.getUUID("Key");
                data.savedSchedules.put(key, new SavedScheduleData(
                        item.getUUID("OriginalId"),
                        item.hasUUID("MergedId") ? item.getUUID("MergedId") : null,
                        item.getCompound("Schedule"),
                        item.getInt("CurrentEntry"),
                        item.getInt("CarriageCount"),
                        item.getBoolean("IsFront"),
                        item.getString("TrainName"),
                        item.contains("WaitTimeSeconds") ? item.getInt("WaitTimeSeconds") : 5
                ));
            } catch (Exception ex) {
                com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                        "[CouplingPersistentData] skipped corrupt saved-schedule entry #{}: {}",
                        i, ex.toString());
            }
        }
        return data;
    }
}
