package com.trainsystemutilities.electrification.item;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.trainsystemutilities.electrification.block.FEInverterBlock;
import com.trainsystemutilities.electrification.block.SubstationMultiblock;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationTicker;
import com.trainsystemutilities.electrification.wire.EnergizedWireState;
import com.trainsystemutilities.electrification.wire.WireConnection;
import com.trainsystemutilities.electrification.wire.WireNetworkSavedData;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 電力チェッカーツール。
 *
 * <p>右クリックで対象ブロックの電化状態を表示:
 * <ul>
 *   <li>碍子 (Insulator): 接続している架線数・通電数・通電架線リスト</li>
 *   <li>変電所 (Substation): 通電生成数</li>
 *   <li>FE インバータ / パンタグラフ: 設置したブロックの情報と、近くの電化列車 1 編成分の合計</li>
 * </ul>
 */
public class PowerCheckerItem extends Item {

    public PowerCheckerItem(Properties props) {
        super(props);
    }

    /**
     * Reads a target before the block's own right-click. The Cubicle, the FE Inverter and the Pantograph consume an
     * ordinary right-click themselves (useWithoutItem), and ServerPlayerGameMode#useItemOn only goes on to the held
     * item's {@link #useOn} when the player sneaks; NeoForge calls this ahead of both.
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (player == null) return InteractionResult.PASS;

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        // A part away from the core reads as its core: a Cubicle's dummies, an FE Inverter's front and back.
        if (block == ModBlocks.SUBSTATION_DUMMY.get()) {
            BlockPos core = SubstationMultiblock.findCore(level, pos);
            if (core != null) {
                pos = core;
                block = ModBlocks.SUBSTATION.get();
            }
        } else if (block == ModBlocks.FE_INVERTER.get()) {
            BlockPos center = FEInverterBlock.centerPosOf(pos, state);
            if (level.getBlockState(center).is(block)) pos = center;
        }

        // The client claims the click for a target and reports nothing; the server's report reaches its chat.
        ServerLevel sLevel = level instanceof ServerLevel s ? s : null;
        if (block == ModBlocks.INSULATOR.get()) {
            if (sLevel != null) reportInsulator(sLevel, player, pos);
        } else if (block == ModBlocks.SUBSTATION.get()) {
            if (sLevel != null) reportSubstation(sLevel, player, pos);
        } else if (block == ModBlocks.FE_INVERTER.get()) {
            if (sLevel != null) reportContraptionBlock(sLevel, player, pos, Component.translatable("block.trainsystemutilities.fe_inverter"));
        } else if (block == ModBlocks.PANTOGRAPH.get()) {
            if (sLevel != null) reportContraptionBlock(sLevel, player, pos, Component.translatable("block.trainsystemutilities.pantograph"));
        } else {
            return InteractionResult.PASS; // not a target: the block's own right-click, then useOn
        }
        return InteractionResult.SUCCESS;
    }

    /** Reached only for a block {@link #onItemUseFirst} passed on, when that block's own right-click did not consume it. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        Block block = level.getBlockState(context.getClickedPos()).getBlock();
        send(player, Component.translatable("tsu.pwck.not_target", block.getName()), ChatFormatting.GRAY);
        return InteractionResult.SUCCESS;
    }

    private void reportInsulator(ServerLevel level, Player player, BlockPos pos) {
        WireNetworkSavedData data = WireNetworkSavedData.get(level);
        EnergizedWireState energized = EnergizedWireState.get(level);
        // この pos に attach している全 wire を列挙 (incident で BlockPos キー直接ヒット)
        List<WireConnection> incident = data.incident(pos);
        int total = incident.size();
        int hot = 0;
        for (WireConnection wc : incident) {
            if (energized.isEnergized(wc)) hot++;
        }
        send(player, Component.translatable("tsu.pwck.header_insulator", pos.toShortString()), ChatFormatting.AQUA);
        send(player, Component.translatable("tsu.pwck.wires_connected", total), ChatFormatting.WHITE);
        if (hot > 0) {
            send(player, Component.translatable("tsu.pwck.wires_hot", hot), ChatFormatting.GREEN);
        } else {
            send(player, Component.translatable("tsu.pwck.no_power"), ChatFormatting.GRAY);
        }
        send(player, Component.translatable("tsu.pwck.totals", data.size(), energized.size()), ChatFormatting.DARK_GRAY);
    }

    private void reportSubstation(ServerLevel level, Player player, BlockPos pos) {
        EnergizedWireState energized = EnergizedWireState.get(level);
        WireNetworkSavedData data = WireNetworkSavedData.get(level);
        send(player, Component.translatable("tsu.pwck.header_substation", pos.toShortString()), ChatFormatting.AQUA);
        send(player, Component.translatable("tsu.pwck.total_wires", data.size()), ChatFormatting.WHITE);
        send(player, Component.translatable("tsu.pwck.energized_marks", energized.size()),
                energized.size() > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    /** 設置済みパンタ/インバータの状態と、近くの電化列車 1 編成分の合計を表示。 */
    private void reportContraptionBlock(ServerLevel level, Player player, BlockPos pos, Component label) {
        send(player, Component.translatable("tsu.pwck.header_block", label, pos.toShortString()), ChatFormatting.AQUA);
        // ワールド配置: BE は単体
        var be = level.getBlockEntity(pos);
        Component beName = be == null
                ? Component.translatable("tsu.pwck.none")
                : Component.literal(be.getClass().getSimpleName());
        send(player, Component.translatable("tsu.pwck.world_be", beName), ChatFormatting.WHITE);
        if (be instanceof com.trainsystemutilities.electrification.blockentity.PantographBlockEntity pbe) {
            send(player, Component.translatable("tsu.pwck.deployed", String.valueOf(pbe.isDeployed())), ChatFormatting.GRAY);
        }
        // The pantograph and the inverter may be on different cars, so the train is read as a whole.
        ContraptionElectrificationTicker.NearbyTrain nearby =
                ContraptionElectrificationTicker.nearestElectrifiedTrain(level, Vec3.atCenterOf(pos), 64);
        if (nearby == null) {
            send(player, Component.translatable("tsu.pwck.no_nearby_train"), ChatFormatting.GRAY);
            return;
        }
        int stored = 0, capacity = 0, pantographs = 0, deployed = 0, inverters = 0, contacting = 0;
        for (Carriage car : nearby.train().carriages) {
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(contraptionOf(car));
            if (info == null) continue;
            pantographs += info.pantographs.size();
            deployed += info.deployedPantographs.size();
            contacting += info.contactingPantographs.size();
            if (!info.inverters.isEmpty()) {
                inverters += info.inverters.size();
                stored += info.getStoredEnergy();
                capacity += ContraptionElectrificationState.Info.CAPACITY;
            }
        }
        send(player, Component.translatable("tsu.pwck.nearest_train", nearby.train().name,
                String.format("%.1f", nearby.distance())), ChatFormatting.GOLD);
        send(player, Component.translatable("tsu.pwck.fe_energy", stored, capacity),
                stored > 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
        send(player, Component.translatable("tsu.pwck.pantograph_count", pantographs, deployed), ChatFormatting.WHITE);
        send(player, Component.translatable("tsu.pwck.inverter_count", inverters), ChatFormatting.WHITE);
        send(player, Component.translatable("tsu.pwck.contacting", contacting),
                contacting == 0 ? ChatFormatting.GRAY : ChatFormatting.GREEN);
    }

    private static Contraption contraptionOf(Carriage carriage) {
        if (carriage == null) return null;
        if (carriage.anyAvailableEntity() instanceof CarriageContraptionEntity cce) {
            return cce.getContraption();
        }
        return null;
    }

    private static void send(Player player, MutableComponent msg, ChatFormatting color) {
        player.sendSystemMessage(msg.withStyle(color));
    }
}
