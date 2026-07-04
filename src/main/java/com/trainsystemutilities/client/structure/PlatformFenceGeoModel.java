package com.trainsystemutilities.client.structure;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.structure.block.PlatformFenceBlock;
import com.trainsystemutilities.structure.blockentity.PlatformFenceBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * ホーム柵 (1m/3m/5m) の Geckolib モデル定義。 BlockState の Block 種別で geo / texture を切り替える。
 */
public class PlatformFenceGeoModel extends GeoModel<PlatformFenceBlockEntity> {

    private static final ResourceLocation MODEL_1M = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/platform_fence_1m.geo.json");
    private static final ResourceLocation MODEL_3M = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/platform_fence_3m.geo.json");
    private static final ResourceLocation MODEL_5M = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/platform_fence_5m.geo.json");

    private static final ResourceLocation TEXTURE_1M = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/platform_fence_1m.png");
    private static final ResourceLocation TEXTURE_3M = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/platform_fence_3m.png");
    private static final ResourceLocation TEXTURE_5M = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/platform_fence_5m.png");

    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/platform_fence.animation.json");

    @Override
    public boolean crashIfBoneMissing() { return false; }

    @Override
    public ResourceLocation getModelResource(PlatformFenceBlockEntity be) {
        int length = lengthOf(be);
        return switch (length) {
            case 3 -> MODEL_3M;
            case 5 -> MODEL_5M;
            default -> MODEL_1M;
        };
    }

    @Override
    public ResourceLocation getTextureResource(PlatformFenceBlockEntity be) {
        int length = lengthOf(be);
        int argb = be.getBandColorARGB();
        return BandTextureCache.get(length, argb);
    }

    @Override
    public ResourceLocation getAnimationResource(PlatformFenceBlockEntity be) { return ANIMATION; }

    private static int lengthOf(PlatformFenceBlockEntity be) {
        if (be.getBlockState().getBlock() instanceof PlatformFenceBlock fence) {
            return fence.getLength();
        }
        return 1;
    }
}
