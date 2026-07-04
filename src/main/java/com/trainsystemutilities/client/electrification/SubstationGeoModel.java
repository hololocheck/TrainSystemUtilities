package com.trainsystemutilities.client.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.blockentity.SubstationBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 変電所キュービクル用 Geckolib モデル。
 *
 * <p>静的モデル (= アニメ無し)。3×4×2 マルチブロック全体を 1 つのモデルとして描画する。
 * {@link SubstationBlockRenderer} が FACING に応じた位置補正と回転を行う。
 */
public class SubstationGeoModel extends GeoModel<SubstationBlockEntity> {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/substation.geo.json");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/substation.animation.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/substation.png");

    @Override
    public ResourceLocation getModelResource(SubstationBlockEntity e) { return MODEL; }

    @Override
    public ResourceLocation getAnimationResource(SubstationBlockEntity e) { return ANIMATION; }

    @Override
    public ResourceLocation getTextureResource(SubstationBlockEntity e) { return TEXTURE; }

    @Override
    public boolean crashIfBoneMissing() { return false; }
}
