package com.trainsystemutilities.registry;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.MemoryCardItem;
import com.trainsystemutilities.item.MonitorLinkCardItem;
import com.trainsystemutilities.item.RailwayManagementBlockItem;
import com.trainsystemutilities.item.StationRangeToolItem;
import com.trainsystemutilities.item.TrainDetectionCardItem;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.item.TransitTerminalItem;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(TrainSystemUtilities.MOD_ID);

    public static final DeferredItem<BlockItem> DOUBLE_MONITOR =
            ITEMS.register("double_monitor",
                    () -> new BlockItem(ModBlocks.DOUBLE_MONITOR.get(), new Item.Properties()));

    public static final DeferredItem<RailwayManagementBlockItem> RAILWAY_MANAGEMENT_BLOCK =
            ITEMS.register("railway_management_block",
                    () -> new RailwayManagementBlockItem(new Item.Properties()));

    public static final DeferredItem<BlockItem> MONITOR =
            ITEMS.register("monitor",
                    () -> new BlockItem(ModBlocks.MONITOR.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> MONITOR_HALF =
            ITEMS.register("monitor_half",
                    () -> new BlockItem(ModBlocks.MONITOR_HALF.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> DOUBLE_MONITOR_HALF =
            ITEMS.register("double_monitor_half",
                    () -> new BlockItem(ModBlocks.DOUBLE_MONITOR_HALF.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> MONITOR_SLIM =
            ITEMS.register("monitor_slim",
                    () -> new BlockItem(ModBlocks.MONITOR_SLIM.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> DOUBLE_MONITOR_SLIM =
            ITEMS.register("double_monitor_slim",
                    () -> new BlockItem(ModBlocks.DOUBLE_MONITOR_SLIM.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> POSTER_MANAGEMENT_BLOCK =
            ITEMS.register("poster_management_block",
                    () -> new BlockItem(ModBlocks.POSTER_MANAGEMENT_BLOCK.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> MANAGEMENT_COMPUTER =
            ITEMS.register("management_computer",
                    () -> new BlockItem(ModBlocks.MANAGEMENT_COMPUTER.get(), new Item.Properties()));

    public static final DeferredItem<MemoryCardItem> MEMORY_CARD =
            ITEMS.register("memory_card",
                    () -> new MemoryCardItem(new Item.Properties()));

    public static final DeferredItem<MonitorLinkCardItem> MONITOR_LINK_CARD =
            ITEMS.register("monitor_link_card",
                    () -> new MonitorLinkCardItem(new Item.Properties()));

    public static final DeferredItem<TrainPresetToolItem> TRAIN_PRESET_TOOL =
            ITEMS.register("train_preset_tool",
                    () -> new TrainPresetToolItem(new Item.Properties()));

    // Phase 14.1: 駅範囲指定ツール
    public static final DeferredItem<StationRangeToolItem> STATION_RANGE_TOOL =
            ITEMS.register("station_range_tool",
                    () -> new StationRangeToolItem(new Item.Properties()));

    // Phase 14.4: 乗り換え案内端末
    public static final DeferredItem<TransitTerminalItem> TRANSIT_TERMINAL =
            ITEMS.register("transit_terminal",
                    () -> new TransitTerminalItem(new Item.Properties()));

    // Phase 18: SAS 統合用 列車検知カード
    public static final DeferredItem<TrainDetectionCardItem> TRAIN_DETECTION_CARD =
            ITEMS.register("train_detection_card",
                    () -> new TrainDetectionCardItem(new Item.Properties()));

    // 券売機システム: 切符 (= 発券される情報アイテム)
    public static final DeferredItem<com.trainsystemutilities.item.TicketItem> TICKET =
            ITEMS.register("ticket",
                    () -> new com.trainsystemutilities.item.TicketItem(new Item.Properties().stacksTo(16)));

    // Phase 21: FE 電化システム
    public static final DeferredItem<BlockItem> INSULATOR =
            ITEMS.register("insulator",
                    () -> new BlockItem(ModBlocks.INSULATOR.get(), new Item.Properties()));

    public static final DeferredItem<WireConnectorItem> WIRE_CONNECTOR =
            ITEMS.register("wire_connector",
                    () -> new WireConnectorItem(new Item.Properties()));

    // 架線スプール — 架線接続ツールに装填する巻線 (1 個 = 100m)。最大搬入 64 個 = 6400m。
    public static final DeferredItem<com.trainsystemutilities.electrification.item.WireSpoolItem> WIRE_SPOOL =
            ITEMS.register("wire_spool",
                    () -> new com.trainsystemutilities.electrification.item.WireSpoolItem(new Item.Properties()));

    // Substation / Pantograph は Geckolib BE renderer で in-world 描画されるため、
    // GeoBlockItem 経由で inventory でも同じ模型を表示する。FE Inverter は静的 JSON
    // model (BlockBench element export) で in-world 描画されるので vanilla path のまま。
    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> SUBSTATION =
            ITEMS.register("substation",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(
                            ModBlocks.SUBSTATION.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> FE_INVERTER =
            ITEMS.register("fe_inverter",
                    () -> new BlockItem(ModBlocks.FE_INVERTER.get(), new Item.Properties()));

    // 装飾版 FE インバータ (機能なし、見た目と placement のみ本物と同一)
    public static final DeferredItem<BlockItem> FE_INVERTER_DUMMY =
            ITEMS.register("fe_inverter_dummy",
                    () -> new BlockItem(ModBlocks.FE_INVERTER_DUMMY.get(), new Item.Properties()));

    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> PANTOGRAPH =
            ITEMS.register("pantograph",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(
                            ModBlocks.PANTOGRAPH.get(), new Item.Properties()));

    // ホーム柵 1m/3m/5m BlockItem (= GeoBlockItem でインベントリにも 3D 描画)
    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> PLATFORM_FENCE_1M =
            ITEMS.register("platform_fence_1m",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(ModBlocks.PLATFORM_FENCE_1M.get(), new Item.Properties()));
    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> PLATFORM_FENCE_3M =
            ITEMS.register("platform_fence_3m",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(ModBlocks.PLATFORM_FENCE_3M.get(), new Item.Properties()));
    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> PLATFORM_FENCE_5M =
            ITEMS.register("platform_fence_5m",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(ModBlocks.PLATFORM_FENCE_5M.get(), new Item.Properties()));
    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> PLATFORM_SCREEN_DOOR =
            ITEMS.register("platform_screen_door",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(ModBlocks.PLATFORM_SCREEN_DOOR.get(), new Item.Properties()));
    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> TICKET_VENDING_MACHINE =
            ITEMS.register("ticket_vending_machine",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(ModBlocks.TICKET_VENDING_MACHINE.get(), new Item.Properties()));
    public static final DeferredItem<com.trainsystemutilities.item.GeoBlockItem> TICKET_GATE =
            ITEMS.register("ticket_gate",
                    () -> new com.trainsystemutilities.item.GeoBlockItem(ModBlocks.TICKET_GATE.get(), new Item.Properties()));

    // 架線柱 (= 単線用ポール、 8 方向設置)。 GeoBlockItem の派生で、 既存 pole に右
    // クリックで軸方向 chain 配置する。 inventory icon は Geckolib model を表示。
    public static final DeferredItem<com.trainsystemutilities.electrification.item.OverheadPoleItem> OVERHEAD_POLE =
            ITEMS.register("overhead_pole",
                    () -> new com.trainsystemutilities.electrification.item.OverheadPoleItem(
                            ModBlocks.OVERHEAD_POLE.get(), new Item.Properties()));

    // 架線トラス (= 複数線路用 portal frame、 8 方向設置)。 GeoBlockItem の派生で、
    // 斜め架線柱 (= angle 1/3/5/7) に向けて click したとき diagonal cell に redirect する。
    public static final DeferredItem<com.trainsystemutilities.electrification.item.OverheadTrussItem> OVERHEAD_TRUSS =
            ITEMS.register("overhead_truss",
                    () -> new com.trainsystemutilities.electrification.item.OverheadTrussItem(
                            ModBlocks.OVERHEAD_TRUSS.get(), new Item.Properties()));

    // グリーンバック (= クロマキー用緑ブロック)
    public static final DeferredItem<BlockItem> GREEN_BACK =
            ITEMS.register("green_back",
                    () -> new BlockItem(ModBlocks.GREEN_BACK.get(), new Item.Properties()));

    // 架線柱自動配置ツール (= 2 点 click で track 間の path を BFS、 自動配置)
    public static final DeferredItem<com.trainsystemutilities.electrification.item.OverheadPoleAutoToolItem> OVERHEAD_POLE_AUTO_TOOL =
            ITEMS.register("overhead_pole_auto_tool",
                    () -> new com.trainsystemutilities.electrification.item.OverheadPoleAutoToolItem(
                            new Item.Properties()));

    /** Phase 24: 電力チェッカーツール — 碍子/変電所/インバータ/パンタに右クリックで状態表示。 */
    public static final DeferredItem<com.trainsystemutilities.electrification.item.PowerCheckerItem> POWER_CHECKER =
            ITEMS.register("power_checker",
                    () -> new com.trainsystemutilities.electrification.item.PowerCheckerItem(
                            new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
