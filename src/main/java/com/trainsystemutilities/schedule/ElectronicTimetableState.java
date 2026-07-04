package com.trainsystemutilities.schedule;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 電子式時刻表として管理されている列車 UUID の server-global 登録簿。
 *
 * <p>per-BE の {@code electronicTimetableTrains} は client 同期 / 画面表示用だが、
 * 運転士モブ右クリックの横取り ({@link com.trainsystemutilities.event.ElectronicScheduleInteraction})
 * は列車から逆引きで「電子式か?」を大域参照する必要があるため、 server 側に 1 本の index を持つ。
 * 各管理用コンピューター BE が tick / 設定時にここへ push する (= sticky、 解除はしない)。
 */
public class ElectronicTimetableState extends SavedData {

    private static final String FILE_NAME = "tsu_electronic_timetables";

    private final Set<UUID> trains = ConcurrentHashMap.newKeySet();

    public boolean isElectronic(UUID id) { return id != null && trains.contains(id); }

    public void add(UUID id) {
        if (id != null && trains.add(id)) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : trains) list.add(StringTag.valueOf(id.toString()));
        tag.put("Trains", list);
        return tag;
    }

    public static ElectronicTimetableState load(CompoundTag tag, HolderLookup.Provider registries) {
        ElectronicTimetableState d = new ElectronicTimetableState();
        ListTag list = tag.getList("Trains", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                d.trains.add(UUID.fromString(list.getString(i)));
            } catch (Exception ignored) {
                com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[ElectronicTimetable] UUID parse failed", ignored);
            }
        }
        return d;
    }

    public static final SavedData.Factory<ElectronicTimetableState> FACTORY =
            new SavedData.Factory<>(ElectronicTimetableState::new, ElectronicTimetableState::load, null);

    public static ElectronicTimetableState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
