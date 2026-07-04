package com.trainsystemutilities.mixin;

import com.simibubi.create.content.trains.signal.SignalBlockEntity;
import com.simibubi.create.content.trains.signal.SignalBlockEntity.SignalState;
import com.simibubi.create.content.trains.signal.SignalRenderer;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.schedule.CouplingSignalController;
import net.createmod.catnip.animation.AnimationTickHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * 信号ブロックのレンダラーにフックして、連結待ち状態で
 * 赤青点滅を実現する。
 */
@Mixin(value = SignalRenderer.class, remap = false)
public abstract class SignalRendererMixin {

    /** DEBUG: SignalRendererMixin の inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_SIGNAL_INJECT = false;

    // Create SignalBlockEntity.state を override する reflection Field を一度だけ解決してキャッシュ。
    // per-frame の getDeclaredField を廃止。 Create 更新で field が消えたら null (= blink 無効) + 起動時 1 回 warn。
    private static final java.lang.reflect.Field STATE_FIELD = resolveStateField();

    private static java.lang.reflect.Field resolveStateField() {
        try {
            var f = SignalBlockEntity.class.getDeclaredField("state");
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.warn("[SignalRenderer] Create SignalBlockEntity.state field "
                    + "not found (Create version change?); coupling signal blink disabled", e);
            return null;
        }
    }

    @Inject(method = "renderSafe", at = @At("HEAD"), require = 0)
    private void trainsystemutilities$beforeRender(SignalBlockEntity be, float partialTicks,
                                                    PoseStack ms, MultiBufferSource buffer,
                                                    int light, int overlay, CallbackInfo ci) {
        if (DEBUG_DISABLE_SIGNAL_INJECT || STATE_FIELD == null) return;
        if (be.getLevel() == null) return;

        // #8 MP fix: server static map でなく、 server から同期された client map を読む (TTL 付き)。
        CouplingSignalController.SignalOverrideState overrideState =
                CouplingSignalController.getClientSignalOverride(be.getBlockPos(), System.currentTimeMillis());

        if (overrideState == CouplingSignalController.SignalOverrideState.RED_BLUE_BLINK) {
            float renderTime = AnimationTickHolder.getRenderTime(be.getLevel());
            boolean showRed = ((int) (renderTime / 10)) % 2 == 0;
            overrideSignalState(be, showRed ? SignalState.RED : SignalState.GREEN);
        } else if (overrideState == CouplingSignalController.SignalOverrideState.RED_WHITE_SIMULTANEOUS) {
            // 切り離し時: 赤白同時点滅
            float renderTime = AnimationTickHolder.getRenderTime(be.getLevel());
            boolean showColor = ((int) (renderTime / 10)) % 2 == 0;
            overrideSignalState(be, showColor ? SignalState.RED : SignalState.GREEN);
        }
    }

    /** キャッシュ済 Field で be.state を上書き。 失敗は debug ログ化 (旧: 無音 catch)。 */
    private static void overrideSignalState(SignalBlockEntity be, SignalState state) {
        try {
            STATE_FIELD.set(be, state);
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.debug("[SignalRenderer] failed to override signal state at {}", be.getBlockPos(), e);
        }
    }
}
