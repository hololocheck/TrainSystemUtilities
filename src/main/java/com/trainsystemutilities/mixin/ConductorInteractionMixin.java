package com.trainsystemutilities.mixin;

import com.simibubi.create.api.behaviour.interaction.ConductorBlockInteractionBehavior;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 電子式時刻表列車の運転士右クリックを横取りし、 運航再開/停止のトグルにする (= 時刻表アイテムは取り出させない)。
 *
 * <p>実経路 (2026-06-26 ログで判明): 運転士は contraption の block 挙動で、 右クリックは
 * {@code ContraptionInteractionPacket} → {@code AbstractContraptionEntity.handlePlayerInteraction}
 * → {@code ConductorBlockInteractionBehavior.handlePlayerInteraction} (server)。
 * {@code EntityInteractSpecific} は通らない。 ここを HEAD で cancel し自前トグルする。
 */
@Mixin(value = ConductorBlockInteractionBehavior.class, remap = false)
public class ConductorInteractionMixin {

    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true, require = 0)
    private void trainsystemutilities$electronicToggle(Player player, InteractionHand hand, BlockPos localPos,
                                                       AbstractContraptionEntity contraptionEntity,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (player == null || player.level().isClientSide()) return;
        if (hand == InteractionHand.OFF_HAND) return;
        // Schedule アイテム所持時は付与処理 (Create) に委譲
        if (player.getItemInHand(hand).getItem() instanceof ScheduleItem) return;
        if (!(contraptionEntity instanceof CarriageContraptionEntity cce)) return;
        Carriage carriage = cce.getCarriage();
        if (carriage == null) return;
        Train train = carriage.train;
        if (train == null || train.runtime == null || train.id == null) return;
        if (train.runtime.getSchedule() == null) return;
        if (!ManagementComputerBlockEntity.isTrainElectronicAnywhere(train.id)) return;

        // 電子式: Create の resume/extract を丸ごと cancel し、 運航再開/停止トグルのみ
        boolean nowPaused = !train.runtime.paused;
        train.runtime.paused = nowPaused;
        player.displayClientMessage(Component.translatable(
                nowPaused ? "tsu.mc.train_stopped_toggle" : "tsu.mc.train_resumed_toggle"), true);
        cir.setReturnValue(true);
        TrainSystemUtilities.LOGGER.info("[Cond-TOGGLE] electronic train {} -> paused={}", train.id, nowPaused);
    }
}
