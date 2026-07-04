package com.trainsystemutilities.client.structure;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.structure.blockentity.PlatformScreenDoorBlockEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class PlatformScreenDoorGeoModel extends GeoModel<PlatformScreenDoorBlockEntity> {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/platform_screen_door.geo.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/platform_screen_door.png");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/platform_screen_door.animation.json");

    @Override
    public boolean crashIfBoneMissing() { return false; }

    @Override public ResourceLocation getModelResource(PlatformScreenDoorBlockEntity be) { return MODEL; }
    @Override
    public ResourceLocation getTextureResource(PlatformScreenDoorBlockEntity be) {
        return BandTextureCache.get(TEXTURE, be.getBandColorARGB());
    }
    @Override public ResourceLocation getAnimationResource(PlatformScreenDoorBlockEntity be) { return ANIMATION; }

    /** ガラス領域 (= テクスチャの alpha 部分) を半透明描画するため translucent 強制。 */
    @Override
    public RenderType getRenderType(PlatformScreenDoorBlockEntity be, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }
}
