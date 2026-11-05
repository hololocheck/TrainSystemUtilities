package com.trainsystemutilities.client.wiki;

import com.mojang.brigadier.Command;
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
 * Wiki の撮影の自動 + 手動トリガー:
 *
 * <ul>
 *   <li>Player login (= world join 直後) → {@link WikiLiveCapture#captureAll} を 1 回スケジュール。session 初回のみ実行。</li>
 *   <li>{@code /tsu-wiki-live} client command で撮り直し (= 画面を変更した後の即反映用)。</li>
 * </ul>
 *
 * <p>wiki の絵は、登録した画面をその場で描く (Manta の live 埋め込み)。撮影はその画面が描けない時の代わりと、web の
 * wiki 用の PNG。生きた画面はクライアントの言語で描かれるので、言語を変えても撮り直しは要らない。layout JSON を
 * 静的に焼いていた prebuild ({@code /tsu-wiki-prebuild}) は Manta 7 Phase 7 で引退した (wiki の絵はすべて
 * {@link WikiLiveCapture#table()} の画面・state — WikiCaptureReferencesTest)。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID,
        value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class WikiCaptureTrigger {

    private static boolean firstLoginDone = false;

    private WikiCaptureTrigger() {}

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (firstLoginDone) return;
        firstLoginDone = true;
        Minecraft.getInstance().execute(() -> {
            int live = WikiLiveCapture.captureAll();
            sendChat("§a[TSU Wiki] Live-captured §f" + live + "§a screens.");
        });
    }

    /**
     * SECURITY/lifecycle (TSU-WIKI-001): disconnect で撮影状態をリセットする。旧実装は firstLoginDone が JVM 単位で残り、
     * 切替先サーバーへ stale な撮影を持ち越した。
     */
    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        firstLoginDone = false;
        WikiLiveCapture.clearCache();
        WikiCapture.LAYOUT_DIMS.clear();
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
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
