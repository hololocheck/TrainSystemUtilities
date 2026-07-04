package com.trainsystemutilities.client.structure;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.structure.blockentity.TicketVendingMachineBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class TicketVendingMachineGeoModel extends GeoModel<TicketVendingMachineBlockEntity> {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/ticket_vending_machine.geo.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/ticket_vending_machine.png");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/ticket_vending_machine.animation.json");

    @Override
    public boolean crashIfBoneMissing() { return false; }

    @Override public ResourceLocation getModelResource(TicketVendingMachineBlockEntity be) { return MODEL; }
    @Override public ResourceLocation getTextureResource(TicketVendingMachineBlockEntity be) { return TEXTURE; }
    @Override public ResourceLocation getAnimationResource(TicketVendingMachineBlockEntity be) { return ANIMATION; }
}
