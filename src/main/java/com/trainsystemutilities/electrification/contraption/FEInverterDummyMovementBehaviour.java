package com.trainsystemutilities.electrification.contraption;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.trainsystemutilities.TrainSystemUtilities;

/**
 * 装飾版 FE インバータの contraption 動作。
 *
 * <p>{@link FEInverterMovementBehaviour} と違い tick 処理は行わず、組立時に
 * {@code dummyInverters} 集合へ自分を登録するだけ。これにより
 * 「列車にパンタ + 装飾インバータが乗っている」ことが管理コンピュータ UI から検知でき、
 * UI からパンタの展開/折畳が可能になる。
 *
 * <p>FE 集電 / 走行停止判定は行わないので、装飾モードの列車は架線を無視して自由に走行できる。
 */
public class FEInverterDummyMovementBehaviour implements MovementBehaviour {

    @Override
    public boolean disableBlockEntityRendering() {
        return false;
    }

    @Override
    public void startMoving(MovementContext context) {
        ContraptionElectrificationState.registerDummyInverter(context.contraption, context.localPos);
        TrainSystemUtilities.LOGGER.info(
                "[Electrification] FEInverterDummy assembled at local {} (decorativeMode)",
                context.localPos);
    }

    @Override
    public void stopMoving(MovementContext context) {
        ContraptionElectrificationState.unregisterDummyInverter(context.contraption, context.localPos);
    }
}
