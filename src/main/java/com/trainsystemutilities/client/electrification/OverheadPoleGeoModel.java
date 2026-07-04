package com.trainsystemutilities.client.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.blockentity.OverheadPoleBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geckolib モデル定義: 架線柱の geo / texture を結びつける。 animation なし (= 静的)。
 */
public class OverheadPoleGeoModel extends GeoModel<OverheadPoleBlockEntity> {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/overhead_pole.geo.json");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/overhead_pole.animation.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/overhead_pole.png");

    @Override
    public boolean crashIfBoneMissing() { return false; }

    @Override
    public ResourceLocation getModelResource(OverheadPoleBlockEntity e) { return MODEL; }

    @Override
    public ResourceLocation getAnimationResource(OverheadPoleBlockEntity e) { return ANIMATION; }

    @Override
    public ResourceLocation getTextureResource(OverheadPoleBlockEntity e) { return TEXTURE; }
}
