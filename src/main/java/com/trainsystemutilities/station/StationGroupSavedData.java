package com.trainsystemutilities.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 駅グループの永続化 (overworld の data フォルダに保存)。
 *
 * <p>マルチプレイで全プレイヤー共通の world data として扱う (Q4: マルチでも使える)。
 *
 * <p>アクセスは server-side のみ。client は network payload 経由で取得 / 操作する。
 */
public class StationGroupSavedData extends SavedData {

    private static final String FILE_NAME = "tsu_station_groups";

    private final Map<UUID, StationGroup> groups = new ConcurrentHashMap<>();

    public Collection<StationGroup> all() {
        return Collections.unmodifiableCollection(groups.values());
    }

    public StationGroup get(UUID id) { return groups.get(id); }

    public void put(StationGroup g) {
        groups.put(g.id(), g);
        setDirty();
        // 駅範囲が変わったらプラットフォームキャッシュを invalidate
        try {
            com.trainsystemutilities.station.routing.PlatformCache.invalidate(g.id());
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[StationGroup] platform cache invalidate (put) failed", e); }
    }

    public boolean remove(UUID id) {
        boolean removed = groups.remove(id) != null;
        if (removed) {
            setDirty();
            try {
                com.trainsystemutilities.station.routing.PlatformCache.invalidate(id);
            } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[StationGroup] platform cache invalidate (remove) failed", e); }
        }
        return removed;
    }

    /** 同一 dim + 全く同じ minPos / maxPos のグループを探す (重複作成検知用)。 */
    public StationGroup findExactRange(String dim, BlockPos minP, BlockPos maxP) {
        for (StationGroup g : groups.values()) {
            if (!g.dimensionId().equals(dim)) continue;
            if (g.minPos().equals(minP) && g.maxPos().equals(maxP)) return g;
        }
        return null;
    }

    /** 指定 dim/pos を含む group を返す。複数あれば最初に見つかったもの。 */
    public StationGroup findContaining(String dim, BlockPos p) {
        // DEBUG: groups の数 + 各 group の dim/range を log
        if (com.trainsystemutilities.TrainSystemUtilities.LOGGER.isDebugEnabled()) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "[StationGroup] findContaining(dim={}, pos={}) — total groups: {}",
                    dim, p, groups.size());
            for (StationGroup g : groups.values()) {
                com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                        "[StationGroup]   group '{}' dim={} range=({},{},{})~({},{},{}) contains? {}",
                        g.name(), g.dimensionId(),
                        g.minPos().getX(), g.minPos().getY(), g.minPos().getZ(),
                        g.maxPos().getX(), g.maxPos().getY(), g.maxPos().getZ(),
                        g.contains(dim, p));
            }
        }
        for (StationGroup g : groups.values()) {
            if (g.contains(dim, p)) return g;
        }
        return null;
    }

    /** P0-5 #3: NBT schema version (= 0 は legacy = schemaVersion tag 無し)。 */
    private static final int SCHEMA_VERSION = 1;

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (StationGroup g : groups.values()) list.add(g.save());
        tag.put("Groups", list);
        return tag;
    }

    public static StationGroupSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.contains("schemaVersion") ? tag.getInt("schemaVersion") : 0;
        if (version > SCHEMA_VERSION) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                    "[StationGroupSavedData] future schemaVersion={} (current={}); best-effort load",
                    version, SCHEMA_VERSION);
        }
        StationGroupSavedData d = new StationGroupSavedData();
        ListTag list = tag.getList("Groups", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                StationGroup g = StationGroup.load(list.getCompound(i));
                d.groups.put(g.id(), g);
            } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[StationGroup] group NBT load failed", ignored); }
        }
        return d;
    }

    public static final SavedData.Factory<StationGroupSavedData> FACTORY =
            new SavedData.Factory<>(StationGroupSavedData::new,
                    StationGroupSavedData::load,
                    null /* DataFixTypes */);

    public static StationGroupSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
