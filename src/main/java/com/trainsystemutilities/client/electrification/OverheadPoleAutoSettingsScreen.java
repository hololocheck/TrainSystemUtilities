package com.trainsystemutilities.client.electrification;

import belugalab.experience.controller.ToggleSwitchController;
import belugalab.mcss3.anim.Animation;
import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import com.trainsystemutilities.client.gui.TsuLayouts;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.network.OverheadPoleAutoSettingsPayload;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 架線柱自動配置ツールの詳細設定 GUI (MCSS JsonLayoutPlainScreen ベース)。
 *
 * <p>BelugaExperience 準拠 (= 数値編集はホバー+ホイール、 boolean は ToggleSwitch)。
 */
@OnlyIn(Dist.CLIENT)
public class OverheadPoleAutoSettingsScreen extends JsonLayoutPlainScreen {

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
                PacketDistributor.sendToServer(new OverheadPoleAutoSettingsPayload(
                        OverheadPoleAutoSettingsPayload.FIELD_CANTILEVER, v ? 1 : 0));
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
                PacketDistributor.sendToServer(new OverheadPoleAutoSettingsPayload(
                        OverheadPoleAutoSettingsPayload.FIELD_PLACE_TRUSS, v ? 1 : 0));
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
                PacketDistributor.sendToServer(new OverheadPoleAutoSettingsPayload(
                        OverheadPoleAutoSettingsPayload.FIELD_PLACE_INSULATOR, v ? 1 : 0));
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

    @Override
    protected String wikiPageId() { return "tools/overhead-pole-auto-tool"; }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/overhead-pole-auto-settings.json"); }

    @Override
    protected String overlayJson() {
        if (showConfirm) return TsuLayouts.load("layouts/overhead-pole-auto-settings-confirm.json");
        return null;
    }

    @Override
    public Animation getDynamicAnimation(String[] classes, String key) {
        Animation base = super.getDynamicAnimation(classes, key);
        if (base != null) return base;
        if ("opas-conf-popup-open".equals(key)) return Animation.popIn(220);
        return null;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private ItemStack heldTool() {
        if (minecraft == null || minecraft.player == null) return ItemStack.EMPTY;
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.is(ModItems.OVERHEAD_POLE_AUTO_TOOL.get())) return main;
        ItemStack off = minecraft.player.getOffhandItem();
        if (off.is(ModItems.OVERHEAD_POLE_AUTO_TOOL.get())) return off;
        return ItemStack.EMPTY;
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        ItemStack stack = heldTool();
        if (stack.isEmpty()) return defaultText;
        for (String c : classes) {
            switch (c) {
                case "opas-storage-chest" -> {
                    String l = stack.get(ModDataComponents.PLACE_LINKED_CHEST_LABEL.get());
                    String v = (l == null || l.isBlank())
                            ? Component.translatable("tsu.opas.storage_none").getString() : truncate(l, 24);
                    return Component.translatable("tsu.opas.storage_chest_fmt", v).getString();
                }
                case "opas-storage-me" -> {
                    String v;
                    if (!net.neoforged.fml.ModList.get().isLoaded("ae2")) {
                        v = Component.translatable("tsu.opas.storage_ae2_missing").getString();
                    } else {
                        String l = stack.get(ModDataComponents.PLACE_LINKED_WAP_LABEL.get());
                        v = (l == null || l.isBlank())
                                ? Component.translatable("tsu.opas.storage_none").getString() : truncate(l, 24);
                    }
                    return Component.translatable("tsu.opas.storage_me_fmt", v).getString();
                }
                case "opas-h-value" -> { return AutoPlaceConfig.getHeight(stack)          + "  ⇅"; }
                case "opas-c-value" -> { return AutoPlaceConfig.getClearance(stack)       + "  ⇅"; }
                case "opas-s-value" -> { return AutoPlaceConfig.getSpan(stack)            + "  ⇅"; }
                case "opas-m-value" -> { return AutoPlaceConfig.getMultiTrackCount(stack) + "  ⇅"; }
                // confirm popup: 数値項目 (= 常時 ✓ 付き表示)
                case "opas-conf-item-1" -> {
                    return Component.translatable("tsu.opas.conf_height_fmt", AutoPlaceConfig.getHeight(stack)).getString();
                }
                case "opas-conf-item-2" -> {
                    return Component.translatable("tsu.opas.conf_clearance_fmt", AutoPlaceConfig.getClearance(stack)).getString();
                }
                case "opas-conf-item-3" -> {
                    return Component.translatable("tsu.opas.conf_multi_fmt", AutoPlaceConfig.getMultiTrackCount(stack)).getString();
                }
                // confirm popup: boolean 項目 (= ON で ○ / OFF で ×、 U+25CB / U+00D7)
                // class 名と dynamicText key 両方の case を用意 (= MCSS layout 仕様の差異吸収)
                case "opas-conf-cant-check", "opas-conf-cant-mark"   -> { return AutoPlaceConfig.getCantilever(stack)      ? "○" : "×"; }
                case "opas-conf-truss-check", "opas-conf-truss-mark" -> { return AutoPlaceConfig.getPlaceTruss(stack)      ? "○" : "×"; }
                case "opas-conf-ins-check", "opas-conf-ins-mark"     -> { return AutoPlaceConfig.getPlaceInsulator(stack)  ? "○" : "×"; }
            }
        }
        return defaultText;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        // toggle track/knob 色
        if ("opas-cant-track-bg".equals(key))  return cantToggle.trackBg();
        if ("opas-cant-knob-bg".equals(key))   return cantToggle.knobBg();
        if ("opas-truss-track-bg".equals(key)) return trussToggle.trackBg();
        if ("opas-truss-knob-bg".equals(key))  return trussToggle.knobBg();
        if ("opas-ins-track-bg".equals(key))   return insToggle.trackBg();
        if ("opas-ins-knob-bg".equals(key))    return insToggle.knobBg();
        // confirm checkbox ✓ 文字色: ON で緑、 OFF で灰
        // 〇: 緑、 ×: 赤
        if ("opas-conf-cant-color".equals(key))  return cantToggle.isOn()  ? 0xFF66BB6A : 0xFFEF5350;
        if ("opas-conf-truss-color".equals(key)) return trussToggle.isOn() ? 0xFF66BB6A : 0xFFEF5350;
        if ("opas-conf-ins-color".equals(key))   return insToggle.isOn()   ? 0xFF66BB6A : 0xFFEF5350;
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("opas-cant-knob-x".equals(key))  return cantToggle.knobX(defaultValue);
        if ("opas-truss-knob-x".equals(key)) return trussToggle.knobX(defaultValue);
        if ("opas-ins-knob-x".equals(key))   return insToggle.knobX(defaultValue);
        return null;
    }

    @Override
    public boolean onElementWheel(String[] classes, String key,
                                   int mouseX, int mouseY, double scrollY) {
        int delta = scrollY > 0 ? 1 : -1;
        if (key == null) return false;
        switch (key) {
            case "opas-h-wheel" -> { adjust(OverheadPoleAutoSettingsPayload.FIELD_HEIGHT, delta);            return true; }
            case "opas-c-wheel" -> { adjust(OverheadPoleAutoSettingsPayload.FIELD_CLEARANCE, delta);         return true; }
            case "opas-s-wheel" -> { adjust(OverheadPoleAutoSettingsPayload.FIELD_SPAN, delta);              return true; }
            case "opas-m-wheel" -> { adjust(OverheadPoleAutoSettingsPayload.FIELD_MULTI_TRACK_COUNT, delta); return true; }
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
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
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
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) belugalab.mcss3.wiki.Wiki.open(pid);
                return;
            }
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            if ("opas-apply-btn".equals(c)) { showConfirm = true; return; }
        }
    }

    /** 確認 popup の「設置モードへ」 → server に APPLY action 送信 → GUI 閉じる。 */
    private void applyAndEnterPlacement() {
        PacketDistributor.sendToServer(new OverheadPoleAutoSettingsPayload(
                OverheadPoleAutoSettingsPayload.FIELD_APPLY_AND_ENTER_PLACEMENT, 1));
        ItemStack s = heldTool();
        if (!s.isEmpty()) {
            AutoPlaceConfig.setToolMode(s, AutoPlaceConfig.TOOL_MODE_SELECTION);
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
        boolean wikiCapture = belugalab.mcss3.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE;
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
            int lw = mc.font.width(label);
            g.drawString(mc.font, label, cx + (cw - lw) / 2, cy + ch - 10, 0xFF888888, false);
        }
    }

    private void adjust(int field, int delta) {
        ItemStack stack = heldTool();
        if (stack.isEmpty()) return;
        int current = switch (field) {
            case OverheadPoleAutoSettingsPayload.FIELD_HEIGHT            -> AutoPlaceConfig.getHeight(stack);
            case OverheadPoleAutoSettingsPayload.FIELD_CLEARANCE         -> AutoPlaceConfig.getClearance(stack);
            case OverheadPoleAutoSettingsPayload.FIELD_SPAN              -> AutoPlaceConfig.getSpan(stack);
            case OverheadPoleAutoSettingsPayload.FIELD_MULTI_TRACK_COUNT -> AutoPlaceConfig.getMultiTrackCount(stack);
            default -> 0;
        };
        int next = current + delta;
        PacketDistributor.sendToServer(new OverheadPoleAutoSettingsPayload(field, next));
        switch (field) {
            case OverheadPoleAutoSettingsPayload.FIELD_HEIGHT            -> AutoPlaceConfig.setHeight(stack, next);
            case OverheadPoleAutoSettingsPayload.FIELD_CLEARANCE         -> AutoPlaceConfig.setClearance(stack, next);
            case OverheadPoleAutoSettingsPayload.FIELD_SPAN              -> AutoPlaceConfig.setSpan(stack, next);
            case OverheadPoleAutoSettingsPayload.FIELD_MULTI_TRACK_COUNT -> AutoPlaceConfig.setMultiTrackCount(stack, next);
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
