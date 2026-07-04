package com.trainsystemutilities.electrification.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 架線トラス角の上方 hit detection 用 invisible block。
 *
 * <p>角トラスの visual は truss block 自身 (= block A) から Y 18..36 (bone) の範囲、
 * world で {@code Y_block + 1.125..2.25} に広がる。 VoxelShape は block 境界を超えて
 * 拡張しても、 ray が source block を通らない場合 raycast されない (= Minecraft の制約)。
 * よって上方 2 block (= A+1, A+2) に invisible ghost を配置し、 各 block 内で
 * truss visual に対応する shape を持たせる。
 *
 * <p>state properties:
 * <ul>
 *   <li>{@link #ANGLE_8} (0..7) — truss と同じ angle、 placeholder shape の rotation 用</li>
 *   <li>{@link #LEVEL} (0..1) — 0 = A+1 (中央 brace 域)、 1 = A+2 (top 梁 域)</li>
 * </ul>
 *
 * <p>lifecycle: {@link OverheadTrussBlock} が onPlace/onRemove で管理。 ghost 単独で
 * placement はできず、 ghost を破壊すると親 truss も破壊される。
 */
public class OverheadTrussGhostBlock extends Block {

    public static final IntegerProperty ANGLE_8 = IntegerProperty.create("angle_8", 0, 7);
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 0, 1);

    /** SHAPES[level][angle]: LEVEL 0 = 中央 brace 域 (Y 0..16), LEVEL 1 = top 梁域 (Y 2..4)。 */
    private static final VoxelShape[][] SHAPES = new VoxelShape[2][8];

    static {
        // 新 model (= 角 truss は構造が 1 unit 下にシフト):
        // bottom 梁 bone Y 17..19、 top 梁 bone Y 33..35 (= 旧 18..20 / 34..36 から -1)
        //
        // LEVEL 0 (= A+1 block 内、 truss bone Y 16..32 → ghost local Y 0..16):
        // bottom 梁 (bone Y 17..19 → ghost Y 1..3) + upper brace の下半分 +
        // 中央 brace 上半。 全体 ghost Y 0..16 を覆う bounding box。
        // X 範囲は truss の角 baseline と同じ (= +X 4px extension)
        SHAPES[0][0] = Block.box(0, 0, 7, 20, 16, 9);
        SHAPES[0][2] = Block.box(7, 0, 0, 9, 16, 20);
        SHAPES[0][4] = Block.box(-4, 0, 7, 16, 16, 9);
        SHAPES[0][6] = Block.box(7, 0, -4, 9, 16, 16);
        VoxelShape l0Diag = Block.box(-4, 0, -4, 20, 16, 20);
        SHAPES[0][1] = l0Diag;
        SHAPES[0][3] = l0Diag;
        SHAPES[0][5] = l0Diag;
        SHAPES[0][7] = l0Diag;

        // LEVEL 1 (= A+2 block 内、 truss bone Y 32..48 → ghost local Y 0..16):
        // top 梁 (bone Y 33..35 → ghost Y 1..3) + upper brace の先端 (bone Y 32..34.30
        // → ghost Y 0..2.30)。 全体 Y 0..3 のみ。
        SHAPES[1][0] = Block.box(0, 0, 7, 20, 3, 9);
        SHAPES[1][2] = Block.box(7, 0, 0, 9, 3, 20);
        SHAPES[1][4] = Block.box(-4, 0, 7, 16, 3, 9);
        SHAPES[1][6] = Block.box(7, 0, -4, 9, 3, 16);
        VoxelShape l1Diag = Block.box(-4, 0, -4, 20, 3, 20);
        SHAPES[1][1] = l1Diag;
        SHAPES[1][3] = l1Diag;
        SHAPES[1][5] = l1Diag;
        SHAPES[1][7] = l1Diag;
    }

    public OverheadTrussGhostBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(ANGLE_8, 0)
                .setValue(LEVEL, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ANGLE_8, LEVEL);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int angle = state.getValue(ANGLE_8);
        int lvl = state.getValue(LEVEL);
        return SHAPES[lvl][angle];
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /**
     * ghost が破壊されたら親 truss (= ghost の {@link #LEVEL} に応じて 1 or 2 block 下)
     * も同時に破壊する。 ただし truss が既に消えている / 別 block に置換中の場合は no-op。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !(newState.getBlock() instanceof OverheadTrussGhostBlock)) {
            int lvl = state.getValue(LEVEL);
            BlockPos trussPos = pos.below(lvl + 1);
            BlockState trussState = level.getBlockState(trussPos);
            if (trussState.getBlock() instanceof OverheadTrussBlock) {
                level.destroyBlock(trussPos, true);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
