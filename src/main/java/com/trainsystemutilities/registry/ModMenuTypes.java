package com.trainsystemutilities.registry;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.gui.ManagementComputerMenu;
import com.trainsystemutilities.gui.PosterManagementMenu;
import com.trainsystemutilities.gui.RailwayManagementMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, TrainSystemUtilities.MOD_ID);

    public static final Supplier<MenuType<ManagementComputerMenu>> MANAGEMENT_COMPUTER_MENU =
            MENUS.register("management_computer",
                    () -> IMenuTypeExtension.create(ManagementComputerMenu::new));

    public static final Supplier<MenuType<PosterManagementMenu>> POSTER_MANAGEMENT_MENU =
            MENUS.register("poster_management",
                    () -> IMenuTypeExtension.create(PosterManagementMenu::new));

    public static final Supplier<MenuType<RailwayManagementMenu>> RAILWAY_MANAGEMENT_MENU =
            MENUS.register("railway_management",
                    () -> IMenuTypeExtension.create(RailwayManagementMenu::new));

    public static final Supplier<MenuType<com.trainsystemutilities.gui.TrainPresetRefillMenu>> TRAIN_PRESET_REFILL_MENU =
            MENUS.register("train_preset_refill",
                    () -> IMenuTypeExtension.create((id, inv, buf) ->
                            new com.trainsystemutilities.gui.TrainPresetRefillMenu(id, inv)));

    public static final Supplier<MenuType<com.trainsystemutilities.gui.WireConnectorMenu>> WIRE_CONNECTOR_MENU =
            MENUS.register("wire_connector",
                    () -> IMenuTypeExtension.create((id, inv, buf) ->
                            new com.trainsystemutilities.gui.WireConnectorMenu(id, inv)));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
