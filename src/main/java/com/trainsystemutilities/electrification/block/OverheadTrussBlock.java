package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.electrification.blockentity.OverheadTrussBlockEntity;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 架線トラス (overhead truss)。 線路を跨ぐ portal frame。
 *
 * <p>8 方向配置: ANGLE_8 = 0..7 (= N / NE / E / SE / S / SW / W / NW)、 各 45°。
 *
 * <p>CORNER 状態: 隣接する架線柱の ANGLE_8 が このトラスと異なる場合 true。
 * 線路カーブ等で柱の角度が変わる地点に置かれたトラスを示し、 描画は背の高い
 * "角トラス" model (= overhead_truss_corner.geo.json) に切り替わる (= 視覚的に
 * 1 block 上に持ち上がる)。 detection 順:
 * <ol>
 *   <li>placement 時: {@link #getStateForPlacement} で隣接 pole を scan</li>
 *   <li>近隣ブロック更新時: {@link #updateShape} で再評価</li>
 * </ol>
 *
 * <p>描画は Geckolib (= {@link com.trainsystemutilities.client.electrification.OverheadTrussBlockRenderer})
 * 経由。 {@link RenderShape#ENTITYBLOCK_ANIMATED} でバニラ baked model 描画を抑止し、
 * BER で 45° 刻み rotation + CORNER による model 切替を行う。
 */
public class OverheadTrussBlock extends BaseEntityBlock {
    public static final MapCodec<OverheadTrussBlock> CODEC = simpleCodec(OverheadTrussBlock::new);

    public static final IntegerProperty ANGLE_8 = IntegerProperty.create("angle_8", 0, 7);
    public static final BooleanProperty CORNER = BooleanProperty.create("corner");

    private static final VoxelShape[] NORMAL_SHAPES = new VoxelShape[8];
    private static final VoxelShape[] CORNER_SHAPES = new VoxelShape[8];

    static {
        VoxelShape normalEW = Block.box(0, 0, 7, 16, 18, 9);
        VoxelShape normalNS = Block.box(7, 0, 0, 9, 18, 16);
        VoxelShape normalDiag = Block.box(0, 0, 0, 16, 18, 16);

        NORMAL_SHAPES[0] = normalEW;
        NORMAL_SHAPES[1] = normalDiag;
        NORMAL_SHAPES[2] = normalNS;
        NORMAL_SHAPES[3] = normalDiag;
        NORMAL_SHAPES[4] = normalEW;
        NORMAL_SHAPES[5] = normalDiag;
        NORMAL_SHAPES[6] = normalNS;
        NORMAL_SHAPES[7] = normalDiag;

        CORNER_SHAPES[0] = normalEW;
        CORNER_SHAPES[1] = normalDiag;
        CORNER_SHAPES[2] = normalNS;
        CORNER_SHAPES[3] = normalDiag;
        CORNER_SHAPES[4] = normalEW;
        CORNER_SHAPES[5] = normalDiag;
        CORNER_SHAPES[6] = normalNS;
        CORNER_SHAPES[7] = normalDiag;
    }

    public OverheadTrussBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(ANGLE_8, 0)
                .setValue(CORNER, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ANGLE_8, CORNER);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelReader level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        // Priority 1: 隣接 pole → pole-truss 方向 (= corner)
        Integer fromPole = resolveAngleFromAdjacentPole(level, pos);
        if (fromPole != null) {
            boolean corner = shouldBeCorner(level, pos, fromPole);
            return this.defaultBlockState()
                    .setValue(ANGLE_8, fromPole)
                    .setValue(CORNER, corner);
        }

        // Priority 2: 隣接 truss → angle inherit (= chain 連続性)
        Integer fromTruss = resolveAngleFromAdjacentTruss(level, pos);
        if (fromTruss != null) {
            return this.defaultBlockState()
                    .setValue(ANGLE_8, fromTruss)
                    .setValue(CORNER, false);
        }

        // Priority 3: player yaw → beam が facing 方向
        // (Create track scan は curve を拾って斜めになる問題があるため使用しない)
        int angle = resolveTrussAngleFromYaw(context);
        return this.defaultBlockState()
                .setValue(ANGLE_8, angle)
                .setValue(CORNER, false);
    }

    /** 隣接 8 cell に OverheadTrussBlock があれば その ANGLE_8 を返す。 chain 配置で
     *  既存 truss の angle を引き継ぐためのもの。 無ければ null。 */
    @Nullable
    private static Integer resolveAngleFromAdjacentTruss(LevelReader level, BlockPos pos) {
        if (level == null || pos == null) return null;
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                           {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] off : offsets) {
            BlockState s = level.getBlockState(pos.offset(off[0], 0, off[1]));
            if (s.getBlock() instanceof OverheadTrussBlock) {
                return s.getValue(ANGLE_8);
            }
        }
        return null;
    }

    /** player yaw を「beam が facing 方向」 となる truss angle に変換。
     *  player なし時は angle 0 (= +X 向き) 返却。 */
    private static int resolveTrussAngleFromYaw(BlockPlaceContext context) {
        LivingEntity player = context.getPlayer();
        if (player == null) return 0;
        return yawToTrussAngle(player.getYRot());
    }

    /** Minecraft yaw を beam がその方向を向く truss angle (= 45° 刻み 8 方向) に変換。 */
    private static int yawToTrussAngle(float yawDegrees) {
        float normalized = ((yawDegrees + 90f) % 360f + 360f) % 360f;
        return Math.round(normalized / 45f) % 8;
    }

    /**
     * 隣接 cell を scan し、 最初に見つかった OverheadPoleBlock との **位置関係** から
     * truss の angle を計算する。 cardinal (N/S/E/W) 4 方向を優先、 次いで diagonal 4 方向。
     * pole が無ければ null。
     *
     * <p>angle は「pole から truss を見た方向」 を {@link OverheadPoleBlock#ANGLE_8} 系に
     * 変換して採用 (= pole の ANGLE_8 値は使わない)。 これにより 4 方向に置いた truss は
     * 各々別 angle になり、 beam が pole から外向きに伸びる cantilever 配置になる。
     *
     * <p>方向 → angle 表 (cantilever style):
     * <ul>
     *   <li>pole の東 (+X) に置く → angle 0 (= beam が +X = 外向き)</li>
     *   <li>pole の南 (+Z) に置く → angle 2 (= beam +Z)</li>
     *   <li>pole の西 (-X) に置く → angle 4 (= beam -X)</li>
     *   <li>pole の北 (-Z) に置く → angle 6 (= beam -Z)</li>
     *   <li>diagonal の場合は同様に 45° 刻み</li>
     * </ul>
     */
    @Nullable
    private static Integer resolveAngleFromAdjacentPole(LevelReader level, BlockPos pos) {
        if (level == null || pos == null) return null;
        // cardinal 優先 (= 通常 placement は cardinal 隣接)
        int[][] cardinalOffsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] off : cardinalOffsets) {
            BlockState s = level.getBlockState(pos.offset(off[0], 0, off[1]));
            if (s.getBlock() instanceof OverheadPoleBlock) {
                // pole → truss 方向 = -off (= off は truss から pole への方向)
                return directionToTrussAngle(-off[0], -off[1]);
            }
        }
        // diagonal fallback
        int[][] diagonalOffsets = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] off : diagonalOffsets) {
            BlockState s = level.getBlockState(pos.offset(off[0], 0, off[1]));
            if (s.getBlock() instanceof OverheadPoleBlock) {
                return directionToTrussAngle(-off[0], -off[1]);
            }
        }
        return null;
    }

    /**
     * 方向ベクトル (dx, dz) を truss の ANGLE_8 (= 0..7) に変換 (= 45° 刻み)。
     */
    private static int directionToTrussAngle(int dx, int dz) {
        double rad = Math.atan2(dz, dx);
        double deg = Math.toDegrees(rad);
        deg = ((deg % 360) + 360) % 360;
        return (int) Math.round(deg / 45.0) % 8;
    }

    /** 隣接ブロック更新時に CORNER 状態を再評価。 */
    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (level instanceof LevelReader reader) {
            int angle = state.getValue(ANGLE_8);
            boolean corner = shouldBeCorner(reader, pos, angle);
            if (corner != state.getValue(CORNER)) {
                return state.setValue(CORNER, corner);
            }
        }
        return super.updateShape(state, dir, neighborState, level, pos, neighborPos);
    }

    /**
     * corner 判定: 全 8 horizontal 方向の隣接 cell に OverheadPoleBlock が 1 つでも
     * あれば true。 truss は pole に取り付けるのが前提なので、 pole 隣接 = corner。
     *
     * <p>隣接 pole が無い場合 (= 孤立 truss) は normal。
     * <p>{@code myAngle} 引数は現 logic では未使用だが、 将来 angle 依存判定に拡張
     * できるよう signature に残す。
     */
    private static boolean shouldBeCorner(LevelReader level, BlockPos pos, int myAngle) {
        if (level == null || pos == null) return false;
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int[] off : offsets) {
            BlockState s = level.getBlockState(pos.offset(off[0], 0, off[1]));
            if (s.getBlock() instanceof OverheadPoleBlock) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int angle = state.getValue(ANGLE_8);
        boolean corner = state.getValue(CORNER);
        return corner ? CORNER_SHAPES[angle] : NORMAL_SHAPES[angle];
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OverheadTrussBlockEntity(pos, state);
    }

    // ===== Ghost block cleanup (= 旧 multi-block 方式の残骸処理) =====
    //
    // 以前の実装で角トラスは上方 2 block に {@link OverheadTrussGhostBlock} を
    // 配置して上端 hit detection を提供していた。 現在は描画 Y offset により角 truss
    // visual が block A 内に収まるため、 ghost は不要になった。
    // onRemove のみ残し、 旧 world data の ghost を truss 撤去時に cleanup する。

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide) {
            removeGhostIfPresent(level, pos.above(1));
            removeGhostIfPresent(level, pos.above(2));
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    private static void removeGhostIfPresent(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof OverheadTrussGhostBlock) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 35);
        }
    }
}
