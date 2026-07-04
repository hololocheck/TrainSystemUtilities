package com.trainsystemutilities.electrification.contraption;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.trainsystemutilities.TrainSystemUtilities;

/**
 * FE インバータの contraption 動作。
 *
 * <p>D2: assemble 検出。
 * D3: tick で車載 FE バッファから維持コストを差し引く。FE 残量 0 → 駆動不可ゲートが立つ (D4 参照)。
 */
public class FEInverterMovementBehaviour implements MovementBehaviour {

    @Override
    public boolean disableBlockEntityRendering() {
        return false;
    }

    @Override
    public void startMoving(MovementContext context) {
        ContraptionElectrificationState.registerInverter(context.contraption, context.localPos);
        TrainSystemUtilities.LOGGER.info(
                "[Electrification] FEInverter assembled at local {} (electrified={})",
                context.localPos,
                ContraptionElectrificationState.isElectrified(context.contraption));
    }

    @Override
    public void stopMoving(MovementContext context) {
        ContraptionElectrificationState.unregisterInverter(context.contraption, context.localPos);
    }

    // 走行維持コストの消費は ContraptionElectrificationTicker.tickAll (列車単位・先頭車から 1 両ずつ、
    // FE_KEEP_ALIVE_PER_TICK/tick) へ移動した。 ここで車両ごとに引くと「車両数 × 1500」になり、 複数
    // インバータ + パンタ 1 本で FE が減り続ける (E1) ため廃止。 tick は default (no-op) に委ねる。
}
