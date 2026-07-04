package com.trainsystemutilities.profiler;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /tsu profile} コマンド: TsuProfiler を在仕事中に有効/無効化、レポート出力。
 *
 * <ul>
 *   <li>{@code /tsu profile start} - 計測開始 (既存統計はリセット)</li>
 *   <li>{@code /tsu profile stop} - 計測停止 (統計は保持、report で確認可)</li>
 *   <li>{@code /tsu profile reset} - 統計リセット (走行中なら計測継続)</li>
 *   <li>{@code /tsu profile report} - 現在の統計をチャットとログに出力</li>
 * </ul>
 *
 * <p>OP (permission level 2) のみ実行可。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class ProfileCommand {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("tsu")
                .then(Commands.literal("profile")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("start").executes(ProfileCommand::start))
                    .then(Commands.literal("stop").executes(ProfileCommand::stop))
                    .then(Commands.literal("reset").executes(ProfileCommand::reset))
                    .then(Commands.literal("report").executes(ProfileCommand::report))
                )
        );
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        TsuProfiler.enable();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[TSU Profiler] Started. Use /tsu profile report to view stats."), true);
        TrainSystemUtilities.LOGGER.info("[TsuProfiler] Started");
        return Command.SINGLE_SUCCESS;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        TsuProfiler.disable();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§e[TSU Profiler] Stopped. Stats retained; use 'report' to view, 'reset' to clear."), true);
        TrainSystemUtilities.LOGGER.info("[TsuProfiler] Stopped");
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        TsuProfiler.reset();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§b[TSU Profiler] Stats reset."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        String reportText = TsuProfiler.report();
        // ログに完全版、チャットには行ごとに送信 (改行混じりは Component で整形しない)
        TrainSystemUtilities.LOGGER.info("\n{}", reportText);
        for (String line : reportText.split("\n")) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private ProfileCommand() {}
}
