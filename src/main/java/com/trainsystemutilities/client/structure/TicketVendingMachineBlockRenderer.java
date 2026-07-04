package com.trainsystemutilities.client.structure;

import com.trainsystemutilities.structure.blockentity.TicketVendingMachineBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class TicketVendingMachineBlockRenderer extends GeoBlockRenderer<TicketVendingMachineBlockEntity> {
    public TicketVendingMachineBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new TicketVendingMachineGeoModel());
    }

    @Override
    public int getViewDistance() { return 256; }
}
