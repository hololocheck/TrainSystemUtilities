package com.trainsystemutilities.client.electrification;

import com.manta.api.controller.ToggleSwitchController;
import com.manta.api.draw.SmoothRenderer;
import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.trainsystemutilities.client.gui.TsuLayouts;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.network.OverheadPoleAutoSettingsAction;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.client.ToolsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 架線柱自動配置ツールの詳細設定 GUI (MCSS JsonLayoutPlainScreen ベース)。
 *
 * <p>BelugaExperience 準拠 (= 数値編集はホバー+ホイール、 boolean は ToggleSwitch)。
 */
@OnlyIn(Dist.CLIENT)
public class OverheadPoleAutoSettingsScreen extends JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    /** 片持ち toggle スイッチ。 */
    private final ToggleSwitchController cantToggle = new ToggleSwitchController(
            "opas-cant-track", "opas-cant-knob",
            () -> {
                ItemStack s = heldTool();
                return !s.isEmpty() && AutoPlaceConfig.getCantilever(s);
            },
            v -> {
                ItemStack s = heldTool();
                if (s.isEmpty()) return;
                ToolsClient.poleSettings(
                        OverheadPoleAutoSettingsAction.FIELD_CANTILEVER, v ? 1 : 0);
                AutoPlaceConfig.setCantilever(s, v);
            });

    private final ToggleSwitchController trussToggle = new ToggleSwitchController(
            "opas-truss-track", "opas-truss-knob",
            () -> {
                ItemStack s = heldTool();
                return !s.isEmpty() && AutoPlaceConfig.getPlaceTruss(s);
            },
            v -> {
                ItemStack s = heldTool();
                if (s.isEmpty()) return;
                ToolsClient.poleSettings(
                        OverheadPoleAutoSettingsAction.FIELD_PLACE_TRUSS, v ? 1 : 0);
                AutoPlaceConfig.setPlaceTruss(s, v);
            });

    private final ToggleSwitchController insToggle = new ToggleSwitchController(
            "opas-ins-track", "opas-ins-knob",
            () -> {
                ItemStack s = heldTool();
                return !s.isEmpty() && AutoPlaceConfig.getPlaceInsulator(s);
            },
            v -> {
                ItemStack s = heldTool();
                if (s.isEmpty()) return;
                ToolsClient.poleSettings(
                        OverheadPoleAutoSettingsAction.FIELD_PLACE_INSULATOR, v ? 1 : 0);
                AutoPlaceConfig.setPlaceInsulator(s, v);
            });

    /** 確認ダイアログ表示中なら true。 overlayJson() で popup を返す。 */
    private boolean showConfirm = false;

    // 3D プレビュー視点状態
    private float prevRotY = 30f, prevRotX = 25f;
    private float prevZoom = 1.0f;
    private float prevPanX = 0f, prevPanY = 0f;
    // drag 開始時の状態 (= delta 計算用)
    private int dragStartX, dragStartY;
    private float dragStartRotX, dragStartRotY, dragStartPanX, dragStartPanY;

    /** 画面を開いた時刻 (= dialog-open scaleIn と同期した 3D プレビューのゲート/フェード用)。 */
    private long openNano = 0L;
    /** 枠の dialog-open (scaleIn 180ms) が開き終わるまで 3D を描かない。 */
    private static final long PREVIEW_GATE_NS = 180_000_000L;
    /** 枠の開き完了後、 3D をフェードインさせる時間。 */
    private static final long PREVIEW_FADE_NS = 160_000_000L;

    public OverheadPoleAutoSettingsScreen() {
        super(Component.translatable("tsu.opas.screen_title"));
    }

    /** The wiki's stand-in's tool: a demo one, whose settings the page changes (改善1, 2026-10-07). Null: the held one. */
    private ItemStack wikiTool = null;

    /** The wiki's stand-in (WikiLiveCapture), on a demo tool with its default settings. */
    public static OverheadPoleAutoSettingsScreen wikiCreate() {
        OverheadPoleAutoSettingsScreen s = new OverheadPoleAutoSettingsScreen();
        s.wikiTool = new ItemStack(ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
        return s;
    }

    @Override
    protected String wikiPageId() { return "tools/overhead-pole-auto-tool"; }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/overhead-pole-auto-settings.json"); }

    @Override
    protected String overlayJson() {
        if (showConfirm) return TsuLayouts.load("layouts/overhead-pole-auto-settings-confirm.json");
        return null;
    }

    // ===== Manta 7 push (Phase 4, TSU batch 2, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when a page
    // opens: the screen overrides no getDynamic*. The hint toggle and the entrances are the base's.
    // Both entrances (dialog-open, opas-conf-popup-open) are the base's defaults - the override that
    // answered popIn(220) for the second said what the base already says. The screen opens from the
    // tool in hand (OverheadPoleAutoToolItem); with none held the value texts are cleared, where the
    // pull left the layout's placeholders ('3', '1', a dash) - a keyed node has none.
    private MantaState pushed, confirmPage;
    private com.manta.api.state.TextSlot tHeight, tClearance, tMulti, tChest, tMe;
    private com.manta.api.state.ColorSlot cCantTrack, cCantKnob, cTrussTrack, cTrussKnob, cInsTrack, cInsKnob;
    private com.manta.api.state.NumberSlot nCantKnobX, nTrussKnobX, nInsKnobX;
    private com.manta.api.state.TextSlot tItem1, tItem2, tItem3;
    private com.manta.api.state.IconSlot iCant, iTruss, iIns;
    private com.manta.api.state.ColorSlot cCantMark, cTrussMark, cInsMark;

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer == PageLayer.OVERLAY) {
            // The one overlay is the placement confirmation (overlayJson).
            confirmPage = MantaState.of(page);
            tItem1 = confirmPage.text("opas-conf-item-1");
            tItem2 = confirmPage.text("opas-conf-item-2");
            tItem3 = confirmPage.text("opas-conf-item-3");
            iCant = confirmPage.icon("opas-conf-cant-check");
            iTruss = confirmPage.icon("opas-conf-truss-check");
            iIns = confirmPage.icon("opas-conf-ins-check");
            cCantMark = confirmPage.color("opas-conf-cant-color");
            cTrussMark = confirmPage.color("opas-conf-truss-color");
            cInsMark = confirmPage.color("opas-conf-ins-color");
            confirmPage.textByKeyOnly();
            pushConfirm();
            return;
        }
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tHeight = pushed.text("opas-h-value");
        tClearance = pushed.text("opas-c-value");
        tMulti = pushed.text("opas-m-value");
        tChest = pushed.text("opas-storage-chest");
        tMe = pushed.text("opas-storage-me");
        cCantTrack = pushed.color("opas-cant-track-bg");
        cCantKnob = pushed.color("opas-cant-knob-bg");
        cTrussTrack = pushed.color("opas-truss-track-bg");
        cTrussKnob = pushed.color("opas-truss-knob-bg");
        cInsTrack = pushed.color("opas-ins-track-bg");
        cInsKnob = pushed.color("opas-ins-knob-bg");
        nCantKnobX = pushed.number("opas-cant-knob-x");
        nTrussKnobX = pushed.number("opas-truss-knob-x");
        nInsKnobX = pushed.number("opas-ins-knob-x");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushAll() {
        pushConfirm();
        if (pushed == null || !pushed.isOpen()) return;
        pushed.set(cCantTrack, cantToggle.trackBg());
        pushed.set(cCantKnob, cantToggle.knobBg());
        pushed.set(cTrussTrack, trussToggle.trackBg());
        pushed.set(cTrussKnob, trussToggle.knobBg());
        pushed.set(cInsTrack, insToggle.trackBg());
        pushed.set(cInsKnob, insToggle.knobBg());
        // Offsets: knobX is default + delta, so its delta is its answer for 0.
        pushed.setOffset(nCantKnobX, cantToggle.knobX(0));
        pushed.setOffset(nTrussKnobX, trussToggle.knobX(0));
        pushed.setOffset(nInsKnobX, insToggle.knobX(0));
        ItemStack stack = heldTool();
        if (stack.isEmpty()) {
            pushed.clear(tHeight);
            pushed.clear(tClearance);
            pushed.clear(tMulti);
            pushed.clear(tChest);
            pushed.clear(tMe);
            return;
        }
        // Values only: the trailing up/down glyph became the value box's child icon (W7-1, R4.23.1).
        pushed.set(tHeight, String.valueOf(AutoPlaceConfig.getHeight(stack)));
        pushed.set(tClearance, String.valueOf(AutoPlaceConfig.getClearance(stack)));
        pushed.set(tMulti, String.valueOf(AutoPlaceConfig.getMultiTrackCount(stack)));
        pushed.set(tChest, storageChest(stack));
        pushed.set(tMe, storageMe(stack));
    }

    /** The confirmation's values: the numbers always shown with their mark, each boolean as its icon (circle-check / x) in green or red. */
    private void pushConfirm() {
        if (confirmPage == null || !confirmPage.isOpen()) return;
        confirmPage.set(cCantMark, cantToggle.isOn() ? 0xFF66BB6A : 0xFFEF5350);
        confirmPage.set(cTrussMark, trussToggle.isOn() ? 0xFF66BB6A : 0xFFEF5350);
        confirmPage.set(cInsMark, insToggle.isOn() ? 0xFF66BB6A : 0xFFEF5350);
        ItemStack stack = heldTool();
        if (stack.isEmpty()) {
            confirmPage.clear(tItem1);
            confirmPage.clear(tItem2);
            confirmPage.clear(tItem3);
            confirmPage.clear(iCant);
            confirmPage.clear(iTruss);
            confirmPage.clear(iIns);
            return;
        }
        confirmPage.set(tItem1, Component.translatable("tsu.opas.conf_height_fmt", AutoPlaceConfig.getHeight(stack)).getString());
        confirmPage.set(tItem2, Component.translatable("tsu.opas.conf_clearance_fmt", AutoPlaceConfig.getClearance(stack)).getString());
        confirmPage.set(tItem3, Component.translatable("tsu.opas.conf_multi_fmt", AutoPlaceConfig.getMultiTrackCount(stack)).getString());
        confirmPage.set(iCant, AutoPlaceConfig.getCantilever(stack) ? "manta:circle-check" : "manta:x");
        confirmPage.set(iTruss, AutoPlaceConfig.getPlaceTruss(stack) ? "manta:circle-check" : "manta:x");
        confirmPage.set(iIns, AutoPlaceConfig.getPlaceInsulator(stack) ? "manta:circle-check" : "manta:x");
    }

    private String storageChest(ItemStack stack) {
        String l = stack.get(ModDataComponents.PLACE_LINKED_CHEST_LABEL.get());
        String v = (l == null || l.isBlank())
                ? Component.translatable("tsu.opas.storage_none").getString()
                : fitStorageValue("tsu.opas.storage_chest_fmt", l);
        return Component.translatable("tsu.opas.storage_chest_fmt", v).getString();
    }

    private String storageMe(ItemStack stack) {
        String v;
        if (!net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            v = Component.translatable("tsu.opas.storage_ae2_missing").getString();
        } else {
            String l = stack.get(ModDataComponents.PLACE_LINKED_WAP_LABEL.get());
            v = (l == null || l.isBlank())
                    ? Component.translatable("tsu.opas.storage_none").getString()
                    : fitStorageValue("tsu.opas.storage_me_fmt", l);
        }
        return Component.translatable("tsu.opas.storage_me_fmt", v).getString();
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** レイアウト上の storage 行の幅 (`opas-storage-*` の `w`)。 */
    private static final int STORAGE_LABEL_W = 186;

    /**
     * 書式文字列の接頭辞を除いた残りの幅に値を収める。
     *
     * <p>文字数で切ってはいけない — 旧実装は 24 文字固定で、日本語は半角の約 2 倍幅なので
     * 本語の UI では箱を常に超えていた。 接頭辞幅は空値で書式化して実測するので言語に依らない。
     */
    private String fitStorageValue(String fmtKey, String value) {
        int prefix = com.manta.api.text.MantaText.uiWidth(
                this.font, Component.translatable(fmtKey, "").getString());
        return com.manta.api.hud.HudText.ellipsize(
                this.font, value, Math.max(16, STORAGE_LABEL_W - prefix));
    }

    private ItemStack heldTool() {
        if (wikiTool != null) return wikiTool;
        if (minecraft == null) return ItemStack.EMPTY;
        return com.manta.api.hud.HeldTools.find(
                minecraft.player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
    }

    @Override
    public boolean onElementWheel(String[] classes, String key,
                                   int mouseX, int mouseY, double scrollY) {
        int delta = scrollY > 0 ? 1 : -1;
        if (key == null) return false;
        switch (key) {
            case "opas-h-wheel" -> { adjust(OverheadPoleAutoSettingsAction.FIELD_HEIGHT, delta);            return true; }
            case "opas-c-wheel" -> { adjust(OverheadPoleAutoSettingsAction.FIELD_CLEARANCE, delta);         return true; }
            case "opas-m-wheel" -> { adjust(OverheadPoleAutoSettingsAction.FIELD_MULTI_TRACK_COUNT, delta); return true; }
            case "opas-preview-zoom" -> {
                prevZoom = Math.max(0.2f, Math.min(8f, prevZoom * (scrollY > 0 ? 1.15f : 0.87f)));
                return true;
            }
        }
        return false;
    }

    /** drag で プレビュー回転 (= 通常) / Shift で pan。 */
    @Override
    public boolean onElementDrag(String[] classes, String key,
                                  int mouseX, int mouseY,
                                  int elX, int elY, int elW, int elH, boolean pressed) {
        if (!"opas-preview-rotate".equals(key)) return false;
        boolean shift = com.manta.api.hud.ModifierKeys.shift(net.minecraft.client.Minecraft.getInstance().getWindow().getWindow());
        if (pressed) {
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragStartRotX = prevRotX;
            dragStartRotY = prevRotY;
            dragStartPanX = prevPanX;
            dragStartPanY = prevPanY;
        } else if (shift) {
            prevPanX = dragStartPanX + (float) (mouseX - dragStartX);
            prevPanY = dragStartPanY + (float) (mouseY - dragStartY);
        } else {
            prevRotY = dragStartRotY + (float) (mouseX - dragStartX) * 1.5f;
            prevRotX = Math.max(-90, Math.min(90,
                    dragStartRotX + (float) (mouseY - dragStartY) * 1.5f));
        }
        return true;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // 確認ダイアログ表示中は その操作を優先
        if (showConfirm) {
            for (String c : classes) {
                if ("opas-conf-yes".equals(c)) { showConfirm = false; applyAndEnterPlacement(); return; }
                if ("opas-conf-cancel".equals(c) || "opas-conf-close".equals(c)) {
                    showConfirm = false; return;
                }
            }
            return; // overlay 外クリックは無視
        }
        if (cantToggle.handleClick(classes))  return;
        if (trussToggle.handleClick(classes)) return;
        if (insToggle.handleClick(classes))   return;
        if (com.manta.api.hud.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) com.manta.api.wiki.Wiki.open(pid);
                return;
            }
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            if ("opas-apply-btn".equals(c)) { showConfirm = true; return; }
        }
    }

    /** 確認 popup の「設置モードへ」 → server に APPLY action 送信 (= 選択モードの「配置する」へ) → GUI 閉じる。 */
    private void applyAndEnterPlacement() {
        // The stand-in enters no placement: the page's confirmation ends there.
        if (wikiTool != null) return;
        ToolsClient.poleSettings(
                OverheadPoleAutoSettingsAction.FIELD_APPLY_AND_ENTER_PLACEMENT, 1);
        ItemStack s = heldTool();
        if (!s.isEmpty()) {
            AutoPlaceConfig.setToolMode(s, AutoPlaceConfig.TOOL_MODE_SELECTION);
            AutoPlaceConfig.setSubMode(s, AutoPlaceConfig.SUB_MODE_PLACE);
        }
        onClose();
    }

    /** preview canvas で 3D portal を描画 (= 実 BlockState を BlockRenderDispatcher で)。 */
    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                            int x, int y, int w, int h, int mouseX, int mouseY) {
        if (!"opas-preview".equals(key)) return;
        if (showConfirm) return; // overlay 表示中は preview を描画しない (= 枠外はみ出し回避)
        ItemStack stack = heldTool();
        if (stack.isEmpty()) return;

        // dialog-open (scaleIn 180ms) で枠が拡大している間は 3D を描かない (= 原寸の 3D が
        // 縮小中の枠からはみ出す desync を解消)。 枠の開き完了に同期してフェードインさせる。
        // wiki capture は 1 フレームで完成状態を撮るためゲートを skip する。
        if (openNano == 0L) openNano = System.nanoTime();
        long elapsed = System.nanoTime() - openNano;
        boolean wikiCapture = com.manta.api.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE;
        if (elapsed < PREVIEW_GATE_NS && !wikiCapture) return;

        // canvas は dialog-relative。 PresetPreviewRenderer と同じく
        // scissor / 3D 描画は screen 座標を要求するため、 pose を dialog 座標分戻す
        float ds = dialogScale();
        int ox = isOverlayActive() ? overlayX() : dialogX();
        int oy = isOverlayActive() ? overlayY() : dialogY();
        int sx = ox + Math.round(x * ds);
        int sy = oy + Math.round(y * ds);
        int sw = Math.round(w * ds);
        int sh = Math.round(h * ds);
        g.pose().pushPose();
        if (ds != 1f) g.pose().scale(1f / ds, 1f / ds, 1f);  // dialog scale を undo して screen 空間へ
        g.pose().translate(-ox, -oy, 0);
        OverheadPortalPreviewRenderer.render(g, sx, sy, sw, sh,
                AutoPlaceConfig.getHeight(stack),
                AutoPlaceConfig.getClearance(stack),
                AutoPlaceConfig.getMultiTrackCount(stack),
                AutoPlaceConfig.getCantilever(stack),
                AutoPlaceConfig.getPlaceTruss(stack),
                AutoPlaceConfig.getPlaceInsulator(stack),
                prevRotY, prevRotX, prevZoom, prevPanX, prevPanY);
        // 枠の開き完了後、 暗色オーバーレイを薄くしてフェードイン
        long fadeElapsed = elapsed - PREVIEW_GATE_NS;
        if (fadeElapsed < PREVIEW_FADE_NS && !wikiCapture) {
            int a = (int) (0xFF * (1f - fadeElapsed / (float) PREVIEW_FADE_NS));
            SmoothRenderer.fillRect(g, sx, sy, sw, sh, (a << 24) | 0x1A1A2E);
        }
        g.pose().popPose();
    }

    /** (旧 2D preview helper、 現在は 3D OverheadPortalPreviewRenderer に置換済) */
    @SuppressWarnings("unused")
    private static void renderPortalPreviewLegacy2D(GuiGraphics g, int cx, int cy, int cw, int ch,
                                              int height, int clearance, int multiTrack,
                                              boolean cantilever, boolean placeTruss, boolean placeIns) {
        // canvas 内座標系の余白
        int pad = 8;
        int areaX = cx + pad, areaY = cy + pad;
        int areaW = cw - pad * 2, areaH = ch - pad * 2;

        // 線路 1 本の幅 = 3 block 相当、 隣接間隔 = 4 block (= 標準軌間 + 余白)
        // 1 セット (= 1 portal) の総幅 = 2 * (clearance + 1.5) + 3 = 6 + 2*clearance
        // 複線数 N → 並走線路 N 本 → 中央 portal の幅は (N-1)*4 + (2*(clearance+1.5) + 3)
        int trackWidthBlocks = 3;
        int trackGapBlocks = 4; // 並走線路の中心間隔
        int leftPoleBlocks = (int) (1.5 + clearance);
        int rightPoleBlocks = (int) (1.5 + clearance);
        int trackCenterSpanBlocks = (multiTrack - 1) * trackGapBlocks;
        int totalWidthBlocks = leftPoleBlocks + trackCenterSpanBlocks + rightPoleBlocks;
        // 高さ方向: pole の高さ (= polesPerStack = height + 1)、 縦余白を確保
        int polesPerStack = height + 1;
        int totalHeightBlocks = Math.max(polesPerStack + 2, 6); // 最低 6 block 高さ

        // scale: 描画領域に収まるよう pixel/block 比を計算
        double scaleW = (double) areaW / Math.max(1, totalWidthBlocks);
        double scaleH = (double) areaH / Math.max(1, totalHeightBlocks);
        double scale = Math.min(scaleW, scaleH);
        if (scale < 1.5) scale = 1.5;

        int drawW = (int) Math.round(totalWidthBlocks * scale);
        int drawH = (int) Math.round(totalHeightBlocks * scale);
        int originX = areaX + (areaW - drawW) / 2;
        int groundY = areaY + areaH - 6; // 線路の Y (= 下から少し上)

        // 地面ライン
        SmoothRenderer.fillRect(g, originX, groundY, drawW, 1, 0xFF555555);

        // 線路 (= multi 本)
        int leftPolePx = (int) Math.round(leftPoleBlocks * scale);
        for (int i = 0; i < multiTrack; i++) {
            int trackCenterPx = originX + leftPolePx + (int) Math.round(i * trackGapBlocks * scale);
            int trackHalfWidthPx = (int) Math.round(1.5 * scale);
            // 枕木 (= ground line の上に少し)
            SmoothRenderer.fillRect(g,
                    trackCenterPx - trackHalfWidthPx, groundY - 2,
                    trackHalfWidthPx * 2, 2, 0xFF6D4C41); // 茶
            // レール (= 2 本)
            SmoothRenderer.fillRect(g, trackCenterPx - trackHalfWidthPx + 1, groundY - 3,
                    1, 2, 0xFFB0BEC5);
            SmoothRenderer.fillRect(g, trackCenterPx + trackHalfWidthPx - 2, groundY - 3,
                    1, 2, 0xFFB0BEC5);
        }

        // pole の Y range: groundY .. groundY - poleHeightPx
        int poleHeightPx = (int) Math.round(polesPerStack * scale);
        int poleTopY = groundY - poleHeightPx;
        // pole の太さ
        int poleW = Math.max(2, (int) Math.round(0.6 * scale));

        // 左 pole (= 通常モードのみ)
        int leftPoleX = originX;
        if (!cantilever) {
            SmoothRenderer.fillRect(g, leftPoleX, poleTopY, poleW, poleHeightPx, 0xFF455A64);
        }
        // 右 pole (= 常に配置)
        int rightPoleX = originX + drawW - poleW;
        SmoothRenderer.fillRect(g, rightPoleX, poleTopY, poleW, poleHeightPx, 0xFF455A64);

        // truss line (= pole の上から 2 段目 Y = trackY + height - 1)
        // poleTopY が trackY + polesPerStack = trackY + height、 truss は -1 = poleTopY + scale
        if (placeTruss) {
            int trussY = poleTopY + (int) Math.round(scale); // 1 段下
            int trussLeftX = leftPoleX + poleW;
            int trussRightX = rightPoleX;
            int trussH = Math.max(2, (int) Math.round(0.6 * scale));
            SmoothRenderer.fillRect(g, trussLeftX, trussY, trussRightX - trussLeftX, trussH, 0xFF37474F);
            // X-brace 模様 (= 中央に薄い線)
            int braceY = trussY + 2;
            int braceH = (int) Math.round(scale * 0.8) - 2;
            if (braceH > 1) {
                SmoothRenderer.fillRect(g, trussLeftX, braceY, trussRightX - trussLeftX, 1, 0x4037474F);
            }
        }

        // 碍子 (= 各 track 上の truss の真下 1 段)
        if (placeIns && placeTruss) {
            int insY = poleTopY + (int) Math.round(scale * 2); // truss の 1 段下
            int insSize = Math.max(2, (int) Math.round(0.5 * scale));
            for (int i = 0; i < multiTrack; i++) {
                int trackCenterPx = originX + leftPolePx + (int) Math.round(i * trackGapBlocks * scale);
                SmoothRenderer.fillRect(g,
                        trackCenterPx - insSize / 2, insY,
                        insSize, insSize, 0xFFE0E0E0);
            }
        }

        // 凡例 / ラベル (= 設定値を canvas 下部に小さく表示)
        var mc = Minecraft.getInstance();
        if (mc.font != null) {
            String label = String.format("H=%d C=%d 複線=%d", height, clearance, multiTrack);
            int lw = com.manta.api.text.MantaText.uiWidth(mc.font, label);
            com.manta.api.text.MantaText.draw(g, mc.font, label, cx + (cw - lw) / 2, cy + ch - 10, 0xFF888888);
        }
    }

    private void adjust(int field, int delta) {
        ItemStack stack = heldTool();
        if (stack.isEmpty()) return;
        int current = switch (field) {
            case OverheadPoleAutoSettingsAction.FIELD_HEIGHT            -> AutoPlaceConfig.getHeight(stack);
            case OverheadPoleAutoSettingsAction.FIELD_CLEARANCE         -> AutoPlaceConfig.getClearance(stack);
            case OverheadPoleAutoSettingsAction.FIELD_MULTI_TRACK_COUNT -> AutoPlaceConfig.getMultiTrackCount(stack);
            default -> 0;
        };
        int next = current + delta;
        ToolsClient.poleSettings(field, next);
        switch (field) {
            case OverheadPoleAutoSettingsAction.FIELD_HEIGHT            -> AutoPlaceConfig.setHeight(stack, next);
            case OverheadPoleAutoSettingsAction.FIELD_CLEARANCE         -> AutoPlaceConfig.setClearance(stack, next);
            case OverheadPoleAutoSettingsAction.FIELD_MULTI_TRACK_COUNT -> AutoPlaceConfig.setMultiTrackCount(stack, next);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
