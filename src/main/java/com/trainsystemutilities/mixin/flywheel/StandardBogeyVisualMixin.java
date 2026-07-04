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
 * Phase 23 最適化: 標準 bogey (shaft × 2) の {@code update()} 差分キャッシュ。
 *
 * <p>Create 純正の {@code StandardBogeyVisual.update} は毎フレーム呼ばれ、
 * 各 {@code TransformedInstance} に {@code setTransform(...).…setChanged()} を実行する。
 * {@code setChanged()} は Flywheel に "GPU buffer 再アップロード要" のフラグを立てるため、
 * 入力 (PoseStack の top matrix + wheelAngle) が前フレームと完全一致する場合でも
 * 毎フレーム GPU 帯域を消費する。
 *
 * <p>本 Mixin は入力をビット単位で比較し、完全一致なら update 全体をキャンセルする。
 * 視覚的劣化はゼロ (一致 = 描画結果も一致)。
 *
 * <p>{@code hide()} 呼出でキャッシュ無効化することで、ポータル隠蔽 → 復帰の遷移で
 * 取りこぼしを起こさない。
 */
@Mixin(value = StandardBogeyVisual.class, remap = false)
public abstract class StandardBogeyVisualMixin {

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
