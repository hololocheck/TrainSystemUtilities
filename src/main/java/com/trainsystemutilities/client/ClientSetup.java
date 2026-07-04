package com.trainsystemutilities.client;

import com.trainsystemutilities.client.renderer.MonitorBlockEntityRenderer;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.registry.ModMenuTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class ClientSetup {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::registerRenderers);
        modEventBus.addListener(ClientSetup::registerScreens);
        // P0-3: McssBridge 経由で MCSS 不在環境の NoClassDefFoundError を graceful catch。
        // MCSS が absent でも TSU 本体機能 (= train preset / station group 等) は動作する。
        com.trainsystemutilities.compat.McssBridge.safeRun("TSUWikiEmbeds.registerAll", () ->
                com.trainsystemutilities.client.wiki.TSUWikiEmbeds.registerAll());
        com.trainsystemutilities.compat.McssBridge.safeRun("TsuScreenHints.registerAll", () ->
                com.trainsystemutilities.client.gui.TsuScreenHints.registerAll());

        // FBO deferred update: メイン描画パス外で FBO 更新を実行することで Iris と共存。
        // AFTER_LEVEL は world rendering 完了後に発火するため、ここで FBO 更新しても
        // 当該フレームの shader pass には影響しない (= 列車点滅を防ぐ)。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderLevelStageEvent event) -> {
                    if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                        com.trainsystemutilities.client.renderer.MonitorFboCache.processPendingUpdates();
                    }
                });
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.MONITOR.get(), MonitorBlockEntityRenderer::new);
        // Phase 21: FE 電化 — Geckolib によるパンタグラフレンダラ
        event.registerBlockEntityRenderer(ModBlockEntities.PANTOGRAPH.get(),
                com.trainsystemutilities.client.electrification.PantographBlockRenderer::new);
        // ホーム柵 1m/3m/5m — Geckolib + 帯 tint
        event.registerBlockEntityRenderer(ModBlockEntities.PLATFORM_FENCE.get(),
                com.trainsystemutilities.client.structure.PlatformFenceBlockRenderer::new);
        // ホームドア — Geckolib + open/close アニメ
        event.registerBlockEntityRenderer(ModBlockEntities.PLATFORM_SCREEN_DOOR.get(),
                com.trainsystemutilities.client.structure.PlatformScreenDoorBlockRenderer::new);
        // 券売機 — Geckolib 静的
        event.registerBlockEntityRenderer(ModBlockEntities.TICKET_VENDING_MACHINE.get(),
                com.trainsystemutilities.client.structure.TicketVendingMachineBlockRenderer::new);
        // 自動改札 — Geckolib フラップ開閉
        event.registerBlockEntityRenderer(ModBlockEntities.TICKET_GATE.get(),
                com.trainsystemutilities.client.structure.TicketGateBlockRenderer::new);
        // Phase 24: 変電所キュービクル (3×4×2 マルチブロック) を Geckolib BER で描画
        event.registerBlockEntityRenderer(ModBlockEntities.SUBSTATION.get(),
                com.trainsystemutilities.client.electrification.SubstationBlockRenderer::new);
        // 架線柱 (= ANGLE_8 で 8 方向回転、 Geckolib model)
        event.registerBlockEntityRenderer(ModBlockEntities.OVERHEAD_POLE.get(),
                com.trainsystemutilities.client.electrification.OverheadPoleBlockRenderer::new);
        // 架線トラス (= ANGLE_8 で 8 方向回転 + CORNER で model 切替、 Geckolib model)
        event.registerBlockEntityRenderer(ModBlockEntities.OVERHEAD_TRUSS.get(),
                com.trainsystemutilities.client.electrification.OverheadTrussBlockRenderer::new);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        // V2 (JsonLayoutScreen) を常に使用。V1 は削除済み。
        event.register(ModMenuTypes.MANAGEMENT_COMPUTER_MENU.get(),
                com.trainsystemutilities.client.gui.ManagementComputerScreenV2::new);
        event.register(ModMenuTypes.RAILWAY_MANAGEMENT_MENU.get(),
                com.trainsystemutilities.client.gui.RailwayManagementScreenV2::new);
        event.register(ModMenuTypes.POSTER_MANAGEMENT_MENU.get(),
                com.trainsystemutilities.client.gui.PosterManagementScreenV2::new);
        event.register(ModMenuTypes.TRAIN_PRESET_REFILL_MENU.get(),
                com.trainsystemutilities.client.gui.TrainPresetRefillScreenV2::new);
        event.register(ModMenuTypes.WIRE_CONNECTOR_MENU.get(),
                com.trainsystemutilities.client.gui.WireConnectorRefillScreen::new);
    }
}
