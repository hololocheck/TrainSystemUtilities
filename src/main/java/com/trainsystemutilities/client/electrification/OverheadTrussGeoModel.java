package com.trainsystemutilities.client.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.block.OverheadTrussBlock;
import com.trainsystemutilities.electrification.blockentity.OverheadTrussBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geckolib モデル定義: 架線トラスの geo / texture。 4 種類の geo を CORNER × diagonal
 * (= angle が 1/3/5/7) で切替:
 * <ul>
 *   <li>normal cardinal: overhead_truss.geo.json (= beam 16 unit、 Y 0..18)</li>
 *   <li>normal diagonal: overhead_truss_diagonal.geo.json (= beam 22.6 unit = sqrt(2)、 Y 17..35)</li>
 *   <li>corner cardinal: overhead_truss_corner.geo.json (= beam 20 unit asym +X、 Y 17..35)</li>
 *   <li>corner diagonal: overhead_truss_diagonal_corner.geo.json (= beam 22.6 asym +X、 Y 17..35)</li>
 * </ul>
 *
 * <p>diagonal 用の beam 長 22.6 = 16×sqrt(2) は diagonal cell 距離 sqrt(2) と整合し、
 * chain 配置で beam が端点で接続する。 animation は静的 (= 空 file)。
 */
public class OverheadTrussGeoModel extends GeoModel<OverheadTrussBlockEntity> {

    private static final ResourceLocation MODEL_NORMAL_CARDINAL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/overhead_truss.geo.json");
    private static final ResourceLocation MODEL_NORMAL_DIAGONAL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/overhead_truss_diagonal.geo.json");
    private static final ResourceLocation MODEL_CORNER_CARDINAL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/overhead_truss_corner.geo.json");
    private static final ResourceLocation MODEL_CORNER_DIAGONAL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/overhead_truss_diagonal_corner.geo.json");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/overhead_truss.animation.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/overhead_truss.png");

    @Override
    public boolean crashIfBoneMissing() { return false; }

    @Override
    public ResourceLocation getModelResource(OverheadTrussBlockEntity e) {
        var state = e.getBlockState();
        boolean corner = state.getValue(OverheadTrussBlock.CORNER);
        // **ANGLE_8 ベース** で model 選択 (= hasCustomYaw 関係なし)。
        // 配置側で 中間角度 (= 16 方向 odd) は ANGLE_8 = 1/3/5/7 (= diagonal) に snap 済。
        // BE float yaw で 22.5° step rotation を適用 → 滑らかな中間角度表示。
        int angle = state.getValue(OverheadTrussBlock.ANGLE_8);
        boolean diagonal = (angle % 2 == 1);
        if (corner) {
            return diagonal ? MODEL_CORNER_DIAGONAL : MODEL_CORNER_CARDINAL;
        } else {
            return diagonal ? MODEL_NORMAL_DIAGONAL : MODEL_NORMAL_CARDINAL;
        }
    }

    @Override
    public ResourceLocation getAnimationResource(OverheadTrussBlockEntity e) { return ANIMATION; }

    @Override
    public ResourceLocation getTextureResource(OverheadTrussBlockEntity e) { return TEXTURE; }
}
