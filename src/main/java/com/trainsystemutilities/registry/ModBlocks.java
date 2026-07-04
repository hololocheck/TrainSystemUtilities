package com.trainsystemutilities.registry;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.block.ManagementComputerBlock;
import com.trainsystemutilities.block.DoubleSidedMonitorBlock;
import com.trainsystemutilities.block.MonitorBlock;
import com.trainsystemutilities.block.PosterManagementBlock;
import com.trainsystemutilities.block.RailwayManagementBlock;
import com.trainsystemutilities.block.ThinMonitorBlock;
import com.trainsystemutilities.block.ThinDoubleSidedMonitorBlock;
import com.trainsystemutilities.electrification.block.FEInverterBlock;
import com.trainsystemutilities.electrification.block.InsulatorBlock;
import com.trainsystemutilities.electrification.block.OverheadPoleBlock;
import com.trainsystemutilities.electrification.block.OverheadTrussBlock;
import com.trainsystemutilities.electrification.block.OverheadTrussGhostBlock;
import com.trainsystemutilities.electrification.block.PantographBlock;
import com.trainsystemutilities.electrification.block.SubstationBlock;
import com.trainsystemutilities.electrification.block.SubstationDummyBlock;
import com.trainsystemutilities.structure.block.PlatformFenceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(TrainSystemUtilities.MOD_ID);

    public static final DeferredBlock<RailwayManagementBlock> RAILWAY_MANAGEMENT_BLOCK =
            BLOCKS.register("railway_management_block", () -> new RailwayManagementBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    public static final DeferredBlock<MonitorBlock> MONITOR =
            BLOCKS.register("monitor", () -> new MonitorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .lightLevel(state -> 4)
            ));

    public static final DeferredBlock<DoubleSidedMonitorBlock> DOUBLE_MONITOR =
            BLOCKS.register("double_monitor", () -> new DoubleSidedMonitorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .lightLevel(state -> 4)
            ));

    // 薄型モニター（半分 8px）
    public static final DeferredBlock<ThinMonitorBlock> MONITOR_HALF =
            BLOCKS.register("monitor_half", () -> new ThinMonitorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F).requiresCorrectToolForDrops().sound(SoundType.METAL).noOcclusion().lightLevel(state -> 4),
                    8));

    public static final DeferredBlock<ThinDoubleSidedMonitorBlock> DOUBLE_MONITOR_HALF =
            BLOCKS.register("double_monitor_half", () -> new ThinDoubleSidedMonitorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F).requiresCorrectToolForDrops().sound(SoundType.METAL).noOcclusion().lightLevel(state -> 4),
                    8));

    // 極薄モニター（4px）
    public static final DeferredBlock<ThinMonitorBlock> MONITOR_SLIM =
            BLOCKS.register("monitor_slim", () -> new ThinMonitorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F).requiresCorrectToolForDrops().sound(SoundType.METAL).noOcclusion().lightLevel(state -> 4),
                    4));

    public static final DeferredBlock<ThinDoubleSidedMonitorBlock> DOUBLE_MONITOR_SLIM =
            BLOCKS.register("double_monitor_slim", () -> new ThinDoubleSidedMonitorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F).requiresCorrectToolForDrops().sound(SoundType.METAL).noOcclusion().lightLevel(state -> 4),
                    4));

    public static final DeferredBlock<PosterManagementBlock> POSTER_MANAGEMENT_BLOCK =
            BLOCKS.register("poster_management_block", () -> new PosterManagementBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    public static final DeferredBlock<ManagementComputerBlock> MANAGEMENT_COMPUTER =
            BLOCKS.register("management_computer", () -> new ManagementComputerBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    // Phase 21: FE 電化システム — 架線の碍子 (接続ノード)
    public static final DeferredBlock<InsulatorBlock> INSULATOR =
            BLOCKS.register("insulator", () -> new InsulatorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(1.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    // Phase 21: 変電所 (FE 受電 → 架線網へ給電) - キュービクル 3×4×2 マルチブロックのコア
    public static final DeferredBlock<SubstationBlock> SUBSTATION =
            BLOCKS.register("substation", () -> new SubstationBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    // Phase 21: 変電所キュービクルのダミーブロック (= マルチブロックの残り 23 マス)
    public static final DeferredBlock<SubstationDummyBlock> SUBSTATION_DUMMY =
            BLOCKS.register("substation_dummy", () -> new SubstationDummyBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .noLootTable()  // ドロップなし (コア側がドロップする)
            ));

    // Phase 21: FE インバータ (車載電力変換ブロック、3 連マルチブロック)
    public static final DeferredBlock<FEInverterBlock> FE_INVERTER =
            BLOCKS.register("fe_inverter", () -> new FEInverterBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()  // 隣接ブロックの面を消さない (= モデルが部分占有のため)
            ));

    // 装飾版 FE インバータ。見た目と placement 挙動は本物と同一だが BE / FE 機能を持たない。
    // パンタを装飾としてのみ使いたいプレイヤー向け (UI からパンタ展開動作だけ行える)。
    public static final DeferredBlock<com.trainsystemutilities.electrification.block.FEInverterDummyBlock> FE_INVERTER_DUMMY =
            BLOCKS.register("fe_inverter_dummy", () -> new com.trainsystemutilities.electrification.block.FEInverterDummyBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    // Phase 21: パンタグラフ (集電装置)
    public static final DeferredBlock<PantographBlock> PANTOGRAPH =
            BLOCKS.register("pantograph", () -> new PantographBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    // ホーム柵 (= 1m / 3m / 5m、 帯色 BE で動的着色)
    private static BlockBehaviour.Properties fenceProps() {
        return BlockBehaviour.Properties.of()
                .strength(2.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.METAL)
                .noOcclusion();
    }
    public static final DeferredBlock<PlatformFenceBlock> PLATFORM_FENCE_1M =
            BLOCKS.register("platform_fence_1m", () -> new PlatformFenceBlock(fenceProps(), 1));
    public static final DeferredBlock<PlatformFenceBlock> PLATFORM_FENCE_3M =
            BLOCKS.register("platform_fence_3m", () -> new PlatformFenceBlock(fenceProps(), 3));
    public static final DeferredBlock<PlatformFenceBlock> PLATFORM_FENCE_5M =
            BLOCKS.register("platform_fence_5m", () -> new PlatformFenceBlock(fenceProps(), 5));

    // ホームドア (= 4 方向、 右クリックで開閉トグル)
    public static final DeferredBlock<com.trainsystemutilities.structure.block.PlatformScreenDoorBlock> PLATFORM_SCREEN_DOOR =
            BLOCKS.register("platform_screen_door", () -> new com.trainsystemutilities.structure.block.PlatformScreenDoorBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    // 券売機 (= 4 方向、 2 ブロック高、 Geckolib 静的描画)
    public static final DeferredBlock<com.trainsystemutilities.structure.block.TicketVendingMachineBlock> TICKET_VENDING_MACHINE =
            BLOCKS.register("ticket_vending_machine", () -> new com.trainsystemutilities.structure.block.TicketVendingMachineBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    // 自動改札 (= 4 方向、 右クリックで開閉トグル、 Geckolib フラップ open/closed アニメ)
    public static final DeferredBlock<com.trainsystemutilities.structure.block.TicketGateBlock> TICKET_GATE =
            BLOCKS.register("ticket_gate", () -> new com.trainsystemutilities.structure.block.TicketGateBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    // 架線柱 (= 単線用ポール、 ANGLE_8 で 8 方向設置)。 Create track のカーブと整合。
    public static final DeferredBlock<OverheadPoleBlock> OVERHEAD_POLE =
            BLOCKS.register("overhead_pole", () -> new OverheadPoleBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    // 架線トラス (= 複数線路用 portal frame、 ANGLE_8 で 8 方向設置)。
    public static final DeferredBlock<OverheadTrussBlock> OVERHEAD_TRUSS =
            BLOCKS.register("overhead_truss", () -> new OverheadTrussBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)
                            .noOcclusion()
            ));

    // 架線トラス角の上方 hit detection 用 invisible ghost。 OverheadTrussBlock が
    // CORNER=true 時に自動配置 / 撤去。 player から item 取得不可、 drop なし。
    public static final DeferredBlock<OverheadTrussGhostBlock> OVERHEAD_TRUSS_GHOST =
            BLOCKS.register("overhead_truss_ghost", () -> new OverheadTrussGhostBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .noLootTable()
                            .noTerrainParticles()
            ));

    // グリーンバック (= クロマキー用純緑 #00FF00 ブロック、 動画 / SS 背景)。
    // lightLevel 15 で常に全明描画 (= 周囲の光に影響されない一様な緑)。
    public static final DeferredBlock<Block> GREEN_BACK =
            BLOCKS.register("green_back", () -> new Block(BlockBehaviour.Properties.of()
                    .strength(0.5F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.WOOL)
                    .lightLevel(state -> 15)
            ));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
