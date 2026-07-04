package com.trainsystemutilities.preset;

import com.simibubi.create.content.trains.track.ITrackBlock;
import com.trainsystemutilities.TrainSystemUtilities;

import static com.trainsystemutilities.preset.TrainPresetMaterials.isTrack;
import static com.trainsystemutilities.preset.TrainPresetMaterials.rotateRel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 汎用プリセット設置システム。TrainPreset を world に貼り付ける。
 */
public final class PresetPlacer {

    private PresetPlacer() {}

    public record Result(int blocksPlaced, int blocksSkipped, int entitiesSpawned, int entitiesFailed,
                         int glueClusters, int gluedBlocks) {
        public Result(int blocksPlaced, int blocksSkipped, int entitiesSpawned, int entitiesFailed) {
            this(blocksPlaced, blocksSkipped, entitiesSpawned, entitiesFailed, 0, 0);
        }
    }

    /** TSU-01: 配置を拒否する security-critical ブロック (任意コマンド実行 / 構造ロード経路)。
     *  barrier/bedrock 等の griefing 寄りブロックは創作自由度のため許可する。 */
    public static final Set<String> FORBIDDEN_BLOCK_IDS = Set.of(
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:structure_block",
            "minecraft:jigsaw"
    );

    /** preset の palette に禁止ブロックが含まれていればその block id を返す (無ければ null)。
     *  palette は使用ブロックの集合なので、 これを見れば配置されうる禁止ブロックを網羅検出できる。 */
    public static String findForbiddenBlock(TrainPreset preset) {
        if (preset == null) return null;
        for (CompoundTag tag : preset.palette) {
            if (tag == null) continue;
            String name = tag.getString("Name");
            if (name != null && FORBIDDEN_BLOCK_IDS.contains(name)) return name;
        }
        return null;
    }

    public static Result placeAt(ServerLevel level, BlockPos origin, TrainPreset preset) {
        return placeAt(level, origin, preset, 0);
    }

    /**
     * 設置時に SuperGlue で接着される非線路ブロックの数を見積もる。
     * 起点の地面以下や線路アンカーは設置されないので除外する。
     * 実際の placeAt は world 状態に応じてさらに skip しうるが、これは事前のタンク残量チェック用の上限。
     */
    public static int countGlueableBlocks(TrainPreset preset) {
        if (preset == null) return 0;
        BlockPos anchorRel = new BlockPos(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        boolean anchorIsTrack = isAnchorTrack(preset);
        int count = 0;
        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.relPos().getY() < preset.anchorRelY) continue;
            if (anchorIsTrack && entry.relPos().equals(anchorRel)) continue;
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) continue;
            if (isTrack(preset.palette.get(entry.paletteIdx()))) continue;
            count++;
        }
        return count;
    }

    /**
     * 線路クリック位置から、実際にプリセットを揃えるアンカー座標を解決する。
     * 線路自体をアンカーにしたプリセットはクリック位置そのまま、
     * bogey など列車側ブロックをアンカーにしたプリセットは 1 ブロック上へ持ち上げる。
     */
    public static BlockPos resolvePlacementOrigin(Level level, BlockPos clickedPos, TrainPreset preset) {
        return resolvePlacementOrigin(level, clickedPos, preset, 0);
    }

    public static BlockPos resolvePlacementOrigin(Level level, BlockPos clickedPos, TrainPreset preset, int rotY) {
        if (clickedPos == null) return null;
        if (level == null || preset == null) return clickedPos;

        BlockState clickedState = level.getBlockState(clickedPos);
        if (!isTrack(clickedState)) {
            return clickedPos;
        }

        BlockPos anchorRel = new BlockPos(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        BlockPos anchorRot = rotateRel(anchorRel, preset.sizeX, preset.sizeZ, rotY);
        TrainPreset.Entry frontBogey = findFrontBogeyEntry(preset);
        if (frontBogey != null) {
            BlockPos frontBogeyRot = rotateRel(frontBogey.relPos(), preset.sizeX, preset.sizeZ, rotY);
            BlockPos desiredBogeyPos = clickedPos.above();
            return desiredBogeyPos.offset(
                    anchorRot.getX() - frontBogeyRot.getX(),
                    anchorRot.getY() - frontBogeyRot.getY(),
                    anchorRot.getZ() - frontBogeyRot.getZ());
        }

        return isAnchorTrack(preset) ? clickedPos : clickedPos.above();
    }

    public static int resolveEffectiveRotY(TrainPreset preset, int manualRotY, Direction markerDirection) {
        int autoRotY = resolveAutoRotY(preset, markerDirection);
        return Math.floorMod(autoRotY + manualRotY, 4);
    }

    public static int resolveAutoRotY(Level level, BlockPos clickedPos, TrainPreset preset, Vec3 lookVec) {
        return resolveAutoRotY(preset, resolveMarkerDirection(level, clickedPos, lookVec));
    }

    public static Direction resolveMarkerDirection(Level level, BlockPos clickedPos, Vec3 lookVec) {
        if (clickedPos == null || level == null) return null;

        BlockState clickedState = level.getBlockState(clickedPos);
        if (!(clickedState.getBlock() instanceof ITrackBlock track)) {
            return null;
        }

        Vec3 effectiveLook = lookVec;
        if (effectiveLook == null || effectiveLook.lengthSqr() < 1.0E-4) {
            effectiveLook = new Vec3(0, 0, 1);
        }

        var nearestTrackAxis = track.getNearestTrackAxis(level, clickedPos, clickedState, effectiveLook);
        if (nearestTrackAxis == null || nearestTrackAxis.getFirst() == null) {
            return null;
        }

        Vec3 axis = nearestTrackAxis.getFirst()
                .scale(nearestTrackAxis.getSecond() == Direction.AxisDirection.POSITIVE ? -1 : 1);
        if (axis.lengthSqr() < 1.0E-4) {
            return null;
        }

        return horizontalDirection(axis);
    }

    /**
     * 黄色マーカー用の実座標。
     * 「最初の車輪が線路上で噛む位置」を示すため、Create の青マーカーと同じ
     * track endpoint を返す。
     */
    public static Vec3 resolveDisplayMarkerVec(Level level, BlockPos clickedPos, Direction markerDirection) {
        if (clickedPos == null) return null;

        Vec3 fallback = Vec3.atBottomCenterOf(clickedPos).add(0, 0.0625, 0);
        if (level == null) return fallback;

        BlockState clickedState = level.getBlockState(clickedPos);
        if (!(clickedState.getBlock() instanceof ITrackBlock track)) {
            return fallback;
        }

        Vec3 desiredDirection = markerDirection == null
                ? null
                : new Vec3(markerDirection.getStepX(), markerDirection.getStepY(), markerDirection.getStepZ());
        Vec3 selectedAxis = null;
        double bestAbsDot = -1;

        for (Vec3 axis : track.getTrackAxes(level, clickedPos, clickedState)) {
            Vec3 flatAxis = axis.multiply(1, 0, 1);
            if (flatAxis.lengthSqr() < 1.0E-4) continue;
            flatAxis = flatAxis.normalize();

            double dot = desiredDirection == null ? 1 : flatAxis.dot(desiredDirection);
            double absDot = Math.abs(dot);
            if (absDot <= bestAbsDot) continue;

            bestAbsDot = absDot;
            selectedAxis = axis.scale(dot < 0 ? -1 : 1);
        }

        if (selectedAxis == null) {
            return fallback;
        }

        Vec3 upNormal = track.getUpNormal(level, clickedPos, clickedState).normalize();
        if (upNormal.lengthSqr() < 1.0E-4) {
            upNormal = new Vec3(0, 1, 0);
        }

        return track.getCurveStart(level, clickedPos, clickedState, selectedAxis)
                .add(upNormal.scale(0.0625));
    }

    public static Vec3 resolveDisplayMarkerVec(Level level, BlockPos clickedPos, TrainPreset preset, int rotY) {
        if (preset == null) {
            return resolveDisplayMarkerVec(level, clickedPos, (Direction) null);
        }
        Direction markerDirection = horizontalDirection(getFirstWheelDirection(preset, rotY));
        return resolveDisplayMarkerVec(level, clickedPos, markerDirection);
    }

    /** rotY: 0/1/2/3 = 0/90/180/270 度 (Y 軸回転)。blockState の rotate も適用。 */
    public static Result placeAt(ServerLevel level, BlockPos origin, TrainPreset preset, int rotY) {
        if (level == null || preset == null || origin == null) return new Result(0, 0, 0, 0);

        // TSU-01: RCE / 構造ロード系ブロックを含む preset は配置しない。 caller を信用せず、
        // 全配置経路の最終ゲートとしてここでも拒否する (= 何も置かず全 skip 扱い)。
        String forbidden = findForbiddenBlock(preset);
        if (forbidden != null) {
            TrainSystemUtilities.LOGGER.warn(
                    "Preset placement rejected: contains forbidden block {} (TSU-01)", forbidden);
            return new Result(0, preset.blocks.size(), 0, 0);
        }

        HolderLookup.Provider registries = level.registryAccess();
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        net.minecraft.world.level.block.Rotation rot = switch (((rotY % 4) + 4) % 4) {
            case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };

        BlockPos anchorRel = new BlockPos(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        BlockPos anchorRot = rotateRel(anchorRel, preset.sizeX, preset.sizeZ, rotY);
        boolean anchorIsTrack = isAnchorTrack(preset);

        int placed = 0;
        int skipped = 0;
        // 接着対象 (非線路の配置済ブロック) を集めて、後で flood-fill で連結成分単位にグルーする
        Set<BlockPos> placedNonTrack = new HashSet<>();

        for (TrainPreset.Entry entry : preset.blocks) {
            // 地面や線路下の補助ブロックを持ち込まない。
            if (entry.relPos().getY() < preset.anchorRelY) {
                skipped++;
                continue;
            }

            // 線路アンカー型だけは、クリックした線路を保持して上書きしない。
            if (anchorIsTrack && entry.relPos().equals(anchorRel)) {
                skipped++;
                continue;
            }

            BlockPos relRot = rotateRel(entry.relPos(), preset.sizeX, preset.sizeZ, rotY);
            BlockPos worldPos = origin.offset(
                    relRot.getX() - anchorRot.getX(),
                    relRot.getY() - anchorRot.getY(),
                    relRot.getZ() - anchorRot.getZ());

            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) {
                skipped++;
                continue;
            }

            try {
                BlockState state = NbtUtils.readBlockState(blockLookup, preset.palette.get(entry.paletteIdx()));
                if (rot != net.minecraft.world.level.block.Rotation.NONE) {
                    state = state.rotate(rot);
                }

                BlockState existing = level.getBlockState(worldPos);
                if (isTrack(existing) && !isTrack(state)) {
                    skipped++;
                    continue;
                }
                if (existing.equals(state)) {
                    skipped++;
                    continue;
                }

                level.setBlock(worldPos, state, Block.UPDATE_CLIENTS);
                if (entry.beNbt() != null) {
                    BlockEntity be = level.getBlockEntity(worldPos);
                    if (be != null) {
                        try {
                            be.loadWithComponents(entry.beNbt(), registries);
                            be.setChanged();
                        } catch (Throwable t) {
                            TrainSystemUtilities.LOGGER.warn(
                                    "BlockEntity restore failed at {}: {}", worldPos, t.getMessage());
                        }
                    }
                }
                placed++;
                // 線路は SuperGlue 対象外 (移動しないため)
                if (!isTrack(state)) {
                    placedNonTrack.add(worldPos.immutable());
                }
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("Block placement failed at {}: {}", worldPos, t.getMessage());
                skipped++;
            }
        }

        int entSpawned = 0;
        int entFailed = 0;
        for (CompoundTag tag : preset.entities) {
            try {
                String typeId = tag.getString("Id");
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(typeId));
                if (type == null) {
                    entFailed++;
                    continue;
                }

                CompoundTag data = tag.getCompound("Data");
                Entity entity = type.create(level);
                if (entity == null) {
                    entFailed++;
                    continue;
                }

                entity.load(data);
                // entity の relPos も Y 軸回転で座標変換 (ブロックと同じ式、ただし double 精度)
                double rx = tag.getDouble("RelX");
                double ry = tag.getDouble("RelY");
                double rz = tag.getDouble("RelZ");
                double rotRx, rotRz;
                int rr = ((rotY % 4) + 4) % 4;
                switch (rr) {
                    case 1 -> { rotRx = preset.sizeZ - rz; rotRz = rx; }
                    case 2 -> { rotRx = preset.sizeX - rx; rotRz = preset.sizeZ - rz; }
                    case 3 -> { rotRx = rz; rotRz = preset.sizeX - rx; }
                    default -> { rotRx = rx; rotRz = rz; }
                }
                double x = origin.getX() + rotRx - anchorRot.getX();
                double y = origin.getY() + ry - anchorRot.getY();
                double z = origin.getZ() + rotRz - anchorRot.getZ();
                // entity の yaw も rotY*90° 加算 (列車の向き合わせ)
                float adjustedYaw = entity.getYRot() + rr * 90f;
                entity.moveTo(x, y, z, adjustedYaw, entity.getXRot());
                entity.setYRot(adjustedYaw);
                level.addFreshEntity(entity);
                entSpawned++;
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("Entity spawn failed: {}", t.getMessage());
                entFailed++;
            }
        }

        // SuperGlue 適用: 6 方向隣接で連結成分 (= 1 車両 ≒ 1 contraption) ごとに 1 つの SuperGlueEntity を配置。
        // 1 つの巨大ボックスで全車両を覆うと「全車両が 1 つの contraption」になり train 動作が壊れるため、
        // 必ず carriage 単位に分割する。
        int glueClusters = 0;
        int gluedBlocks = 0;
        if (!placedNonTrack.isEmpty()) {
            Set<BlockPos> visited = new HashSet<>(placedNonTrack.size() * 2);
            for (BlockPos seed : placedNonTrack) {
                if (visited.contains(seed)) continue;

                Deque<BlockPos> queue = new ArrayDeque<>();
                queue.push(seed);
                int minX = seed.getX(), minY = seed.getY(), minZ = seed.getZ();
                int maxX = minX, maxY = minY, maxZ = minZ;
                int clusterSize = 0;

                while (!queue.isEmpty()) {
                    BlockPos cur = queue.poll();
                    if (!visited.add(cur)) continue;
                    clusterSize++;
                    if (cur.getX() < minX) minX = cur.getX();
                    if (cur.getY() < minY) minY = cur.getY();
                    if (cur.getZ() < minZ) minZ = cur.getZ();
                    if (cur.getX() > maxX) maxX = cur.getX();
                    if (cur.getY() > maxY) maxY = cur.getY();
                    if (cur.getZ() > maxZ) maxZ = cur.getZ();

                    for (Direction d : Direction.values()) {
                        BlockPos next = cur.relative(d);
                        if (placedNonTrack.contains(next) && !visited.contains(next)) {
                            queue.push(next);
                        }
                    }
                }

                if (clusterSize == 0) continue;
                // Create の安全域として 1 軸 ≤ 256 のみ glue する (それを超える contraption はそもそも assemble 不能)
                if ((maxX - minX) > 256 || (maxY - minY) > 256 || (maxZ - minZ) > 256) {
                    TrainSystemUtilities.LOGGER.warn(
                            "SuperGlue cluster too large at {}: {}x{}x{} (skipped)",
                            seed, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
                    continue;
                }

                try {
                    net.minecraft.world.phys.AABB glueBox = new net.minecraft.world.phys.AABB(
                            minX, minY, minZ,
                            maxX + 1, maxY + 1, maxZ + 1);
                    var glueEntity = new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(
                            level, glueBox);
                    level.addFreshEntity(glueEntity);
                    glueClusters++;
                    gluedBlocks += clusterSize;
                } catch (Throwable t) {
                    TrainSystemUtilities.LOGGER.warn("SuperGlue spawn failed at cluster {}: {}", seed, t.getMessage());
                }
            }
        }

        TrainSystemUtilities.LOGGER.info(
                "Preset placed at {}: {} blocks ({} skipped), {} entities ({} failed), glue {} clusters / {} blocks",
                origin, placed, skipped, entSpawned, entFailed, glueClusters, gluedBlocks);
        return new Result(placed, skipped, entSpawned, entFailed, glueClusters, gluedBlocks);
    }

    /** Level をそのまま受けて、ServerLevel の場合のみ配置するラッパー。 */
    public static Result placeAt(Level level, BlockPos origin, TrainPreset preset) {
        if (level instanceof ServerLevel sl) return placeAt(sl, origin, preset);
        return new Result(0, 0, 0, 0);
    }

    private static boolean isAnchorTrack(TrainPreset preset) {
        if (preset == null) return false;
        BlockPos anchorRel = new BlockPos(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        for (TrainPreset.Entry entry : preset.blocks) {
            if (!anchorRel.equals(entry.relPos())) continue;
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) return false;
            return isTrack(preset.palette.get(entry.paletteIdx()));
        }
        return false;
    }

    private static BlockPos findFrontTrackRel(TrainPreset preset) {
        if (preset == null) return null;

        TrainPreset.Entry frontBogey = findFrontBogeyEntry(preset);
        if (frontBogey != null) {
            BlockPos bogeyRel = frontBogey.relPos();
            boolean useX = shouldUseXAxis(preset);
            int direction = getFrontDirection(preset, bogeyRel, useX);
            BlockPos desired = useX ? bogeyRel.offset(direction, 0, 0) : bogeyRel.offset(0, 0, direction);
            BlockPos bestNearBogey = findNearestTrackTo(preset, desired, 4);
            if (bestNearBogey != null) {
                return bestNearBogey;
            }
        }

        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) continue;
            if (!isTrack(preset.palette.get(entry.paletteIdx()))) continue;
            BlockPos rel = entry.relPos();
            int score = scoreRel(rel);
            if (score >= bestScore) continue;
            bestScore = score;
            best = rel;
        }
        return best;
    }

    private static TrainPreset.Entry findFrontBogeyEntry(TrainPreset preset) {
        if (preset == null) return null;
        boolean useX = shouldUseXAxis(preset);
        TrainPreset.Entry best = null;
        int bestPrimary = Integer.MAX_VALUE;
        int bestSecondary = Integer.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;
        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) continue;
            CompoundTag stateTag = preset.palette.get(entry.paletteIdx());
            if (stateTag == null) continue;
            String name = stateTag.getString("Name");
            if (name == null || !name.contains("bogey")) continue;

            BlockPos rel = entry.relPos();
            int primary = useX ? rel.getX() : rel.getZ();
            int secondary = useX ? rel.getZ() : rel.getX();
            int y = rel.getY();

            if (primary > bestPrimary) continue;
            if (primary == bestPrimary && y > bestY) continue;
            if (primary == bestPrimary && y == bestY && secondary >= bestSecondary) continue;

            bestPrimary = primary;
            bestSecondary = secondary;
            bestY = y;
            best = entry;
        }
        return best;
    }

    private static BlockPos findNearestTrackTo(TrainPreset preset, BlockPos desired, int maxDistance) {
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestScore = Integer.MAX_VALUE;
        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) continue;
            if (!isTrack(preset.palette.get(entry.paletteIdx()))) continue;

            BlockPos rel = entry.relPos();
            int dx = Math.abs(rel.getX() - desired.getX());
            int dy = Math.abs(rel.getY() - desired.getY());
            int dz = Math.abs(rel.getZ() - desired.getZ());
            int distance = dx + dz + dy * 2;
            if (distance > maxDistance) continue;

            int score = scoreRel(rel);
            if (distance < bestDistance || (distance == bestDistance && score < bestScore)) {
                bestDistance = distance;
                bestScore = score;
                best = rel;
            }
        }
        return best;
    }

    private static boolean shouldUseXAxis(TrainPreset preset) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;

        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) continue;
            CompoundTag stateTag = preset.palette.get(entry.paletteIdx());
            if (stateTag == null) continue;
            String name = stateTag.getString("Name");
            if (name == null || !name.contains("bogey")) continue;

            BlockPos rel = entry.relPos();
            minX = Math.min(minX, rel.getX());
            maxX = Math.max(maxX, rel.getX());
            minZ = Math.min(minZ, rel.getZ());
            maxZ = Math.max(maxZ, rel.getZ());
            found = true;
        }

        if (!found) {
            return preset.sizeX >= preset.sizeZ;
        }
        return (maxX - minX) >= (maxZ - minZ);
    }

    private static int getFrontDirection(TrainPreset preset, BlockPos bogeyRel, boolean useX) {
        int axisValue = useX ? bogeyRel.getX() : bogeyRel.getZ();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (TrainPreset.Entry entry : preset.blocks) {
            BlockPos rel = entry.relPos();
            int value = useX ? rel.getX() : rel.getZ();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        int distanceToMin = Math.abs(axisValue - min);
        int distanceToMax = Math.abs(max - axisValue);
        return distanceToMin <= distanceToMax ? -1 : 1;
    }

    private static int resolveAutoRotY(TrainPreset preset, Direction markerDirection) {
        Direction targetDirection = horizontalDirection(markerDirection);
        Direction presetDirection = getBaseMarkerDirection(preset);
        if (targetDirection == null || presetDirection == null) {
            return 0;
        }

        for (int rotY = 0; rotY < 4; rotY++) {
            if (rotateHorizontal(presetDirection, rotY) == targetDirection) {
                return rotY;
            }
        }
        return 0;
    }

    private static Direction getBaseMarkerDirection(TrainPreset preset) {
        if (preset == null) {
            return null;
        }
        Direction bodyDirection = getTrainBodyDirection(preset, 0);
        if (bodyDirection != null) {
            return bodyDirection;
        }
        return horizontalDirection(getFirstWheelDirection(preset, 0));
    }

    private static Direction getTrainBodyDirection(TrainPreset preset, int rotY) {
        TrainPreset.Entry frontBogey = findFrontBogeyEntry(preset);
        TrainPreset.Entry trailingBogey = findTrailingBogeyEntry(preset);
        if (frontBogey == null || trailingBogey == null || frontBogey == trailingBogey) {
            return null;
        }

        BlockPos frontBogeyRot = rotateRel(frontBogey.relPos(), preset.sizeX, preset.sizeZ, rotY);
        BlockPos trailingBogeyRot = rotateRel(trailingBogey.relPos(), preset.sizeX, preset.sizeZ, rotY);
        Vec3 direction = new Vec3(
                trailingBogeyRot.getX() - frontBogeyRot.getX(),
                trailingBogeyRot.getY() - frontBogeyRot.getY(),
                trailingBogeyRot.getZ() - frontBogeyRot.getZ());
        return horizontalDirection(direction);
    }

    private static Vec3 getFirstWheelDirection(TrainPreset preset, int rotY) {
        TrainPreset.Entry frontBogey = findFrontBogeyEntry(preset);
        if (frontBogey == null) {
            return new Vec3(0, 0, 1);
        }

        BlockPos frontTrackRel = findFrontTrackRel(preset);
        if (frontTrackRel == null) {
            return new Vec3(0, 0, 1);
        }

        BlockPos bogeyRot = rotateRel(frontBogey.relPos(), preset.sizeX, preset.sizeZ, rotY);
        BlockPos frontTrackRot = rotateRel(frontTrackRel, preset.sizeX, preset.sizeZ, rotY);
        BlockPos diff = frontTrackRot.subtract(bogeyRot);
        Vec3 direction = new Vec3(diff.getX(), diff.getY(), diff.getZ());
        if (direction.lengthSqr() < 1.0E-4) {
            return new Vec3(0, 0, 1);
        }
        return direction.normalize();
    }

    private static Direction rotateHorizontal(Direction direction, int rotY) {
        Direction current = horizontalDirection(direction);
        if (current == null) {
            return null;
        }

        int steps = Math.floorMod(rotY, 4);
        for (int i = 0; i < steps; i++) {
            current = switch (current) {
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case SOUTH -> Direction.WEST;
                case WEST -> Direction.NORTH;
                default -> current;
            };
        }
        return current;
    }

    private static TrainPreset.Entry findTrailingBogeyEntry(TrainPreset preset) {
        if (preset == null) return null;
        boolean useX = shouldUseXAxis(preset);
        TrainPreset.Entry best = null;
        int bestPrimary = Integer.MIN_VALUE;
        int bestSecondary = Integer.MIN_VALUE;
        int bestY = Integer.MIN_VALUE;
        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) continue;
            CompoundTag stateTag = preset.palette.get(entry.paletteIdx());
            if (stateTag == null) continue;
            String name = stateTag.getString("Name");
            if (name == null || !name.contains("bogey")) continue;

            BlockPos rel = entry.relPos();
            int primary = useX ? rel.getX() : rel.getZ();
            int secondary = useX ? rel.getZ() : rel.getX();
            int y = rel.getY();

            if (primary < bestPrimary) continue;
            if (primary == bestPrimary && y < bestY) continue;
            if (primary == bestPrimary && y == bestY && secondary <= bestSecondary) continue;

            bestPrimary = primary;
            bestSecondary = secondary;
            bestY = y;
            best = entry;
        }
        return best;
    }

    private static Direction horizontalDirection(Direction direction) {
        if (direction == null) {
            return null;
        }
        if (direction.getAxis().isHorizontal()) {
            return direction;
        }
        int x = direction.getStepX();
        int z = direction.getStepZ();
        if (x == 0 && z == 0) {
            return null;
        }
        return Math.abs(x) >= Math.abs(z)
                ? (x >= 0 ? Direction.EAST : Direction.WEST)
                : (z >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static Direction horizontalDirection(Vec3 direction) {
        if (direction == null) {
            return null;
        }
        double absX = Math.abs(direction.x);
        double absZ = Math.abs(direction.z);
        if (absX < 1.0E-4 && absZ < 1.0E-4) {
            return null;
        }
        return absX >= absZ
                ? (direction.x >= 0 ? Direction.EAST : Direction.WEST)
                : (direction.z >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static int scoreRel(BlockPos rel) {
        return rel.getY() * 10000 + rel.getX() + rel.getZ();
    }

    // C2: isTrack / rotateRel は TrainPresetMaterials に集約 (逐語重複を解消、上の static import で利用)。
}
