package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P0-5 #1 skeleton: 列車キャリッジの electrification storedEnergy をワールド永続化する SavedData。
 *
 * <p><b>現状は NOT WIRED。</b>
 * このクラスは P0-5 #2-#8 で
 * {@link com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState}
 * の in-memory {@code PERSISTENT_ENERGY} {@link java.util.concurrent.ConcurrentHashMap}
 * を置き換える予定の受け皿として作成されたスケルトンであり、まだどこからも参照されていない。
 *
 * <p>マイグレーション計画:
 * <ul>
 *   <li>#1 (本ファイル): SavedData scaffold を用意 (= ここ)。</li>
 *   <li>#2-#8: ContraptionElectrificationState の PERSISTENT_ENERGY 読み書きを
 *       この SavedData 経由に差し替え、サーバ再起動でも storedEnergy が消えないようにする。</li>
 * </ul>
 *
 * <p>キー: {@code carriageKey} (= trainId と carriageIndex を pack した long)。
 * 値: 現在の storedEnergy (FE)。
 *
 * <p>シリアライズは ListTag に {@code {k:long, v:int}} を並べる素朴形式。
 * {@code schemaVersion} を CompoundTag root に書いて将来のフォーマット変更に備える。
 */
public final class TsuPersistentEnergyData extends SavedData {

    /** {@code data/} 配下に書かれる SavedData ファイル名 (拡張子なし)。 */
    public static final String DATA_NAME = "tsu_persistent_energy";

    /** 現在のシリアライズフォーマットバージョン。 */
    private static final int SCHEMA_VERSION = 1;

    /** carriageKey → storedEnergy (FE)。 */
    private final Map<Long, Integer> entries = new ConcurrentHashMap<>();

    public TsuPersistentEnergyData() {}

    /** key に対応する storedEnergy を返す。未登録なら null。 */
    public Integer get(long key) {
        return entries.get(key);
    }

    /** storedEnergy を上書きし dirty マーク。 */
    public void put(long key, int energy) {
        entries.put(key, energy);
        setDirty();
    }

    /** エントリを削除し、削除前の値を返す (なければ null)。dirty マーク。 */
    public Integer remove(long key) {
        Integer prev = entries.remove(key);
        if (prev != null) setDirty();
        return prev;
    }

    /** 読み取り専用ビュー。外部からの構造変更は不可。 */
    public Map<Long, Integer> snapshot() {
        return Collections.unmodifiableMap(entries);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (Map.Entry<Long, Integer> e : entries.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("k", e.getKey());
            entry.putInt("v", e.getValue());
            list.add(entry);
        }
        tag.put("entries", list);
        return tag;
    }

    private static TsuPersistentEnergyData load(CompoundTag tag, HolderLookup.Provider registries) {
        TsuPersistentEnergyData d = new TsuPersistentEnergyData();
        int version = tag.contains("schemaVersion") ? tag.getInt("schemaVersion") : SCHEMA_VERSION;
        if (version != SCHEMA_VERSION) {
            TrainSystemUtilities.LOGGER.warn(
                    "[TsuPersistentEnergyData] schemaVersion={} (expected {}); loading best-effort",
                    version, SCHEMA_VERSION);
        }
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            d.entries.put(entry.getLong("k"), entry.getInt("v"));
        }
        return d;
    }

    public static final SavedData.Factory<TsuPersistentEnergyData> FACTORY =
            new SavedData.Factory<>(TsuPersistentEnergyData::new,
                    TsuPersistentEnergyData::load, null);

    /**
     * オーバーワールドの DataStorage に紐付くシングルトンインスタンスを返す。
     * server が overworld を持たない異常時は呼び出し側で null チェックすること。
     */
    public static TsuPersistentEnergyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }
}
