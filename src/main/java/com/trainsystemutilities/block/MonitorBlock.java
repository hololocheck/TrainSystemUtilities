package com.trainsystemutilities.block;

import com.trainsystemutilities.blockentity.MonitorBlockEntity;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

/**
 * モニターブロック - 設置して使用するブロック。
 * テクスチャが隣接するモニター同士でつながり、巨大モニターを形成する。
 * 管理用コンピューターと関連付けて路線図を投影する。
 */
public class MonitorBlock extends BaseEntityBlock {
    public static final MapCodec<MonitorBlock> CODEC = simpleCodec(MonitorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    // 同じ向きのモニターが隣接しているか（画面面を基準に上下左右）
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /** 任意のBlockStateがモニターブロック（片面/両面/薄型含む）かどうか判定 */
    public static boolean isMonitorBlock(BlockState state) {
        return state.getBlock() instanceof MonitorBlock || state.getBlock() instanceof DoubleSidedMonitorBlock;
    }

    /** 任意のBlockStateが両面モニターかどうか */
    public static boolean isDoubleSidedMonitor(BlockState state) {
        return state.getBlock() instanceof DoubleSidedMonitorBlock;
    }

    public MonitorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(UP, false)
                .setValue(DOWN, false)
                .setValue(LEFT, false)
                .setValue(RIGHT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().getOpposite();
        return this.defaultBlockState()
                .setValue(FACING, facing)
                .setValue(UP, isMonitorAt(level, pos.above(), facing))
                .setValue(DOWN, isMonitorAt(level, pos.below(), facing))
                .setValue(LEFT, isMonitorAt(level, pos.relative(facing.getClockWise()), facing))
                .setValue(RIGHT, isMonitorAt(level, pos.relative(facing.getCounterClockWise()), facing));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction facing = state.getValue(FACING);
        return state
                .setValue(UP, isMonitorAt(level, pos.above(), facing))
                .setValue(DOWN, isMonitorAt(level, pos.below(), facing))
                .setValue(LEFT, isMonitorAt(level, pos.relative(facing.getClockWise()), facing))
                .setValue(RIGHT, isMonitorAt(level, pos.relative(facing.getCounterClockWise()), facing));
    }

    private boolean isMonitorAt(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return isMonitorBlock(state)
                && state.getValue(FACING) == facing;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
                                               BlockHitResult hitResult) {
        // モニターへのリンクは MonitorLinkCardItem 経由で行う。
        // 管理用コンピューター item の右クリック登録フローは廃止 (Phase 5d migration)。
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MonitorBlockEntity monitorBE) {
                monitorBE.updateMultiBlockStructure();
            }
        }
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, ModBlockEntities.MONITOR.get(),
                MonitorBlockEntity::tick);
    }
}
