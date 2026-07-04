package com.trainsystemutilities.client.structure;

import com.trainsystemutilities.structure.blockentity.TicketGateBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class TicketGateBlockRenderer extends GeoBlockRenderer<TicketGateBlockEntity> {
    public TicketGateBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new TicketGateGeoModel());
    }

    @Override
    public int getViewDistance() { return 256; }
}
