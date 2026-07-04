package com.trainsystemutilities.compat.jei;

import com.trainsystemutilities.TrainSystemUtilities;
import belugalab.mcss3.screen.JsonLayoutScreen;
import com.trainsystemutilities.client.gui.ManagementComputerScreenV2;
import com.trainsystemutilities.client.gui.PosterManagementScreenV2;
import com.trainsystemutilities.client.gui.RailwayManagementScreenV2;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

@JeiPlugin
public class TSUJeiPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "jei_plugin");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // V2 (JsonLayoutScreen) の overlay 範囲を JEI に通知 (sidebar tooltips が
        // MCSS modal popup と重ならないように)。
        registration.addGuiContainerHandler(RailwayManagementScreenV2.class, jsonOverlayHandler());
        registration.addGuiContainerHandler(PosterManagementScreenV2.class, jsonOverlayHandler());
        registration.addGuiContainerHandler(ManagementComputerScreenV2.class, jsonOverlayHandler());
    }

    /**
     * JEI handler for any {@link JsonLayoutScreen} subclass: returns the
     * current overlay rectangles (primary + secondary if both shown) so JEI
     * suppresses its own UI inside MCSS-managed modal popups.
     */
    private static <T extends JsonLayoutScreen<?>> IGuiContainerHandler<T> jsonOverlayHandler() {
        return new IGuiContainerHandler<>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(T screen) {
                java.util.ArrayList<Rect2i> areas = new java.util.ArrayList<>();
                if (screen.hasOverlay()) {
                    areas.add(new Rect2i(
                            screen.overlayX(), screen.overlayY(),
                            screen.overlayW(), screen.overlayH()));
                }
                if (screen.hasOverlay2()) {
                    areas.add(new Rect2i(
                            screen.overlay2X(), screen.overlay2Y(),
                            screen.overlay2W(), screen.overlay2H()));
                }
                return areas;
            }
        };
    }
}
