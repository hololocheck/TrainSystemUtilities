package com.trainsystemutilities.station;

import com.trainsystemutilities.blockentity.LineSymbol;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 駅 → 割り当て路線記号 の権威マップ (= level data、 常時メモリ常駐)。
 *
 * <p>従来は管理用コンピューター ({@code ManagementComputerBlockEntity}) が block entity 間 push で
 * {@code RailwayManagementBlockEntity} に路線記号を配っていたが、 遠隔駅の chunk が unload されていると
 * {@code level.getBlockEntity()} が null を返して push が届かず、 「離れた駅のモニターに反映されない」
 * バグの原因になっていた。
 *
 * <p>本 store は chunk load に依存しない。 管理用コンピューターは割り当て / 記号編集のたびに
 * ここへ全駅ぶんを書き込み、 各 {@code RailwayManagementBlockEntity} は tick で自駅キーを引いて
 * 解決する (= computer が unload 中でも解決できる)。 server-side のみ。 overworld data storage に常駐。
 *
 * <p>格納する {@link LineSymbol} は必ず snapshot copy ({@code LineSymbol.load(symbol.save())})。
 * 記号編集は既存インスタンスを in-place で書き換えるため、 参照を共有すると変更検知 (setDirty /
 * RMBE 側の差分判定) がすり抜ける。
 */
public class LineSymbolStore extends SavedData {

    private static final String FILE_NAME = "tsu_line_symbols";

    /** stationKey (= {@code ManagementComputerBlockEntity.stationKey(name,pos)}) → 割り当てシンボル。 */
    private final Map<String, LineSymbol> byStation = new HashMap<>();

    /** 割り当てシンボルを取得。 未割り当てなら null。 返り値は store 内の snapshot (読み取り専用として扱う)。 */
    public LineSymbol getSymbol(String stationKey) {
        return stationKey == null ? null : byStation.get(stationKey);
    }

    /** 割り当てを設定 (symbol==null で解除)。 値が変わったときだけ snapshot を差し替えて dirty 化する。 */
    public void setSymbol(String stationKey, LineSymbol symbol) {
        if (stationKey == null || stationKey.isEmpty()) return;
        if (symbol == null) {
            if (byStation.remove(stationKey) != null) setDirty();
            return;
        }
        CompoundTag now = symbol.save();
        LineSymbol prev = byStation.get(stationKey);
        if (prev == null || !prev.save().equals(now)) {
            byStation.put(stationKey, LineSymbol.load(now)); // snapshot copy
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var e : byStation.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Key", e.getKey());
            entry.put("Symbol", e.getValue().save());
            list.add(entry);
        }
        tag.put("Assignments", list);
        return tag;
    }

    public static LineSymbolStore load(CompoundTag tag, HolderLookup.Provider registries) {
        LineSymbolStore d = new LineSymbolStore();
        ListTag list = tag.getList("Assignments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String key = entry.getString("Key");
            LineSymbol sym = LineSymbol.load(entry.getCompound("Symbol"));
            if (!key.isEmpty() && sym != null) d.byStation.put(key, sym);
        }
        return d;
    }

    public static final SavedData.Factory<LineSymbolStore> FACTORY =
            new SavedData.Factory<>(LineSymbolStore::new, LineSymbolStore::load, null);

    public static LineSymbolStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
