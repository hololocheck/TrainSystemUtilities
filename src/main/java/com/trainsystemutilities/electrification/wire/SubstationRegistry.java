package com.trainsystemutilities.electrification.wire;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 変電所レジストリ (per-dimension の {@link SavedData})。
 *
 * <p>目的: 変電所 BE がチャンクロード外でも給電 tick を動かせるようにする。
 * 変電所 BE が tick できるのは所属チャンクがロードされた時だけだが、
 * <ul>
 *   <li>{@link WireNetworkSavedData} は SavedData で常時メモリ常駐</li>
 *   <li>{@link EnergizedWireState} は毎 tick 変電所が markEnergized() で再構築</li>
 * </ul>
 * という構造のため、変電所チャンクが非ロードだと「架線が通電していない」と判定されて
 * 走行中の列車が FE 不足で停止する致命的バグが発生していた。
 *
 * <p>本レジストリは BE の位置と FE バッファ残量を per-dimension で保持し、
 * グローバル tick ハンドラ {@link com.trainsystemutilities.electrification.SubstationTickHandler}
 * がこれを毎 tick 走査して給電を実行する。これにより変電所チャンクのロード状態に依らず
 * 架線網が正しく通電される。
 *
 * <p>BE の {@code energy} フィールドはこの SavedData の値の "view" として動作する。
 * BE.onLoad() で登録 + 同期、BE.receiveEnergy() で SavedData 更新、ブロック破壊で登録解除。
 */
public final class SubstationRegistry extends SavedData {

    private static final String FILE_NAME = "tsu_substation_registry";

    /** pos packed → 現在の FE バッファ残量。 */
    private final Map<Long, Integer> feByPos = new ConcurrentHashMap<>();
    /** pos packed → コアブロックの FACING (= マルチブロック方向)。
     *  チャンクロード外でも 3×4×2 全マスを SubstationTickHandler が走査できるよう SavedData 化。
     *  初期値は NORTH (= 既存変電所の互換用)。 */
    private final Map<Long, Direction> facingByPos = new ConcurrentHashMap<>();

    /** 登録された全変電所 (packed pos) の不変ビュー。tick handler が走査に使う。 */
    public Collection<Long> allPackedPositions() {
        return Collections.unmodifiableCollection(feByPos.keySet());
    }

    public boolean isRegistered(BlockPos pos) {
        return feByPos.containsKey(pos.asLong());
    }

    /** {@code pos} の FE バッファを取得。未登録なら 0 を返す。 */
    public int getFe(BlockPos pos) {
        Integer v = feByPos.get(pos.asLong());
        return v == null ? 0 : v;
    }

    /** {@code pos} の FE バッファを設定。未登録なら自動登録する。
     *  setDirty() を呼ぶので呼び出し側は永続化を意識しなくてよい。 */
    public void setFe(BlockPos pos, int fe) {
        int prev = feByPos.getOrDefault(pos.asLong(), Integer.MIN_VALUE);
        if (prev == fe) return;
        feByPos.put(pos.asLong(), Math.max(0, fe));
        setDirty();
    }

    /** {@code pos} の FACING を設定。SubstationTickHandler がマルチブロックレイアウト計算に使う。 */
    public void setFacing(BlockPos pos, Direction facing) {
        if (facing == null) return;
        Direction prev = facingByPos.get(pos.asLong());
        if (prev == facing) return;
        facingByPos.put(pos.asLong(), facing);
        setDirty();
    }

    /** {@code pos} の FACING を取得。未登録なら NORTH (= デフォルト)。 */
    public Direction getFacing(BlockPos pos) {
        Direction d = facingByPos.get(pos.asLong());
        return d == null ? Direction.NORTH : d;
    }

    /** {@code pos} を登録 (= 初回設置時または BE.onLoad)。既存なら facing のみ更新。 */
    public void register(BlockPos pos, int initialFe, Direction facing) {
        if (feByPos.putIfAbsent(pos.asLong(), Math.max(0, initialFe)) == null) {
            if (facing != null) facingByPos.put(pos.asLong(), facing);
            setDirty();
            TrainSystemUtilities.LOGGER.info(
                    "[SubstationRegistry] registered pos={} initialFe={} facing={}",
                    pos, initialFe, facing);
        } else if (facing != null) {
            // 既存登録 → facing だけ更新 (= 過去にハードコード NORTH で登録された旧 entry を訂正)
            setFacing(pos, facing);
        }
    }

    /** 旧 API 互換: facing 不明な場合 NORTH で登録。 */
    public void register(BlockPos pos, int initialFe) {
        register(pos, initialFe, Direction.NORTH);
    }

    /** {@code pos} を登録解除 (= ブロック破壊時に呼ばれる)。 */
    public void unregister(BlockPos pos) {
        boolean removed = feByPos.remove(pos.asLong()) != null;
        facingByPos.remove(pos.asLong());
        if (removed) {
            setDirty();
            TrainSystemUtilities.LOGGER.info(
                    "[SubstationRegistry] unregistered pos={}", pos);
        }
    }

    public int size() { return feByPos.size(); }

    /** P0-5 #3: NBT schema version。 0 = legacy。 */
    private static final int SCHEMA_VERSION = 1;

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (var e : feByPos.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("p", e.getKey());
            t.putInt("fe", e.getValue());
            Direction facing = facingByPos.get(e.getKey());
            if (facing != null) t.putByte("fc", (byte) facing.get3DDataValue());
            list.add(t);
        }
        tag.put("Substations", list);
        return tag;
    }

    public static SubstationRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.contains("schemaVersion") ? tag.getInt("schemaVersion") : 0;
        if (version > SCHEMA_VERSION) {
            TrainSystemUtilities.LOGGER.warn(
                    "[SubstationRegistry] future schemaVersion={} (current={}); best-effort load",
                    version, SCHEMA_VERSION);
        }
        SubstationRegistry r = new SubstationRegistry();
        ListTag list = tag.getList("Substations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            long pos = t.getLong("p");
            r.feByPos.put(pos, Math.max(0, t.getInt("fe")));
            if (t.contains("fc")) {
                r.facingByPos.put(pos, Direction.from3DDataValue(t.getByte("fc")));
            }
        }
        TrainSystemUtilities.LOGGER.info(
                "[SubstationRegistry] loaded {} substations", r.feByPos.size());
        return r;
    }

    public static final SavedData.Factory<SubstationRegistry> FACTORY =
            new SavedData.Factory<>(SubstationRegistry::new,
                    SubstationRegistry::load, null);

    public static SubstationRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
