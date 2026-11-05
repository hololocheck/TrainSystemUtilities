package com.trainsystemutilities.client.wiki;

import com.mojang.blaze3d.systems.RenderSystem;
import com.trainsystemutilities.client.gui.ManagementComputerScreenV2;
import com.trainsystemutilities.client.gui.PosterManagementScreenV2;
import com.trainsystemutilities.client.gui.RailwayManagementScreenV2;
import com.trainsystemutilities.client.transit.TransitTerminalScreen;
import com.trainsystemutilities.client.gui.StationGroupManageScreen;
import com.trainsystemutilities.client.gui.StationGroupSaveScreen;
import com.trainsystemutilities.client.gui.TrainPresetRefillScreenV2;
import com.trainsystemutilities.client.gui.TrainPresetSaveScreenV2;
import com.trainsystemutilities.client.gui.WireConnectorScreen;
import com.trainsystemutilities.gui.TrainPresetRefillMenu;
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
 * <p>実 Screen を render するので drawCanvas (路線記号 SVG / カラーピッカー / 3D 等) と
 * dynamic text が本物で写る = ゲーム内表示と一致する (layout JSON を静的に焼いていた prebuild は
 * Manta 7 Phase 7 で引退 — wiki の絵はすべてこの表の画面・state)。
 *
 * <p>結果は {@code trainsystemutilities:textures/wiki/screens/<id>__<state>__<lang>.png} の
 * DynamicTexture として登録され、 jar 同梱 PNG (= 古い fallback) を上書きする。 session 限定
 * (再ログインで再キャプチャ)。 各画面は全段階 try/catch で、 失敗時は同梱 PNG にフォールバック
 * (クラッシュ / 退行なし)。
 *
 * <p>対象: 管理用コンピューター / 鉄道管理 / ポスター管理 / 乗換案内端末 + 自己完結ツール
 * 画面群 (wire-connector / station-group / train-preset / preset-place-upload) + sample データで開く
 * data 駆動画面 (電化詳細 / preset-place の詳細・profile)。
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
        // 日本語 / 英語 + クライアントの言語をキャプチャ (captureLanguages)。
        // 言語を一時 inject して撮る → 元に戻す (リソースリロード不要で軽い)。
        net.minecraft.locale.Language original = net.minecraft.locale.Language.getInstance();
        int n = 0;
        // overlay/dialog の入場アニメを止めて完成状態で撮る
        com.manta.api.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = true;
        try {
            for (String lang : captureLanguages(mc.getLanguageManager().getSelected())) {
                try {
                    var injected = net.minecraft.client.resources.language.ClientLanguage.loadFrom(
                            mc.getResourceManager(), java.util.List.of("en_us", lang), false);
                    net.minecraft.locale.Language.inject(injected);
                } catch (Throwable t) {
                    LOGGER.warn("[WikiLive] language inject failed for {}: {}", lang, t.toString());
                }
                for (com.manta.api.wiki.WikiLiveScreens.Row r : wikiRows()) {
                    n += captureStates(r.id(), lang, r.factory(), r.apply(), r.states().toArray(String[]::new));
                }
            }
        } finally {
            net.minecraft.locale.Language.inject(original); // 元の言語に戻す
            com.manta.api.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = false;
        }
        return n;
    }

    /**
     * 撮る言語: 日本語 / 英語 (どのクライアントにも出せる) と、クライアントの言語。wiki のページは自分の言語の capture を指す
     * (`<lang>` の md は `__<lang>.png` — MANTA_7_CONCEPT X1、2026-09-26) ので、その言語の実画面を撮らないと中国語の
     * クライアントに出る撮影が無い (生きた画面が描けない時の代わりの絵)。
     */
    static java.util.List<String> captureLanguages(String current) {
        java.util.LinkedHashSet<String> langs = new java.util.LinkedHashSet<>(java.util.List.of("ja_jp", "en_us"));
        if (current != null && !current.isBlank()) {
            langs.add(current);
        }
        return java.util.List.copyOf(langs);
    }

    /** 1 画面の複数 state を順に構築・適用・キャプチャ。 各 state は独立して try/catch。
     *  done キーに lang を含めるので、 言語切替後 (例 en_us) は再キャプチャされる。 */
    private static int captureStates(String id, String lang, Supplier<? extends Screen> factory,
                                     java.util.function.BiConsumer<Screen, String> apply,
                                     String... states) {
        // ループは部品 (B11)。apply は init の**後**—— SAS は逆にしていて、
        // どちらが正しいかは実機で 1 枚撮って比べないと決まらないので現状維持。
        return com.manta.api.wiki.WikiCaptureLoop.captureStates(done, id, lang, factory, apply, false,
                (screen, sid, st, lg) -> {
                    // forcedState + forcedLang で明示登録 (inject した言語でファイル名も付ける)。
                    // savePng=true: 検証用に screenshots/wiki へも保存。
                    WikiCapture.captureScreen(screen, sid, st, lg, true, true);
                    return true;   // 旧実装も戻り値を見ずに done へ入れていた
                }, LOGGER, states);
    }

    /**
     * One screen of the catalogue: how to build it, how to put it into a named state, and which states
     * exist. Extracted from {@link #captureAll()} on 2026-09-21 so that a reader OTHER than the capture
     * path can walk the same list without copying it — a second table would go stale the first time a
     * state is added here, and the screens are owned on this side.
     *
     * <p>Two readers walk it: the capture path and Manta's wiki ({@link #registerLive}), which draws
     * each screen live. An entry point that SHOWED one of these screens with {@code setScreen} was
     * written and then removed on 2026-09-21: several are {@code AbstractContainerScreen}s built with
     * {@code containerId = 0} and a throwaway inventory, and a player with no screen open also has
     * containerId 0 ({@code InventoryMenu}), so the server accepts their slot clicks against the REAL
     * inventory — armour included. Neither reader calls {@code setScreen}, and the wiki builds and draws
     * inside Manta's playback containment, where no packet and no slot action on the player's own menu
     * leaves (WikiLiveScreens). Anything else that shows these must solve that first.
     *
     * <p>A third reader, BelugaAOS's UI sweep, reads {@link #table()} by reflection and shows each state in a
     * containment of its own. {@code sweepOnly} are the states only it opens, never photographed nor drawn by the
     * wiki (user, 2026-10-21): a list filled past its viewport, so its scrollbar thumb is up for the sweep to drag
     * (BelugaAOS v57 drags every thumb it can see; a list that fits hides its thumb, and its drag was never checked).
     */
    record Entry(String id, Supplier<Screen> factory,
                 java.util.function.BiConsumer<Screen, String> apply, java.util.Set<String> sweepOnly,
                 String... states) {

        /** A screen the wiki photographs and draws in every state. */
        Entry(String id, Supplier<Screen> factory, java.util.function.BiConsumer<Screen, String> apply,
              String... states) {
            this(id, factory, apply, java.util.Set.of(), states);
        }

        /** The states the wiki photographs and draws: every state but the sweep's own. */
        String[] wikiStates() {
            return java.util.Arrays.stream(states).filter(s -> !sweepOnly.contains(s)).toArray(String[]::new);
        }
    }

    /** The catalogue. Order is the capture order, which is also the order a driver walks. */
    static java.util.List<Entry> table() {
        return java.util.List.of(
            new Entry("management-computer",
                    ManagementComputerScreenV2::wikiCreate,
                        (s, st) -> ((ManagementComputerScreenV2) s).wikiApplyState(st),
                    java.util.Set.of("trains-long", "schedule-share-long", "sched-station-long"),
                    "map", "trains", "schedule", "schedule-detail", "schedule-share", "schedule-editor",
                        "stations", "symbol",
                        "symbol-editor", "color-picker", "layout-edit", "monitor-color",
                        "sched-editor", "train-detail", "electrification-detail", "station-assign",
                        // 改善1 (2026-10-07): the pages that play their own operation on these tabs
                        "route-map", "tickets", "symbol-create",
                        "trains-long", "schedule-share-long", "sched-station-long"),
            new Entry("railway-management",
                    RailwayManagementScreenV2::wikiCreate,
                        (s, st) -> ((RailwayManagementScreenV2) s).wikiApplyState(st),
                    java.util.Set.of("announcement-long", "announcement-share-long", "screen-door-long"),
                    "main", "settings", "color", "announcement", "screen-door",
                        "announcement-long", "announcement-share-long", "screen-door-long"),
            new Entry("poster-management",
                    PosterManagementScreenV2::wikiCreate,
                        (s, st) -> ((PosterManagementScreenV2) s).wikiApplyState(st),
                    java.util.Set.of("main-long"),
                    "main", "anim", "main-long"),
            // Its own terminal, not TransitTerminalState's player one: a page shows four tabs at once and plays a search
            // on one (TransitTerminalScreen.wikiCreate - its own state, stations, trains and track).
            new Entry("transit-terminal",
                    TransitTerminalScreen::wikiCreate,
                        (s, st) -> ((TransitTerminalScreen) s).wikiApplyState(st),
                    "top", "schedule", "map", "settings"),
            // The confirmations are Manta's one ConfirmDialog since 9ac78f1 (R4.27.1): the pages' <id>-<state>__<lang>
            // captures are these states (WikiCaptureReferencesTest). On a demo tool since 改善1 (2026-10-07), whatever the
            // viewer holds.
            new Entry("wire-connector",
                    WireConnectorScreen::wikiCreate,
                        (s, st) -> ((WireConnectorScreen) s).wikiApplyState(st),
                    java.util.Set.of("main-long"),
                    "main", "confirm", "preset-delete", "preset-save",
                        // 改善1 (2026-10-07): the custom wire page plays its own operation, and the refill section starts
                        // here and goes on with wire-connector-refill
                        "custom", "refill", "main-long"),
            // The refill screen on a demo tool, wire spools in its throwaway hotbar (改善1, 2026-10-07).
            new Entry("wire-connector-refill",
                    WikiLiveCapture::createWireRefill, NOOP_APPLY,
                    "main"),
            new Entry("station-group-save",
                    () -> new StationGroupSaveScreen(net.minecraft.core.BlockPos.ZERO,
                                net.minecraft.core.BlockPos.ZERO.above()),
                        NOOP_APPLY,
                    "main"),
            new Entry("station-group-manage",
                    StationGroupManageScreen::new,
                        (s, st) -> ((StationGroupManageScreen) s).wikiApplyState(st),
                    "main", "delete"),
            new Entry("train-preset-save",
                    TrainPresetSaveScreenV2::new, NOOP_APPLY,
                    "main"),
            // 改善1 (2026-10-07): the screens the wiki had no picture of - each on demo data of its own wikiCreate, and
            // none sends anything (the pole settings place nothing, the machine sells nothing, the card keeps its links).
            new Entry("overhead-pole-auto-settings",
                    com.trainsystemutilities.client.electrification.OverheadPoleAutoSettingsScreen::wikiCreate, NOOP_APPLY,
                    "main"),
            new Entry("ticket-vending-machine",
                    com.trainsystemutilities.client.gui.TicketVendingMachineScreen::wikiCreate, NOOP_APPLY,
                    "main"),
            new Entry("memory-card-reset",
                    com.trainsystemutilities.client.gui.MemoryCardResetScreen::wikiCreate, NOOP_APPLY,
                    "main"),
            new Entry("train-preset-refill",
                    WikiLiveCapture::createRefill, NOOP_APPLY,
                    "main"),
            // The tool's own screen with the demo presets and tool (its wikiCreate): "refill" is the refill page's start,
            // from which a step goes on with train-preset-refill (改善1, 2026-10-07).
            new Entry("train-preset-browse",
                    () -> onlineWikiScreen("com.trainsystemutilities.client.gui.TrainPresetBrowseScreenV2"),
                        WikiLiveCapture::onlineWikiState,
                    java.util.Set.of("mine-long"),
                    "mine", "refill", "mine-long"),
            // The demo preset, demo pictures attached, nothing published (its wikiCreate - 改善1, 2026-10-07).
            new Entry("preset-place-upload",
                    () -> onlineWikiScreen("com.trainsystemutilities.client.gui.PresetPlaceUploadScreenV2"),
                        WikiLiveCapture::onlineWikiState,
                    "main"),
            // A post and a profile of Preset Place, with sample data: their own wikiCreate builds them without a call to
            // the service (the real constructors track a view, ask likes and follows, and list posts).
            new Entry("preset-place",
                    () -> onlineWikiScreen("com.trainsystemutilities.client.gui.PresetPlaceDetailScreenV2"),
                        WikiLiveCapture::onlineWikiState,
                    "detail", "detail-report"),
            new Entry("preset-place-profile",
                    () -> onlineWikiScreen("com.trainsystemutilities.client.gui.PresetPlaceProfileScreenV2"),
                        NOOP_APPLY,
                    "main"),
            // A creator's analytics made up by its wikiCreate, nothing fetched (改善1, 2026-10-07).
            new Entry("creator-center",
                    () -> onlineWikiScreen("com.trainsystemutilities.client.gui.CreatorCenterScreenV2"),
                        WikiLiveCapture::onlineWikiState,
                    "main"),
            // The icon editor on no icon yet: its load button puts in a demo icon instead of asking for a file, and its
            // save keeps nothing (its wikiCreate - 改善1, 2026-10-07).
            new Entry("profile-icon-editor",
                    () -> onlineWikiScreen("com.trainsystemutilities.client.gui.ProfileIconEditorScreenV2"),
                        WikiLiveCapture::onlineWikiState,
                    "main")
        );
    }

    /**
     * The same table, lent to Manta's wiki to draw each screen live where a page shows its picture (Manta
     * {@code WikiLiveScreens}, MANTA_7_PHASE7_PLAYBACK 3.1): the wiki builds and draws it inside its playback containment
     * (no packet, no slot action on the player's own menu, no screen change), so the container screens built here with
     * {@code containerId = 0} never reach the server. Once, at client setup.
     */
    public static void registerLive() {
        com.manta.api.wiki.WikiLiveScreens.register("trainsystemutilities", wikiRows());
    }

    /** The table as the wiki reads it - the capture loop and the live registration both: each screen in its wiki states. */
    static java.util.List<com.manta.api.wiki.WikiLiveScreens.Row> wikiRows() {
        java.util.List<com.manta.api.wiki.WikiLiveScreens.Row> rows = new java.util.ArrayList<>();
        for (Entry e : table()) {
            rows.add(new com.manta.api.wiki.WikiLiveScreens.Row(e.id(), e.factory(), e.apply(), false,
                    e.wikiStates()));
        }
        return rows;
    }

    /** Every id and its states, one line each, for a driver to enumerate without parsing this file. */
    public static java.util.List<String> catalogueLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Entry e : table()) {
            out.add("[tsu-wiki] catalogue " + e.id() + " " + String.join(" ", e.states()));
        }
        return out;
    }

    /** closed 側の画面の wiki 用の見本 (その class の static wikiCreate()) を reflection で (proguard-release.pro が名前を残す)。 */
    private static Screen onlineWikiScreen(String className) {
        try {
            return (Screen) Class.forName(className).getMethod("wikiCreate").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("online screen unavailable: " + className, e);
        }
    }

    /** closed 側の画面に state を当てる (その画面の wikiApplyState(String)、reflection で)。 */
    private static void onlineWikiState(Screen screen, String state) {
        try {
            screen.getClass().getMethod("wikiApplyState", String.class).invoke(screen, state);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("online screen state unavailable: " + screen.getClass().getName(), e);
        }
    }

    /**
     * TrainPresetRefill は Menu 駆動なので dummy menu + player inventory で構築。 The stand-in holds the demo tool and some
     * slime blocks in its throwaway hotbar, which the refill page puts into the slot (改善1, 2026-10-07).
     */
    private static Screen createRefill() {
        var p = Minecraft.getInstance().player;
        var inv = new net.minecraft.world.entity.player.Inventory(p);
        inv.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SLIME_BLOCK, 32));
        var screen = new TrainPresetRefillScreenV2(
                new TrainPresetRefillMenu(0, inv), inv, net.minecraft.network.chat.Component.empty());
        screen.wikiStandIn();
        return screen;
    }

    /** The wire refill the same way: a dummy menu on a throwaway inventory, wire spools in its hotbar (改善1, 2026-10-07). */
    private static Screen createWireRefill() {
        var p = Minecraft.getInstance().player;
        var inv = new net.minecraft.world.entity.player.Inventory(p);
        inv.setItem(0, new net.minecraft.world.item.ItemStack(
                com.trainsystemutilities.registry.ModItems.WIRE_SPOOL.get(), 16));
        var screen = new com.trainsystemutilities.client.gui.WireConnectorRefillScreen(
                new com.trainsystemutilities.gui.WireConnectorMenu(0, inv), inv,
                net.minecraft.network.chat.Component.empty());
        screen.wikiStandIn();
        return screen;
    }
}
