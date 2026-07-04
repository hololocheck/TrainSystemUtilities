package com.trainsystemutilities.client.gui;
import belugalab.mcss3.anim.Transition;
import belugalab.mcss3.anim.Easing;
import belugalab.mcss3.anim.Animation;

import belugalab.mcss3.screen.JsonLayoutEngine;
import belugalab.mcss3.screen.JsonLayoutHandler;
import belugalab.mcss3.screen.JsonLayoutScreen;
import belugalab.experience.controller.ToggleSwitchController;
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
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PosterManagementScreen V2 (Container 系、JsonLayoutScreen ベース)。
 * <p>主要レイアウトは JSON。動的要素 (knob 位置、テキスト、画像リスト) は
 * getDynamic*  / repeat で解決。アニメ設定 popup は overlayJson で表示。
 * オーナースキン顔 / 画像ホバープレビューは afterDialogRender で描画。
 */
public class PosterManagementScreenV2 extends JsonLayoutScreen<PosterManagementMenu> {

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
    private final belugalab.experience.controller.ScrollViewport imageScroll =
            new belugalab.experience.controller.ScrollViewport(() -> be().getImageIds().size(), VISIBLE_ROWS);
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

    public static PosterManagementScreenV2 wikiCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.core.BlockPos pos = mc.player.blockPosition();
        var be = new com.trainsystemutilities.blockentity.PosterManagementBlockEntity(
                pos, com.trainsystemutilities.registry.ModBlocks.POSTER_MANAGEMENT_BLOCK.get().defaultBlockState());
        be.setLevel(mc.level);
        Inventory inv = new Inventory(mc.player); // 空 (持ち物アイテム非表示)
        var menu = new PosterManagementMenu(0, inv, be);
        return new PosterManagementScreenV2(menu, inv,
                Component.translatable("tsu.poster_management.title"));
    }

    public void wikiApplyState(String state) {
        showAnimSettings = "anim".equals(state);
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

    private boolean fitMode() { return localFitMode != null ? localFitMode : be().isFitToMonitor(); }
    private boolean animSingle() { return localAnimSingle != null ? localAnimSingle : be().isAnimateSingle(); }
    private boolean monitorEnabled() { return localMonitorEnabled != null ? localMonitorEnabled : be().isMonitorEnabled(); }
    private boolean imageEnabled(int i) {
        return localImageEnabled.containsKey(i) ? localImageEnabled.get(i) : be().isImageEnabled(i);
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        var be = be();
        for (String c : classes) {
            switch (c) {
                case "info-text":
                    return be.getAnimationType().getDisplayName()
                            + " / " + Component.translatable("tsu.poster.interval_fmt", String.format("%.0f", be.getSlideInterval())).getString();
                case "image-count-badge":
                    return Component.translatable("tsu.poster.image_count_fmt", be.getImageIds().size()).getString();
                case "section-title":
                    return Component.translatable("tsu.poster.section_images_fmt", be.getImageIds().size()).getString();
                case "fit-mode-text":
                    return fitMode() ? "FIT" : "COVER";
                case "monitor-status-label": {
                    int groups = be.getLinkedMonitorGroupCount();
                    boolean on = monitorEnabled() && groups > 0;
                    return Component.translatable(on ? "tsu.poster.monitor_online" : "tsu.poster.monitor_offline").getString();
                }
                case "monitor-info": {
                    int groups = be.getLinkedMonitorGroupCount();
                    return groups > 0
                            ? Component.translatable("tsu.poster.monitor_groups_linked_fmt", groups).getString()
                            : Component.translatable("tsu.rm.monitor_disconnected").getString();
                }
                case "anim-type-value":
                    return be.getAnimationType().getDisplayName();
                case "anim-interval-display":
                    return Component.translatable("tsu.poster.seconds_fmt", String.format("%.0f", be.getSlideInterval())).getString();
                case "anim-duration-display":
                    return Component.translatable("tsu.poster.seconds_fmt", String.format("%.1f", be.getAnimationDuration())).getString();
            }
        }
        // image row (repeat)
        int idx = JsonLayoutEngine.currentRepeatIndex();
        if (idx >= 0) {
            int realIdx = idx + imageScroll.offset();
            List<UUID> ids = be.getImageIds();
            List<String> names = be.getImageNames();
            if (realIdx < ids.size()) {
                for (String c : classes) {
                    if ("image-idx".equals(c)) {
                        if (!imageEnabled(realIdx)) return "-";
                        int displayNum = 0;
                        for (int j = 0; j <= realIdx; j++) if (imageEnabled(j)) displayNum++;
                        return String.valueOf(displayNum);
                    }
                    if ("image-name".equals(c)) {
                        String raw = realIdx < names.size() && !names.get(realIdx).isEmpty()
                                ? names.get(realIdx)
                                : "image-" + ids.get(realIdx).toString().substring(0, 6) + ".png";
                        return raw;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public belugalab.mcss3.anim.Animation getDynamicAnimation(String[] classes, String key) {
        belugalab.mcss3.anim.Animation base = super.getDynamicAnimation(classes, key);
        if (base != null) return base;
        if ("image-row-shuffle".equals(key)) {
            // swap で動いた 2 行だけ方向付きで slide-in、それ以外は無アニメ。
            int repeatIdx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (repeatIdx < 0) return null;
            int realIdx = repeatIdx + imageScroll.offset();
            if (realIdx == lastSwapMovedUpIdx) {
                // 下から上に上がった → translateY +19→0 (下からスライドイン)
                return belugalab.mcss3.anim.Animation.of(220)
                        .easing(belugalab.mcss3.anim.Easing.EASE_OUT)
                        .translateY(19f, 0f)
                        .build();
            }
            if (realIdx == lastSwapMovedDownIdx) {
                return belugalab.mcss3.anim.Animation.of(220)
                        .easing(belugalab.mcss3.anim.Easing.EASE_OUT)
                        .translateY(-19f, 0f)
                        .build();
            }
            return null;
        }
        if ("preview-flip".equals(key)) return belugalab.mcss3.anim.Animation.flipX(420);
        return null;
    }

    @Override
    public belugalab.mcss3.anim.Transition getDynamicTransition(String[] classes, String key) {
        // toggle-bg / toggle-knob は基底 JsonLayoutScreen が解決。
        return super.getDynamicTransition(classes, key);
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        // hint-toggle-bg / hint-knob-bg は JsonLayoutEngine が HintToggleHelper にルートするので解決不要。
        // 全 toggle (fit/anim-single/monitor) は controller、monitor は withVisualState で derived 解決。
        switch (key) {
            case "fit-toggle-bg":           return fitToggle.trackBg();
            case "fit-knob-bg":             return fitToggle.knobBg();
            case "anim-single-toggle-bg":   return animSingleToggle.trackBg();
            case "anim-single-knob-bg":     return animSingleToggle.knobBg();
            case "monitor-toggle-bg":       return monitorToggle.trackBg();
            case "monitor-knob-bg":         return monitorToggle.knobBg();
            case "monitor-indicator-bg":    return monitorToggle.indicatorBg();
            case "monitor-status-color":    return monitorToggle.statusText();
        }
        // Owner face box border: Private = 赤、Public = 緑
        if ("owner-border".equals(key)) {
            return be().isPrivateMode() ? 0xFFef5350 : 0xFF66bb6a;
        }
        // Anim popup の type ボタン: 選択中だけ primary 配色で強調 (V1 anim-type-selected 相当)
        if (key.startsWith("anim-type-") && (key.endsWith("-bg") || key.endsWith("-color"))) {
            int dash = key.lastIndexOf('-');
            int firstDash = "anim-type-".length();
            String idxStr = key.substring(firstDash, dash);
            try {
                int idx = Integer.parseInt(idxStr);
                var types = com.trainsystemutilities.blockentity.PosterManagementBlockEntity
                        .AnimationType.values();
                boolean selected = idx >= 0 && idx < types.length
                        && be().getAnimationType() == types[idx];
                if (key.endsWith("-bg"))    return selected ? 0xFF4FC3F7 : 0x4D000000; // primary or default row bg
                if (key.endsWith("-color")) return selected ? 0xFF000000 : 0xFFAAAAAA; // black on cyan vs muted
            } catch (NumberFormatException ignored) {}
        }
        // 画像行が disabled (= isImageEnabled(false)) なら全体的にグレー化。
        int idx = JsonLayoutEngine.currentRepeatIndex();
        if (idx < 0) return null;
        int realIdx = idx + imageScroll.offset();
        if (realIdx >= be().getImageIds().size()) return null;
        boolean enabled = imageEnabled(realIdx);
        if (enabled) return null; // default を尊重
        switch (key) {
            case "image-row-bg":     return 0x66000000;    // より暗い
            case "image-row-border": return 0xFF333333;
            case "image-idx-color":  return 0xFF666666;
            case "image-name-color": return 0xFF888888;
        }
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        switch (key) {
            // hint-knob-x は JsonLayoutEngine が HintToggleHelper にルート (解決不要)。
            case "fit-knob-x":         return fitToggle.knobX(defaultValue);
            case "anim-single-knob-x": return animSingleToggle.knobX(defaultValue);
            case "monitor-knob-x":     return monitorToggle.knobX(defaultValue);
            case "image-count":
                return imageScroll.rowCount();
            case "image-scroll":       return 0;
            case "scrollbar-thumb-y":
                return imageScroll.thumbY(defaultValue, (VISIBLE_ROWS * 19 - 1) - 2, SCROLLBAR_THUMB_H);
            case "scrollbar-thumb-h":
                return SCROLLBAR_THUMB_H;
        }
        return null;
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        if ("scrollbar-visible".equals(key)) {
            return imageScroll.needsScrollbar();
        }
        return null;
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
                    clearOverlayAnimByClass("anim-preview-canvas");
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
                    int rowRightX = this.leftPos + 10 + (DLG_INNER_W - 4 - 6);  // PAD + LIST_INNER_W
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
        clearMainAnimByClass("image-row");
    }

    /** poster JSON 上の DLG_INNER_W (幅 260)。Java 側ヒット判定で使用。 */
    private static final int DLG_INNER_W = 260;
    /** scrollbar thumb の固定高さ (画像数に依らず一定)。 */
    private static final int SCROLLBAR_THUMB_H = 20;

    /** MCSS 基底の sendButtonClick に委譲。 */
    private void clickButton(int id) { sendButtonClick(id); }

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
        int[] r = findElementByClass("image-list-wrap");
        if (r != null) {
            int rx = dialogX() + r[0], ry = dialogY() + r[1];
            if (mx >= rx && mx < rx + r[2] && my >= ry && my < ry + r[3]) {
                imageScroll.scroll(dy > 0 ? -1 : 1);  // ScrollViewport が clamp 内包 (§4.19)
                return true;
            }
        }
        // Overlay popup wheel: cycle interval/duration if hovered display.
        // overlayX/Y は popup の現在位置 (drag 後も含む) を返すので、findElementByClass の
        // popup-local 座標に加算して画面座標に変換する。
        if (showAnimSettings && hasOverlay()) {
            int ox = overlayX(), oy = overlayY();
            int[] intR = findElementByClass("anim-interval-display");
            if (intR != null) {
                int ix = ox + intR[0], iy = oy + intR[1];
                if (mx >= ix && mx < ix + intR[2] && my >= iy && my < iy + intR[3]) {
                    clickButton(dy > 0 ? 2 : 3);
                    return true;
                }
            }
            int[] durR = findElementByClass("anim-duration-display");
            if (durR != null) {
                int dxx = ox + durR[0], dyy = oy + durR[1];
                if (mx >= dxx && mx < dxx + durR[2] && my >= dyy && my < dyy + durR[3]) {
                    clickButton(dy > 0 ? 4 : 5);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mx, my, dx, dy);
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
        belugalab.tsu.api.OwnerFacePainter.draw(g, x, y, w, h, be().getOwnerUUID());
    }

    /**
     * 画像 hover プレビュー (ダイアログの左外側にフロート)。
     * afterDialogRender 内で呼ばれる時点で pose はスクリーン原点 (overlay も pop 済) のため、
     * 描画は SCREEN 座標で行う必要がある。
     */
    private void drawImagePreviewTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int[] listR = findElementByClass("image-list-wrap");
        if (listR == null) { hoveredImageIndex = -1; return; }
        int lx0 = this.leftPos + listR[0];
        int ly0 = this.topPos  + listR[1];
        int lw = listR[2];
        int lh = listR[3];
        int newHover = -1;
        if (mouseX >= lx0 && mouseX < lx0 + lw
                && mouseY >= ly0 && mouseY < ly0 + lh) {
            int rel = mouseY - ly0;
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
        int maxScreenY = this.height - panelH - 4;
        if (py > maxScreenY) py = maxScreenY;

        // パネル枠 (cyan border) + 半透明背景
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, px, py, panelW, panelH, 5f, 0xFF4fc3f7);
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, px + 1, py + 1, panelW - 2, panelH - 2, 4f, 0xDD1a1a2e);
        if (tex != null) {
            // MCSS のバッファ描画では DynamicTexture の blit を flush で挟んで texture binding を
            // 確定させる必要がある (ImageNode と同じ定石)。flush 無しだと binding が確定せず空白になる。
            g.flush();
            g.blit(tex, px + padding, py + padding, 0, 0, dw, dh, dw, dh);
            g.flush();
        } else {
            // 読込中の placeholder
            String msg = Component.translatable("tsu.poster.loading").getString();
            int tw = this.font.width(msg);
            g.drawString(this.font, msg,
                    px + (panelW - tw) / 2, py + panelH / 2 - 4, 0xFF888888, false);
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
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, x, y, w, h, 5f, 0xFF000000);
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, x + 1, y + 1, w - 2, h - 2, 4f, 0xFF12122a);

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
        var pm = g.pose().last().pose();
        int sx = Math.round(innerX * pm.m00() + pm.m30());
        int sy = Math.round(innerY * pm.m11() + pm.m31());
        g.enableScissor(sx, sy,
                Math.round((innerX + innerW) * pm.m00() + pm.m30()),
                Math.round((innerY + innerH) * pm.m11() + pm.m31()));
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

    // === File dialog (TinyFileDialogs) ===

    private void openFileDialog() {
        Thread t = new Thread(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(3);
                filters.put(stack.UTF8("*.png"));
                filters.put(stack.UTF8("*.jpg"));
                filters.put(stack.UTF8("*.jpeg"));
                filters.flip();
                String result = TinyFileDialogs.tinyfd_openFileDialog(
                        Component.translatable("tsu.upload.pick_image_dialog_title").getString(), "", filters,
                        "Image Files (*.png, *.jpg, *.jpeg)", false);
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
                        belugalab.mcss3.graph.AnimationNode.completeNewAnimationsFor(2000L);
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
        }, "Poster-FileChooser-V2");
        t.setDaemon(true);
        t.start();
    }
}
