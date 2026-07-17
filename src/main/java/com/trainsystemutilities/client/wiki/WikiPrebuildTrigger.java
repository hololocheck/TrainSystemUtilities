package com.trainsystemutilities.client.wiki;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Wiki prebuild の自動 + 手動トリガー:
 *
 * <ul>
 *   <li>Player login (= world join 直後) → {@link WikiPrebuildCapture#prebuildAll}
 *       を 1 回スケジュール。session 初回のみ実行 (cache 済はスキップ)。</li>
 *   <li>{@code /tsu-wiki-prebuild} client command で手動再 prebuild。</li>
 *   <li>{@code /tsu-wiki-prebuild <name>} で単一 layout のみ。</li>
 *   <li>{@code /tsu-wiki-prebuild-clear} で cache クリア (= 次回 trigger で再 prebuild)。</li>
 * </ul>
 *
 * <p>言語切替は単純に {@code /tsu-wiki-prebuild} を再実行すれば良い (新 lang の組み合わせが
 * 未 prebuild なので新規キャプチャされる)。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID,
        value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class WikiPrebuildTrigger {

    private static boolean firstLoginDone = false;

    private WikiPrebuildTrigger() {}

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (firstLoginDone) return;
        firstLoginDone = true;
        // 先に prebuild (= fallback)、 後に live キャプチャ (= 実 Screen render が最後に
        // DynamicTexture を上書き = 実画面が必ず勝つ)。 順序が逆だと prebuild が live を潰す。
        Minecraft.getInstance().execute(() -> {
            int count = WikiPrebuildCapture.prebuildAll();
            int live = WikiLiveCapture.captureAll();
            sendChat("§a[TSU Wiki] Prebuilt §f" + count + "§a, live-captured §f" + live + "§a screens.");
        });
    }

    /**
     * SECURITY/lifecycle (TSU-WIKI-001): disconnect で prebuild 状態をリセットし、生成した
     * DynamicTexture を解放する。旧実装は firstLoginDone が JVM 単位で残り、切替先サーバーへ
     * stale texture を持ち越し、texture が leak した。
     */
    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        firstLoginDone = false;
        WikiPrebuildCapture.clearCache();
        WikiLiveCapture.clearCache();
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("tsu-wiki-prebuild")
                        .executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                int n = WikiPrebuildCapture.prebuildAll();
                                sendChat("§a[TSU Wiki] Prebuilt §f" + n + "§a layouts.");
                            });
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    Minecraft.getInstance().execute(() -> {
                                        boolean ok = WikiPrebuildCapture.prebuildOne(name);
                                        sendChat(ok
                                                ? "§a[TSU Wiki] Prebuilt §f" + name
                                                : "§c[TSU Wiki] Failed: §f" + name);
                                    });
                                    return Command.SINGLE_SUCCESS;
                                }))
        );
        event.getDispatcher().register(
                Commands.literal("tsu-wiki-prebuild-clear")
                        .executes(ctx -> {
                            WikiPrebuildCapture.clearCache();
                            WikiLiveCapture.clearCache();
                            sendChat("§e[TSU Wiki] Prebuild + live cache cleared.");
                            return Command.SINGLE_SUCCESS;
                        })
        );
        // live キャプチャだけ再実行 (= 画面を変更した後の即反映用)
        event.getDispatcher().register(
                Commands.literal("tsu-wiki-live")
                        .executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                WikiLiveCapture.clearCache();
                                int n = WikiLiveCapture.captureAll();
                                sendChat("§a[TSU Wiki] Live-captured §f" + n + "§a screens.");
                            });
                            return Command.SINGLE_SUCCESS;
                        })
        );
    }

    private static void sendChat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.WHITE), false);
        }
    }
}
