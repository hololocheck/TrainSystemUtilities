package com.trainsystemutilities.client.preview;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.TrainPreviewRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * 列車プレビュー (3D モデル) 用 client-side キャッシュ。
 *
 * <p>Server から {@link TrainPreviewRequestPayload} で gzipped contraption データを取得し、
 * 一定時間 (デフォルト 5 分) キャッシュする。{@link com.trainsystemutilities.client.gui.ManagementComputerScreenV2}
 * の drawTrainModel が、entity が client にロードされていない列車のプレビューに使う。
 *
 * <p>API:
 * <ul>
 *   <li>{@link #get(UUID)} - キャッシュ済みデータを取得 (なければ null)</li>
 *   <li>{@link #requestIfNeeded(UUID)} - キャッシュにない/期限切れなら server に request 送信</li>
 *   <li>{@link #receive(UUID, byte[])} - server からの応答を解凍 + cache 格納</li>
 * </ul>
 */
public final class TrainPreviewCache {

    private TrainPreviewCache() {}

    /** キャッシュエントリ。fetchedAtNanos > 0 なら有効。 spacing = 車両間隔 (gap 計算用、 live train 不要)。 */
    public record CachedTrain(UUID trainId, long fetchedAtNanos, List<CarriageBlocks> carriages, int[] spacing) {}

    /** 1 車両分のブロック (順序保持: contraption の origin 周辺から外向き)。 */
    public record CarriageBlocks(Map<BlockPos, BlockEntry> blocks) {}

    /** ブロック 1 個。state と (任意の) BE NBT。 */
    public record BlockEntry(BlockState state, CompoundTag beNbt) {}

    private static final ConcurrentHashMap<UUID, CachedTrain> cache = new ConcurrentHashMap<>();
    /** 直近 request 送信時刻 (per train)。same trainId 連射防止。 */
    private static final ConcurrentHashMap<UUID, Long> lastRequestNanos = new ConcurrentHashMap<>();

    /** キャッシュ有効期限: 5 分。これを超えたら期限切れ扱いで再 request。 */
    private static final long TTL_NANOS = 5L * 60L * 1_000_000_000L;
    /** 同一 train の連続 request 間隔下限: 2 秒。 */
    private static final long REQUEST_INTERVAL_NANOS = 2_000_000_000L;
    /** 受信 NBT の保護的 size cap: 32 MiB heap (decompressed)。 */
    private static final long MAX_NBT_SIZE = 32L * 1024 * 1024;

    /**
     * @return キャッシュ済みデータ。なし/期限切れなら null。
     */
    public static CachedTrain get(UUID trainId) {
        if (trainId == null) return null;
        CachedTrain c = cache.get(trainId);
        if (c == null) return null;
        if (System.nanoTime() - c.fetchedAtNanos > TTL_NANOS) return null;
        return c;
    }

    /**
     * キャッシュ未取得 OR 期限切れの場合に server へ request を送信する。
     * Rate limit (2秒に1回/train) で連射を防ぐ。
     */
    public static void requestIfNeeded(UUID trainId) {
        if (trainId == null) return;
        long now = System.nanoTime();
        CachedTrain existing = cache.get(trainId);
        if (existing != null && now - existing.fetchedAtNanos <= TTL_NANOS) return; // fresh cache あり
        Long lastReq = lastRequestNanos.get(trainId);
        if (lastReq != null && now - lastReq < REQUEST_INTERVAL_NANOS) return; // 連射制限
        lastRequestNanos.put(trainId, now);
        try {
            PacketDistributor.sendToServer(new TrainPreviewRequestPayload(trainId));
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn(
                    "[TrainPreviewCache] failed to send request for {}: {}", trainId, t.toString());
        }
    }

    /**
     * Server からの応答を受信。gzipped NBT を解凍 + decode してキャッシュに格納。
     * Minecraft client thread から呼ぶ (registries access のため)。
     */
    public static void receive(UUID trainId, byte[] gzippedData) {
        if (trainId == null || gzippedData == null) return;
        try {
            CompoundTag root;
            try (var bais = new ByteArrayInputStream(gzippedData);
                 var gz = new GZIPInputStream(bais);
                 var dis = new DataInputStream(gz)) {
                root = NbtIo.read(dis, NbtAccounter.create(MAX_NBT_SIZE));
            }
            if (root == null) {
                TrainSystemUtilities.LOGGER.warn(
                        "[TrainPreviewCache] decode train {}: root NBT was null", trainId);
                return;
            }
            CachedTrain decoded = decode(trainId, root);
            cache.put(trainId, decoded);
            // 上限ガード (異常時の暴走防御)
            if (cache.size() > 256) {
                long now = System.nanoTime();
                cache.entrySet().removeIf(e -> now - e.getValue().fetchedAtNanos > TTL_NANOS);
            }
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn(
                    "[TrainPreviewCache] decode train {} failed: {}", trainId, t.toString());
        }
    }

    private static CachedTrain decode(UUID trainId, CompoundTag root) {
        Minecraft mc = Minecraft.getInstance();
        HolderLookup.Provider registries = mc.level != null
                ? mc.level.registryAccess()
                : Minecraft.getInstance().getConnection() != null
                        ? Minecraft.getInstance().getConnection().registryAccess()
                        : null;
        if (registries == null) {
            return new CachedTrain(trainId, System.nanoTime(), List.of(), new int[0]);
        }
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        var carriagesTag = root.getList("carriages", net.minecraft.nbt.Tag.TAG_COMPOUND);
        List<CarriageBlocks> carriages = new ArrayList<>(carriagesTag.size());
        for (int i = 0; i < carriagesTag.size(); i++) {
            CompoundTag cTag = carriagesTag.getCompound(i);
            var paletteTag = cTag.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int p = 0; p < paletteTag.size(); p++) {
                try {
                    palette[p] = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(p));
                } catch (Throwable t) {
                    palette[p] = null;
                }
            }
            var blocksTag = cTag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
            // LinkedHashMap で挿入順保持 (Contraption.getBlocks() の意味的に近い)
            Map<BlockPos, BlockEntry> blocks = new LinkedHashMap<>(blocksTag.size());
            for (int b = 0; b < blocksTag.size(); b++) {
                CompoundTag bt = blocksTag.getCompound(b);
                int[] xyz = bt.getIntArray("pos");
                if (xyz.length != 3) continue;
                int pi = bt.getInt("pi");
                if (pi < 0 || pi >= palette.length || palette[pi] == null) continue;
                CompoundTag beNbt = bt.contains("be", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        ? bt.getCompound("be") : null;
                blocks.put(new BlockPos(xyz[0], xyz[1], xyz[2]),
                        new BlockEntry(palette[pi], beNbt));
            }
            carriages.add(new CarriageBlocks(blocks));
        }
        int[] spacing = root.getIntArray("spacing");   // 旧 snapshot には無い → 空配列 (gap=default)
        return new CachedTrain(trainId, System.nanoTime(), carriages, spacing);
    }

    /** デバッグ用 + P0-1 #7 で ClientDisconnect handler から呼ばれる。
     *  server 切替時に前 server の preview / rate-limit 状態が残らないよう全部 clear する。 */
    public static void clear() {
        cache.clear();
        lastRequestNanos.clear();
    }
}
