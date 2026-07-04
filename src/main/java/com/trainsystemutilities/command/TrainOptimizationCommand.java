package com.trainsystemutilities.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.trainsystemutilities.TrainSystemUtilities;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /tsu-optimize -- Create 列車描画最適化 (Phase 23) のランタイム制御 + 統計表示。
 *
 * <p>サブコマンド:
 * <ul>
 *   <li>{@code /tsu-optimize stats}                 — 現在の統計を表示</li>
 *   <li>{@code /tsu-optimize stats reset}           — カウンタを 0 にリセット</li>
 *   <li>{@code /tsu-optimize set frustum_cull <bool>}  — frustum 早期 return を切替</li>
 *   <li>{@code /tsu-optimize set bogey_cache <bool>}   — bogey 差分キャッシュを切替</li>
 *   <li>{@code /tsu-optimize set enabled <bool>}       — マスタースイッチ</li>
 * </ul>
 *
 * <p>サーバ専用 JVM では {@code TsuTrainOptimization} (client only) が存在しないため、
 * 統計表示も "OFF" 固定で応答 (実害なし)。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class TrainOptimizationCommand {

    private TrainOptimizationCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tsu-optimize")
                // P0-8: OP-only (= 一般 player が optimization stats reset / 設定変更 不可化)
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("stats")
                        .executes(ctx -> showStats(ctx.getSource()))
                        .then(Commands.literal("reset")
                                .executes(ctx -> resetStats(ctx.getSource()))))
                .then(Commands.literal("set")
                        .then(Commands.literal("enabled")
                                .then(Commands.argument("v", BoolArgumentType.bool())
                                        .executes(ctx -> setEnabled(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "v")))))
                        .then(Commands.literal("frustum_cull")
                                .then(Commands.argument("v", BoolArgumentType.bool())
                                        .executes(ctx -> setFrustum(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "v")))))
                        .then(Commands.literal("bogey_cache")
                                .then(Commands.argument("v", BoolArgumentType.bool())
                                        .executes(ctx -> setBogey(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "v")))))));
    }

    // ============================================================================
    // dist barrier: client クラスを直接参照すると dedicated server で NoClassDefFoundError。
    // 個別 method を切ってリフレクション風にアクセスする手もあるが、
    // ここでは FMLEnvironment.dist チェックで分岐し、client class は別 inner で参照する。
    // ============================================================================

    private static int showStats(CommandSourceStack src) {
        if (FMLEnvironment.dist.isDedicatedServer()) {
            src.sendSuccess(() -> Component.literal("[tsu-optimize] dedicated server: render optimization N/A")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        return ClientOps.showStats(src);
    }

    private static int resetStats(CommandSourceStack src) {
        if (FMLEnvironment.dist.isDedicatedServer()) return 0;
        return ClientOps.resetStats(src);
    }

    private static int setEnabled(CommandSourceStack src, boolean v) {
        if (FMLEnvironment.dist.isDedicatedServer()) return 0;
        return ClientOps.setEnabled(src, v);
    }

    private static int setFrustum(CommandSourceStack src, boolean v) {
        if (FMLEnvironment.dist.isDedicatedServer()) return 0;
        return ClientOps.setFrustum(src, v);
    }

    private static int setBogey(CommandSourceStack src, boolean v) {
        if (FMLEnvironment.dist.isDedicatedServer()) return 0;
        return ClientOps.setBogey(src, v);
    }

    /** クライアントクラスへの参照を遅延ロード化するためのネスト。 */
    private static final class ClientOps {
        static int showStats(CommandSourceStack src) {
            var opt = com.trainsystemutilities.client.optimization.TsuTrainOptimization.STATS;
            long fs = opt.frustumSkips.get();
            long fp = opt.frustumPasses.get();
            long bh = opt.bogeyCacheHits.get();
            long bm = opt.bogeyCacheMisses.get();
            long bogeyTotal = bh + bm;
            double bogeyHitRate = bogeyTotal == 0 ? 0.0 : (100.0 * bh / bogeyTotal);
            long frustumTotal = fs + fp;
            double cullRate = frustumTotal == 0 ? 0.0 : (100.0 * fs / frustumTotal);

            src.sendSuccess(() -> Component.literal("[tsu-optimize] master="
                    + com.trainsystemutilities.client.optimization.TsuTrainOptimization.isEnabled()
                    + " frustum_cull=" + com.trainsystemutilities.client.optimization.TsuTrainOptimization.isFrustumCullEnabled()
                    + " bogey_cache=" + com.trainsystemutilities.client.optimization.TsuTrainOptimization.isBogeyDiffCacheEnabled())
                    .withStyle(ChatFormatting.AQUA), false);
            src.sendSuccess(() -> Component.literal(String.format(
                    "  frustum: %d skip / %d pass  (cull %.1f%%)", fs, fp, cullRate))
                    .withStyle(ChatFormatting.GRAY), false);
            src.sendSuccess(() -> Component.literal(String.format(
                    "  bogey:   %d hit / %d miss  (hit %.1f%%)", bh, bm, bogeyHitRate))
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        static int resetStats(CommandSourceStack src) {
            com.trainsystemutilities.client.optimization.TsuTrainOptimization.STATS.reset();
            src.sendSuccess(() -> Component.literal("[tsu-optimize] stats reset")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        static int setEnabled(CommandSourceStack src, boolean v) {
            com.trainsystemutilities.client.optimization.TsuTrainOptimization.setEnabled(v);
            src.sendSuccess(() -> Component.literal("[tsu-optimize] enabled = " + v)
                    .withStyle(v ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
            return 1;
        }

        static int setFrustum(CommandSourceStack src, boolean v) {
            com.trainsystemutilities.client.optimization.TsuTrainOptimization.setFrustumCull(v);
            src.sendSuccess(() -> Component.literal("[tsu-optimize] frustum_cull = " + v)
                    .withStyle(v ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
            return 1;
        }

        static int setBogey(CommandSourceStack src, boolean v) {
            com.trainsystemutilities.client.optimization.TsuTrainOptimization.setBogeyDiffCache(v);
            src.sendSuccess(() -> Component.literal("[tsu-optimize] bogey_cache = " + v)
                    .withStyle(v ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
            return 1;
        }
    }
}
