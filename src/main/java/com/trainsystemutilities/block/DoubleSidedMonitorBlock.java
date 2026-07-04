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
 * 両面モニターブロック - 表裏両方にスクリーンがあるモニター。
 * 接続テクスチャは片面モニターと同じ仕組み。
 * 両面モニター同士、および片面モニターとも接続可能。
 */
public class DoubleSidedMonitorBlock extends BaseEntityBlock {
    public static final MapCodec<DoubleSidedMonitorBlock> CODEC = simpleCodec(DoubleSidedMonitorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public DoubleSidedMonitorBlock(Properties properties) {
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

    /** Check for both single and double-sided monitors with the same facing */
    private boolean isMonitorAt(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        if (MonitorBlock.isMonitorBlock(state)
                && state.getValue(FACING) == facing) {
            return true;
        }
        return false;
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
        // モニターへのリンクは MonitorLinkCardItem 経由で行う (Phase 5d migration)。
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
