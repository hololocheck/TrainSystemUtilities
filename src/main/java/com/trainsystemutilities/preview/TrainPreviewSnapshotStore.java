package com.trainsystemutilities.preview;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * Server 側で全列車の contraption block データを snapshot として保持するストア。
 *
 * <p>動作:
 * <ul>
 *   <li>サーバ tick (5秒間隔) で {@link Create#RAILWAYS}.trains を走査</li>
 *   <li>未 capture の列車を発見したら snapshot を作成 (= entity が server に load されてる時)</li>
 *   <li>消えた列車の snapshot は削除</li>
 *   <li>{@link com.trainsystemutilities.network.TrainPreviewRequestPayload} は snapshot をそのまま返す</li>
 * </ul>
 *
 * <p>これにより、player の view distance と無関係にプレビューが取得できる。
 * 組立時に capture・解除時に削除のセマンティクスを実現。
 *
 * <p>ライフタイム: in-memory のみ。サーバ再起動後は次回観測時に再 capture。
 */
public final class TrainPreviewSnapshotStore {

    private TrainPreviewSnapshotStore() {}

    /** capture 済み snapshot (gzipped NBT)。 */
    private static final ConcurrentHashMap<UUID, byte[]> snapshots = new ConcurrentHashMap<>();
    /** 各 snapshot の blockCount (再 capture 判定用)。 */
    private static final ConcurrentHashMap<UUID, Integer> snapshotBlockCounts = new ConcurrentHashMap<>();

    /** 最後の scan tick (server gameTime)。 */
    private static long lastScanTick = 0;
    /** scan 間隔 (ticks): 5秒。 */
    private static final long SCAN_INTERVAL_TICKS = 100;

    /**
     * Server tick から呼ばれる。Create.RAILWAYS.trains を見て snapshot を更新。
     *
     * @param gameTime  現在の server game time (level.getGameTime())
     * @param registries  HolderLookup.Provider (BlockState 直列化用)
     */
    public static void tick(long gameTime, HolderLookup.Provider registries) {
        if (gameTime - lastScanTick < SCAN_INTERVAL_TICKS) return;
        lastScanTick = gameTime;

        Map<UUID, Train> trains = Create.RAILWAYS.trains;
        if (trains == null) return;

        // 1. 未 capture / 変化した列車を capture
        for (var entry : trains.entrySet()) {
            UUID id = entry.getKey();
            Train train = entry.getValue();
            if (train == null) continue;

            int currentBlockCount = countBlocks(train);
            Integer existingCount = snapshotBlockCounts.get(id);
            if (existingCount != null && existingCount == currentBlockCount && currentBlockCount > 0) {
                continue; // 既に capture 済みかつ blockCount 一致 → skip
            }
            if (currentBlockCount == 0) continue; // 取得不可 → 次回 retry
            try {
                byte[] data = serializeTrain(train, registries);
                snapshots.put(id, data);
                snapshotBlockCounts.put(id, currentBlockCount);
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.warn(
                        "[TrainPreviewSnapshot] capture failed for train {}: {}",
                        id, e.getMessage());
            }
        }

        // 2. 消滅した列車の snapshot を削除
        var iter = snapshots.keySet().iterator();
        while (iter.hasNext()) {
            UUID id = iter.next();
            if (!trains.containsKey(id)) {
                snapshotBlockCounts.remove(id);
                iter.remove();
            }
        }
    }

    /** snapshot を取得。なければ null。 */
    public static byte[] get(UUID trainId) {
        return snapshots.get(trainId);
    }

    /**
     * 指定列車を即時 capture (非同期 scan を待たず、initial request 時の fallback で使う)。
     * 成功時は snapshot 格納 + bytes 返却。失敗時は null。
     */
    public static byte[] captureNow(UUID trainId, HolderLookup.Provider registries) {
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null) return null;
        int blockCount = countBlocks(train);
        if (blockCount == 0) return null;
        try {
            byte[] data = serializeTrain(train, registries);
            snapshots.put(trainId, data);
            snapshotBlockCounts.put(trainId, blockCount);
            return data;
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.warn(
                    "[TrainPreviewSnapshot] captureNow failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Carriage の {@code serialisedEntity} (CompoundTag) を取得。
     * これは entity の saved NBT 全体で、entity が unload されても保持される。
     * 内部に Contraption + Blocks (Palette + BlockList) を含む。
     */
    private static CompoundTag getSerialisedEntity(com.simibubi.create.content.trains.entity.Carriage carriage) {
        try {
            var f = serialisedEntityField(carriage.getClass());
            if (f != null) {
                Object obj = f.get(carriage);
                if (obj instanceof CompoundTag tag && !tag.isEmpty()) return tag;
            }
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Preview] serialised entity reflect failed", e); }
        return null;
    }

    private static volatile java.lang.reflect.Field cachedSerialisedEntityField;
    private static java.lang.reflect.Field serialisedEntityField(Class<?> carriageClass) {
        java.lang.reflect.Field f = cachedSerialisedEntityField;
        if (f != null) return f;
        Class<?> cls = carriageClass;
        while (cls != null && cls != Object.class) {
            for (var fld : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(fld.getModifiers())) continue;
                if ("serialisedEntity".equals(fld.getName())
                        && CompoundTag.class.isAssignableFrom(fld.getType())) {
                    fld.setAccessible(true);
                    cachedSerialisedEntityField = fld;
                    return fld;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Carriage から Contraption (entity 経由) を取得。
     * entity が load 中の場合のみ返り、それ以外は null。
     */
    private static Contraption resolveContraption(com.simibubi.create.content.trains.entity.Carriage carriage) {
        try {
            var ent = carriage.anyAvailableEntity();
            if (ent != null) {
                Contraption c = ent.getContraption();
                if (c != null) return c;
            }
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Preview] contraption resolve failed", e); }
        return null;
    }


    private static int countBlocks(Train train) {
        int total = 0;
        for (var carriage : train.carriages) {
            // (1) saved entity NBT から直接 BlockList サイズを取る (entity unload 中も有効)
            int n = countBlocksFromSavedNbt(getSerialisedEntity(carriage));
            if (n > 0) {
                total += n;
                continue;
            }
            // (2) entity が load されている場合のフォールバック
            Contraption con = resolveContraption(carriage);
            if (con != null && con.getBlocks() != null) total += con.getBlocks().size();
        }
        return total;
    }

    /**
     * serialisedEntity NBT 内の {@code Contraption.Blocks.BlockList} サイズを返す。
     * 構造が想定外なら 0。
     */
    private static int countBlocksFromSavedNbt(CompoundTag entityNbt) {
        if (entityNbt == null) return 0;
        try {
            // Contraption は CarriageContraption (subclass) なので key は "Contraption" のはず
            if (!entityNbt.contains("Contraption", net.minecraft.nbt.Tag.TAG_COMPOUND)) return 0;
            var contraption = entityNbt.getCompound("Contraption");
            if (!contraption.contains("Blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)) return 0;
            var blocks = contraption.getCompound("Blocks");
            if (!blocks.contains("BlockList", net.minecraft.nbt.Tag.TAG_LIST)) return 0;
            return blocks.getList("BlockList", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * serialisedEntity NBT を直接読んで、TSU 形式 (palette + blocks) の CompoundTag に変換。
     *
     * <p>Create の保存形式 (実測):
     * <pre>
     * Contraption: {
     *   Blocks: {
     *     Palette: [BlockState NBT ...],
     *     BlockList: [{Pos:long(packed), State:int(palette idx),
     *                  Data:CompoundTag?, UpdateTag:CompoundTag?}, ...]
     *   }
     * }
     * </pre>
     */
    private static CompoundTag serializeCarriageFromSavedNbt(CompoundTag entityNbt) {
        CompoundTag carriageTag = new CompoundTag();
        try {
            if (entityNbt == null
                    || !entityNbt.contains("Contraption", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                carriageTag.put("palette", new ListTag());
                carriageTag.put("blocks", new ListTag());
                return carriageTag;
            }
            var contraption = entityNbt.getCompound("Contraption");
            if (!contraption.contains("Blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                carriageTag.put("palette", new ListTag());
                carriageTag.put("blocks", new ListTag());
                return carriageTag;
            }
            var blocks = contraption.getCompound("Blocks");

            ListTag paletteSrc = blocks.getList("Palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
            ListTag blockListSrc = blocks.getList("BlockList", net.minecraft.nbt.Tag.TAG_COMPOUND);

            ListTag paletteTag = new ListTag();
            for (int i = 0; i < paletteSrc.size(); i++) paletteTag.add(paletteSrc.getCompound(i).copy());

            ListTag blocksTag = new ListTag();
            for (int i = 0; i < blockListSrc.size(); i++) {
                CompoundTag src = blockListSrc.getCompound(i);
                CompoundTag dst = new CompoundTag();
                // Create の Pos は packed long (BlockPos.asLong()) 形式
                int x, y, z;
                if (src.contains("Pos", net.minecraft.nbt.Tag.TAG_LONG)) {
                    long packed = src.getLong("Pos");
                    var pos = net.minecraft.core.BlockPos.of(packed);
                    x = pos.getX(); y = pos.getY(); z = pos.getZ();
                } else {
                    // 後方互換: X/Y/Z 別 int 形式 (古い Create / 他 mod)
                    x = src.getInt("X"); y = src.getInt("Y"); z = src.getInt("Z");
                }
                int state = src.getInt("State");
                dst.putIntArray("pos", new int[]{x, y, z});
                dst.putInt("pi", state);
                // BE NBT: Data (full save) を優先、なければ UpdateTag (client 同期用)
                // Copycat 系は UpdateTag に material BlockState が入っている
                if (src.contains("Data", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    dst.put("be", src.getCompound("Data"));
                } else if (src.contains("UpdateTag", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    dst.put("be", src.getCompound("UpdateTag"));
                }
                blocksTag.add(dst);
            }

            carriageTag.put("palette", paletteTag);
            carriageTag.put("blocks", blocksTag);
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn(
                    "[TrainPreviewSnapshot] serializeCarriageFromSavedNbt failed: {}", t.toString());
            carriageTag.put("palette", new ListTag());
            carriageTag.put("blocks", new ListTag());
        }
        return carriageTag;
    }

    /**
     * Train を gzipped CompoundTag にシリアライズ。
     * フォーマット (TrainPreviewRequestPayload と同じ):
     * <pre>
     * root: { carriages: [
     *   { palette: [BlockState NBT, ...], blocks: [{ pos:[x,y,z], pi:int, be:CompoundTag? }, ...] },
     *   ...
     * ] }
     * </pre>
     */
    private static byte[] serializeTrain(Train train, HolderLookup.Provider registries) throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag carriagesTag = new ListTag();
        for (var carriage : train.carriages) {
            // (1) saved NBT から直接 → entity 不要、unload 中も有効
            CompoundTag savedNbt = getSerialisedEntity(carriage);
            if (savedNbt != null && countBlocksFromSavedNbt(savedNbt) > 0) {
                carriagesTag.add(serializeCarriageFromSavedNbt(savedNbt));
                continue;
            }
            // (2) entity 経由 fallback
            CompoundTag carriageTag = new CompoundTag();
            Contraption con = resolveContraption(carriage);
            if (con == null || con.getBlocks() == null) {
                carriageTag.put("palette", new ListTag());
                carriageTag.put("blocks", new ListTag());
                carriagesTag.add(carriageTag);
                continue;
            }

            Map<BlockState, Integer> paletteMap = new HashMap<>();
            List<BlockState> paletteList = new ArrayList<>();
            ListTag blocksTag = new ListTag();
            for (var entry : con.getBlocks().entrySet()) {
                var sbi = entry.getValue();
                if (sbi == null || sbi.state() == null) continue;
                BlockState state = sbi.state();
                Integer idx = paletteMap.get(state);
                if (idx == null) {
                    idx = paletteList.size();
                    paletteMap.put(state, idx);
                    paletteList.add(state);
                }
                CompoundTag blockTag = new CompoundTag();
                var pos = entry.getKey();
                blockTag.putIntArray("pos", new int[]{pos.getX(), pos.getY(), pos.getZ()});
                blockTag.putInt("pi", idx);
                if (sbi.nbt() != null) blockTag.put("be", sbi.nbt());
                blocksTag.add(blockTag);
            }
            ListTag paletteTag = new ListTag();
            for (BlockState s : paletteList) paletteTag.add(NbtUtils.writeBlockState(s));

            carriageTag.put("palette", paletteTag);
            carriageTag.put("blocks", blocksTag);
            carriagesTag.add(carriageTag);
        }
        root.put("carriages", carriagesTag);
        // 車両間隔 (gap 計算用)。 client は live train が無くても正しい間隔で車両を並べられる。
        int[] spacing = new int[train.carriageSpacing.size()];
        for (int i = 0; i < spacing.length; i++) spacing[i] = train.carriageSpacing.get(i);
        root.putIntArray("spacing", spacing);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(baos);
             var dos = new DataOutputStream(gz)) {
            NbtIo.write(root, dos);
        }
        return baos.toByteArray();
    }

    /** デバッグ/サーバ停止時にクリア。 */
    public static void clear() {
        snapshots.clear();
        snapshotBlockCounts.clear();
        lastScanTick = 0;
    }

    public static int snapshotCount() {
        return snapshots.size();
    }
}
