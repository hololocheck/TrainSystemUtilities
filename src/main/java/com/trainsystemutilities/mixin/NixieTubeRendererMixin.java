package com.trainsystemutilities.mixin;

import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeRenderer;
import com.simibubi.create.content.trains.signal.SignalBlockEntity.SignalState;
import com.trainsystemutilities.schedule.CouplingSignalController;
import net.createmod.catnip.animation.AnimationTickHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * ニキシー管レンダラーにフックして、連結待ち状態の信号で
 * 赤青点滅を実現する。
 *
 * 通常の信号状態（RED）を点滅パターンに変更:
 * - 赤表示（0.5秒）→ 青表示（0.5秒）の繰り返し
 */
@Mixin(value = NixieTubeRenderer.class, remap = false)
public abstract class NixieTubeRendererMixin {

    /**
     * renderSafe の先頭でニキシー管の信号状態を一時的に変更。
     * 連結待ち中の信号なら、renderTime に応じて RED/GREEN を交互に設定し、
     * 赤青点滅の視覚効果を生む。
     */
    /** DEBUG: NixieTubeRendererMixin の inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_NIXIE_INJECT = false;

    @Inject(method = "renderSafe", at = @At("HEAD"), require = 0)
    private void trainsystemutilities$beforeRender(NixieTubeBlockEntity be, float partialTicks,
                                                    PoseStack ms, MultiBufferSource buffer,
                                                    int light, int overlay, CallbackInfo ci) {
        if (DEBUG_DISABLE_NIXIE_INJECT) return;
        if (be.signalState == null) return;
        if (be.getLevel() == null) return;

        // この信号の近くに連結待ちの駅があるか確認
        // #8 MP fix: server static map でなく、 server から同期された client map を読む (TTL 付き)。
        CouplingSignalController.SignalOverrideState overrideState =
                CouplingSignalController.getClientSignalOverride(be.getBlockPos(), System.currentTimeMillis());

        if (overrideState == CouplingSignalController.SignalOverrideState.RED_BLUE_BLINK) {
            // 連結時: 赤白交互点滅 10tick(0.5秒)ごと
            float renderTime = AnimationTickHolder.getRenderTime(be.getLevel());
            boolean showRed = ((int) (renderTime / 10)) % 2 == 0;
            be.signalState = showRed ? SignalState.RED : SignalState.GREEN;
        } else if (overrideState == CouplingSignalController.SignalOverrideState.RED_WHITE_SIMULTANEOUS) {
            // 切り離し時: 赤白同時点滅（両方ON→両方OFF）
            float renderTime = AnimationTickHolder.getRenderTime(be.getLevel());
            boolean showColor = ((int) (renderTime / 10)) % 2 == 0;
            be.signalState = showColor ? SignalState.RED : null;
        }
    }
}
