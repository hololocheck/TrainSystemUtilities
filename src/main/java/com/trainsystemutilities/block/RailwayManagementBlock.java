package com.trainsystemutilities.block;

import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 鉄道管理ブロック - 線路に設置して、ネットワーク上の路線情報を管理する。
 * Create の駅ブロックと同様に線路をクリックして設置する方式。
 */
public class RailwayManagementBlock extends BaseEntityBlock {
    public static final MapCodec<RailwayManagementBlock> CODEC = simpleCodec(RailwayManagementBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 1 cell 内に collision (= 高さ 32 voxel = 2 block 高。 券売機と同じ方式)。
     *  モデルは上端 35 voxel まで伸びるが、 上の 3 voxel は視覚のみ。 */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 32, 16);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public RailwayManagementBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RailwayManagementBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RailwayManagementBlockEntity rmbe) {
            // 初回設置者を所有者として記録
            if (rmbe.getOwnerUUID() == null) {
                rmbe.setOwner(player.getUUID(), player.getName().getString());
            }
            // プライベートモード時のアクセス制御
            if (!rmbe.canAccess(player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.trainsystemutilities.block_locked", rmbe.getOwnerName().isEmpty()
                                ? net.minecraft.network.chat.Component.translatable("message.trainsystemutilities.block_owner_unknown")
                                : net.minecraft.network.chat.Component.literal(rmbe.getOwnerName()))
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.openMenu(new net.minecraft.world.MenuProvider() {
                    @Override
                    public net.minecraft.network.chat.Component getDisplayName() {
                        return net.minecraft.network.chat.Component.translatable("block.trainsystemutilities.railway_management_block");
                    }
                    @Override
                    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inv, Player p) {
                        return new com.trainsystemutilities.gui.RailwayManagementMenu(id, inv, rmbe);
                    }
                }, pos);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RailwayManagementBlockEntity) {
                net.minecraft.world.Containers.dropContents(level, pos, (RailwayManagementBlockEntity) be);
                // 壊れたブロックは駅を守らない (⑲)。 chunk の unload ではここを通らない。
                ((RailwayManagementBlockEntity) be).releaseStationGuard();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, ModBlockEntities.RAILWAY_MANAGEMENT.get(),
                RailwayManagementBlockEntity::tick);
    }
}
