package com.trainsystemutilities.preset;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * 範囲 (pos1, pos2) からブロック群と carriage entity をスキャンして
 * TrainPreset インスタンスを構築する。
 *
 * 制限: ブロックは loaded chunk 内のもののみ捕捉。chunk が unload されている範囲は飛ばす。
 * 大規模な範囲 (例: 1M 超ブロック) はサーバーをフリーズさせる可能性があるため、
 * MAX_VOLUME を超える場合は早期に拒否する。
 */
public final class TrainPresetCapture {

    /** 1 プリセットで扱える最大ブロック数 (例: 64x16x64 = 65536)。 */
    public static final long MAX_VOLUME = 256L * 256 * 256; // 16M cap、現実的な列車には十分

    private TrainPresetCapture() {}

    /** 範囲スキャン → 新規 TrainPreset を返す。null チェック失敗時は null。 */
    public static TrainPreset capture(Level level, BlockPos p1, BlockPos p2,
                                        String presetName, String authorName, UUID authorUUID) {
        if (level == null || p1 == null || p2 == null) return null;

        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY());
        int maxZ = Math.max(p1.getZ(), p2.getZ());

        long sizeX = (long) (maxX - minX) + 1;
        long sizeY = (long) (maxY - minY) + 1;
        long sizeZ = (long) (maxZ - minZ) + 1;
        long volume = sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) {
            TrainSystemUtilities.LOGGER.warn(
                    "Preset capture rejected: volume {} exceeds max {}", volume, MAX_VOLUME);
            return null;
        }

        TrainPreset preset = new TrainPreset();
        preset.name = presetName == null ? "preset" : presetName;
        preset.author = authorName == null ? "" : authorName;
        preset.authorUUID = authorUUID;
        preset.createdEpochMs = System.currentTimeMillis();
        preset.sizeX = (int) sizeX;
        preset.sizeY = (int) sizeY;
        preset.sizeZ = (int) sizeZ;

        BlockPos origin = new BlockPos(minX, minY, minZ);
        HolderLookup.Provider registries = level.registryAccess();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) continue;
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;

                    int idx = preset.interPalette(state);
                    BlockPos rel = new BlockPos(x - minX, y - minY, z - minZ);

                    CompoundTag beTag = null;
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (be != null) {
                        try {
                            beTag = be.saveWithoutMetadata(registries);
                        } catch (Throwable t) {
                            TrainSystemUtilities.LOGGER.warn(
                                    "BlockEntity NBT save failed at {}: {}", cursor, t.getMessage());
                        }
                    }
                    preset.blocks.add(new TrainPreset.Entry(rel, idx, beTag));
                }
            }
        }

        // CarriageContraptionEntity の NBT スナップショット
        AABB aabb = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        for (Entity e : level.getEntitiesOfClass(CarriageContraptionEntity.class, aabb)) {
            try {
                CompoundTag tag = new CompoundTag();
                tag.putString("Id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getType()).toString());
                CompoundTag data = new CompoundTag();
                e.saveWithoutId(data);
                tag.put("Data", data);
                // 相対座標を保存して設置時に origin 加算
                tag.putDouble("RelX", e.getX() - minX);
                tag.putDouble("RelY", e.getY() - minY);
                tag.putDouble("RelZ", e.getZ() - minZ);
                preset.entities.add(tag);
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("Carriage entity NBT save failed: {}", t.getMessage());
            }
        }

        // anchor 自動検出: 優先度
        //   1. 最も低い (X, Z 基準でも一番手前) bogey ブロック (= 列車の最初の車輪)
        //   2. 最も低い track / rail ブロック
        //   3. CarriageContraptionEntity の最も低い位置 (assembled train)
        //   4. 底面中央 (フォールバック)
        TrainPreset.Entry bogeyEntry = null, trackEntry = null;
        int lowestBogeySum = Integer.MAX_VALUE; // Y * 10000 + X + Z で「低くて手前」を優先
        int lowestTrackSum = Integer.MAX_VALUE;
        for (TrainPreset.Entry e : preset.blocks) {
            if (e.paletteIdx() < 0 || e.paletteIdx() >= preset.palette.size()) continue;
            CompoundTag stateTag = preset.palette.get(e.paletteIdx());
            if (stateTag == null) continue;
            String name = stateTag.getString("Name");
            if (name == null) continue;
            int sum = e.relPos().getY() * 10000 + e.relPos().getX() + e.relPos().getZ();
            if (name.contains("bogey")) {
                if (sum < lowestBogeySum) { lowestBogeySum = sum; bogeyEntry = e; }
            } else if (name.contains("track") || name.contains("rail")) {
                if (sum < lowestTrackSum) { lowestTrackSum = sum; trackEntry = e; }
            }
        }
        TrainPreset.Entry anchorEntry = bogeyEntry != null ? bogeyEntry : trackEntry;
        if (anchorEntry != null) {
            preset.anchorRelX = anchorEntry.relPos().getX();
            preset.anchorRelY = anchorEntry.relPos().getY();
            preset.anchorRelZ = anchorEntry.relPos().getZ();
        } else if (!preset.entities.isEmpty()) {
            // CarriageContraptionEntity の最も低い Y を anchor 位置として使う
            double bestY = Double.MAX_VALUE;
            double bestX = 0, bestZ = 0;
            for (CompoundTag eTag : preset.entities) {
                double rx = eTag.getDouble("RelX");
                double ry = eTag.getDouble("RelY");
                double rz = eTag.getDouble("RelZ");
                if (ry < bestY) { bestY = ry; bestX = rx; bestZ = rz; }
            }
            preset.anchorRelX = (int) Math.round(bestX);
            preset.anchorRelY = (int) Math.floor(bestY);
            preset.anchorRelZ = (int) Math.round(bestZ);
        } else {
            // 底面中央
            preset.anchorRelX = Math.max(0, preset.sizeX / 2);
            preset.anchorRelY = 0;
            preset.anchorRelZ = Math.max(0, preset.sizeZ / 2);
        }
        // 範囲外には収めない (capture range の境界内に clamp)
        preset.anchorRelX = Math.max(0, Math.min(preset.sizeX - 1, preset.anchorRelX));
        preset.anchorRelY = Math.max(0, Math.min(preset.sizeY - 1, preset.anchorRelY));
        preset.anchorRelZ = Math.max(0, Math.min(preset.sizeZ - 1, preset.anchorRelZ));

        TrainSystemUtilities.LOGGER.info(
                "Captured preset {}: {}x{}x{} = {} blocks, {} entities, anchor=({},{},{})",
                preset.name, preset.sizeX, preset.sizeY, preset.sizeZ,
                preset.blocks.size(), preset.entities.size(),
                preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        return preset;
    }

    public static Component summary(TrainPreset p) {
        return Component.literal(String.format("[Preset] %s | %dx%dx%d | %d blocks | %d entities",
                p.name, p.sizeX, p.sizeY, p.sizeZ, p.blocks.size(), p.entities.size()));
    }

    /**
     * 抑止用未使用フィールド警告回避。registries は将来 entity の deserialize 時に使う想定。
     */
    @SuppressWarnings("unused")
    private static HolderLookup.Provider unusedRefHolder() {
        return null;
    }

    /** 抑止用: Registries import を残すため。 */
    @SuppressWarnings("unused")
    private static Class<?> unusedRegistries() { return Registries.class; }
}
