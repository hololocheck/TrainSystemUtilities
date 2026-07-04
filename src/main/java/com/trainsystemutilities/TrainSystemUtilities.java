package com.trainsystemutilities;

import com.trainsystemutilities.client.ClientSetup;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.registry.ModBlocks;
import com.trainsystemutilities.registry.ModCreativeTabs;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.contraption.MonitorMovementBehaviour;
import com.trainsystemutilities.registry.ModMenuTypes;
import com.trainsystemutilities.schedule.CouplingCondition;
import com.trainsystemutilities.schedule.TrainCouplingManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TrainSystemUtilities.MOD_ID)
public class TrainSystemUtilities {
    public static final String MOD_ID = "trainsystemutilities";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // TsuProfiler 用: サーバ tick の開始 nanoTime (= 0 なら計測無効)
    private static volatile long serverTickStart = 0;

    public TrainSystemUtilities(IEventBus modEventBus) {
        LOGGER.info("Initializing Train System Utilities");

        // Manta は独立 mod (@Mod("manta")) として self-init される。
        // TSU 側では自身の mod_id を MCSS に登録するだけ。
        com.manta.MantaBootstrap.registerMod(MOD_ID);
        com.manta.MantaBootstrap.registerWikiMod(MOD_ID);

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        com.trainsystemutilities.registry.ModDataComponents.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        // サーバ tick 全体の計測 (TsuProfiler)
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> {
            serverTickStart = com.trainsystemutilities.profiler.TsuProfiler.start();
        });

        // サーバーtickイベントで保留中の連結/切り離し処理を実行 + tick 終端の計測
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            long couplingStart = com.trainsystemutilities.profiler.TsuProfiler.start();
            try {
                TrainCouplingManager.processPendingMerges();
                TrainCouplingManager.processPendingSplits();
                TrainCouplingManager.processMergeWait();
                TrainCouplingManager.cleanupStaleEntries();
            } finally {
                com.trainsystemutilities.profiler.TsuProfiler.end(
                        com.trainsystemutilities.profiler.TsuProfiler.Phase.TSU_COUPLING, couplingStart);
            }
            // 列車プレビュー snapshot 更新 (5秒間隔)。
            // overworld の registries / gameTime を使う (列車は dimension をまたぐが registries は共通)。
            try {
                var server = event.getServer();
                if (server != null) {
                    var overworld = server.overworld();
                    if (overworld != null) {
                        com.trainsystemutilities.preview.TrainPreviewSnapshotStore.tick(
                                overworld.getGameTime(), overworld.registryAccess());
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("[TrainPreviewSnapshot] tick failed: {}", t.toString());
            }
            com.trainsystemutilities.profiler.TsuProfiler.end(
                    com.trainsystemutilities.profiler.TsuProfiler.Phase.SERVER_TICK, serverTickStart);
            serverTickStart = 0;
        });

        // サーバ停止時に snapshot をクリア (次回起動時に再 capture)
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
            com.trainsystemutilities.preview.TrainPreviewSnapshotStore.clear();
        });

        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.register(modEventBus);
            // クライアントtickで連結後の処理（現在はno-op）
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
                TrainCouplingManager.processClientSync();
            });
        }
    }

    /** DEBUG: MovementBehaviour 登録を一時的にスキップして、登録自体がフリッカー原因か検証。
     *  検証結果: TSU を mods から外しても点滅したため Create 純正の挙動と判明 → false に戻す。 */
    private static final boolean DEBUG_DISABLE_MOVEMENT_BEHAVIOUR_REGISTER = false;

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (!DEBUG_DISABLE_MOVEMENT_BEHAVIOUR_REGISTER) {
                // モニターブロックにMovementBehaviourを登録
                MonitorMovementBehaviour behaviour = new MonitorMovementBehaviour();
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.MONITOR.get(), behaviour);
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.DOUBLE_MONITOR.get(), behaviour);
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.MONITOR_HALF.get(), behaviour);
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.DOUBLE_MONITOR_HALF.get(), behaviour);
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.MONITOR_SLIM.get(), behaviour);
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.DOUBLE_MONITOR_SLIM.get(), behaviour);
                LOGGER.info("Registered MonitorMovementBehaviour for contraption rendering");

                // Phase 21: FE 電化 — インバータ / パンタグラフの contraption 検出
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.FE_INVERTER.get(),
                                new com.trainsystemutilities.electrification.contraption.FEInverterMovementBehaviour());
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.FE_INVERTER_DUMMY.get(),
                                new com.trainsystemutilities.electrification.contraption.FEInverterDummyMovementBehaviour());
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                        .register(ModBlocks.PANTOGRAPH.get(),
                                new com.trainsystemutilities.electrification.contraption.PantographMovementBehaviour());
                LOGGER.info("Registered FE 電化 MovementBehaviours (FEInverter / FEInverterDummy / Pantograph)");
            } else {
                LOGGER.info("[DEBUG] MovementBehaviour registration SKIPPED for flicker investigation");
            }

            // スケジュール条件に連結・切り離しモードを追加
            com.simibubi.create.content.trains.schedule.Schedule.CONDITION_TYPES.add(
                    net.createmod.catnip.data.Pair.of(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, "coupling"),
                            CouplingCondition::new
                    )
            );
            LOGGER.info("Registered CouplingCondition for schedule editor");

            // P0-3: AE2 連携を Ae2Bridge.safeRun 経由に統一 (= ModList check + class link
            // 例外 graceful catch + idempotent registration を bridge 層で保証)。
            com.trainsystemutilities.compat.Ae2Bridge.safeRun("registerGridLinkable", () ->
                    com.trainsystemutilities.compat.ae2.TrainPresetAe2Integration.registerGridLinkable());

            // SAS が導入されていれば PlaybackEndedEvent をフックしてシーケンシャル再生を有効化
            com.trainsystemutilities.announce.AnnouncementScheduler.init();
        });
    }
}
