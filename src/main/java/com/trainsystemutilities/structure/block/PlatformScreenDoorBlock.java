package com.trainsystemutilities.structure.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.structure.blockentity.PlatformScreenDoorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * ホームドア: 6 ブロック幅 multi-block。 中心 (master) + 左右 3 dummy ずつ計 7 cells で collision。
 * 右クリックで開閉トグル。
 */
public class PlatformScreenDoorBlock extends BaseEntityBlock {
    public static final MapCodec<PlatformScreenDoorBlock> CODEC = simpleCodec(PlatformScreenDoorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    /** モデル 6 block 幅: master + 左 3 + 右 2 = 計 6 cells で非対称配置。
     *  cells: -3, -2, -1, 0 (master), +1, +2。 端 2 cells (= -3, +2) = 柵、 中央 4 cells = ドア。 */
    private static final int LEFT_DUMMIES = 3;
    private static final int RIGHT_DUMMIES = 2;

    private static final VoxelShape SHAPE_NS = Block.box(6, 0, 0, 10, 24, 16);
    private static final VoxelShape SHAPE_EW = Block.box(0, 0, 6, 16, 24, 10);

    public PlatformScreenDoorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getCounterClockWise();
        // multiblock: dummy セル (左右計 5) が全て置換可能でなければ設置させない (= null。 アイテム消費なし)。
        // これにより「一旦置いてから setPlacedBy で削除」(= 消える) を防ぐ。
        if (!canPlaceDummies(context.getLevel(), context.getClickedPos(), lengthAxis(facing))) {
            return null;
        }
        return this.defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        VoxelShape full = switch (state.getValue(FACING)) {
            case NORTH, SOUTH -> SHAPE_NS;
            case EAST, WEST   -> SHAPE_EW;
            default -> SHAPE_NS;
        };
        // 開いている時は中央 4 cells (= ドア) のみ collision なし、 端 2 cells (= 柵) は維持
        if (state.getValue(OPEN) && isDoorCell(state, level, pos)) {
            return Shapes.empty();
        }
        return full;
    }

    /** master からの axis 距離で「ドア cell」 判定: along ∈ [-2, +1] は ドア、 along = -3 or +2 は 柵。 */
    private static boolean isDoorCell(BlockState state, BlockGetter level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof PlatformScreenDoorBlockEntity door)) return true;  // 不明なら ドア扱い
        BlockPos masterPos = door.isMaster() ? pos : door.getMasterPos();
        if (masterPos == null) return true;
        Direction axis = lengthAxis(state.getValue(FACING));
        BlockPos diff = pos.subtract(masterPos);
        int along = axis.getStepX() * diff.getX() + axis.getStepZ() * diff.getZ();
        return along >= -2 && along <= 1;  // ドア cells: -2, -1, 0, +1
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlatformScreenDoorBlockEntity(pos, state);
    }

    /** ドア長軸 (= モデル Z 軸 = facing 方向と平行)。 axis 方向 = master から RIGHT 方向。 */
    public static Direction lengthAxis(Direction facing) {
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
        Direction axis = lengthAxis(state.getValue(FACING));

        // 非対称配置: axis 反対方向に LEFT_DUMMIES、 axis 方向に RIGHT_DUMMIES
        if (!canPlaceDummies(level, pos, axis)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (placer instanceof Player player && !player.isCreative()) {
                player.getInventory().add(new ItemStack(this.asItem()));
            }
            return;
        }

        for (int i = 1; i <= LEFT_DUMMIES; i++) {
            placeDummy(level, pos.relative(axis.getOpposite(), i), state, pos);
        }
        for (int i = 1; i <= RIGHT_DUMMIES; i++) {
            placeDummy(level, pos.relative(axis, i), state, pos);
        }
    }

    private static boolean canPlaceDummies(Level level, BlockPos pos, Direction axis) {
        for (int i = 1; i <= LEFT_DUMMIES; i++) {
            if (!canReplace(level, pos.relative(axis.getOpposite(), i))) return false;
        }
        for (int i = 1; i <= RIGHT_DUMMIES; i++) {
            if (!canReplace(level, pos.relative(axis, i))) return false;
        }
        return true;
    }

    private static boolean canReplace(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || s.canBeReplaced();
    }

    private static void placeDummy(Level level, BlockPos pos, BlockState state, BlockPos masterPos) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PlatformScreenDoorBlockEntity door) {
            door.setDummy(masterPos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PlatformScreenDoorBlockEntity door) {
                if (door.isMaster()) {
                    Direction axis = lengthAxis(state.getValue(FACING));
                    for (int i = 1; i <= LEFT_DUMMIES; i++) {
                        clearDummy(level, pos.relative(axis.getOpposite(), i));
                    }
                    for (int i = 1; i <= RIGHT_DUMMIES; i++) {
                        clearDummy(level, pos.relative(axis, i));
                    }
                } else {
                    BlockPos m = door.getMasterPos();
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
            if (be instanceof PlatformScreenDoorBlockEntity door && !door.isMaster()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        // 手動開閉は廃止。 ホームドアは鉄道管理ブロックの連動制御
        // (検知カード条件 → ScreenDoorConditionEvaluator → setOpen) でのみ開閉する。
        return InteractionResult.PASS;
    }
}
