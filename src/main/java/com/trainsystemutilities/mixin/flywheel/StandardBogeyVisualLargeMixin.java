package com.trainsystemutilities.mixin.flywheel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.StandardBogeyVisual;
import com.trainsystemutilities.client.optimization.TsuTrainOptimization;

import net.minecraft.nbt.CompoundTag;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 23: {@code StandardBogeyVisual.Large} (9 instance: secondaryShaft × 2 + drive + belt +
 * piston + wheels + pin) の差分キャッシュ。
 *
 * <p>Large.update は最も重い setTransform チェーン (sin/cos / belt scroll を含む) を含むため、
 * HEAD キャンセルの節約効果は他形態より大きい。Small と同じパターンで実装。
 *
 * @see StandardBogeyVisualMixin
 */
@Mixin(value = StandardBogeyVisual.Large.class, remap = false)
public abstract class StandardBogeyVisualLargeMixin {

    @Unique
    private final Matrix4f trainsystemutilities$lastPose = new Matrix4f();

    @Unique
    private int trainsystemutilities$lastWheelAngleBits = 0;

    @Unique
    private boolean trainsystemutilities$hasCache = false;

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
    private void trainsystemutilities$skipIfUnchanged(CompoundTag bogeyData, float wheelAngle, PoseStack poseStack,
                                                      CallbackInfo ci) {
        if (!TsuTrainOptimization.isBogeyDiffCacheEnabled()) return;

        int angleBits = Float.floatToRawIntBits(wheelAngle);
        Matrix4f current = poseStack.last().pose();

        if (trainsystemutilities$hasCache
                && angleBits == trainsystemutilities$lastWheelAngleBits
                && trainsystemutilities$lastPose.equals(current)) {
            TsuTrainOptimization.STATS.bogeyCacheHits.incrementAndGet();
            ci.cancel();
            return;
        }

        trainsystemutilities$lastPose.set(current);
        trainsystemutilities$lastWheelAngleBits = angleBits;
        trainsystemutilities$hasCache = true;
        TsuTrainOptimization.STATS.bogeyCacheMisses.incrementAndGet();
    }

    @Inject(method = "hide", at = @At("HEAD"), require = 0)
    private void trainsystemutilities$invalidateOnHide(CallbackInfo ci) {
        trainsystemutilities$hasCache = false;
    }
}
