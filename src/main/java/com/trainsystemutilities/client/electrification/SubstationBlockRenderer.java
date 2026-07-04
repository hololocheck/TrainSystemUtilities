package com.trainsystemutilities.client.electrification;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.trainsystemutilities.electrification.block.SubstationBlock;
import com.trainsystemutilities.electrification.blockentity.SubstationBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 変電所キュービクル用 BlockEntity Renderer (Geckolib)。
 *
 * <p>マルチブロック (3×4×2 = 24 マス) の全モデルをコアブロックの BE 描画時に 1 回だけ描画する。
 *
 * <p>{@link GeoBlockRenderer} は標準で (0.5, 0, 0.5) 平行移動 + FACING 回転を行うが、
 * コアブロックがマルチブロックの「前面中央寄り (= 西から 2 番目)」に位置するため、
 * モデルの中央 (= bone origin) がキュービクル中央に揃うように追加オフセットを掛ける。
 *
 * <p>オフセット (FACING ごと、キュービクル前面中央下端への補正):
 * <ul>
 *   <li>NORTH: (+0.5, 0, -0.5)</li>
 *   <li>SOUTH: (-0.5, 0, +0.5)</li>
 *   <li>EAST:  (+0.5, 0, +0.5)</li>
 *   <li>WEST:  (-0.5, 0, -0.5)</li>
 * </ul>
 */
public class SubstationBlockRenderer extends GeoBlockRenderer<SubstationBlockEntity> {

    public SubstationBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new SubstationGeoModel());
    }

    @Override
    public void render(SubstationBlockEntity be, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Direction facing = be.getBlockState().getValue(SubstationBlock.FACING);
        float dx = 0, dz = 0;
        switch (facing) {
            case NORTH -> { dx = +0.5f; dz = -0.5f; }
            case SOUTH -> { dx = -0.5f; dz = +0.5f; }
            case EAST  -> { dx = +0.5f; dz = +0.5f; }
            case WEST  -> { dx = -0.5f; dz = -0.5f; }
            default -> {}
        }
        poseStack.pushPose();
        poseStack.translate(dx, 0, dz);
        super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }

    /** Geckolib 標準の rotateBlock を 180° オフセット付きで上書き。
     *  本キュービクル bbmodel は body が -Z 方向 (= BlockBench 既定の「前方」) に伸びる設計のため、
     *  Geckolib の「FACING=NORTH で 0° 回転」規約と 180° ズレている。
     *  全 FACING に対して 180° 加算することで、ボディが FACING と反対側 (= 背面側) に
     *  正しく伸びるようにする。 */
    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
        super.rotateBlock(facing, poseStack);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
    }
}
