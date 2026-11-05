package com.trainsystemutilities.electrification.autoplace;

import com.trainsystemutilities.electrification.block.InsulatorBlock;
import com.trainsystemutilities.electrification.block.OverheadPlacementHelper;
import com.trainsystemutilities.electrification.block.OverheadPoleBlock;
import com.trainsystemutilities.electrification.block.OverheadTrussBlock;
import com.trainsystemutilities.registry.ModBlocks;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 架線柱配置 helper (= 1 クリックで 1 portal frame を配置)。
 *
 * <p>**自動配置アルゴリズムは破棄済み**。 このクラスは「ユーザーがクリックした 1 線路の上に
 * portal (= pole 2 + truss line + insulator) を 1 個立てる」 補助機能のみ。
 *
 * <p>multiTrack 設定があれば world scan で並走線路を検出し、 1 つの広い portal で
 * 並走 N 本を覆い、 各線路真上に碍子を配置する。
 */
public final class AutoPlaceExecutor {

    private static final double TRACK_HALF_WIDTH = 1.5;
    private static final int CLEARANCE_TOLERANCE = 10;

    /** 配置結果。 placedCount = 合計ブロック数、 poles/trusses/insulators = 種別ごとの実数、
     *  insufficient = 資材不足で配置中止した場合 true。 */
    public record Result(int placedCount, int poles, int trusses, int insulators, boolean insufficient) {
        public Result(int placedCount) { this(placedCount, 0, 0, 0, false); }
        public static Result noMaterials() { return new Result(0, 0, 0, 0, true); }
    }

    /** プレビュー用: 配置する 1 ブロックの情報 (= position + state + yaw)。 */
    public record PreviewItem(BlockPos pos, BlockState state, float yawDegrees) {
        public PreviewItem(BlockPos pos, BlockState state) { this(pos, state, Float.NaN); }
    }

    /** angle8 index (= 0..7) を horizontal axis に逆変換 (= ANGLE_8 convention)。 */
    public static Vec3 angle8ToHorizontalAxis(int angle8) {
        double rad = Math.toRadians(((angle8 % 8) + 8) % 8 * 45.0);
        return new Vec3(Math.sin(rad), 0, -Math.cos(rad));
    }

    private AutoPlaceExecutor() {}

    /**
     * placeOne と同じ位置計算をするが、 setBlock せず PreviewItem の list を返す
     * (= 半透明 hover プレビュー render 用)。
     */
    public static List<PreviewItem> computePreview(Level level, BlockPos trackPos, ItemStack tool) {
        List<PreviewItem> items = new ArrayList<>();
        if (level == null || trackPos == null || tool == null) return items;
        if (!AutoPlacePathFinder.isTrack(level, trackPos)) return items;
        Vec3 axis = AutoPlacePathFinder.getHorizontalAxis(level, trackPos);
        if (axis == null) return items;
        return computePreviewAt(level, horizontalCenter(trackPos), axis, tool);
    }

    /** Vec3 + axis 版 (= Create カーブ raycast 用)。 */
    public static List<PreviewItem> computePreviewAt(Level level, Vec3 trackCenter, Vec3 axis, ItemStack tool) {
        return computePreviewAt(level, trackCenter, axis, tool, null);
    }

    /** parallelCenters override 版 (= curve 並走複線 preview 対応)。 */
    public static List<PreviewItem> computePreviewAt(Level level, Vec3 trackCenter, Vec3 axis,
                                                       ItemStack tool, List<Vec3> parallelCenters) {
        List<PreviewItem> items = new ArrayList<>();
        if (level == null || trackCenter == null || axis == null || tool == null) return items;
        BlockPos trackPos = BlockPos.containing(trackCenter);

        int height      = AutoPlaceConfig.getHeight(tool);
        int clearance   = AutoPlaceConfig.getClearance(tool);
        int scanRange   = AutoPlaceConfig.getScanRange(tool);
        boolean cantilever = AutoPlaceConfig.getCantilever(tool);
        boolean placeTruss = AutoPlaceConfig.getPlaceTruss(tool);
        boolean placeIns   = AutoPlaceConfig.getPlaceInsulator(tool);

        // 手動回転 (= 8 方向 ANGLE_8) を axis に適用
        int manualRot = AutoPlaceConfig.getManualRotation(tool);
        if (manualRot > 0) {
            double rad = Math.toRadians(manualRot * 45.0);
            double cos = Math.cos(rad), sin = Math.sin(rad);
            axis = new Vec3(axis.x * cos - axis.z * sin, 0, axis.x * sin + axis.z * cos);
        }

        Block poleBlock  = ModBlocks.OVERHEAD_POLE.get();
        Block trussBlock = ModBlocks.OVERHEAD_TRUSS.get();
        Block insBlock   = ModBlocks.INSULATOR.get();

        // 8 方向 snap (= 16 方向は廃止)
        int poleAngle = OverheadPlacementHelper.axisToAngle8(axis);
        Vec3 snappedAxis = angle8ToHorizontalAxis(poleAngle);
        float poleYawDeg = poleAngle * 45f;
        Vec3 perp = new Vec3(-snappedAxis.z, 0, snappedAxis.x).normalize();

        Vec3 outerNeg;
        Vec3 outerPos;
        if (parallelCenters != null && parallelCenters.size() >= 2) {
            double minPerp = 0, maxPerp = 0;
            Vec3 minPt = trackCenter, maxPt = trackCenter;
            for (Vec3 c : parallelCenters) {
                double along = (c.x - trackCenter.x) * perp.x + (c.z - trackCenter.z) * perp.z;
                if (along < minPerp) { minPerp = along; minPt = c; }
                if (along > maxPerp) { maxPerp = along; maxPt = c; }
            }
            outerNeg = minPt;
            outerPos = maxPt;
        } else {
            // 単線扱い (= 呼び出し元が multiTrack 設定を尊重済み)
            outerNeg = trackCenter;
            outerPos = trackCenter;
        }

        int topY = trackPos.getY() + height - 1;
        int insY = trackPos.getY() + height - 2;
        int polesPerStack = height + 1;

        BlockPos polePosPos = findUsablePolePos(level, trackCenter, outerPos, perp, +1, clearance, polesPerStack);
        BlockPos polePosNeg = null;
        if (!cantilever) {
            polePosNeg = findUsablePolePos(level, trackCenter, outerNeg, perp, -1, clearance, polesPerStack);
            if (polePosNeg == null || polePosPos == null) return items;
        } else if (polePosPos == null) {
            polePosPos = findUsablePolePos(level, trackCenter, outerNeg, perp, -1, clearance, polesPerStack);
            if (polePosPos == null) return items;
            perp = perp.scale(-1);
            Vec3 tmpOuter = outerNeg; outerNeg = outerPos; outerPos = tmpOuter;
        }

        // pole stack (= 16 方向 yaw)
        BlockState poleState = poleBlock.defaultBlockState()
                .setValue(OverheadPoleBlock.ANGLE_8, poleAngle);
        if (polePosNeg != null) {
            for (int y = 0; y < polesPerStack; y++)
                items.add(new PreviewItem(polePosNeg.above(y), poleState, poleYawDeg));
        }
        if (polePosPos != null) {
            for (int y = 0; y < polesPerStack; y++)
                items.add(new PreviewItem(polePosPos.above(y), poleState, poleYawDeg));
        }

        // truss
        BlockPos trussStart;
        BlockPos trussEnd;
        if (cantilever) {
            double offset = (TRACK_HALF_WIDTH + clearance) * 2.0;
            int dx = (int) Math.round(-perp.x * offset);
            int dz = (int) Math.round(-perp.z * offset);
            trussStart = new BlockPos(polePosPos.getX() + dx, topY, polePosPos.getZ() + dz);
            trussEnd = polePosPos;
        } else {
            trussStart = polePosNeg;
            trussEnd = polePosPos;
        }
        if (placeTruss) {
            BlockPos centerCell = new BlockPos(trackPos.getX(), topY, trackPos.getZ());
            int dx = trussEnd.getX() - trussStart.getX();
            int dz = trussEnd.getZ() - trussStart.getZ();
            int trussLineAngle = directionToTrussAngle8(dx, dz);
            float trussLineYawDeg = trussLineAngle * 45f;
            List<BlockPos> lineCells = lineThroughCenter(trussStart, trussEnd, centerCell, topY);
            int last = lineCells.size() - 1;
            for (int s = 1; s < last; s++) {
                BlockPos pos = lineCells.get(s);
                boolean isEndSide = (s == last - 1);
                boolean isStartSide = (s == 1);
                int useAngle = isEndSide ? (trussLineAngle + 4) % 8 : trussLineAngle;
                boolean isCorner = isEndSide || (isStartSide && !cantilever);
                BlockState state = trussBlock.defaultBlockState()
                        .setValue(OverheadTrussBlock.ANGLE_8, useAngle)
                        .setValue(OverheadTrussBlock.CORNER, isCorner);
                // yawDeg = NaN: BE.setYawDegrees を skip して実配置 (= ANGLE_8 のみ) と一致
                items.add(new PreviewItem(pos, state, Float.NaN));
            }
        }

        // insulators
        if (placeIns) {
            BlockState insState = insBlock.defaultBlockState().setValue(InsulatorBlock.FACING, Direction.DOWN);
            // 片持ち (= cantilever) は単線専用、 並走検出を無効化
            if (!cantilever && parallelCenters != null && parallelCenters.size() >= 2) {
                // 並走 N 本各 trackCenter 真上に碍子 (= 各点 Y を使用、 carve 高低差対応)
                for (Vec3 c : parallelCenters) {
                    int ix = (int) Math.floor(c.x);
                    int iz = (int) Math.floor(c.z);
                    int iy = (int) Math.floor(c.y) + height - 2;
                    items.add(new PreviewItem(new BlockPos(ix, iy, iz), insState));
                }
            } else {
                // 単線: trackCenter 真上に 1 個のみ (= cantilever 含む)
                int ix = (int) Math.floor(trackCenter.x);
                int iz = (int) Math.floor(trackCenter.z);
                int iy = trackPos.getY() + height - 2;
                items.add(new PreviewItem(new BlockPos(ix, iy, iz), insState));
                // 旧 numTracks 自動計算 logic 廃止 (= 単線片持ちで 2 個生成のバグ回避)
                /* old:
                double negAlong = (outerNeg.x - trackCenter.x) * perp.x
                        + (outerNeg.z - trackCenter.z) * perp.z;
                double posAlong = (outerPos.x - trackCenter.x) * perp.x
                        + (outerPos.z - trackCenter.z) * perp.z;
                double totalPerpDist = posAlong - negAlong;
                final int trackSpacing = 3;
                int numTracks = Math.max(1, (int) Math.round(totalPerpDist / trackSpacing) + 1);
                int trackX = (int) Math.floor(trackCenter.x);
                int trackZ = (int) Math.floor(trackCenter.z);
                for (int i = 0; i < numTracks; i++) {
                    double perpOffset = negAlong + i * trackSpacing;
                    int insX = trackX + (int) Math.round(perp.x * perpOffset);
                    int insZ = trackZ + (int) Math.round(perp.z * perpOffset);
                    items.add(new PreviewItem(new BlockPos(insX, insY, insZ), insState));
                }
                */
            }
        }
        return items;
    }

    /**
     * trackPos (= ユーザーがクリックした線路) の真上に portal frame 1 個を配置する
     * (= player が非クリエイティブならリンク済み chest+ME から資材を消費)。
     * @return 配置されたブロック数
     */
    public static Result placeOne(Level level, BlockPos trackPos, ItemStack tool, Player player) {
        if (level == null || trackPos == null || tool == null) return new Result(0);
        if (!AutoPlacePathFinder.isTrack(level, trackPos)) return new Result(0);
        Vec3 axis = AutoPlacePathFinder.getHorizontalAxis(level, trackPos);
        if (axis == null) return new Result(0);
        return placeOneAt(level, horizontalCenter(trackPos), axis, tool, null, player);
    }

    /**
     * Vec3 trackCenter (= carve 内部の連続位置 OK) と axis を直接受け取って配置。
     * Create カーブ raycast (= {@link TrackCurveRaycast}) からの hit を そのまま配置できる。
     * {@code parallelCenters} が非 null + size ≥ 2 なら (= curve 並走複線対応)、 outerNeg/outerPos と碍子配置に使用。
     * {@code player} が非 null かつ非クリエイティブなら、 リンク済み chest+ME から
     * 架線柱/トラス/碍子 を消費する (= サバイバルコスト)。 不足時は {@link Result#insufficient()}。
     */
    public static Result placeOneAt(Level level, Vec3 trackCenter, Vec3 axis, ItemStack tool,
                                      List<Vec3> parallelCenters, Player player) {
        if (level == null || trackCenter == null || axis == null || tool == null) return new Result(0);

        // === 資材ゲート (creative / player==null は免除) ===
        // preview で全体コストを事前チェック (= plan ≥ 実配置数 なので、 通れば消費は必ず成功)。
        boolean consumeMaterials = player != null && !player.getAbilities().instabuild
                && player instanceof ServerPlayer && level instanceof ServerLevel;
        if (consumeMaterials) {
            Map<Item, Integer> req = countByType(computePreviewAt(level, trackCenter, axis, tool, parallelCenters));
            if (!req.isEmpty()) {
                var missing = OverheadPoleSupply.getMissing((ServerPlayer) player, tool, (ServerLevel) level, req);
                if (!missing.isEmpty()) return Result.noMaterials();
            }
        }

        BlockPos trackPos = BlockPos.containing(trackCenter);

        int height      = AutoPlaceConfig.getHeight(tool);
        int clearance   = AutoPlaceConfig.getClearance(tool);
        int scanRange   = AutoPlaceConfig.getScanRange(tool);
        boolean cantilever = AutoPlaceConfig.getCantilever(tool);
        boolean placeTruss = AutoPlaceConfig.getPlaceTruss(tool);
        boolean placeIns   = AutoPlaceConfig.getPlaceInsulator(tool);

        // 手動回転 (= 8 方向 ANGLE_8) を axis に適用
        int manualRot = AutoPlaceConfig.getManualRotation(tool);
        if (manualRot > 0) {
            double rad = Math.toRadians(manualRot * 45.0);
            double cos = Math.cos(rad), sin = Math.sin(rad);
            axis = new Vec3(axis.x * cos - axis.z * sin, 0, axis.x * sin + axis.z * cos);
        }

        Block poleBlock  = ModBlocks.OVERHEAD_POLE.get();
        Block trussBlock = ModBlocks.OVERHEAD_TRUSS.get();
        Block insBlock   = ModBlocks.INSULATOR.get();

        // **8 方向 snap** (= 16 方向は廃止、 ANGLE_8 のみ使用)
        int poleAngle = OverheadPlacementHelper.axisToAngle8(axis);
        Vec3 snappedAxis = angle8ToHorizontalAxis(poleAngle);
        float poleYawDeg = poleAngle * 45f;
        Vec3 perp = new Vec3(-snappedAxis.z, 0, snappedAxis.x).normalize();

        // === 並走 track 検出 ===
        // parallelCenters が override されていれば 並走 N 本扱い、
        // null なら **単線扱い** (= 呼び出し元が multiTrack 設定を尊重済み、 world scan による誤検出回避)
        Vec3 outerNeg;
        Vec3 outerPos;
        if (parallelCenters != null && parallelCenters.size() >= 2) {
            double minPerp = 0, maxPerp = 0;
            Vec3 minPt = trackCenter, maxPt = trackCenter;
            for (Vec3 c : parallelCenters) {
                double along = (c.x - trackCenter.x) * perp.x + (c.z - trackCenter.z) * perp.z;
                if (along < minPerp) { minPerp = along; minPt = c; }
                if (along > maxPerp) { maxPerp = along; maxPt = c; }
            }
            outerNeg = minPt;
            outerPos = maxPt;
        } else {
            // 単線: outer = trackCenter で portal を最短化
            outerNeg = trackCenter;
            outerPos = trackCenter;
        }

        int topY = trackPos.getY() + height - 1;
        int insY = trackPos.getY() + height - 2;
        int polesPerStack = height + 1;

        int poleCount = 0;
        int trussCount = 0;
        int insCount = 0;

        // === pole 位置探索 + 配置 ===
        BlockPos polePosPos = findUsablePolePos(level, trackCenter, outerPos, perp, +1, clearance, polesPerStack);
        BlockPos polePosNeg = null;
        if (!cantilever) {
            polePosNeg = findUsablePolePos(level, trackCenter, outerNeg, perp, -1, clearance, polesPerStack);
            if (polePosNeg == null || polePosPos == null) return new Result(0);
        } else if (polePosPos == null) {
            polePosPos = findUsablePolePos(level, trackCenter, outerNeg, perp, -1, clearance, polesPerStack);
            if (polePosPos == null) return new Result(0);
            perp = perp.scale(-1);
            Vec3 tmpOuter = outerNeg; outerNeg = outerPos; outerPos = tmpOuter;
        }

        if (polePosNeg != null) poleCount += placePoleStack(level, polePosNeg, poleBlock, poleAngle, poleYawDeg, polesPerStack);
        if (polePosPos != null) poleCount += placePoleStack(level, polePosPos, poleBlock, poleAngle, poleYawDeg, polesPerStack);

        // === truss ===
        BlockPos trussStart;
        BlockPos trussEnd;
        if (cantilever) {
            double offset = (TRACK_HALF_WIDTH + clearance) * 2.0;
            int dx = (int) Math.round(-perp.x * offset);
            int dz = (int) Math.round(-perp.z * offset);
            trussStart = new BlockPos(polePosPos.getX() + dx, topY, polePosPos.getZ() + dz);
            trussEnd = polePosPos;
        } else {
            trussStart = polePosNeg;
            trussEnd = polePosPos;
        }

        // 旧 8 方向 model 方式 (= 動的 anchor 方式は廃止): cantilever / 非 cantilever 共通で
        // placeTrussLine が静的 truss block を line 配置する。
        if (placeTruss && trussStart != null && trussEnd != null) {
            int dx = trussEnd.getX() - trussStart.getX();
            int dz = trussEnd.getZ() - trussStart.getZ();
            int trussLineAngle = directionToTrussAngle8(dx, dz);
            float trussLineYawDeg = trussLineAngle * 45f;
            BlockPos centerCell = new BlockPos(trackPos.getX(), topY, trackPos.getZ());
            trussCount += placeTrussLine(level, trussStart, trussEnd, centerCell, topY,
                    trussBlock, trussLineAngle, trussLineYawDeg, cantilever);
        }

        if (placeIns) {
            // 片持ち (= cantilever) は単線専用、 並走検出を無効化
            if (!cantilever && parallelCenters != null && parallelCenters.size() >= 2) {
                // 並走 N 本 (= 各 Vec3) の真上に碍子。 各点の Y を直接使用 (= curve でも高低差対応)
                for (Vec3 c : parallelCenters) {
                    int insX = (int) Math.floor(c.x);
                    int insZ = (int) Math.floor(c.z);
                    int insYLocal = (int) Math.floor(c.y) + height - 2;
                    BlockPos insPos = new BlockPos(insX, insYLocal, insZ);
                    BlockState existing = level.getBlockState(insPos);
                    if (!existing.isAir() && !existing.canBeReplaced()) continue;
                    BlockState state = insBlock.defaultBlockState().setValue(InsulatorBlock.FACING, Direction.DOWN);
                    level.setBlock(insPos, state, Block.UPDATE_ALL);
                    insCount++;
                }
            } else {
                // 単線 (= cantilever 含む): trackCenter 真上に 1 個のみ
                int insXSingle = (int) Math.floor(trackCenter.x);
                int insZSingle = (int) Math.floor(trackCenter.z);
                int insYSingle = trackPos.getY() + height - 2;
                BlockPos insPos = new BlockPos(insXSingle, insYSingle, insZSingle);
                BlockState existing = level.getBlockState(insPos);
                if (existing.isAir() || existing.canBeReplaced()) {
                    BlockState state = insBlock.defaultBlockState().setValue(InsulatorBlock.FACING, Direction.DOWN);
                    level.setBlock(insPos, state, Block.UPDATE_ALL);
                    insCount++;
                }
            }
        }

        int placed = poleCount + trussCount + insCount;
        // 実際に置いた数だけ chest+ME から消費 (= preview で事前チェック済みなので必ず成功する)
        if (consumeMaterials && placed > 0) {
            Map<Item, Integer> actualReq = new LinkedHashMap<>();
            if (poleCount > 0) actualReq.put(ModItems.OVERHEAD_POLE.get(), poleCount);
            if (trussCount > 0) actualReq.put(ModItems.OVERHEAD_TRUSS.get(), trussCount);
            if (insCount > 0) actualReq.put(ModItems.INSULATOR.get(), insCount);
            OverheadPoleSupply.consume((ServerPlayer) player, tool, (ServerLevel) level, actualReq);
        }
        return new Result(placed, poleCount, trussCount, insCount, false);
    }

    private static Vec3 horizontalCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** PreviewItem の plan を block 種別ごとの必要 Item 数に集計 (= 資材コスト算出用)。 */
    private static Map<Item, Integer> countByType(List<PreviewItem> plan) {
        LinkedHashMap<Item, Integer> m = new LinkedHashMap<>();
        for (PreviewItem it : plan) {
            Block b = it.state().getBlock();
            Item item;
            if (b == ModBlocks.OVERHEAD_POLE.get()) item = ModItems.OVERHEAD_POLE.get();
            else if (b == ModBlocks.OVERHEAD_TRUSS.get()) item = ModItems.OVERHEAD_TRUSS.get();
            else if (b == ModBlocks.INSULATOR.get()) item = ModItems.INSULATOR.get();
            else continue;
            m.merge(item, 1, Integer::sum);
        }
        return m;
    }

    private static BlockPos findUsablePolePos(Level level, Vec3 trackCenter, Vec3 outerEdge,
                                                Vec3 perp, int sign, int baseClearance, int polesPerStack) {
        BlockPos primary = computePolePos(trackCenter, outerEdge, perp, sign, baseClearance);
        if (canPlaceStack(level, primary, polesPerStack)) return primary;
        for (int delta = 1; delta <= CLEARANCE_TOLERANCE; delta++) {
            BlockPos try1 = computePolePos(trackCenter, outerEdge, perp, sign, baseClearance + delta);
            if (canPlaceStack(level, try1, polesPerStack)) return try1;
            int adj2 = baseClearance - delta;
            if (adj2 < 0) continue;
            BlockPos try2 = computePolePos(trackCenter, outerEdge, perp, sign, adj2);
            if (canPlaceStack(level, try2, polesPerStack)) return try2;
        }
        return null;
    }

    private static BlockPos computePolePos(Vec3 trackCenter, Vec3 outerEdge, Vec3 perp,
                                            int sign, int clearance) {
        double offsetAlongPerp = (outerEdge.x - trackCenter.x) * perp.x
                + (outerEdge.z - trackCenter.z) * perp.z;
        int intDist = (int) Math.round(TRACK_HALF_WIDTH + clearance);
        double totalAlongPerp = offsetAlongPerp + sign * intDist;
        int trackX = (int) Math.floor(trackCenter.x);
        int trackY = (int) Math.floor(trackCenter.y);
        int trackZ = (int) Math.floor(trackCenter.z);
        return new BlockPos(
                trackX + (int) Math.round(perp.x * totalAlongPerp),
                trackY,
                trackZ + (int) Math.round(perp.z * totalAlongPerp));
    }

    private static boolean canPlaceStack(Level level, BlockPos basePos, int height) {
        for (int y = 0; y < height; y++) {
            BlockState s = level.getBlockState(basePos.above(y));
            if (!s.isAir() && !s.canBeReplaced()) return false;
        }
        return true;
    }

    private static int placePoleStack(Level level, BlockPos basePos, Block poleBlock,
                                       int angle8, float yawDegrees, int height) {
        int placed = 0;
        for (int y = 0; y < height; y++) {
            BlockPos pos = basePos.above(y);
            BlockState existing = level.getBlockState(pos);
            if (!existing.isAir() && !existing.canBeReplaced()) break;
            BlockState state = poleBlock.defaultBlockState()
                    .setValue(OverheadPoleBlock.ANGLE_8, angle8);
            level.setBlock(pos, state, Block.UPDATE_ALL);
            // BE に yaw 設定 (NaN なら ANGLE_8 fallback、 setYaw 呼ばない)
            if (!Float.isNaN(yawDegrees)) {
                var be = level.getBlockEntity(pos);
                if (be instanceof com.trainsystemutilities.electrification.blockentity.OverheadPoleBlockEntity poleBe) {
                    poleBe.setYawDegrees(yawDegrees);
                }
            }
            placed++;
        }
        return placed;
    }

    private static int placeTrussLine(Level level, BlockPos start, BlockPos end,
                                       BlockPos centerCell, int yLine,
                                       Block trussBlock, int fallbackAngle8, float lineYawDeg, boolean cantilever) {
        List<BlockPos> lineCells = lineThroughCenter(start, end, centerCell, yLine);
        if (lineCells.size() <= 2) return 0;
        int last = lineCells.size() - 1;
        int placed = 0;
        for (int s = 1; s < last; s++) {
            BlockPos pos = lineCells.get(s);
            BlockState existing = level.getBlockState(pos);
            if (!existing.isAir() && !existing.canBeReplaced()) continue;
            boolean isEndSide = (s == last - 1);
            boolean isStartSide = (s == 1);
            // **全 cell で line 全体の angle/yaw を統一** (= cell ごと local 方向で切替えると
            //   model 不一致で「繋がらない」 表示になる)
            int useAngle = isEndSide ? (fallbackAngle8 + 4) % 8 : fallbackAngle8;
            float useYaw = isEndSide ? ((lineYawDeg + 180f) % 360f) : lineYawDeg;
            boolean isCorner = isEndSide || (isStartSide && !cantilever);
            BlockState state = trussBlock.defaultBlockState()
                    .setValue(OverheadTrussBlock.ANGLE_8, useAngle)
                    .setValue(OverheadTrussBlock.CORNER, isCorner);
            level.setBlock(pos, state, Block.UPDATE_ALL);
            // setYawDegrees は呼ばない (= 8 方向のみ、 ANGLE_8 で yaw が決まる)
            placed++;
        }
        return placed;
    }

    private static int directionToTrussAngle8(int dx, int dz) {
        if (dx == 0 && dz == 0) return 0;
        double rad = Math.atan2(dz, dx);
        double deg = ((Math.toDegrees(rad) % 360) + 360) % 360;
        return (int) Math.round(deg / 45.0) % 8;
    }

    private static List<BlockPos> lineThroughCenter(BlockPos start, BlockPos end,
                                                     BlockPos center, int y) {
        List<BlockPos> seg1 = bresenhamLine(start.getX(), start.getZ(),
                center.getX(), center.getZ(), y);
        List<BlockPos> seg2 = bresenhamLine(center.getX(), center.getZ(),
                end.getX(), end.getZ(), y);
        List<BlockPos> result = new ArrayList<>(seg1);
        for (int i = 1; i < seg2.size(); i++) result.add(seg2.get(i));
        return result;
    }

    private static List<BlockPos> bresenhamLine(int x0, int z0, int x1, int z1, int y) {
        List<BlockPos> result = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int cx = x0, cz = z0;
        result.add(new BlockPos(cx, y, cz));
        int safety = 256;
        while ((cx != x1 || cz != z1) && safety-- > 0) {
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; cx += sx; }
            if (e2 < dx)  { err += dx; cz += sz; }
            result.add(new BlockPos(cx, y, cz));
        }
        return result;
    }
}
