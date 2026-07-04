package com.trainsystemutilities.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.trainsystemutilities.blockentity.MonitorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * モニターBlockEntityRenderer（空）。
 * 実際の描画はMonitorWorldRenderer（RenderLevelStageEvent）が担当。
 * Sodium等の最適化modとの互換性のため。
 */
public class MonitorBlockEntityRenderer implements BlockEntityRenderer<MonitorBlockEntity> {

    public MonitorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MonitorBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // Rendering handled by MonitorWorldRenderer via RenderLevelStageEvent
    }
}
