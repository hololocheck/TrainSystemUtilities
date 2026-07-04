package com.trainsystemutilities.client.structure;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.structure.blockentity.TicketGateBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class TicketGateGeoModel extends GeoModel<TicketGateBlockEntity> {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/ticket_gate.geo.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/ticket_gate.png");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/ticket_gate.animation.json");

    @Override
    public boolean crashIfBoneMissing() { return false; }

    @Override public ResourceLocation getModelResource(TicketGateBlockEntity be) { return MODEL; }
    @Override public ResourceLocation getTextureResource(TicketGateBlockEntity be) { return TEXTURE; }
    @Override public ResourceLocation getAnimationResource(TicketGateBlockEntity be) { return ANIMATION; }
}
