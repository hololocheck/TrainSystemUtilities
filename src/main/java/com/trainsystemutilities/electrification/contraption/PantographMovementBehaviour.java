package com.trainsystemutilities.electrification.contraption;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.trainsystemutilities.TrainSystemUtilities;

/**
 * パンタグラフの contraption 動作。
 *
 * <p>D2: assemble / disassemble で {@link ContraptionElectrificationState} へ登録/解除。
 * 集電・接触判定は {@link ContraptionElectrificationTicker} 側で全列車を毎 tick 一括処理する
 * (= 停車中も給電するため Create の tick 駆動に依存しない)。
 */
public class PantographMovementBehaviour implements MovementBehaviour {

    @Override
    public boolean disableBlockEntityRendering() {
        return false;
    }

    @Override
    public void startMoving(MovementContext context) {
        ContraptionElectrificationState.registerPantograph(context.contraption, context.localPos);
        boolean nbtDeployed = readDeployedFromContraption(context);
    }

    /** Contraption が保持している BE NBT から Deployed フラグを読み出す。 */
    private static boolean readDeployedFromContraption(MovementContext context) {
        try {
            var blocks = context.contraption.getBlocks();
            if (blocks == null) return false;
            var info = blocks.get(context.localPos);
            if (info == null) return false;
            var tag = info.nbt();
            if (tag == null) return false;
            return tag.getBoolean("Deployed");
        } catch (Exception ex) {
            TrainSystemUtilities.LOGGER.debug("[Pantograph] read Deployed NBT failed", ex);
            return false;
        }
    }

    @Override
    public void stopMoving(MovementContext context) {
        ContraptionElectrificationState.unregisterPantograph(context.contraption, context.localPos);
    }

    @Override
    public void tick(MovementContext context) {
        // no-op: 集電は ContraptionElectrificationTicker が一元管理。
    }
}
