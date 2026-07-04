package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.block.SubstationMultiblock;
import com.trainsystemutilities.electrification.blockentity.SubstationBlockEntity;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * FE 電化の capability 登録 (mod イベントバス)。
 *
 * <p>{@code SubstationBlockEntity} は {@link IEnergyStorage} を直接実装するので
 * BE そのものを capability として返す。
 *
 * <p>変電所キュービクル (3×4×2 = 24 ブロック) は、コアブロック 1 個 + ダミーブロック 23 個で
 * 構成される。ケーブル MOD が任意の面に接続できるよう、ダミーブロック側にも capability を
 * Block レベルで登録し、コアブロックの BE へ delegate する。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ElectrificationCapabilities {

    private ElectrificationCapabilities() {}

    @SubscribeEvent
    public static void onRegister(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.FE_INVERTER.get(),
                (be, side) -> be
        );

        // Dummy BE 経由でも capability を引けるようにする (Jade 等が BE 経由で
        // capability を resolve する path に対応)。core へ delegate。
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.SUBSTATION_DUMMY.get(),
                (be, side) -> {
                    if (be == null || be.getLevel() == null) return null;
                    BlockPos corePos = SubstationMultiblock.findCore(be.getLevel(), be.getBlockPos());
                    if (corePos == null) return null;
                    BlockEntity coreBe = be.getLevel().getBlockEntity(corePos);
                    if (coreBe instanceof SubstationBlockEntity sub) {
                        return (IEnergyStorage) sub;
                    }
                    return null;
                }
        );
        // ダミーブロックは BE を持たないので Block 単位で登録、コアへ delegate
        event.registerBlock(
                Capabilities.EnergyStorage.BLOCK,
                (level, pos, state, beIgnored, side) -> {
                    if (level == null) return null;
                    BlockPos corePos = SubstationMultiblock.findCore(level, pos);
                    if (corePos == null) {
                        TrainSystemUtilities.LOGGER.info(
                                "[SubCap] queried at dummy {} side={} → core NOT FOUND",
                                pos, side);
                        return null;
                    }
                    BlockEntity coreBe = level.getBlockEntity(corePos);
                    if (coreBe instanceof SubstationBlockEntity sub) {
                        if (capLogCountdown-- <= 0) {
                            capLogCountdown = CAP_LOG_INTERVAL;
                            TrainSystemUtilities.LOGGER.info(
                                    "[SubCap] queried at dummy {} side={} → core@{} fe={}/{}",
                                    pos, side, corePos, sub.getStoredEnergy(), SubstationBlockEntity.CAPACITY);
                        }
                        return (IEnergyStorage) sub;
                    }
                    TrainSystemUtilities.LOGGER.info(
                            "[SubCap] queried at dummy {} → core@{} BE={} (not SubstationBE)",
                            pos, corePos, coreBe);
                    return null;
                },
                ModBlocks.SUBSTATION_DUMMY.get()
        );

        // コアブロック側のサンプリングログも一応 (= ケーブルが core 直接接続される場合)
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.SUBSTATION.get(),
                (be, side) -> {
                    if (coreCapLogCountdown-- <= 0) {
                        coreCapLogCountdown = CAP_LOG_INTERVAL;
                        TrainSystemUtilities.LOGGER.info(
                                "[SubCap] queried at CORE {} side={} fe={}/{}",
                                be.getBlockPos(), side, be.getStoredEnergy(), SubstationBlockEntity.CAPACITY);
                    }
                    return be;
                }
        );
    }

    private static int capLogCountdown = 0;
    private static int coreCapLogCountdown = 0;
    private static final int CAP_LOG_INTERVAL = 200;
}
