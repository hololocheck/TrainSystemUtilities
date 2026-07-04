package com.trainsystemutilities.station.routing.navfield;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NavField の永続化 (overworld の data フォルダに保存)。
 *
 * <p>初回アクセス時に Minecraft フレームワークが NBT から読み込み、in-memory map に展開。
 * setDirty() で書き出しスケジュール。NavFieldCache はこの SavedData を backing store として使う。
 *
 * <p>シリアライズ: 各 platform field は 2 つの LongArrayTag (= parent keys + values) で保存。
 * 60000 cells × 16 bytes = ~1MB / platform。複数駅でも数 MB レベルで現実的。
 */
public class NavFieldSavedData extends SavedData {

    private static final String FILE_NAME = "tsu_nav_fields";

    /** Key = (groupId, platform) → field。ConcurrentHashMap で thread-safe access。 */
    private final Map<Key, NavField> fields = new ConcurrentHashMap<>();

    public record Key(UUID groupId, int platform) {}

    public NavField get(UUID groupId, int platform) {
        if (groupId == null || platform <= 0) return null;
        return fields.get(new Key(groupId, platform));
    }

    public void put(NavField field) {
        if (field == null || field.groupId() == null) return;
        fields.put(new Key(field.groupId(), field.platform()), field);
        setDirty();
    }

    public void removeAll(UUID groupId) {
        if (groupId == null) return;
        boolean removed = fields.entrySet().removeIf(e -> e.getKey().groupId.equals(groupId));
        if (removed) setDirty();
    }

    public int size() { return fields.size(); }

    /** P0-5 #3: NBT schema version。 0 = legacy。 */
    private static final int SCHEMA_VERSION = 1;

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (Map.Entry<Key, NavField> e : fields.entrySet()) {
            list.add(saveField(e.getKey(), e.getValue()));
        }
        tag.put("Fields", list);
        TrainSystemUtilities.LOGGER.info("[NavFieldSavedData] saved {} fields", fields.size());
        return tag;
    }

    private static CompoundTag saveField(Key key, NavField field) {
        CompoundTag t = new CompoundTag();
        t.putUUID("group", key.groupId);
        t.putInt("platform", key.platform);
        t.putLong("goal", field.goal().asLong());
        // parentOf を flat な long[] (= [k1, v1, k2, v2, ...]) にシリアライズ
        // Map iteration 順は不定だが、復元時に同じ意味になればよい (Map → Map)
        // NavField.parentOf は private — リフレクション or accessor 必要。
        // ここでは accessor 用の簡易 export を NavField 側に追加する想定。
        Map<Long, Long> parents = field.exportParentMap();
        long[] keys = new long[parents.size()];
        long[] vals = new long[parents.size()];
        int i = 0;
        for (Map.Entry<Long, Long> pe : parents.entrySet()) {
            keys[i] = pe.getKey();
            vals[i] = pe.getValue();
            i++;
        }
        t.put("k", new LongArrayTag(keys));
        t.put("v", new LongArrayTag(vals));
        return t;
    }

    public static NavFieldSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.contains("schemaVersion") ? tag.getInt("schemaVersion") : 0;
        if (version > SCHEMA_VERSION) {
            TrainSystemUtilities.LOGGER.warn(
                    "[NavFieldSavedData] future schemaVersion={} (current={}); best-effort load",
                    version, SCHEMA_VERSION);
        }
        NavFieldSavedData d = new NavFieldSavedData();
        ListTag list = tag.getList("Fields", Tag.TAG_COMPOUND);
        int loaded = 0;
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag t = list.getCompound(i);
                NavField field = loadField(t);
                if (field != null) {
                    d.fields.put(new Key(field.groupId(), field.platform()), field);
                    loaded++;
                }
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.warn("[NavFieldSavedData] entry {} load failed: {}",
                        i, e.toString());
            }
        }
        TrainSystemUtilities.LOGGER.info("[NavFieldSavedData] loaded {} fields", loaded);
        return d;
    }

    private static NavField loadField(CompoundTag t) {
        UUID groupId = t.getUUID("group");
        int platform = t.getInt("platform");
        BlockPos goal = BlockPos.of(t.getLong("goal"));
        long[] keys = t.getLongArray("k");
        long[] vals = t.getLongArray("v");
        if (keys.length != vals.length) return null;
        Map<Long, Long> parents = new HashMap<>(keys.length * 4 / 3);
        for (int i = 0; i < keys.length; i++) {
            parents.put(keys[i], vals[i]);
        }
        return new NavField(groupId, platform, goal, parents, null);
    }

    public static final SavedData.Factory<NavFieldSavedData> FACTORY =
            new SavedData.Factory<>(NavFieldSavedData::new,
                    NavFieldSavedData::load, null);

    public static NavFieldSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
