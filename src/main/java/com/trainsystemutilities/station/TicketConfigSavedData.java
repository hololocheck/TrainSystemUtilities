package com.trainsystemutilities.station;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 券売機で販売可とする着駅 StationGroup の集合 (= ネットワーク全体で共通)。
 *
 * <p>管理用コンピューターの「券売機」タブで取捨選択する。 server-side のみ。
 * client へは network payload (TicketConfigSyncPayload) 経由で同期する。
 */
public class TicketConfigSavedData extends SavedData {

    private static final String FILE_NAME = "tsu_ticket_config";

    private final Set<UUID> sellable = ConcurrentHashMap.newKeySet();

    /** 販売可の着駅 UUID 集合 (読み取り専用ビュー)。 */
    public Set<UUID> sellable() { return Collections.unmodifiableSet(sellable); }

    public boolean isSellable(UUID id) { return id != null && sellable.contains(id); }

    public void setSellable(UUID id, boolean on) {
        if (id == null) return;
        boolean changed = on ? sellable.add(id) : sellable.remove(id);
        if (changed) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : sellable) list.add(StringTag.valueOf(id.toString()));
        tag.put("Sellable", list);
        return tag;
    }

    public static TicketConfigSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TicketConfigSavedData d = new TicketConfigSavedData();
        ListTag list = tag.getList("Sellable", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try { d.sellable.add(UUID.fromString(list.getString(i))); } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TicketConfig] sellable UUID parse failed", ignored); }
        }
        return d;
    }

    public static final SavedData.Factory<TicketConfigSavedData> FACTORY =
            new SavedData.Factory<>(TicketConfigSavedData::new, TicketConfigSavedData::load, null);

    public static TicketConfigSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
