package com.trainsystemutilities.electrification.item;

import com.trainsystemutilities.electrification.block.OverheadPoleBlock;
import com.trainsystemutilities.electrification.block.OverheadTrussBlock;
import com.trainsystemutilities.item.GeoBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 架線トラス用 BlockItem。 斜め架線柱 (= ANGLE_8 が 1/3/5/7) の側面に向けて click した
 * とき、 standard placement の cardinal cell ではなく、 pole の回転を適用した
 * diagonal cell に redirect する。
 *
 * <p>意図: 斜め pole の "wide face" は世界座標系で diagonal 方向 (= NE/SE/SW/NW) に
 * 位置するため、 cardinal cell (= 斜め pole の "corner" に相当) ではなく diagonal cell
 * に truss を配置する。
 *
 * <p>例: pole.angle = 1 (NE 軸) の +X face を click した場合:
 * <ol>
 *   <li>click face direction = +X</li>
 *   <li>pole の回転 -1*45° = -45° を適用</li>
 *   <li>rotated direction = (+0.707, +0.707) → 整数化 (+1, +1) = SE</li>
 *   <li>placement target = pole の SE cell</li>
 * </ol>
 *
 * <p>cardinal pole (= ANGLE_8 0/2/4/6) では redirect せず、 standard 配置を使う。
 */
public class OverheadTrussItem extends GeoBlockItem {

    public OverheadTrussItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);

        // Chain placement: 既存 truss にクリック → beam 方向の chain end (= 同 angle truss
        // が連続する先の最初の空 cell) を探索して同 angle で配置。 同じ truss を連続クリック
        // で chain が延長していく UX。
        if (clickedState.getBlock() instanceof OverheadTrussBlock) {
            int sourceAngle = clickedState.getValue(OverheadTrussBlock.ANGLE_8);
            BlockPos targetPos = findChainEndForPlacement(level, clickedPos, sourceAngle);
            if (targetPos != null) {
                // Goal 検出: target の beam 方向 1 cell 先に pole があれば、 ここが
                // chain の終端 (= goal corner)。 corner truss の非対称 beam を chain 側に
                // 向けるため angle を +4 (= 180° 反転) する。
                BlockPos beamNextCell = computeNextCellInBeamDirection(targetPos, sourceAngle);
                boolean atGoal = beamNextCell != null
                        && level.getBlockState(beamNextCell).getBlock() instanceof OverheadPoleBlock;
                int desiredAngle = atGoal ? (sourceAngle + 4) % 8 : sourceAngle;

                BlockPlaceContext redirectCtx = new RedirectedPlaceContext(context, targetPos);
                if (redirectCtx.canPlace()) {
                    InteractionResult result = this.place(redirectCtx);
                    if (result.consumesAction()) {
                        // chain 一貫性のため、 配置された truss の angle を desiredAngle に
                        // 強制 (= 中間 chain は sourceAngle、 goal は sourceAngle+4)。
                        BlockState placedState = level.getBlockState(targetPos);
                        if (placedState.getBlock() instanceof OverheadTrussBlock
                                && placedState.getValue(OverheadTrussBlock.ANGLE_8) != desiredAngle) {
                            BlockState forcedState = placedState.setValue(OverheadTrussBlock.ANGLE_8, desiredAngle);
                            level.setBlock(targetPos, forcedState, Block.UPDATE_ALL);
                        }
                        return result;
                    }
                }
            }
        }

        // 斜め pole + horizontal face click のときは wide face cell に redirect
        if (clickedState.getBlock() instanceof OverheadPoleBlock) {
            int poleAngle = clickedState.getValue(OverheadPoleBlock.ANGLE_8);
            Direction face = context.getClickedFace();
            if (poleAngle % 2 == 1 && face != null && face.getAxis() != Direction.Axis.Y) {
                BlockPos targetPos = computeRotatedTarget(clickedPos, face, poleAngle);
                BlockPos defaultPos = clickedPos.relative(face);
                if (!targetPos.equals(defaultPos)) {
                    BlockPlaceContext redirectCtx = new RedirectedPlaceContext(context, targetPos);
                    if (redirectCtx.canPlace()) {
                        InteractionResult result = this.place(redirectCtx);
                        if (result.consumesAction()) {
                            return result;
                        }
                    }
                }
            }
        }

        return super.useOn(context);
    }

    /**
     * truss の beam 方向 (= angle に対応する単位ベクトル) を整数 cell offset に丸めて、
     * その方向の隣接 cell を返す。 chain 配置の target を決定するために使用。
     */
    private static BlockPos computeNextCellInBeamDirection(BlockPos trussPos, int trussAngle) {
        double rad = Math.toRadians(trussAngle * 45.0);
        int dx = (int) Math.round(Math.cos(rad));
        int dz = (int) Math.round(Math.sin(rad));
        if (dx == 0 && dz == 0) return null;
        dx = Math.max(-1, Math.min(1, dx));
        dz = Math.max(-1, Math.min(1, dz));
        return trussPos.offset(dx, 0, dz);
    }

    /**
     * 起点 truss から beam 方向に歩いて、 同 angle truss が連続する区間の先にある
     * 最初の空 cell (= chain の end) を返す。 同じ truss を連続クリックで chain が
     * 延びていくため、 起点 truss から end までに既存 truss が何個あっても飛び越える。
     *
     * <p>chain が別 angle の truss や非 truss 障害物にぶつかったら null (= 配置不可)。
     * 安全のため最大 64 cell まで walk する。
     */
    private static BlockPos findChainEndForPlacement(net.minecraft.world.level.Level level,
                                                      BlockPos startPos, int trussAngle) {
        BlockPos current = startPos;
        for (int i = 0; i < 64; i++) {
            BlockPos next = computeNextCellInBeamDirection(current, trussAngle);
            if (next == null) return null;
            BlockState nextState = level.getBlockState(next);
            if (!(nextState.getBlock() instanceof OverheadTrussBlock)) {
                // 空 cell (or 別 block) — chain の end
                return next;
            }
            // 既存 truss: 同 angle なら chain 継続、 別 angle なら chain 切れて配置不可
            if (nextState.getValue(OverheadTrussBlock.ANGLE_8) != trussAngle) {
                return null;
            }
            current = next;
        }
        return null;
    }

    /**
     * click face direction を pole の回転 (-angle×45°) で Y 軸まわりに回転し、
     * 整数 cell offset に丸める。 結果の cell は pole の "wide face" 方向の隣接 cell。
     */
    private static BlockPos computeRotatedTarget(BlockPos polePos, Direction clickedFace, int poleAngle) {
        int faceDx = clickedFace.getStepX();
        int faceDz = clickedFace.getStepZ();

        double rad = Math.toRadians(-poleAngle * 45.0);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        // Y 軸まわり回転: new = R(rad) * (dx, dz)
        double rotatedDx = cos * faceDx + sin * faceDz;
        double rotatedDz = -sin * faceDx + cos * faceDz;

        int targetDx = (int) Math.round(rotatedDx);
        int targetDz = (int) Math.round(rotatedDz);
        // 隣接 cell の範囲に clamp
        targetDx = Math.max(-1, Math.min(1, targetDx));
        targetDz = Math.max(-1, Math.min(1, targetDz));

        return polePos.offset(targetDx, 0, targetDz);
    }

    /**
     * getClickedPos を redirect 先に書き換える BlockPlaceContext。 placement logic
     * (= OverheadTrussBlock.getStateForPlacement 等) はこの位置を見るので、 angle や
     * corner state も target cell 基準で正しく計算される。
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
