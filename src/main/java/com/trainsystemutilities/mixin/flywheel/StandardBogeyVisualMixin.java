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

    /** Mixin は @Unique field のインライン初期化子をターゲットの ctor に移送しないため、
     *  初期化子を書いても実行時は null になる (= NPE で visual 生成ごと失敗する)。初回 miss 時に遅延生成する。 */
    @Unique
    private Matrix4f trainsystemutilities$lastPose;

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
        Matrix4f last = trainsystemutilities$lastPose;

        if (trainsystemutilities$hasCache && last != null
                && angleBits == trainsystemutilities$lastWheelAngleBits
                && last.equals(current)) {
            TsuTrainOptimization.STATS.bogeyCacheHits.incrementAndGet();
            ci.cancel();
            return;
        }

        if (last == null) {
            last = new Matrix4f();
            trainsystemutilities$lastPose = last;
        }
        last.set(current);
        trainsystemutilities$lastWheelAngleBits = angleBits;
        trainsystemutilities$hasCache = true;
        TsuTrainOptimization.STATS.bogeyCacheMisses.incrementAndGet();
    }

    @Inject(method = "hide", at = @At("HEAD"), require = 0)
    private void trainsystemutilities$invalidateOnHide(CallbackInfo ci) {
        trainsystemutilities$hasCache = false;
    }
}
