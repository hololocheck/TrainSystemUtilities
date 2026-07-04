package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.electrification.blockentity.SubstationDummyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 変電所キュービクル (3×4×2) のダミーブロック 23 個分。
 *
 * <p>視覚的には完全不可視 ({@link RenderShape#INVISIBLE}) で、{@link SubstationBlockRenderer}
 * がコアブロック側で全 24 マスをカバーするモデルを 1 回描画する。
 *
 * <p>当たり判定は通常ブロック (= 1×1×1) なので歩いて通れない。
 *
 * <p>破壊された場合は {@link SubstationMultiblock#findCore} で対応するコアを探し、
 * コア側を air に置換することでマルチブロック全体を撤去する (= コアの onRemove が
 * 残りのダミーを巻き取る)。アイテムドロップはコア位置で行われる。
 */
public class SubstationDummyBlock extends Block implements EntityBlock {

    public static final MapCodec<SubstationDummyBlock> CODEC = simpleCodec(SubstationDummyBlock::new);

    public SubstationDummyBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** Marker BE so Jade discovers the block and fires per-block providers
     *  (energy bar in particular). State / tick はゼロコスト。 */
    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SubstationDummyBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        // 隣接ブロックの面カリングをしない (= 不可視ブロックが周囲ブロックの面を消してしまうのを防ぐ)
        return Shapes.empty();
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, net.minecraft.world.phys.HitResult target,
                                         net.minecraft.world.level.LevelReader level, BlockPos pos, net.minecraft.world.entity.player.Player player) {
        // ダミーは pick で取れない → コアブロックの item を返す
        BlockPos corePos = SubstationMultiblock.findCore((Level) level, pos);
        if (corePos != null) {
            return new ItemStack(com.trainsystemutilities.registry.ModBlocks.SUBSTATION.get());
        }
        return ItemStack.EMPTY;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // ダミーが「破壊された」(= block が変わった、 oldState と newState の block が異なる) 場合のみ反応
        // ただし SubstationBlock.onRemove からの cascade dismantle 中はスキップ (= 再帰防止)
        if (!oldState.is(newState.getBlock()) && !SubstationBlock.isDismantlingMultiblock()) {
            BlockPos corePos = SubstationMultiblock.findCore(level, pos);
            if (corePos != null) {
                BlockState coreState = level.getBlockState(corePos);
                if (coreState.getBlock() instanceof SubstationBlock) {
                    // コア側を撤去 → SubstationBlock.onRemove が残り 22 個のダミーを巻き取る
                    // destroyBlock(true) でコアアイテムがドロップ
                    level.destroyBlock(corePos, true);
                }
            }
        }
        super.onRemove(oldState, level, pos, newState, isMoving);
    }
}
