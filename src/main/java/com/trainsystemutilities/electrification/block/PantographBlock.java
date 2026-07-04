package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.electrification.blockentity.PantographBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * パンタグラフ。屋根に載せて架線から集電する。
 *
 * <p>描画は Geckolib (= {@link com.trainsystemutilities.client.electrification.PantographBlockRenderer})
 * 経由で行う。{@link RenderShape#ENTITYBLOCK_ANIMATED} を返してバニラの baked model 描画を抑止し、
 * BlockEntityRenderer のみで描画させる。
 *
 * <p>右クリックで {@code fold} アニメをトリガー (= 動作確認用テスト機能)。
 */
public class PantographBlock extends BaseEntityBlock {
    public static final MapCodec<PantographBlock> CODEC = simpleCodec(PantographBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 静置時のだいたいのコリジョン (Geckolib モデルは block 境界を超えて広がるが、当たり判定は 1 ブロック内に留める)。 */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 5, 16);

    public PantographBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
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
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // ENTITYBLOCK_ANIMATED = vanilla baked model はレンダーせず、BlockEntityRenderer のみで描画。
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PantographBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PantographBlockEntity pantograph) {
            pantograph.toggleDeployState();
            Component stateName = Component.translatable(pantograph.isDeployed()
                    ? "tsu.pantograph.state_deployed" : "tsu.pantograph.state_folded");
            ChatFormatting color = pantograph.isDeployed() ? ChatFormatting.AQUA : ChatFormatting.GRAY;
            player.displayClientMessage(
                    Component.translatable("tsu.pantograph.block_state_fmt", stateName).withStyle(color),
                    true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
