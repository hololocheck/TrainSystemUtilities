package com.trainsystemutilities.client.extract;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client command: {@code /tsu-extract-items [size]}
 *
 * <p>TSU の全 item / block の inventory アイコンを {@link ItemIconExtractor} で
 * 抽出して {@code <gamedir>/mcss3-cache/items/trainsystemutilities/} に PNG 保存する。
 *
 * <p>{@code size} はオプション (default 128 px、 範囲 16-1024)。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID,
        value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class ItemExtractCommand {

    private static final int DEFAULT_SIZE = 512;

    private ItemExtractCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("tsu-extract-items")
                        .executes(ctx -> run(DEFAULT_SIZE))
                        .then(Commands.argument("size", IntegerArgumentType.integer(16, 1024))
                                .executes(ctx -> run(IntegerArgumentType.getInteger(ctx, "size"))))
        );
    }

    private static int run(int size) {
        Minecraft.getInstance().execute(() -> {
            sendChat("§e[TSU Extract] §f" + size + "§e px で抽出中...");
            int n = ItemIconExtractor.extractAll(TrainSystemUtilities.MOD_ID, size);
            sendChat("§a[TSU Extract] §f" + n + "§a アイコンを §fmcss3-cache/items/trainsystemutilities/§a に保存しました。");
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void sendChat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal(msg).withStyle(ChatFormatting.WHITE), false);
        }
    }
}
