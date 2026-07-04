package com.trainsystemutilities.structure.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.structure.blockentity.PlatformFenceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * ホーム柵 (1m/3m/5m 共通)。 length は constructor で指定。
 *
 * <p>**multi-block**: 1m は 1 cell、 3m/5m は master + 2/4 個の dummy cell。
 * dummy も同じ block で {@link PlatformFenceBlockEntity#isMaster}=false で区別。
 * 各 cell が full collision を持つので柵全長で当たり判定される。
 *
 * <p>柵の長軸 = FACING に直交する horizontal axis。 facing=N/S なら長軸 = E/W、
 * facing=E/W なら長軸 = N/S。
 */
public class PlatformFenceBlock extends BaseEntityBlock {
    public static final MapCodec<PlatformFenceBlock> CODEC =
            MapCodec.unit(() -> new PlatformFenceBlock(Properties.of(), 1));
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** collision: モデルの cube 幅 (4 voxel) に合わせ、 高さは vanilla フェンス同等 24 voxel
     *  (= 1.5 block) でジャンプ越え不可。 facing で長軸が変わるので X/Z 反転の 2 種類。 */
    private static final VoxelShape SHAPE_NS = Block.box(6, 0, 0, 10, 24, 16);  // 長軸 Z
    private static final VoxelShape SHAPE_EW = Block.box(0, 0, 6, 16, 24, 10);  // 長軸 X

    private final int length;

    public PlatformFenceBlock(Properties properties, int length) {
        super(properties);
        this.length = length;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public int getLength() { return length; }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 柵長軸が player の左右に伸びる + 帯面が player を向くよう、 反時計回り 90° の facing を採用
        Direction facing = context.getHorizontalDirection().getCounterClockWise();
        // multiblock (3m/5m): dummy セルが全て置換可能でなければ設置させない (= null を返しアイテムを消費させない)。
        // これにより「一旦置いてから setPlacedBy で削除」(= 消える) を防ぐ。
        if (length > 1) {
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            Direction axis = lengthAxis(facing);
            int half = (length - 1) / 2;
            for (int i = 1; i <= half; i++) {
                if (!canReplace(level, pos.relative(axis, i))
                        || !canReplace(level, pos.relative(axis.getOpposite(), i))) {
                    return null;
                }
            }
        }
        return this.defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case NORTH, SOUTH -> SHAPE_NS;
            case EAST, WEST   -> SHAPE_EW;
            default -> SHAPE_NS;
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlatformFenceBlockEntity(pos, state);
    }

    /** 柵の長軸 (= モデル Z 軸 = facing 方向)。 BlockBench で柵が Z 方向に伸びるよう作成済み。 */
    private static Direction lengthAxis(Direction facing) {
        return switch (facing) {
            case NORTH, SOUTH -> Direction.NORTH;
            case EAST, WEST   -> Direction.EAST;
            default -> Direction.NORTH;
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                             @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) return;
        if (length <= 1) return;

        Direction facing = state.getValue(FACING);
        Direction axis = lengthAxis(facing);
        int half = (length - 1) / 2;  // 3m → 1, 5m → 2

        // dummy 配置先が全部 air/replaceable か事前チェック
        for (int i = 1; i <= half; i++) {
            BlockPos a = pos.relative(axis, i);
            BlockPos b = pos.relative(axis.getOpposite(), i);
            if (!canReplace(level, a) || !canReplace(level, b)) {
                // 詰まり: master も削除
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (placer instanceof net.minecraft.world.entity.player.Player p && !p.isCreative()) {
                    p.getInventory().add(new ItemStack(this.asItem()));
                }
                return;
            }
        }

        // dummy 配置 (= 同じ block + state、 BE で isMaster=false)
        for (int i = 1; i <= half; i++) {
            placeDummy(level, pos.relative(axis, i), state, pos);
            placeDummy(level, pos.relative(axis.getOpposite(), i), state, pos);
        }
    }

    private static boolean canReplace(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || s.canBeReplaced();
    }

    private static void placeDummy(Level level, BlockPos pos, BlockState state, BlockPos masterPos) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PlatformFenceBlockEntity fence) {
            fence.setDummy(masterPos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PlatformFenceBlockEntity fence) {
                if (fence.isMaster()) {
                    // master 削除: 全 dummy を air にする
                    Direction facing = state.getValue(FACING);
                    Direction axis = lengthAxis(facing);
                    int half = (length - 1) / 2;
                    for (int i = 1; i <= half; i++) {
                        clearDummy(level, pos.relative(axis, i));
                        clearDummy(level, pos.relative(axis.getOpposite(), i));
                    }
                } else {
                    // dummy 削除: master 経由で全体破壊 (= 再帰で master 分岐に入る)
                    BlockPos m = fence.getMasterPos();
                    if (m != null && level.getBlockState(m).getBlock() == this) {
                        level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    private void clearDummy(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() == this) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PlatformFenceBlockEntity fence && !fence.isMaster()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
