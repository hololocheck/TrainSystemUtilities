package com.trainsystemutilities.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetCapture;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /tsupreset save &lt;name&gt; -- 手持ちの列車プリセットツールに設定された
 * Pos1/Pos2 範囲をスキャンしてプリセットファイルに保存する。
 *
 * /tsupreset list  -- サーバー上の保存済みプリセットを一覧表示する。
 *
 * Phase 3 で GUI から保存できるようになるまでの暫定操作。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public class TrainPresetCommand {

    /** クリエイティブでも素材消費を強制するテスト用フラグ (server JVM 共有)。 */
    private static volatile boolean forceConsumeEnabled = false;

    public static boolean isForceConsumeEnabled() { return forceConsumeEnabled; }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tsupreset")
                // P0-8: OP-only (= 一般 player が preset save/test 設定変更 不可化)
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> doSave(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list")
                        .executes(ctx -> doList(ctx.getSource())))
                .then(Commands.literal("test")
                        .then(Commands.literal("forceConsume")
                                .executes(ctx -> doShowForceConsume(ctx.getSource()))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> doSetForceConsume(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "enabled"))))))
        );
    }

    private static int doShowForceConsume(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal(
                        "forceConsume = " + forceConsumeEnabled
                                + " (true \u3067\u30af\u30ea\u30a8\u30a4\u30c6\u30a3\u30d6\u3067\u3082\u7d20\u6750\u3092\u6d88\u8cbb)")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int doSetForceConsume(CommandSourceStack src, boolean enabled) {
        forceConsumeEnabled = enabled;
        src.sendSuccess(() -> Component.literal("forceConsume = " + enabled)
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int doSave(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Player only"));
            return 0;
        }
        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) {
            src.sendFailure(Component.translatable("tsu.cmd.tool_required"));
            return 0;
        }
        BlockPos p1 = TrainPresetToolItem.getPos1(stack);
        BlockPos p2 = TrainPresetToolItem.getPos2(stack);
        if (p1 == null || p2 == null) {
            src.sendFailure(Component.translatable("tsu.cmd.pos_both_required"));
            return 0;
        }
        TrainPreset preset = TrainPresetCapture.capture(player.serverLevel(), p1, p2,
                name, player.getName().getString(), player.getUUID());
        if (preset == null || preset.isEmpty()) {
            src.sendFailure(Component.translatable("tsu.cmd.capture_failed"));
            return 0;
        }
        try {
            var path = TrainPresetStorage.save(player.getServer(), preset);
            src.sendSuccess(() -> Component.translatable("tsu.cmd.save_success_fmt", path.getFileName().toString())
                    .withStyle(ChatFormatting.GREEN), false);
            src.sendSuccess(() -> TrainPresetCapture.summary(preset), false);
            return 1;
        } catch (Exception ex) {
            TrainSystemUtilities.LOGGER.error("Preset save failed", ex);
            src.sendFailure(Component.translatable("tsu.cmd.save_failed_fmt", ex.getMessage()));
            return 0;
        }
    }

    private static int doList(CommandSourceStack src) {
        if (src.getServer() == null) return 0;
        var entries = TrainPresetStorage.listAll(src.getServer());
        if (entries.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("tsu.cmd.no_presets").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (var e : entries) {
            src.sendSuccess(() -> Component.literal(" - " + e.name() + " [" + e.authorDir() + "]")
                    .withStyle(ChatFormatting.AQUA), false);
        }
        return entries.size();
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
