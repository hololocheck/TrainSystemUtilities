package com.trainsystemutilities.electrification.item;

import com.trainsystemutilities.electrification.block.OverheadPoleBlock;
import com.trainsystemutilities.item.GeoBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 架線柱用 BlockItem ({@link GeoBlockItem} 派生)。 既存 pole に右クリックすると、
 * pole の **真上 (= 縦方向 +Y)** に同 angle の新 pole を chain 配置する。 連続クリックで
 * 既存 pole の上端まで延長していく UX (= 高さ増設用)。
 * Inventory icon は親 GeoBlockItem 経由で Geckolib model を表示。
 */
public class OverheadPoleItem extends GeoBlockItem {

    public OverheadPoleItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);

        // Chain placement: 既存 pole にクリック → 上方向 (= +Y) chain end の空 cell に
        // 同 angle で配置 (= 縦方向に pole を積み上げる)
        if (clickedState.getBlock() instanceof OverheadPoleBlock) {
            int sourceAngle = clickedState.getValue(OverheadPoleBlock.ANGLE_8);
            BlockPos targetPos = findChainEndVertical(level, clickedPos, sourceAngle);
            if (targetPos != null) {
                BlockPlaceContext redirectCtx = new RedirectedPlaceContext(context, targetPos);
                if (redirectCtx.canPlace()) {
                    InteractionResult result = this.place(redirectCtx);
                    if (result.consumesAction()) {
                        // chain 一貫性のため、 配置された pole の angle を source angle に強制
                        // (= Create track scan で別 angle 推定されても source に揃える)
                        BlockState placedState = level.getBlockState(targetPos);
                        if (placedState.getBlock() instanceof OverheadPoleBlock
                                && placedState.getValue(OverheadPoleBlock.ANGLE_8) != sourceAngle) {
                            BlockState forcedState = placedState.setValue(OverheadPoleBlock.ANGLE_8, sourceAngle);
                            level.setBlock(targetPos, forcedState, Block.UPDATE_ALL);
                        }
                        return result;
                    }
                }
            }
        }

        return super.useOn(context);
    }

    /**
     * 起点 pole から **真上 (= +Y)** に歩いて、 同 angle pole が連続する先の最初の空 cell
     * を返す。 同じ pole を連続クリックで chain が縦に積み上がっていくため、 起点から
     * end までに既存 pole が何個あっても飛び越える。
     *
     * <p>上方向に別 angle pole や非 pole 障害物にぶつかったら null。 最大 64 cell まで walk。
     */
    private static BlockPos findChainEndVertical(Level level, BlockPos startPos, int poleAngle) {
        BlockPos current = startPos;
        for (int i = 0; i < 64; i++) {
            BlockPos next = current.above();
            BlockState nextState = level.getBlockState(next);
            if (!(nextState.getBlock() instanceof OverheadPoleBlock)) {
                return next;
            }
            if (nextState.getValue(OverheadPoleBlock.ANGLE_8) != poleAngle) {
                return null;
            }
            current = next;
        }
        return null;
    }

    /**
     * getClickedPos を redirect 先に書き換える BlockPlaceContext。 placement logic
     * (= OverheadPoleBlock.getStateForPlacement) はこの位置を見るので、 angle 等も
     * target cell 基準で計算される (= 隣接 Create track 等)。
     */
    private static class RedirectedPlaceContext extends BlockPlaceContext {
        private final BlockPos redirectPos;

        public RedirectedPlaceContext(UseOnContext context, BlockPos redirectPos) {
            super(context);
            this.redirectPos = redirectPos;
        }

        @Override
        public BlockPos getClickedPos() {
            return redirectPos;
        }

        @Override
        public boolean canPlace() {
            return getLevel().getBlockState(redirectPos).canBeReplaced(this);
        }

        @Override
        public boolean replacingClickedOnBlock() {
            return false;
        }
    }
}
