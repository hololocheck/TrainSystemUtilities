package com.trainsystemutilities.registry;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TrainSystemUtilities.MOD_ID);

    public static final Supplier<CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + TrainSystemUtilities.MOD_ID + ".main"))
                    .icon(() -> new ItemStack(ModItems.MANAGEMENT_COMPUTER.get()))
                    .displayItems((params, output) -> {
                        // 1 段目: 管理系 + モニター 6 種
                        output.accept(ModItems.MANAGEMENT_COMPUTER.get());
                        output.accept(ModItems.RAILWAY_MANAGEMENT_BLOCK.get());
                        output.accept(ModItems.POSTER_MANAGEMENT_BLOCK.get());
                        output.accept(ModItems.MONITOR.get());
                        output.accept(ModItems.MONITOR_HALF.get());
                        output.accept(ModItems.MONITOR_SLIM.get());
                        output.accept(ModItems.DOUBLE_MONITOR.get());
                        output.accept(ModItems.DOUBLE_MONITOR_HALF.get());
                        output.accept(ModItems.DOUBLE_MONITOR_SLIM.get());
                        // 2 段目: 電化ブロック + 駅設備 + ツール
                        output.accept(ModItems.FE_INVERTER.get());
                        output.accept(ModItems.FE_INVERTER_DUMMY.get());
                        output.accept(ModItems.PANTOGRAPH.get());
                        output.accept(ModItems.SUBSTATION.get());
                        output.accept(ModItems.PLATFORM_FENCE_1M.get());
                        output.accept(ModItems.PLATFORM_FENCE_3M.get());
                        output.accept(ModItems.PLATFORM_FENCE_5M.get());
                        output.accept(ModItems.PLATFORM_SCREEN_DOOR.get());
                        output.accept(ModItems.TICKET_VENDING_MACHINE.get());
                        output.accept(ModItems.TICKET_GATE.get());
                        output.accept(ModItems.OVERHEAD_POLE.get());
                        output.accept(ModItems.OVERHEAD_TRUSS.get());
                        output.accept(ModItems.INSULATOR.get());
                        output.accept(ModItems.GREEN_BACK.get());
                        output.accept(ModItems.TRAIN_PRESET_TOOL.get());
                        output.accept(ModItems.TRANSIT_TERMINAL.get());
                        output.accept(ModItems.MEMORY_CARD.get());
                        output.accept(ModItems.MONITOR_LINK_CARD.get());
                        output.accept(ModItems.STATION_RANGE_TOOL.get());
                        output.accept(ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
                        output.accept(ModItems.TRAIN_DETECTION_CARD.get());
                        output.accept(ModItems.POWER_CHECKER.get());
                        output.accept(ModItems.WIRE_CONNECTOR.get());
                        output.accept(ModItems.WIRE_SPOOL.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_TABS.register(eventBus);
    }
}
