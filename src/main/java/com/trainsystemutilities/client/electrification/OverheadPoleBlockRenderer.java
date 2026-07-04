package com.trainsystemutilities.client.electrification;

import com.mojang.blaze3d.vertex.PoseStack;
import com.trainsystemutilities.electrification.block.OverheadPoleBlock;
import com.trainsystemutilities.electrification.blockentity.OverheadPoleBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Geckolib BER: 架線柱の描画 + ANGLE_8 (= 8 方向) rotation を適用。
 *
 * <p>rotation を BER 側で行う理由: blockstate JSON の y rotation は 0/90/180/270 のみで
 * 45° 刻みを表現できないため、 model 自体を回転させる必要がある。 Geckolib が
 * `defaultRender` 内で poseStack を block 中心 (0.5, 0, 0.5) に translate する直前に
 * rotation を挿入することで、 model が block 中心軸まわりに回転する。
 */
public class OverheadPoleBlockRenderer extends GeoBlockRenderer<OverheadPoleBlockEntity> {

    public OverheadPoleBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new OverheadPoleGeoModel());
    }

    /** 描画距離拡張: デフォルト 64 → 256 ブロック。 Create 列車に追従して遠景でも消えない。 */
    @Override
    public int getViewDistance() { return 256; }

    @Override
    public void render(OverheadPoleBlockEntity be, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // BE に float yaw があれば優先 (= 連続角度)、 無ければ ANGLE_8 ベース (= 既存配置の後方互換)
        float yawDeg;
        if (be.hasCustomYaw()) {
            yawDeg = be.getYawDegrees();
        } else {
            int angle8 = be.getBlockState().getValue(OverheadPoleBlock.ANGLE_8);
            yawDeg = angle8 * 45f;
        }
        if (Math.abs(yawDeg) < 0.01f) {
            super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yawDeg));
        poseStack.translate(-0.5, 0, -0.5);
        super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
