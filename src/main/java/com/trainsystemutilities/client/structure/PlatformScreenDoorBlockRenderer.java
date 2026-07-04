package com.trainsystemutilities.client.structure;

import com.mojang.blaze3d.vertex.PoseStack;
import com.trainsystemutilities.structure.block.PlatformScreenDoorBlock;
import com.trainsystemutilities.structure.blockentity.PlatformScreenDoorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class PlatformScreenDoorBlockRenderer extends GeoBlockRenderer<PlatformScreenDoorBlockEntity> {
    public PlatformScreenDoorBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new PlatformScreenDoorGeoModel());
    }

    @Override
    public int getViewDistance() { return 256; }

    @Override
    public void render(PlatformScreenDoorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!be.isMaster()) return;
        poseStack.pushPose();

        BlockState state = be.getBlockState();
        Direction axis = state.hasProperty(PlatformScreenDoorBlock.FACING)
                ? PlatformScreenDoorBlock.lengthAxis(state.getValue(PlatformScreenDoorBlock.FACING))
                : Direction.NORTH;
        Direction offset = axis.getOpposite();
        poseStack.translate(offset.getStepX() * 0.5D, 0, offset.getStepZ() * 0.5D);

        super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
