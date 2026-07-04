package com.trainsystemutilities.client.wiki;

import com.mojang.blaze3d.systems.RenderSystem;
import com.trainsystemutilities.client.gui.ManagementComputerScreenV2;
import com.trainsystemutilities.client.gui.PosterManagementScreenV2;
import com.trainsystemutilities.client.gui.RailwayManagementScreenV2;
import com.trainsystemutilities.client.transit.TransitTerminalScreen;
import com.trainsystemutilities.client.transit.TransitTerminalState;
import com.trainsystemutilities.client.gui.StationGroupManageScreen;
import com.trainsystemutilities.client.gui.StationGroupSaveScreen;
import com.trainsystemutilities.client.gui.TrainPresetRefillScreenV2;
import com.trainsystemutilities.client.gui.TrainPresetSaveScreenV2;
import com.trainsystemutilities.client.gui.WireConnectorScreen;
import com.trainsystemutilities.gui.TrainPresetRefillMenu;
import com.trainsystemutilities.preset.TrainPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Wiki 自動 live キャプチャ — 各画面を実 Screen として構築し、 正しい tab/overlay state で
 * {@link WikiCapture#captureScreen(Screen, String, boolean)} する。
 *
 * <p>{@link WikiPrebuildCapture} (= layout JSON を静的に焼く方式) と違い、 こちらは
 * 実 Screen を render するので drawCanvas (路線記号 SVG / カラーピッカー / 3D 等) と
 * dynamic text が本物で写る = ゲーム内表示と一致する。
 *
 * <p>結果は {@code trainsystemutilities:textures/wiki/screens/<id>__<state>__<lang>.png} の
 * DynamicTexture として登録され、 jar 同梱 PNG (= 古い fallback) を上書きする。 session 限定
 * (再ログインで再キャプチャ)。 各画面は全段階 try/catch で、 失敗時は同梱 PNG にフォールバック
 * (クラッシュ / 退行なし)。
 *
 * <p>対象: 管理用コンピューター / 鉄道管理 / ポスター管理 / 乗換案内端末 + 自己完結ツール
 * 画面群 (wire-connector / station-group / train-preset / preset-place-upload)。
 * data 駆動画面 (profile / creator-center / electrification-detail / preset-place-detail) は
 * sample データ注入が必要なため順次追加。
 */
public final class WikiLiveCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger("TSU-WikiLive");

    /** 撮影済みキー (= id/state)。 session 内で重複キャプチャを防ぐ。 再ログインでリセット。 */
    private static final Set<String> done = ConcurrentHashMap.newKeySet();

    /** 単一 state 画面用 no-op applier (= 既定 state をそのまま撮る)。 */
    private static final java.util.function.BiConsumer<Screen, String> NOOP_APPLY = (s, st) -> {};

    private WikiLiveCapture() {}

    public static void clearCache() { done.clear(); }

    /** 全 live-capture 対象を撮る。 render thread 必須 (でなければ execute に回す)。 */
    public static int captureAll() {
        Minecraft mc = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            mc.execute(WikiLiveCapture::captureAll);
            return 0;
        }
        if (mc.player == null || mc.level == null) return 0;
        // 日本語 / 英語 両方をキャプチャ (= 英語クライアントでも英語画像が出る)。
        // 言語を一時 inject して撮る → 元に戻す (リソースリロード不要で軽い)。
        net.minecraft.locale.Language original = net.minecraft.locale.Language.getInstance();
        TransitTerminalState.Tab savedTransitTab = TransitTerminalState.tab(); // global state 退避
        int n = 0;
        // overlay/dialog の入場アニメを止めて完成状態で撮る
        belugalab.mcss3.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = true;
        try {
            for (String lang : new String[]{"ja_jp", "en_us"}) {
                try {
                    var injected = net.minecraft.client.resources.language.ClientLanguage.loadFrom(
                            mc.getResourceManager(), java.util.List.of("en_us", lang), false);
                    net.minecraft.locale.Language.inject(injected);
                } catch (Throwable t) {
                    LOGGER.warn("[WikiLive] language inject failed for {}: {}", lang, t.toString());
                }
                n += captureStates("management-computer", lang,
                        ManagementComputerScreenV2::wikiCreate,
                        (s, st) -> ((ManagementComputerScreenV2) s).wikiApplyState(st),
                        "map", "trains", "schedule", "schedule-detail", "schedule-share", "schedule-editor",
                        "stations", "symbol",
                        "symbol-editor", "color-picker", "layout-edit", "monitor-color");
                n += captureStates("railway-management", lang,
                        RailwayManagementScreenV2::wikiCreate,
                        (s, st) -> ((RailwayManagementScreenV2) s).wikiApplyState(st),
                        "main", "settings", "color", "announcement", "screen-door");
                n += captureStates("poster-management", lang,
                        PosterManagementScreenV2::wikiCreate,
                        (s, st) -> ((PosterManagementScreenV2) s).wikiApplyState(st),
                        "main", "anim");
                n += captureStates("transit-terminal", lang,
                        TransitTerminalScreen::new,
                        (s, st) -> TransitTerminalState.setTab(transitTab(st)),
                        "top", "schedule", "map", "settings");

                // === 自己完結ツール画面 (既定 state = main、 applier 不要)。 factory.get() ごと
                //     try/catch 保護されるので headless で描けない画面は skip され退行しない。
                //     data 駆動 (profile/creator-center/electrification-detail 等) は sample 注入が
                //     必要なため別バッチ。 ===
                n += captureStates("wire-connector", lang,
                        WireConnectorScreen::new, NOOP_APPLY, "main");
                n += captureStates("station-group-save", lang,
                        () -> new StationGroupSaveScreen(net.minecraft.core.BlockPos.ZERO,
                                net.minecraft.core.BlockPos.ZERO.above()),
                        NOOP_APPLY, "main");
                n += captureStates("station-group-manage", lang,
                        StationGroupManageScreen::new, NOOP_APPLY, "main");
                n += captureStates("train-preset-save", lang,
                        TrainPresetSaveScreenV2::new, NOOP_APPLY, "main");
                n += captureStates("train-preset-refill", lang,
                        WikiLiveCapture::createRefill, NOOP_APPLY, "main");
                // preset-place (online) 画面は reflection 構築 — online 群を含まないビルドでは
                // factory が throw → captureStates の try/catch で skip され退行しない。
                n += captureStates("train-preset-browse", lang,
                        () -> onlineScreen("com.trainsystemutilities.client.gui.TrainPresetBrowseScreenV2",
                                new Class<?>[0]),
                        NOOP_APPLY, "mine");
                n += captureStates("preset-place-upload", lang,
                        () -> onlineScreen("com.trainsystemutilities.client.gui.PresetPlaceUploadScreenV2",
                                new Class<?>[]{TrainPreset.class}, new TrainPreset()),
                        NOOP_APPLY, "main");
            }
        } finally {
            net.minecraft.locale.Language.inject(original); // 元の言語に戻す
            TransitTerminalState.setTab(savedTransitTab); // global tab を元に戻す
            belugalab.mcss3.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = false;
        }
        return n;
    }

    /** 1 画面の複数 state を順に構築・適用・キャプチャ。 各 state は独立して try/catch。
     *  done キーに lang を含めるので、 言語切替後 (例 en_us) は再キャプチャされる。 */
    private static int captureStates(String id, String lang, Supplier<Screen> factory,
                                     java.util.function.BiConsumer<Screen, String> apply,
                                     String... states) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int ok = 0;
        for (String st : states) {
            String key = id + "/" + st + "/" + lang;
            if (done.contains(key)) { ok++; continue; }
            try {
                Screen screen = factory.get();
                if (screen == null) return ok; // player/level not ready — 次回 login で再試行
                screen.init(mc, w, h);
                apply.accept(screen, st);
                // forcedState + forcedLang で明示登録 (inject した言語でファイル名も付ける)。
                // savePng=true: 検証用に screenshots/wiki へも保存。
                WikiCapture.captureScreen(screen, id, st, lang, true, true);
                done.add(key);
                ok++;
            } catch (Throwable t) {
                LOGGER.warn("[WikiLive] {} failed (fallback to bundled PNG): {}", key, t.toString());
            }
        }
        return ok;
    }

    private static TransitTerminalState.Tab transitTab(String st) {
        return switch (st) {
            case "schedule" -> TransitTerminalState.Tab.SCHEDULE;
            case "map" -> TransitTerminalState.Tab.MAP;
            case "settings" -> TransitTerminalState.Tab.SETTINGS;
            default -> TransitTerminalState.Tab.TOP;
        };
    }

    /** closed (preset-place) 側の画面を reflection で構築 (= open→closed の hard 参照を持たない)。 */
    private static Screen onlineScreen(String className, Class<?>[] paramTypes, Object... args) {
        try {
            return (Screen) Class.forName(className).getConstructor(paramTypes).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("online screen unavailable: " + className, e);
        }
    }

    /** TrainPresetRefill は Menu 駆動なので dummy menu + player inventory で構築。 */
    private static Screen createRefill() {
        var p = Minecraft.getInstance().player;
        var inv = new net.minecraft.world.entity.player.Inventory(p);
        return new TrainPresetRefillScreenV2(
                new TrainPresetRefillMenu(0, inv), inv, net.minecraft.network.chat.Component.empty());
    }
}
