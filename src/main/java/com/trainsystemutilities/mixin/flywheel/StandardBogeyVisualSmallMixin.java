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
 * Phase 23: {@code StandardBogeyVisual.Small} (frame + wheel × 2 = 5 instance) の差分キャッシュ。
 *
 * <p>Small.update は super.update を呼んだあと wheel1/wheel2/frame の transform を行う。
 * HEAD でキャンセルすることで {@code setChanged()} 4〜5 回分の GPU 帯域を完全に節約する。
 * 親側 {@link StandardBogeyVisualMixin} のキャッシュとは独立して動作 (each @Unique field set is per-class)。
 *
 * @see StandardBogeyVisualMixin
 */
@Mixin(value = StandardBogeyVisual.Small.class, remap = false)
public abstract class StandardBogeyVisualSmallMixin {

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
