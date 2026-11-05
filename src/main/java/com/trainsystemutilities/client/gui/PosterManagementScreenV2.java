package com.trainsystemutilities.client.gui;
import com.manta.api.anim.Transition;
import com.manta.api.anim.Easing;
import com.manta.api.anim.Animation;

import com.manta.api.screen.JsonLayoutEngine;
import com.manta.api.screen.JsonLayoutHandler;
import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.manta.api.controller.ToggleSwitchController;
import com.trainsystemutilities.blockentity.PosterManagementBlockEntity;
import com.trainsystemutilities.blockentity.PosterManagementBlockEntity.AnimationType;
import com.trainsystemutilities.gui.PosterManagementMenu;
import com.trainsystemutilities.network.ImageUploadChunkPayload;
import com.trainsystemutilities.network.ImageUploadStartPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PosterManagementScreen V2 (Container 系、JsonLayoutScreen ベース)。
 * <p>主要レイアウトは JSON。Dynamic values (knob position, text, the image list) are pushed into
 * the page (Manta 7 push) and laid out by repeat。アニメ設定 popup は overlayJson で表示。
 * オーナースキン顔 / 画像ホバープレビューは afterDialogRender で描画。
 */
public class PosterManagementScreenV2 extends JsonLayoutScreen<PosterManagementMenu> {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    @Override
    protected String wikiPageId() { return "poster-management"; }

    private boolean showAnimSettings = false;
    private Boolean localMonitorEnabled = null;
    private Boolean localFitMode = null;
    private Boolean localAnimSingle = null;
    /** Fit-to-monitor toggle (= local optimistic state + server click 6 で反映)。 */
    private final ToggleSwitchController fitToggle =
            new ToggleSwitchController("fit-toggle-track", "fit-toggle-knob",
                    this::fitMode, v -> { localFitMode = v; clickButton(6); });
    /** Anim-single toggle (= local optimistic state + server click 7)。 */
    private final ToggleSwitchController animSingleToggle =
            new ToggleSwitchController("anim-single-toggle-track", "anim-single-toggle-knob",
                    this::animSingle, v -> { localAnimSingle = v; clickButton(7); });
    /** Monitor toggle (= logical state + derived visual: monitorEnabled && getLinkedMonitorGroupCount() > 0)。
     *  withVisualState() で visual と logical を分離 — click は logical を反転、bg/knob は derived value で描画。 */
    private final ToggleSwitchController monitorToggle =
            new ToggleSwitchController("monitor-toggle-track", "monitor-toggle-knob",
                    this::monitorEnabled, v -> { localMonitorEnabled = v; clickButton(0); })
                    .withVisualState(() -> monitorEnabled() && be().getLinkedMonitorGroupCount() > 0);
    private final Map<Integer, Boolean> localImageEnabled = new HashMap<>();
    /** 画像リスト scroll (= §4.19 ScrollViewport, ScrollMath を排除)。 */
    private final com.manta.api.controller.ScrollViewport imageScroll =
            new com.manta.api.controller.ScrollViewport(() -> be().getImageIds().size(), VISIBLE_ROWS);
    private int hoveredImageIndex = -1;
    private long previewShowNano = 0;
    private static final long PREVIEW_ANIM_NS = 150_000_000L;

    /** リスト一度に表示する画像行数。JSON 側 LIST_H と整合 (5*19-1=94)。 */
    private static final int VISIBLE_ROWS = 5;
    /** 直近の swap で「下から上に」動いた行の元 index (= moved-up 行の現 index)。
     *  該当行だけ translateY +19→0 でスライドインさせる。 */
    private int lastSwapMovedUpIdx = -1;
    /** 直近の swap で「上から下に」動いた行の現 index。translateY -19→0 でスライドイン。 */
    private int lastSwapMovedDownIdx = -1;
    /** anim popup 内のプレビュー canvas で連続再生する用の起点時刻。 */
    private long animPreviewStartNano = System.nanoTime();
    private com.trainsystemutilities.blockentity.PosterManagementBlockEntity.AnimationType lastPreviewType;

    public PosterManagementScreenV2(PosterManagementMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // ===== Wiki live-capture support (WikiLiveCapture から呼ばれる) =====

    /**
     * Built by wikiCreate: the block entity is the wiki's stand-in, so a press is applied to it here as the server's menu
     * would apply it (clickButton), and the file button adds the next demo poster instead of opening the system's file
     * dialog (改善1, 2026-10-07: the page added no image and changed nothing).
     */
    private boolean wikiMode = false;
    /** The demo posters (wiki/demo/poster-N.png, drawn for the wiki from shapes only): the page starts with two. */
    private static final int WIKI_POSTERS = 3;
    private static final int WIKI_POSTERS_AT_START = 2;

    public static PosterManagementScreenV2 wikiCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.core.BlockPos pos = mc.player.blockPosition();
        var be = new com.trainsystemutilities.blockentity.PosterManagementBlockEntity(
                pos, com.trainsystemutilities.registry.ModBlocks.POSTER_MANAGEMENT_BLOCK.get().defaultBlockState());
        be.setLevel(mc.level);
        for (int i = 1; i <= WIKI_POSTERS_AT_START; i++) wikiAddPoster(be, i);
        Inventory inv = new Inventory(mc.player); // 空 (持ち物アイテム非表示)
        var menu = new PosterManagementMenu(0, inv, be);
        var screen = new PosterManagementScreenV2(menu, inv,
                Component.translatable("tsu.poster_management.title"));
        screen.wikiMode = true;
        return screen;
    }

    /**
     * Demo poster {@code n} into the stand-in's list, its picture into the poster textures under its own name-based id (a
     * random id of a real upload never takes that form), so the row's hover preview shows it - past the demo pictures
     * (the sweep's "main-long"), one of them again, so no row asks the server for a picture. A picture that cannot be read
     * leaves the row without one, as a real upload still downloading.
     */
    private static void wikiAddPoster(PosterManagementBlockEntity be, int n) {
        UUID id = UUID.nameUUIDFromBytes(("tsu-wiki-poster-" + n).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        be.addImageId(id, "poster-" + n + ".png");
        if (com.trainsystemutilities.client.renderer.PosterTextureManager.getDimensions(id) != null) return;
        int picture = (n - 1) % WIKI_POSTERS + 1;
        var rl = ResourceLocation.fromNamespaceAndPath("trainsystemutilities", "wiki/demo/poster-" + picture + ".png");
        try (var in = Minecraft.getInstance().getResourceManager().open(rl)) {
            com.trainsystemutilities.client.renderer.PosterTextureManager.onImageReceived(id, in.readAllBytes());
        } catch (java.io.IOException e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn("[PosterScreen] wiki demo poster {} unreadable: {}", rl, e.toString());
        }
    }

    /**
     * "anim" opens the animation settings. "main-long" is BelugaAOS's UI sweep's alone (WikiLiveCapture's sweep-only
     * states): the image list holding twice the rows it shows, so its scrollbar thumb is up to be dragged.
     */
    public void wikiApplyState(String state) {
        showAnimSettings = "anim".equals(state);
        if ("main-long".equals(state)) {
            for (int n = be().getImageIds().size() + 1; n <= 2 * VISIBLE_ROWS; n++) wikiAddPoster(be(), n);
        }
    }
    // dialog 開封の scaleIn(180) と連動した inventory item scale-in は MCSS 基底クラス
    // (JsonLayoutScreen) が default で提供するため、本 subclass では override 不要。

    @Override
    protected String layoutJson() {
        return loadResourceJson("layouts/poster-management.json");
    }

    @Override
    protected String overlayJson() {
        return showAnimSettings ? loadResourceJson("layouts/poster-management-anim.json") : null;
    }

    @Override
    protected int[] overlayDefaultPosition(int overlayW, int overlayH) {
        // V1 と同じく popup はメイン GUI の右側に展開。
        // Phase 5d FIX: dialogScale 適用 (autoscale 対応)
        return new int[]{dialogLocalToScreenX(this.imageWidth + 8), dialogLocalToScreenY(0)};
    }

    /** MCSS 基底の loadModResourceJson に委譲 (TsuLayouts.load 経由)。 */
    private static String loadResourceJson(String path) { return TsuLayouts.load(path); }

    private PosterManagementBlockEntity be() { return getMenu().getBlockEntity(); }

    // ===== Manta 7 push (Phase 4, TSU batch 3, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when a page
    // opens: the screen overrides no getDynamic*. The hint toggle, dialog-open, anim-popup-open and
    // the toggle transitions are the base's. The one overlay is the animation settings popup.
    /** A row moved up by a swap slides in from below; one moved down, from above (the others do not move). */
    private static final com.manta.api.anim.Animation SHUFFLE_UP = com.manta.api.anim.Animation.of(220)
            .easing(com.manta.api.anim.Easing.EASE_OUT).translateY(19f, 0f).build();
    private static final com.manta.api.anim.Animation SHUFFLE_DOWN = com.manta.api.anim.Animation.of(220)
            .easing(com.manta.api.anim.Easing.EASE_OUT).translateY(-19f, 0f).build();
    private static final int ANIM_TYPE_ITEMS = 11;

    private MantaState pushed, overlayPage;
    private com.manta.api.state.TextSlot tInfo, tBadge, tSection, tStatus, tMonitorInfo, tRowIdx, tRowName;
    private com.manta.api.state.ColorSlot cFitTrack, cFitKnob, cSingleTrack, cSingleKnob, cMonitorTrack, cMonitorKnob;
    private com.manta.api.state.ColorSlot cIndicator, cStatus, cOwner, cRowBg, cRowBorder, cRowIdx, cRowName;
    private com.manta.api.state.NumberSlot nFitKnobX, nSingleKnobX, nMonitorKnobX, nRows, nScroll, nThumbY, nThumbH;
    private com.manta.api.state.BoolSlot bScrollbar;
    private com.manta.api.state.AnimationSlot aShuffle;
    private com.manta.api.state.TextSlot tInterval, tDuration;
    private final com.manta.api.state.ColorSlot[] cTypeBg = new com.manta.api.state.ColorSlot[ANIM_TYPE_ITEMS];
    private final com.manta.api.state.ColorSlot[] cTypeColor = new com.manta.api.state.ColorSlot[ANIM_TYPE_ITEMS];

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer == PageLayer.OVERLAY) {
            overlayPage = MantaState.of(page);
            tInterval = overlayPage.text("anim-interval-display");
            tDuration = overlayPage.text("anim-duration-display");
            for (int i = 0; i < ANIM_TYPE_ITEMS; i++) {
                cTypeBg[i] = overlayPage.color("anim-type-" + i + "-bg");
                cTypeColor[i] = overlayPage.color("anim-type-" + i + "-color");
            }
            overlayPage.set(overlayPage.animation("preview-flip"), com.manta.api.anim.Animation.flipX(420));
            overlayPage.textByKeyOnly();
            pushOverlay();
            return;
        }
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tInfo = pushed.text("info-text");
        tBadge = pushed.text("image-count-badge");
        tSection = pushed.text("section-title");
        tStatus = pushed.text("monitor-status-label");
        tMonitorInfo = pushed.text("monitor-info");
        tRowIdx = pushed.text("image-idx");
        tRowName = pushed.text("image-name");
        cFitTrack = pushed.color("fit-toggle-bg");
        cFitKnob = pushed.color("fit-knob-bg");
        cSingleTrack = pushed.color("anim-single-toggle-bg");
        cSingleKnob = pushed.color("anim-single-knob-bg");
        cMonitorTrack = pushed.color("monitor-toggle-bg");
        cMonitorKnob = pushed.color("monitor-knob-bg");
        cIndicator = pushed.color("monitor-indicator-bg");
        cStatus = pushed.color("monitor-status-color");
        cOwner = pushed.color("owner-border");
        cRowBg = pushed.color("image-row-bg");
        cRowBorder = pushed.color("image-row-border");
        cRowIdx = pushed.color("image-idx-color");
        cRowName = pushed.color("image-name-color");
        nFitKnobX = pushed.number("fit-knob-x");
        nSingleKnobX = pushed.number("anim-single-knob-x");
        nMonitorKnobX = pushed.number("monitor-knob-x");
        nRows = pushed.number("image-count");
        nScroll = pushed.number("image-scroll");
        nThumbY = pushed.number("scrollbar-thumb-y");
        nThumbH = pushed.number("scrollbar-thumb-h");
        bScrollbar = pushed.bool("scrollbar-visible");
        aShuffle = pushed.animation("image-row-shuffle");
        pushed.textByKeyOnly();
        pushAll();
    }

    /** The animation settings popup: its two durations, and the selected type's button in the primary colours. */
    private void pushOverlay() {
        if (overlayPage == null || !overlayPage.isOpen()) return;
        var be = be();
        overlayPage.set(tInterval, Component.translatable("tsu.poster.seconds_fmt", String.format("%.0f", be.getSlideInterval())).getString());
        overlayPage.set(tDuration, Component.translatable("tsu.poster.seconds_fmt", String.format("%.1f", be.getAnimationDuration())).getString());
        var types = PosterManagementBlockEntity.AnimationType.values();
        for (int i = 0; i < ANIM_TYPE_ITEMS; i++) {
            boolean selected = i < types.length && be.getAnimationType() == types[i];
            overlayPage.set(cTypeBg[i], selected ? 0xFF4FC3F7 : 0x4D000000);      // primary, or the default row bg
            overlayPage.set(cTypeColor[i], selected ? 0xFF000000 : 0xFFAAAAAA);   // black on cyan, or muted
        }
    }

    private void pushAll() {
        pushOverlay();
        if (pushed == null || !pushed.isOpen()) return;
        var be = be();
        pushed.set(tInfo, be.getAnimationType().getDisplayName()
                + " / " + Component.translatable("tsu.poster.interval_fmt", String.format("%.0f", be.getSlideInterval())).getString());
        pushed.set(tBadge, Component.translatable("tsu.poster.image_count_fmt", be.getImageIds().size()).getString());
        pushed.set(tSection, Component.translatable("tsu.poster.section_images_fmt", be.getImageIds().size()).getString());
        int groups = be.getLinkedMonitorGroupCount();
        pushed.set(tStatus, Component.translatable(monitorEnabled() && groups > 0
                ? "tsu.poster.monitor_online" : "tsu.poster.monitor_offline").getString());
        pushed.set(tMonitorInfo, groups > 0
                ? Component.translatable("tsu.poster.monitor_groups_linked_fmt", groups).getString()
                : Component.translatable("tsu.rm.monitor_disconnected").getString());
        // Every toggle is its controller's; the monitor's derives its visual state (withVisualState).
        pushed.set(cFitTrack, fitToggle.trackBg());
        pushed.set(cFitKnob, fitToggle.knobBg());
        pushed.set(cSingleTrack, animSingleToggle.trackBg());
        pushed.set(cSingleKnob, animSingleToggle.knobBg());
        pushed.set(cMonitorTrack, monitorToggle.trackBg());
        pushed.set(cMonitorKnob, monitorToggle.knobBg());
        pushed.set(cIndicator, monitorToggle.indicatorBg());
        pushed.set(cStatus, monitorToggle.statusText());
        // The owner's face box: private red, public green.
        pushed.set(cOwner, com.manta.api.hud.OwnerAccess.ringColor(be.isPrivateMode()));
        // Offsets: knobX / thumbY are default + delta, so each delta is its answer for 0.
        pushed.setOffset(nFitKnobX, fitToggle.knobX(0));
        pushed.setOffset(nSingleKnobX, animSingleToggle.knobX(0));
        pushed.setOffset(nMonitorKnobX, monitorToggle.knobX(0));
        pushed.set(nRows, imageScroll.rowCount());
        pushed.set(nScroll, 0);
        pushed.setOffset(nThumbY, imageScroll.thumbY(0, (VISIBLE_ROWS * 19 - 1) - 2, SCROLLBAR_THUMB_H));
        pushed.set(nThumbH, SCROLLBAR_THUMB_H);
        pushed.set(bScrollbar, imageScroll.needsScrollbar());
        // The image rows, under the scroll: a disabled image's row goes grey, an enabled one keeps the
        // layout's colours; only the two rows a swap moved slide in.
        java.util.List<UUID> ids = be.getImageIds();
        java.util.List<String> names = be.getImageNames();
        pushed.clear(aShuffle);
        for (int row = 0; row < imageScroll.rowCount(); row++) {
            int realIdx = row + imageScroll.offset();
            if (realIdx >= ids.size()) break;
            int displayNum = 0;
            for (int j = 0; j <= realIdx; j++) if (imageEnabled(j)) displayNum++;
            pushed.set(tRowIdx, row, imageEnabled(realIdx) ? String.valueOf(displayNum) : "-");
            pushed.set(tRowName, row, realIdx < names.size() && !names.get(realIdx).isEmpty()
                    ? names.get(realIdx)
                    : "image-" + ids.get(realIdx).toString().substring(0, 6) + ".png");
            if (imageEnabled(realIdx)) {
                pushed.clear(cRowBg, row);
                pushed.clear(cRowBorder, row);
                pushed.clear(cRowIdx, row);
                pushed.clear(cRowName, row);
            } else {
                pushed.set(cRowBg, row, 0x66000000);
                pushed.set(cRowBorder, row, 0xFF333333);
                pushed.set(cRowIdx, row, 0xFF666666);
                pushed.set(cRowName, row, 0xFF888888);
            }
            if (realIdx == lastSwapMovedUpIdx) {
                pushed.set(aShuffle, row, SHUFFLE_UP);
            } else if (realIdx == lastSwapMovedDownIdx) {
                pushed.set(aShuffle, row, SHUFFLE_DOWN);
            } else {
                pushed.clear(aShuffle, row);
            }
        }
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    private boolean fitMode() { return localFitMode != null ? localFitMode : be().isFitToMonitor(); }
    private boolean animSingle() { return localAnimSingle != null ? localAnimSingle : be().isAnimateSingle(); }
    private boolean monitorEnabled() { return localMonitorEnabled != null ? localMonitorEnabled : be().isMonitorEnabled(); }
    private boolean imageEnabled(int i) {
        return localImageEnabled.containsKey(i) ? localImageEnabled.get(i) : be().isImageEnabled(i);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        // ポップアップ要素は MCSS engine が overlay 専用ツリーを clickElement するため、
        // クラス名で popup/main を判定すれば showAnimSettings ゲートは不要。
        // → main GUI のクリックはこの先のメインハンドラに必ず通る (popup と排他にしない)。
        for (String c : classes) {
            if ("anim-popup-close".equals(c)) { showAnimSettings = false; return; }
            if ("mc-popup-close".equals(c)) {
                // anim popup 表示中はそれを閉じ、 そうでなければ main GUI を閉じる (R4.17.1)
                if (showAnimSettings) { showAnimSettings = false; } else { onClose(); }
                return;
            }
            if ("anim-interval-display".equals(c)) { clickButton(2); return; }
            if ("anim-duration-display".equals(c)) { clickButton(4); return; }
            if (c.startsWith("anim-type-") && !c.equals("anim-type-value")) {
                String tail = c.substring("anim-type-".length());
                try {
                    int idx = Integer.parseInt(tail);
                    clickButton(20 + idx);
                    animPreviewStartNano = System.nanoTime();
                    return;
                } catch (NumberFormatException ignored) {}
            }
        }

        // hint-toggle-track/knob は base class HintToggleHelper が自動処理。
        // 全 toggle (fit / anim-single / monitor) は controller dispatch。
        if (fitToggle.handleClick(classes)) return;
        if (animSingleToggle.handleClick(classes)) return;
        if (monitorToggle.handleClick(classes)) return;
        // main layout clicks
        for (String c : classes) {
            switch (c) {
                case "file-pick-btn":
                    if (wikiMode) {
                        // The next demo poster not yet in the list; none left, nothing (as a dialog closed unchosen).
                        int n = be().getImageIds().size() + 1;
                        if (n <= WIKI_POSTERS) wikiAddPoster(be(), n);
                        return;
                    }
                    openFileDialog();
                    return;
                case "anim-settings-btn":
                    // 既に開いている時に再クリックすると閉じる (トグル動作)
                    showAnimSettings = !showAnimSettings;
                    return;
                case "owner-face-box":
                case "owner-face-canvas": // 中央の顔 canvas が innermost auto-clickable で実クリックはこちらに来る
                    clickButton(8);
                    return;
            }
        }
        // image row repeat. 注意: MCSS engine は親 → 子 の順で onElementClick を発火するため、
        // 「image-row」が子ボタンより先に呼ばれる。ボタン領域 (右端 60px) でのクリックは
        // image-row の toggle が走らないようマウス座標でガードする。
        int idx = JsonLayoutEngine.currentRepeatIndex();
        if (idx >= 0) {
            int realIdx = idx + imageScroll.offset();
            for (String c : classes) {
                if ("image-up-btn".equals(c)) {
                    if (realIdx > 0) {
                        triggerSwap(realIdx, realIdx - 1, /*moveUp=*/true);
                        clickButton(300 + realIdx);
                    }
                    return;
                }
                if ("image-down-btn".equals(c)) {
                    if (realIdx < be().getImageIds().size() - 1) {
                        triggerSwap(realIdx, realIdx + 1, /*moveUp=*/false);
                        clickButton(400 + realIdx);
                    }
                    return;
                }
                if ("image-del-btn".equals(c)) {
                    clickButton(100 + realIdx); localImageEnabled.clear();
                    return;
                }
                if ("image-row".equals(c)) {
                    // 行右端 60px 以内のクリックはボタン領域 → toggle スキップ。
                    // mouseX は基底が渡す dialog-local なので、境界も local で持つ。
                    // this.leftPos を足していたので (local 0..280 に対し 340+260-60) この分岐は
                    // 一度も成立していなかった (最終読み 2026-09-12)。
                    int rowRightX = 10 + (DLG_INNER_W - 4 - 6);  // PAD + LIST_INNER_W
                    if (mouseX >= rowRightX - 60) return;
                    localImageEnabled.put(realIdx, !imageEnabled(realIdx));
                    clickButton(500 + realIdx);
                    return;
                }
            }
        }
    }

    /** 並び替え発火時の状態更新。affected 2 行の slide-in 方向を記録し、
     *  clearMainAnimByClass で animation を再トリガー。 */
    private void triggerSwap(int fromIdx, int toIdx, boolean moveUp) {
        localImageEnabled.clear();
        if (moveUp) {
            // fromIdx (clicked row) は新 index = toIdx に上がる → 「下から上に」へ
            lastSwapMovedUpIdx   = toIdx;
            lastSwapMovedDownIdx = fromIdx;  // 元 toIdx 行は下にずれた
        } else {
            lastSwapMovedDownIdx = toIdx;
            lastSwapMovedUpIdx   = fromIdx;
        }
    }

    /** poster JSON 上の DLG_INNER_W (幅 260)。Java 側ヒット判定で使用。 */
    private static final int DLG_INNER_W = 260;
    /** scrollbar thumb の固定高さ (画像数に依らず一定)。 */
    private static final int SCROLLBAR_THUMB_H = 20;

    /** MCSS 基底の sendButtonClick に委譲。 The wiki's stand-in takes the button as the server's menu would
     *  (PosterManagementMenu.clickMenuButton: its block entity's values only). */
    private void clickButton(int id) {
        if (wikiMode) {
            getMenu().clickMenuButton(Minecraft.getInstance().player, id);
            return;
        }
        sendButtonClick(id);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (showAnimSettings) { showAnimSettings = false; return true; }
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        // Image list scroll — VISIBLE_ROWS (5) を超えるときだけ有効。
        // popup 表示中もメイン GUI 操作は許可するので showAnimSettings は条件にしない。
        // 宣言矩形は dialog-local、mx/my は screen。UI スケールつまみが 1.0 でないと
        // dialogX() + local は描画位置と一致しない (実機 2026-09-12、つまみ 0.910) ので、
        // manta の isOverElement に写像を任せる。
        if (isOverElement("image-list-wrap", mx, my)) {
            imageScroll.scroll(dy > 0 ? -1 : 1);  // ScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        // The popup's interval / duration: the engine's wheel (their wheelKey, onElementWheel - 2026-10-07, so a wiki
        // playback's wheel is aimed at its display as a press is).
        return super.mouseScrolled(mx, my, dx, dy);
    }

    /** The image list's thumb, grabbed and dragged (Manta 2.87.0's dragThumb, R4.19.5). */
    @Override
    public boolean onElementDrag(String[] classes, String key, int mouseX, int mouseY,
                                 int elementX, int elementY, int elementW, int elementH, boolean pressed) {
        if ("poster-image-thumb-drag".equals(key)) {
            return imageScroll.dragThumb(mouseY, pressed);
        }
        return super.onElementDrag(classes, key, mouseX, mouseY, elementX, elementY, elementW, elementH, pressed);
    }

    @Override
    public boolean onElementWheel(String[] classes, String key, int mouseX, int mouseY, double scrollY) {
        if (!showAnimSettings) return false;
        if ("anim-interval-wheel".equals(key)) {
            clickButton(scrollY > 0 ? 2 : 3);
            return true;
        }
        if ("anim-duration-wheel".equals(key)) {
            clickButton(scrollY > 0 ? 4 : 5);
            return true;
        }
        return false;
    }

    // === Canvas painters — JSON declares the slots, we just paint ===

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        switch (key) {
            case "owner-face" -> drawOwnerFace(g, x, y, w, h);
            case "anim-preview" -> drawAnimPreview(g, x, y, w, h);
        }
    }

    /**
     * 画像 hover プレビューはダイアログの右に絶対座標で描画したいので
     * canvas の clip を避けて afterDialogRender で実装。
     * pose は overlay 描画前で main layout の dialog (leftPos, topPos) origin が
     * 適用済 — dialog-local 座標で描く。
     */
    @Override
    protected void afterDialogRender(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (showAnimSettings) return; // popup 表示中は hover preview を出さない
        drawImagePreviewTooltip(g, mouseX, mouseY);
    }

    private void drawOwnerFace(GuiGraphics g, int x, int y, int w, int h) {
        com.manta.api.hud.OwnerFacePainter.draw(g, x, y, w, h, be().getOwnerUUID());
    }

    /**
     * 画像 hover プレビュー (ダイアログの左外側にフロート)。
     * afterDialogRender 内で呼ばれる時点で pose はスクリーン原点 (overlay も pop 済) のため、
     * 描画は SCREEN 座標で行う必要がある。
     */
    private void drawImagePreviewTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int[] listR = findElementByClass("image-list-wrap");
        if (listR == null) { hoveredImageIndex = -1; return; }
        // 行の高さ 19 は dialog-local の値なので、判定も local で行う (screen に写すと
        // UI スケールつまみが 1.0 でないときだけ行がずれる - 実機 2026-09-12)。
        double lmx = toLocalMx(mouseX);
        double lmy = toLocalMy(mouseY);
        int lx0 = listR[0];
        int ly0 = listR[1];
        int lw = listR[2];
        int lh = listR[3];
        int newHover = -1;
        if (com.manta.api.hud.Rects.contains(lmx, lmy, lx0, ly0, lw, lh)) {
            int rel = (int) (lmy - ly0);
            int rowIdx = rel / 19;
            if (rowIdx >= 0 && rowIdx < VISIBLE_ROWS) {
                int realIdx = rowIdx + imageScroll.offset();
                if (realIdx < be().getImageIds().size()) newHover = realIdx;
            }
        }
        if (newHover < 0) { hoveredImageIndex = -1; return; }
        if (newHover != hoveredImageIndex) {
            hoveredImageIndex = newHover;
            previewShowNano = System.nanoTime();
        }
        UUID imageId = be().getImageIds().get(hoveredImageIndex);
        // getOrRequest は初回 null を返してダウンロード要求するが、
        // 数フレーム後にテクスチャが揃うとプレビューが現れる。
        ResourceLocation tex = com.trainsystemutilities.client.renderer.PosterTextureManager.getOrRequest(imageId);
        int[] dims = com.trainsystemutilities.client.renderer.PosterTextureManager.getDimensions(imageId);
        int maxW = 160, maxH = 140;
        int imgW, imgH;
        if (dims != null && dims[0] > 0 && dims[1] > 0) {
            float aspect = dims[0] / (float) dims[1];
            if (aspect > 1f) { imgW = maxW; imgH = (int) (maxW / aspect); }
            else            { imgH = maxH; imgW = (int) (maxH * aspect); }
            imgW = Math.min(maxW, Math.max(imgW, 60));
            imgH = Math.min(maxH, Math.max(imgH, 40));
        } else { imgW = 120; imgH = 80; }
        long elapsed = System.nanoTime() - previewShowNano;
        float p = Math.min(1f, (float) elapsed / PREVIEW_ANIM_NS);
        float eased = 1f - (1f - p) * (1f - p);
        int dw = (int) (imgW * eased);
        int dh = (int) (imgH * eased);
        if (dw <= 0 || dh <= 0) return;
        int padding = 4;
        int panelW = dw + padding * 2;
        int panelH = dh + padding * 2;
        // SCREEN 座標で描画。デフォルトはメイン GUI 左外側 (右辺 = leftPos):
        //   px = leftPos - panelW, py = topPos
        // 左側スペース不足時は右外側にフォールバック。
        int px = this.leftPos - panelW;
        int py = this.topPos;
        if (px < 4) px = this.leftPos + this.imageWidth + 4;
        if (wikiMode) {
            // The wiki's picture is the dialog: the preview stands over the dialog's upper right, inside it.
            px = getDialogScreenX() + getDialogDisplayW() - panelW - 8;
            py = getDialogScreenY() + 30;
        }
        int maxScreenY = this.height - panelH - 4;
        if (py > maxScreenY) py = maxScreenY;

        // パネル枠 (cyan border) + 半透明背景
        com.manta.api.hud.HudChrome.innerRect(g, px, py, panelW, panelH, 0xDD1a1a2e, 0xFF4fc3f7);
        if (tex != null) {
            // MCSS のバッファ描画では DynamicTexture の blit を flush で挟んで texture binding を
            // 確定させる必要がある (ImageNode と同じ定石)。flush 無しだと binding が確定せず空白になる。
            g.flush();
            g.blit(tex, px + padding, py + padding, 0, 0, dw, dh, dw, dh);
            g.flush();
        } else {
            // 読込中の placeholder
            String msg = Component.translatable("tsu.poster.loading").getString();
            int tw = com.manta.api.text.MantaText.uiWidth(this.font, msg);
            com.manta.api.text.MantaText.draw(g, this.font, msg, px + (panelW - tw) / 2, py + panelH / 2 - 4, 0xFF888888);
        }
    }

    /**
     * Anim popup 内のプレビュータイル。現在の AnimationType を 2 枚のサンプルタイル
     * (色違いブロック) を交互に切り替えるループで再生する。type 切替時は
     * preview-flip animationKey が canvas にも適用されてフリップ演出も入る。
     */
    private void drawAnimPreview(GuiGraphics g, int x, int y, int w, int h) {
        var be = be();
        var type = be.getAnimationType();
        if (lastPreviewType != type) {
            lastPreviewType = type;
            animPreviewStartNano = System.nanoTime();
        }
        // 背景パネル
        com.manta.api.hud.HudChrome.innerRect(g, x, y, w, h, 0xFF12122a, 0xFF000000);

        // 2 枚のサンプル (cyan / orange) を切替えながら anim 再生。
        // canvas 領域から食み出ないよう scissor で内部 rect にクリップする。
        int innerX = x + 4, innerY = y + 4, innerW = w - 8, innerH = h - 8;
        long now = System.nanoTime();
        long durMs  = (long) (be.getAnimationDuration() * 1000.0);
        long intMs  = (long) (be.getSlideInterval()    * 1000.0);
        long cycleMs = Math.max(800, intMs + durMs);
        long elapsedMs = ((now - animPreviewStartNano) / 1_000_000L) % cycleMs;
        int holdMs = (int) Math.max(0, intMs - durMs);
        boolean inTransition = elapsedMs >= holdMs;
        float t = inTransition
                ? Math.min(1f, (elapsedMs - holdMs) / (float) Math.max(50, durMs))
                : 0f;
        long fullCycle = ((now - animPreviewStartNano) / 1_000_000L) / cycleMs;
        boolean swapped = (fullCycle & 1L) == 1L;
        int colorA = swapped ? 0xFFFF8A65 : 0xFF4FC3F7;
        int colorB = swapped ? 0xFF4FC3F7 : 0xFFFF8A65;
        // enableScissor は SCREEN-space rect を取り pose の scale/translate を考慮しない。
        // overlay は overlayScale(=dialogScale) で scale 描画されるため、pose 行列から実
        // screen rect を算出する (overlayX()+innerX の手動加算は dialogScale 非乗算で高 GUI
        // スケール時に切れる)。
        int[] clip = com.manta.api.preview.GuiPreviewCamera.scissorRect(
                g.pose().last().pose(), innerX, innerY, innerW, innerH);
        g.enableScissor(clip[0], clip[1], clip[2], clip[3]);
        drawAnimSample(g, innerX, innerY, innerW, innerH, type, t, colorA, colorB);
        g.disableScissor();
        // 種別名はリストで強調表示するので preview 上には描かない (見にくいため)。
    }

    /**
     * 1 frame の anim プレビュー描画。t=0 で fromTile が完全表示、t=1 で toTile が
     * 完全表示。中間は AnimationType ごとの遷移。
     */
    private void drawAnimSample(GuiGraphics g, int x, int y, int w, int h,
                                com.trainsystemutilities.blockentity.PosterManagementBlockEntity.AnimationType type,
                                float t, int colorA, int colorB) {
        switch (type) {
            case SLIDE_LEFT -> {
                int dx = (int) (w * t);
                g.fill(x - dx,        y, x - dx + w,        y + h, colorA);
                g.fill(x + w - dx,    y, x + w - dx + w,    y + h, colorB);
            }
            case SLIDE_RIGHT -> {
                int dx = (int) (w * t);
                g.fill(x + dx,        y, x + dx + w,        y + h, colorA);
                g.fill(x + dx - w,    y, x + dx,            y + h, colorB);
            }
            case SLIDE_UP -> {
                int dy = (int) (h * t);
                g.fill(x, y - dy,     x + w, y - dy + h,     colorA);
                g.fill(x, y + h - dy, x + w, y + h - dy + h, colorB);
            }
            case SLIDE_DOWN -> {
                int dy = (int) (h * t);
                g.fill(x, y + dy,     x + w, y + dy + h,     colorA);
                g.fill(x, y + dy - h, x + w, y + dy,         colorB);
            }
            case FADE -> {
                int alphaA = (int) ((1f - t) * 0xFF) & 0xFF;
                int alphaB = (int) (t * 0xFF) & 0xFF;
                g.fill(x, y, x + w, y + h, (alphaA << 24) | (colorA & 0xFFFFFF));
                g.fill(x, y, x + w, y + h, (alphaB << 24) | (colorB & 0xFFFFFF));
            }
            case FLIP -> {
                // scale X: 1→0 (前半), 0→1 (後半)
                int cx = x + w / 2;
                if (t < 0.5f) {
                    float s = 1f - t * 2f;
                    int half = (int) (w / 2 * s);
                    g.fill(cx - half, y, cx + half, y + h, colorA);
                } else {
                    float s = (t - 0.5f) * 2f;
                    int half = (int) (w / 2 * s);
                    g.fill(cx - half, y, cx + half, y + h, colorB);
                }
            }
            case ZOOM_IN -> {
                float s = 1f - t;
                int dw = (int) (w * s), dh = (int) (h * s);
                int cx = x + w / 2, cy = y + h / 2;
                g.fill(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2, colorA);
                float s2 = t;
                int dw2 = (int) (w * s2), dh2 = (int) (h * s2);
                g.fill(cx - dw2 / 2, cy - dh2 / 2, cx + dw2 / 2, cy + dh2 / 2, colorB);
            }
            case SLIDE_LEFT_FADE -> {
                int dx = (int) (w * t);
                int alphaA = (int) ((1f - t) * 0xFF) & 0xFF;
                int alphaB = (int) (t * 0xFF) & 0xFF;
                g.fill(x - dx, y, x - dx + w, y + h, (alphaA << 24) | (colorA & 0xFFFFFF));
                g.fill(x + w - dx, y, x + w - dx + w, y + h, (alphaB << 24) | (colorB & 0xFFFFFF));
            }
            case SLIDE_UP_FADE -> {
                int dy = (int) (h * t);
                int alphaA = (int) ((1f - t) * 0xFF) & 0xFF;
                int alphaB = (int) (t * 0xFF) & 0xFF;
                g.fill(x, y - dy, x + w, y - dy + h, (alphaA << 24) | (colorA & 0xFFFFFF));
                g.fill(x, y + h - dy, x + w, y + h - dy + h, (alphaB << 24) | (colorB & 0xFFFFFF));
            }
            case ZOOM_FADE -> {
                int alphaA = (int) ((1f - t) * 0xFF) & 0xFF;
                int alphaB = (int) (t * 0xFF) & 0xFF;
                float s2 = 0.5f + t * 0.5f;
                int dw2 = (int) (w * s2), dh2 = (int) (h * s2);
                int cx = x + w / 2, cy = y + h / 2;
                g.fill(x, y, x + w, y + h, (alphaA << 24) | (colorA & 0xFFFFFF));
                g.fill(cx - dw2 / 2, cy - dh2 / 2, cx + dw2 / 2, cy + dh2 / 2,
                        (alphaB << 24) | (colorB & 0xFFFFFF));
            }
            case NONE -> {
                g.fill(x, y, x + w, y + h, t < 0.5f ? colorA : colorB);
            }
        }
    }

    // === File dialog (NativeFileDialog: only for the open screen, never for the wiki's stand-in) ===

    private void openFileDialog() {
        NativeFileDialog.open(this, "Poster-FileChooser-V2",
                Component.translatable("tsu.upload.pick_image_dialog_title").getString(),
                new String[] {"*.png", "*.jpg", "*.jpeg"}, "Image Files (*.png, *.jpg, *.jpeg)", result -> {
            try {
                if (result == null) return;
                Path filePath = Path.of(result);
                byte[] fileData = Files.readAllBytes(filePath);
                String fileName = filePath.getFileName().toString();
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        var be = be();
                        int totalSize = fileData.length;
                        int chunkSize = ImageUploadChunkPayload.CHUNK_SIZE;
                        int chunkCount = (totalSize + chunkSize - 1) / chunkSize;
                        // V3 fix: server sync で Screen が再生成されても animation を再 trigger
                        // しないよう、2 秒の suppress window を予約する
                        // facade 化 Phase 2-b: 内部 graph.AnimationNode を com.manta.api 経由に
                        com.manta.api.anim.MantaAnim.completeNewAnimations(2000L);
                        PacketDistributor.sendToServer(new ImageUploadStartPayload(
                                be.getBlockPos(), fileName, totalSize, chunkCount));
                        for (int i = 0; i < chunkCount; i++) {
                            int offset = i * chunkSize;
                            int len = Math.min(chunkSize, totalSize - offset);
                            byte[] chunk = new byte[len];
                            System.arraycopy(fileData, offset, chunk, 0, len);
                            PacketDistributor.sendToServer(new ImageUploadChunkPayload(i, chunk));
                        }
                    });
                }
            } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[PosterScreen] file-chooser image upload failed", e); }
        });
    }
}
