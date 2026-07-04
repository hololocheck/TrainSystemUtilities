package com.trainsystemutilities.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Phase 24 デバッグ用: 注視中の列車の全パンタを展開/折畳。
 *
 * <p>{@code /tsu-pantograph deploy} → 最寄りの電化列車の全パンタ展開
 * {@code /tsu-pantograph fold} → 同 折畳
 * {@code /tsu-pantograph status} → 最寄りの電化列車の各 contraption の panto 状態列挙
 *
 * <p>管理 GUI ができるまでの暫定操作手段。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class PantographCommand {

    private PantographCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(LiteralArgumentBuilder.<CommandSourceStack>literal("tsu-pantograph")
                // P0-8: OP-only (= 全プレイヤーが他人の列車の panto を toggle 可能だった Critical fix、 WF-E)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("deploy").executes(ctx -> run(ctx.getSource(), true)))
                .then(Commands.literal("fold").executes(ctx -> run(ctx.getSource(), false)))
                .then(Commands.literal("status").executes(PantographCommand::status))
                .then(Commands.literal("open").executes(PantographCommand::openScreen))
        );
    }

    /** 注視中の電化列車の電化詳細スクリーンを開く (クライアント側)。 */
    private static int openScreen(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer sp)) return 0;
        Train train = nearestElectrifiedTrain(sp);
        if (train == null) {
            sp.sendSystemMessage(Component.translatable("tsu.pantograph.no_nearby_train")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        // クライアント側で screen を開く payload を送信
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new com.trainsystemutilities.network.OpenElectrificationScreenPayload(train.id));
        return Command.SINGLE_SUCCESS;
    }

    private static int run(CommandSourceStack src, boolean deploy) {
        if (!(src.getEntity() instanceof ServerPlayer sp)) return 0;
        Train train = nearestElectrifiedTrain(sp);
        if (train == null) {
            sp.sendSystemMessage(Component.translatable("tsu.pantograph.no_nearby_train")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        int toggled = 0;
        for (Carriage carriage : train.carriages) {
            Contraption c = contraptionOf(carriage);
            if (c == null) continue;
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info == null || !info.isElectrified()) continue;
            int before = info.deployedPantographs.size();
            info.setAllPantographsDeployed(deploy);
            toggled += Math.abs(info.deployedPantographs.size() - before);
        }
        sp.sendSystemMessage(Component.translatable("tsu.pantograph.result_fmt",
                Component.translatable(deploy ? "tsu.pantograph.cmd_deploy" : "tsu.pantograph.cmd_fold"),
                toggled)
                .withStyle(ChatFormatting.AQUA));
        return Command.SINGLE_SUCCESS;
    }

    private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer sp)) return 0;
        Train train = nearestElectrifiedTrain(sp);
        if (train == null) {
            sp.sendSystemMessage(Component.translatable("tsu.pantograph.no_nearby_train")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        sp.sendSystemMessage(Component.translatable("tsu.pantograph.electrified_train_fmt", train.id)
                .withStyle(ChatFormatting.AQUA));
        int idx = 0;
        for (Carriage carriage : train.carriages) {
            Contraption c = contraptionOf(carriage);
            if (c == null) { idx++; continue; }
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info == null) { idx++; continue; }
            sp.sendSystemMessage(Component.literal(String.format(
                    "  Car #%d: inverter=%d, panto=%d (deployed=%d), FE=%d/%d",
                    idx, info.inverters.size(), info.pantographs.size(),
                    info.deployedPantographs.size(), info.getStoredEnergy(),
                    ContraptionElectrificationState.Info.CAPACITY))
                    .withStyle(ChatFormatting.GRAY));
            idx++;
        }
        return Command.SINGLE_SUCCESS;
    }

    /** プレイヤーから 64 ブロック以内の電化列車を探す。 */
    private static Train nearestElectrifiedTrain(ServerPlayer sp) {
        Train best = null;
        double bestDist = 64 * 64;
        for (Train train : Create.RAILWAYS.trains.values()) {
            for (Carriage c : train.carriages) {
                if (c == null || c.anyAvailableEntity() == null) continue;
                var ent = c.anyAvailableEntity();
                if (ent.level() != sp.serverLevel()) continue;
                double d = ent.distanceToSqr(sp);
                if (d < bestDist) {
                    Contraption con = contraptionOf(c);
                    if (con == null) continue;
                    ContraptionElectrificationState.Info info =
                            ContraptionElectrificationState.getInfo(con);
                    if (info != null && info.isElectrified()) {
                        best = train;
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }

    private static Contraption contraptionOf(Carriage carriage) {
        if (carriage == null) return null;
        if (carriage.anyAvailableEntity() instanceof CarriageContraptionEntity cce) {
            return cce.getContraption();
        }
        return null;
    }
}
