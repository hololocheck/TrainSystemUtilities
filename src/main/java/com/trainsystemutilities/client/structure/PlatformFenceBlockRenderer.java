package com.trainsystemutilities.client.structure;

import com.mojang.blaze3d.vertex.PoseStack;
import com.trainsystemutilities.structure.blockentity.PlatformFenceBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * ホーム柵 BER。 dummy 子 block は描画 skip、 master のみ描画。
 * 着色はテクスチャ自体を bandColor で置換した動的テクスチャ (= {@link BandTextureCache}) で実現するため、
 * vertex tint は不要 (= GeoModel.getTextureResource で動的 PNG が選択される)。
 * FACING 回転は Geckolib 標準 (= 自動) に任せる。
 */
public class PlatformFenceBlockRenderer extends GeoBlockRenderer<PlatformFenceBlockEntity> {

    public PlatformFenceBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new PlatformFenceGeoModel());
    }

    /** 描画距離拡張: 架線柱 / トラスと同じく 256 ブロック。 */
    @Override
    public int getViewDistance() { return 256; }

    @Override
    public void render(PlatformFenceBlockEntity be, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!be.isMaster()) return;
        super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
    }
}
