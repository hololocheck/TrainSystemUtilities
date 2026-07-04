package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.electrification.blockentity.InsulatorBlockEntity;
import com.trainsystemutilities.electrification.wire.WireNetworkSavedData;
import com.trainsystemutilities.electrification.wire.WireSyncBroadcaster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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
 * 架線の碍子 (insulator)。架線の接続ノード = 給電網のグラフノード。
 *
 * <p>FACING はポストが「生える方向」(= 設置面の反対側)。
 * 例: 床に置けば FACING=UP で上に伸びる、天井に吊れば FACING=DOWN で下に伸びる。
 * 碍子の先端 (= ワイヤー取付点) は block 中央 + FACING * 0.4。
 */
public class InsulatorBlock extends BaseEntityBlock {
    public static final MapCodec<InsulatorBlock> CODEC = simpleCodec(InsulatorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    private static final VoxelShape SHAPE_UP    = Block.box(5, 0, 5, 11, 12, 11);
    private static final VoxelShape SHAPE_DOWN  = Block.box(5, 4, 5, 11, 16, 11);
    private static final VoxelShape SHAPE_NORTH = Block.box(5, 5, 4, 11, 11, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(5, 5, 0, 11, 11, 12);
    private static final VoxelShape SHAPE_EAST  = Block.box(0, 5, 5, 12, 11, 11);
    private static final VoxelShape SHAPE_WEST  = Block.box(4, 5, 5, 16, 11, 11);

    public InsulatorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case UP    -> SHAPE_UP;
            case DOWN  -> SHAPE_DOWN;
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST  -> SHAPE_EAST;
            case WEST  -> SHAPE_WEST;
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InsulatorBlockEntity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            // 碍子撤去時、入射する全架線接続を削除 (= 宙ぶらりんワイヤー防止)。
            var removed = WireNetworkSavedData.get(server).removeAllAt(pos);
            if (!removed.isEmpty()) {
                WireSyncBroadcaster.broadcast(server);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
