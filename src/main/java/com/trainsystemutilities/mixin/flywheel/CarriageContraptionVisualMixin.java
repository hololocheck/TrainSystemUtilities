package com.trainsystemutilities.mixin.flywheel;

import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import com.trainsystemutilities.client.optimization.TsuTrainOptimization;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 23 最適化: Carriage 単位の frustum 早期 return。
 *
 * <p>Create 純正 (mc1.21.1) の {@code CarriageContraptionVisual.beginFrame} は frustum 判定を
 * 一切行わず、毎フレーム {@code animate(partialTick)} を呼び出し、bogey ごとに
 * {@code translateBogey} + {@code BogeyVisual.update} を走らせる。
 *
 * <p>視界外の列車は描画されないにもかかわらず CPU コストを毎フレーム支払うため、
 * {@code LevelRenderer.cullingFrustum} で AABB 判定を行い、frustum 外の場合は
 * {@code animate} の HEAD でキャンセルする。
 *
 * <p>{@code entity} フィールドは {@link AbstractEntityVisual} (Flywheel) に protected で
 * 定義されているため、mixin class を継承形式にして直接アクセスする。
 * @Shadow で superclass field を取ろうとすると mixin がターゲットクラス自身に field を要求し
 * 起動失敗するため (= MixinApplyError)、{@code extends AbstractEntityVisual<Entity>} で
 * Java コンパイラに継承を認識させる方式。
 */
@Mixin(value = CarriageContraptionVisual.class, remap = false)
public abstract class CarriageContraptionVisualMixin extends AbstractEntityVisual<Entity> {

    /**
     * Mixin class の ctor は実行時に呼ばれない (= bytecode 上はターゲットクラス自身がインスタンス化される)。
     * 単に Java コンパイラに親クラスを認識させるためのダミー。
     */
    protected CarriageContraptionVisualMixin(VisualizationContext ctx, Entity entity, float partialTick) {
        super(ctx, entity, partialTick);
    }

    /**
     * {@code animate(partialTick)} は private だが Mixin から HEAD inject 可能。
     * require=0 で Create バージョン差異があっても起動失敗しない。
     */
    @Inject(method = "animate", at = @At("HEAD"), cancellable = true, require = 0)
    private void trainsystemutilities$frustumCull(float partialTick, CallbackInfo ci) {
        if (!TsuTrainOptimization.isFrustumCullEnabled()) return;

        // entity は AbstractEntityVisual から継承される protected final field
        Entity ent = this.entity;
        if (ent == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        Frustum frustum;
        try {
            frustum = mc.levelRenderer.cullingFrustum;
        } catch (Throwable t) {
            return;
        }
        if (frustum == null) return;

        AABB aabb = ent.getBoundingBox().inflate(TsuTrainOptimization.frustumCullPadding());
        if (!frustum.isVisible(aabb)) {
            TsuTrainOptimization.STATS.frustumSkips.incrementAndGet();
            ci.cancel();
            return;
        }
        TsuTrainOptimization.STATS.frustumPasses.incrementAndGet();
    }
}
