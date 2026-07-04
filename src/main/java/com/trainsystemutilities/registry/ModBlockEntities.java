package com.trainsystemutilities.registry;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.MonitorBlockEntity;
import com.trainsystemutilities.blockentity.PosterManagementBlockEntity;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.electrification.blockentity.FEInverterBlockEntity;
import com.trainsystemutilities.electrification.blockentity.InsulatorBlockEntity;
import com.trainsystemutilities.electrification.blockentity.OverheadPoleBlockEntity;
import com.trainsystemutilities.electrification.blockentity.OverheadTrussBlockEntity;
import com.trainsystemutilities.electrification.blockentity.PantographBlockEntity;
import com.trainsystemutilities.electrification.blockentity.SubstationBlockEntity;
import com.trainsystemutilities.electrification.blockentity.SubstationDummyBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TrainSystemUtilities.MOD_ID);

    public static final Supplier<BlockEntityType<RailwayManagementBlockEntity>> RAILWAY_MANAGEMENT =
            BLOCK_ENTITIES.register("railway_management",
                    () -> BlockEntityType.Builder.of(
                            RailwayManagementBlockEntity::new,
                            ModBlocks.RAILWAY_MANAGEMENT_BLOCK.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<MonitorBlockEntity>> MONITOR =
            BLOCK_ENTITIES.register("monitor",
                    () -> BlockEntityType.Builder.of(
                            MonitorBlockEntity::new,
                            ModBlocks.MONITOR.get(),
                            ModBlocks.DOUBLE_MONITOR.get(),
                            ModBlocks.MONITOR_HALF.get(),
                            ModBlocks.DOUBLE_MONITOR_HALF.get(),
                            ModBlocks.MONITOR_SLIM.get(),
                            ModBlocks.DOUBLE_MONITOR_SLIM.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<PosterManagementBlockEntity>> POSTER_MANAGEMENT =
            BLOCK_ENTITIES.register("poster_management",
                    () -> BlockEntityType.Builder.of(
                            PosterManagementBlockEntity::new,
                            ModBlocks.POSTER_MANAGEMENT_BLOCK.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<ManagementComputerBlockEntity>> MANAGEMENT_COMPUTER =
            BLOCK_ENTITIES.register("management_computer",
                    () -> BlockEntityType.Builder.of(
                            ManagementComputerBlockEntity::new,
                            ModBlocks.MANAGEMENT_COMPUTER.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<InsulatorBlockEntity>> INSULATOR =
            BLOCK_ENTITIES.register("insulator",
                    () -> BlockEntityType.Builder.of(
                            InsulatorBlockEntity::new,
                            ModBlocks.INSULATOR.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<SubstationBlockEntity>> SUBSTATION =
            BLOCK_ENTITIES.register("substation",
                    () -> BlockEntityType.Builder.of(
                            SubstationBlockEntity::new,
                            ModBlocks.SUBSTATION.get()
                    ).build(null));

    /** Substation の 23 個のダミーブロックにアタッチする stub BE。データは持たず tick も
     *  しないが、Jade 等の tooltip mod が「ここに block entity がある」と認識して
     *  energy bar provider を発火するために必要。capability は
     *  {@code ElectrificationCapabilities} で core へ delegate 済み。 */
    public static final Supplier<BlockEntityType<SubstationDummyBlockEntity>> SUBSTATION_DUMMY =
            BLOCK_ENTITIES.register("substation_dummy",
                    () -> BlockEntityType.Builder.of(
                            SubstationDummyBlockEntity::new,
                            ModBlocks.SUBSTATION_DUMMY.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<FEInverterBlockEntity>> FE_INVERTER =
            BLOCK_ENTITIES.register("fe_inverter",
                    () -> BlockEntityType.Builder.of(
                            FEInverterBlockEntity::new,
                            ModBlocks.FE_INVERTER.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<PantographBlockEntity>> PANTOGRAPH =
            BLOCK_ENTITIES.register("pantograph",
                    () -> BlockEntityType.Builder.of(
                            PantographBlockEntity::new,
                            ModBlocks.PANTOGRAPH.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<com.trainsystemutilities.structure.blockentity.PlatformFenceBlockEntity>> PLATFORM_FENCE =
            BLOCK_ENTITIES.register("platform_fence",
                    () -> BlockEntityType.Builder.of(
                            com.trainsystemutilities.structure.blockentity.PlatformFenceBlockEntity::new,
                            ModBlocks.PLATFORM_FENCE_1M.get(),
                            ModBlocks.PLATFORM_FENCE_3M.get(),
                            ModBlocks.PLATFORM_FENCE_5M.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<com.trainsystemutilities.structure.blockentity.PlatformScreenDoorBlockEntity>> PLATFORM_SCREEN_DOOR =
            BLOCK_ENTITIES.register("platform_screen_door",
                    () -> BlockEntityType.Builder.of(
                            com.trainsystemutilities.structure.blockentity.PlatformScreenDoorBlockEntity::new,
                            ModBlocks.PLATFORM_SCREEN_DOOR.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<com.trainsystemutilities.structure.blockentity.TicketVendingMachineBlockEntity>> TICKET_VENDING_MACHINE =
            BLOCK_ENTITIES.register("ticket_vending_machine",
                    () -> BlockEntityType.Builder.of(
                            com.trainsystemutilities.structure.blockentity.TicketVendingMachineBlockEntity::new,
                            ModBlocks.TICKET_VENDING_MACHINE.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<com.trainsystemutilities.structure.blockentity.TicketGateBlockEntity>> TICKET_GATE =
            BLOCK_ENTITIES.register("ticket_gate",
                    () -> BlockEntityType.Builder.of(
                            com.trainsystemutilities.structure.blockentity.TicketGateBlockEntity::new,
                            ModBlocks.TICKET_GATE.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<OverheadPoleBlockEntity>> OVERHEAD_POLE =
            BLOCK_ENTITIES.register("overhead_pole",
                    () -> BlockEntityType.Builder.of(
                            OverheadPoleBlockEntity::new,
                            ModBlocks.OVERHEAD_POLE.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<OverheadTrussBlockEntity>> OVERHEAD_TRUSS =
            BLOCK_ENTITIES.register("overhead_truss",
                    () -> BlockEntityType.Builder.of(
                            OverheadTrussBlockEntity::new,
                            ModBlocks.OVERHEAD_TRUSS.get()
                    ).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
