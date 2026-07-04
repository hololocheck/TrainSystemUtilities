package com.trainsystemutilities.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.blockentity.PosterManagementBlockEntity;
import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class PosterManagementBlock extends BaseEntityBlock {
    public static final MapCodec<PosterManagementBlock> CODEC = simpleCodec(PosterManagementBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public PosterManagementBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PosterManagementBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PosterManagementBlockEntity posterBE) {
                // 初回設置者を所有者として記録
                if (posterBE.getOwnerUUID() == null) {
                    posterBE.setOwner(player.getUUID(), player.getName().getString());
                }
                // プライベートモード時のアクセス制御
                if (!posterBE.canAccess(player)) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u3053\u306e\u30d6\u30ed\u30c3\u30af\u306f " + (posterBE.getOwnerName().isEmpty() ? "someone" : posterBE.getOwnerName()) + " \u306b\u3088\u3063\u3066\u30ed\u30c3\u30af\u3055\u308c\u3066\u3044\u307e\u3059"), true);
                    return InteractionResult.FAIL;
                }
                serverPlayer.openMenu(posterBE, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PosterManagementBlockEntity) {
                net.minecraft.world.Containers.dropContents(level, pos, (PosterManagementBlockEntity) be);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, ModBlockEntities.POSTER_MANAGEMENT.get(),
                PosterManagementBlockEntity::tick);
    }
}
