package com.trainsystemutilities.schedule;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列車 UUID → 種別コードの server-global な source of truth。
 *
 * <p>種別は管理用コンピューターの電子式時刻表タブで設定するが、 表示するのは駅の鉄道管理ブロックや
 * 走行中コントラプションのモニターなので、 設定した BE の外から引ける必要がある。 よって
 * per-BE ではなく {@link ElectronicTimetableState} と同型の server-global SavedData に置く。
 *
 * <p>client からは直接読まないこと (§5.1)。 client は BE 同期 / payload 経由で配信された値を読む。
 */
public class TrainTypeState extends SavedData {

    private static final String FILE_NAME = "tsu_train_types";

    private final Map<UUID, String> types = new ConcurrentHashMap<>();

    /** 設定済みの種別コード。 未設定なら {@link TrainTypes#NONE}。 */
    public String get(UUID trainId) {
        if (trainId == null) return TrainTypes.NONE;
        return types.getOrDefault(trainId, TrainTypes.NONE);
    }

    /** 種別を設定する。 未設定 ({@link TrainTypes#NONE}) を渡すとエントリごと削除する。 */
    public void set(UUID trainId, String code) {
        if (trainId == null) return;
        String prev = types.get(trainId);
        if (!TrainTypes.isSet(code)) {
            if (prev != null) { types.remove(trainId); setDirty(); }
            return;
        }
        if (!code.equals(prev)) { types.put(trainId, code); setDirty(); }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> e : types.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", e.getKey());
            entry.putString("Type", e.getValue());
            list.add(entry);
        }
        tag.put("Types", list);
        return tag;
    }

    public static TrainTypeState load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainTypeState d = new TrainTypeState();
        ListTag list = tag.getList("Types", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag entry = list.getCompound(i);
                String code = entry.getString("Type");
                if (TrainTypes.isSet(code)) d.types.put(entry.getUUID("Id"), code);
            } catch (Exception ignored) {
                com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrainType] entry parse failed", ignored);
            }
        }
        return d;
    }

    public static final SavedData.Factory<TrainTypeState> FACTORY =
            new SavedData.Factory<>(TrainTypeState::new, TrainTypeState::load, null);

    public static TrainTypeState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /**
     * server 側の便宜ラッパー。 client level や server 未取得時は未設定を返すので、
     * 呼び出し側で isClientSide 分岐を書かなくてよい。
     */
    public static String typeOf(Level level, UUID trainId) {
        if (level == null || level.isClientSide() || level.getServer() == null) return TrainTypes.NONE;
        return get(level.getServer()).get(trainId);
    }
}
