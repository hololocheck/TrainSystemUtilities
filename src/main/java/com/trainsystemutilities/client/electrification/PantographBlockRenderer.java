package com.trainsystemutilities.client.electrification;

import com.mojang.blaze3d.vertex.PoseStack;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.blockentity.PantographBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Geckolib による BlockEntity Renderer。
 *
 * <p>{@link PantographGeoModel} が指定するアセットを使って、毎フレーム現在のアニメ状態に基づいて
 * 描画する。アニメ駆動は Block 側の右クリックハンドラから {@code triggerAnim} で発火。
 */
public class PantographBlockRenderer extends GeoBlockRenderer<PantographBlockEntity> {

    public PantographBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new PantographGeoModel());
    }

    @Override
    public void render(PantographBlockEntity be, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
    }
}
