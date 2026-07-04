package com.trainsystemutilities.schedule;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 連結停車のための幾何計算 (オーバーハング / 停車距離)。
 *
 * <p>B (god-class 本体縮小): {@code TrainCouplingManager} から coupling-geometry の関心を分離。
 * Create の Carriage/Train 形状を読むため Create 結合だが、state lookup (waitingAtStation 等) は
 * 持たず Train を引数で受け取る。 manager が lookup して本クラスへ委譲する。
 */
public final class CouplingGeometry {

    private CouplingGeometry() {}

    /**
     * キャリッジの最大オーバーハングを測定する。
     * 方向判定をせず、両端のオーバーハングの大きい方を返す。
     * どちら側にブロックを追加しても正しく検出できる。
     */
    public static double measureMaxOverhang(Carriage carriage) {
        try {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) return 2;

            CarriageContraption contraption = (CarriageContraption) entity.getContraption();
            if (contraption == null) return 2;

            Direction.Axis forwardAxis = contraption.getAssemblyDirection().getAxis();

            var blocks = contraption.getBlocks();
            if (blocks == null || blocks.isEmpty()) return 2;

            List<Integer> bogeyCoords = new ArrayList<>();
            int blockMin = Integer.MAX_VALUE;
            int blockMax = Integer.MIN_VALUE;
            for (var entry : blocks.entrySet()) {
                int coord = forwardAxis.choose(
                        entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ());
                if (coord < blockMin) blockMin = coord;
                if (coord > blockMax) blockMax = coord;
                if (entry.getValue().state().getBlock() instanceof AbstractBogeyBlock<?>) {
                    bogeyCoords.add(coord);
                }
            }

            if (bogeyCoords.isEmpty()) return 2;

            int bogeyMin = bogeyCoords.stream().min(Integer::compareTo).orElse(0);
            int bogeyMax = bogeyCoords.stream().max(Integer::compareTo).orElse(0);

            // 両端のオーバーハング（ボギーからブロック端の外面まで）
            double ohNeg = bogeyMin - blockMin;
            double ohPos = blockMax - bogeyMax;
            return Math.max(ohNeg, ohPos);
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.debug("[Coupling] overhang measure failed", e);
            return 2;
        }
    }

    /**
     * 停車距離 = 待機列車のボギー間トラック距離 + 待機列車最後尾の最大オーバーハング
     *          + 隙間(2 block) + 侵入列車先頭の最大オーバーハング。
     * 待機 / 侵入の Train は呼び出し側 (manager) が解決して渡す。
     */
    public static double calculateStopDistance(Train waitingTrain, Train incomingTrain) {
        try {
            // ボギー間トラック距離
            double trackFootprint = 0;
            for (var carriage : waitingTrain.carriages) {
                trackFootprint += carriage.bogeySpacing;
            }
            for (int sp : waitingTrain.carriageSpacing) {
                trackFootprint += sp;
            }

            // 最後尾キャリッジの最大オーバーハング
            if (waitingTrain.carriages.isEmpty() || incomingTrain.carriages.isEmpty()) return 0;
            Carriage lastCarriage = waitingTrain.carriages.get(waitingTrain.carriages.size() - 1);
            double trailingOH = measureMaxOverhang(lastCarriage);
            double waitingRear = trackFootprint + trailingOH;

            // 侵入列車先頭キャリッジの最大オーバーハング
            Carriage firstIncoming = incomingTrain.carriages.get(0);
            double incomingFront = measureMaxOverhang(firstIncoming);

            double gap = 2.0;
            return waitingRear + gap + incomingFront;
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.debug("[Coupling] stop distance calc failed", e);
            return 10;
        }
    }
}
