package com.trainsystemutilities.electrification.item;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 電力チェッカーツール。
 *
 * <p>右クリックで対象ブロックの電化状態を表示:
 * <ul>
 *   <li>碍子 (Insulator): 接続している架線数・通電数・通電架線リスト</li>
 *   <li>変電所 (Substation): 通電生成数</li>
 *   <li>FE インバータ / パンタグラフ: 設置時は単体情報、列車組込時は所属列車の Info.storedEnergy</li>
 * </ul>
 */
public class PowerCheckerItem extends Item {

    public PowerCheckerItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (player == null) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sLevel)) return InteractionResult.PASS;

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (block == ModBlocks.INSULATOR.get()) {
            reportInsulator(sLevel, player, pos);
        } else if (block == ModBlocks.SUBSTATION.get()) {
            reportSubstation(sLevel, player, pos);
        } else if (block == ModBlocks.FE_INVERTER.get()) {
            reportContraptionBlock(sLevel, player, pos, Component.translatable("block.trainsystemutilities.fe_inverter"));
        } else if (block == ModBlocks.PANTOGRAPH.get()) {
            reportContraptionBlock(sLevel, player, pos, Component.translatable("block.trainsystemutilities.pantograph"));
        } else {
            send(player, Component.translatable("tsu.pwck.not_target", block.getName()), ChatFormatting.GRAY);
            return InteractionResult.SUCCESS;
        }
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

    /** 設置済みパンタ/インバータの状態を表示。列車に組み込まれている場合はその Info も表示。 */
    private void reportContraptionBlock(ServerLevel level, Player player, BlockPos pos, Component label) {
        send(player, Component.translatable("tsu.pwck.header_block", label, pos.toShortString()), ChatFormatting.AQUA);
        // ワールド配置: BE は単体
        var be = level.getBlockEntity(pos);
        Component beName = be == null
                ? Component.translatable("tsu.pwck.none")
                : Component.literal(be.getClass().getSimpleName());
        send(player, Component.translatable("tsu.pwck.world_be", beName), ChatFormatting.WHITE);
        if (be instanceof com.trainsystemutilities.electrification.blockentity.PantographBlockEntity pbe) {
            send(player, Component.translatable("tsu.pwck.deployed", pbe.isDeployed()), ChatFormatting.GRAY);
        }
        // 近傍の電化列車を列挙
        Train nearestTrain = null;
        Contraption nearestC = null;
        double bestDist = 64 * 64;
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train == null || train.carriages == null) continue;
            for (Carriage car : train.carriages) {
                Contraption c = contraptionOf(car);
                if (c == null || c.entity == null) continue;
                if (c.entity.level() != level) continue;
                double d = c.entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (d < bestDist) {
                    ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
                    if (info != null && info.isElectrified()) {
                        bestDist = d;
                        nearestTrain = train;
                        nearestC = c;
                    }
                }
            }
        }
        if (nearestC != null) {
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(nearestC);
            send(player, Component.translatable("tsu.pwck.nearest_train", nearestTrain.name,
                    String.format("%.1f", Math.sqrt(bestDist))), ChatFormatting.GOLD);
            send(player, Component.translatable("tsu.pwck.fe_energy", info.getStoredEnergy(),
                    ContraptionElectrificationState.Info.CAPACITY),
                    info.getStoredEnergy() > 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
            send(player, Component.translatable("tsu.pwck.pantograph_count",
                    info.pantographs.size(), info.deployedPantographs.size()), ChatFormatting.WHITE);
            send(player, Component.translatable("tsu.pwck.inverter_count", info.inverters.size()), ChatFormatting.WHITE);
            send(player, Component.translatable("tsu.pwck.contacting", info.contactingPantographs.size()),
                    info.contactingPantographs.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.GREEN);
        } else {
            send(player, Component.translatable("tsu.pwck.no_nearby_train"), ChatFormatting.GRAY);
        }
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
