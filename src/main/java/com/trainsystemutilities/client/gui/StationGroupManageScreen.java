package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import belugalab.experience.controller.PixelScrollViewport;
import belugalab.experience.controller.TextInputController;
import belugalab.experience.controller.TileGrid;
import belugalab.experience.render.TextCaretRenderer;
import com.trainsystemutilities.network.StationGroupDeletePayload;
import com.trainsystemutilities.network.StationGroupListRequestPayload;
import com.trainsystemutilities.network.StationGroupRenamePayload;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * 駅グループ管理画面 (左 = タイル一覧, 右 = 編集パネル)。
 * 列車プリセットツール browse 画面と同じパターン: canvas 1 枚で
 * Java 側から動的にタイル群を描画 + click 判定。
 */
@OnlyIn(Dist.CLIENT)
public class StationGroupManageScreen extends JsonLayoutPlainScreen {

    @Override
    protected String wikiPageId() { return "tools/station-range-tool"; }

    private static final int TILE_H = 22;
    private static final int TILE_GAP = 2;
    /** sgm-list canvas の表示高 (= station-group-manage.json の sgm-list h)。 */
    private static final int LIST_VIEWPORT_H = 275;

    /** 選択中のグループ ID (null = 未選択)。 */
    private UUID selectedId = null;
    /** 編集中の name (リネーム適用前)。Enter で apply / focus はカスタムフラグで管理。 */
    private final TextInputController editingName =
            new TextInputController(64, "").onSubmit(this::applyRename);
    private boolean nameFocused = false;
    /** タイルリストのスクロール (= §4.19 標準 PixelScrollViewport)。 */
    private final PixelScrollViewport listScroll =
            new PixelScrollViewport(() -> StationGroupClientCache.all().size() * (TILE_H + TILE_GAP), LIST_VIEWPORT_H);
    /** 削除確認ダイアログ表示フラグ。 */
    private boolean showDeleteConfirm = false;

    public StationGroupManageScreen() {
        super(Component.translatable("tsu.station_tool.manage_title"));
    }

    public static void open() {
        // 開いた時点で最新リストを要求 (push されているはずだが念のため)
        PacketDistributor.sendToServer(new StationGroupListRequestPayload());
        Minecraft.getInstance().setScreen(new StationGroupManageScreen());
    }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/station-group-manage.json"); }

    @Override
    protected String overlayJson() {
        if (showDeleteConfirm) {
            return TsuLayouts.load("layouts/station-group-manage-delete.json");
        }
        return null;
    }

    private StationGroup selected() {
        if (selectedId == null) return null;
        for (var g : StationGroupClientCache.all()) if (g.id().equals(selectedId)) return g;
        return null;
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        if ("sgm-detail-visible".equals(key)) return selected() != null;
        if ("sgm-no-selection-visible".equals(key)) return selected() == null;
        return defaultValue;
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        StationGroup g = selected();
        for (String c : classes) {
            if ("sgm-name-value".equals(c)) return editingName.value();
            if ("sgm-info".equals(c) && g != null) {
                return Component.translatable("tsu.station_tool.platforms_count_fmt",
                        g.stationBlockPositions().size()).getString();
            }
            if ("sgm-range".equals(c) && g != null) {
                return String.format("(%d,%d,%d) ~ (%d,%d,%d)",
                        g.minPos().getX(), g.minPos().getY(), g.minPos().getZ(),
                        g.maxPos().getX(), g.maxPos().getY(), g.maxPos().getZ());
            }
            if ("sgm-del-prompt".equals(c) && g != null) {
                return Component.translatable("tsu.station_tool.delete_prompt_fmt", g.name()).getString();
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        if ("sgm-name-border".equals(key) && nameFocused) return 0xFF4FC3F7;
        return null;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // 削除確認 overlay 中はその buttons を最優先処理
        if (showDeleteConfirm) {
            for (String c : classes) {
                if ("sgm-del-popup-close".equals(c) || "mc-popup-close".equals(c)
                        || "sgm-del-no".equals(c)) {
                    showDeleteConfirm = false;
                    return;
                }
                if ("sgm-del-yes".equals(c)) {
                    deleteSelected();
                    showDeleteConfirm = false;
                    return;
                }
            }
            return; // overlay 外クリックは無視 (close 操作のみ受付)
        }
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) belugalab.mcss3.wiki.Wiki.open(pid);
                return;
            }
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            // name input の click も caret canvas の click も focus とみなす
            if ("sgm-name-input".equals(c) || "sgm-name-caret".equals(c)) {
                nameFocused = true;
                return;
            }
            if ("sgm-rename-btn".equals(c)) { applyRename(); return; }
            if ("sgm-delete-btn".equals(c)) { showDeleteConfirm = true; return; }
            if ("sgm-list".equals(c)) {
                // タイルクリック / 右クリック削除
                handleListClick(mouseX, mouseY, button);
                return;
            }
        }
        // クリックが name input/caret の外なら focus 解除
        boolean inName = false;
        for (String c : classes) {
            if ("sgm-name-input".equals(c) || "sgm-name-caret".equals(c)) inName = true;
        }
        if (!inName) nameFocused = false;
    }

    private void handleListClick(int mouseX, int mouseY, int button) {
        int[] rect = findElementByClass("sgm-list");
        if (rect == null) return;
        // mouseX/mouseY は dialog-relative (JsonLayoutPlainScreen が screen→dialog 変換済)
        // rect も dialog-relative なので dialogX/Y を足してはいけない。
        var groups = StationGroupClientCache.all();
        TileGrid grid = new TileGrid(rect[0], rect[1], 1, rect[2], TILE_H + TILE_GAP, rect[2], TILE_H);
        int idx = grid.hitTest(mouseX, mouseY, listScroll.offset(), groups.size());
        if (idx < 0) return;
        StationGroup g = groups.get(idx);
        if (button == 1) {
            // 右クリック: 削除確認ダイアログを開く (左クリック選択と同じく selectedId を更新して
            // confirm prompt にグループ名が表示されるようにする)
            selectedId = g.id();
            editingName.setValue(g.name());
            nameFocused = false;
            showDeleteConfirm = true;
        } else {
            selectedId = g.id();
            editingName.setValue(g.name());
            nameFocused = false;
        }
    }

    @Override
    public boolean onElementWheel(String[] classes, String key,
                                  int mouseX, int mouseY, double scrollY) {
        if ("sgm-list-scroll".equals(key)) {
            listScroll.scroll(scrollY > 0 ? -16 : 16);  // PixelScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        return false;
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        if ("sgm-list".equals(key)) {
            drawTileList(g, x, y, w, h, mouseX, mouseY);
            return;
        }
        if ("sgm-name-caret".equals(key)) {
            if (!nameFocused) return;
            TextCaretRenderer.draw(g, this.font, editingName.value(), x, y, w, h, 0xFF4FC3F7);
            return;
        }
        if ("sgm-platforms".equals(key)) {
            drawPlatformList(g, x, y, w, h);
            return;
        }
    }

    private void drawTileList(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        var groups = StationGroupClientCache.all();
        TileGrid grid = new TileGrid(x, y, 1, w, TILE_H + TILE_GAP, w, TILE_H);
        int hovered = grid.hitTest(mouseX, mouseY, listScroll.offset(), groups.size());
        // scissor で外側にはみ出さないようにする (screen 座標が必要)
        float ds = dialogScale();
        int sx1 = dialogX() + Math.round(x * ds), sy1 = dialogY() + Math.round(y * ds);
        int sx2 = sx1 + Math.round(w * ds), sy2 = sy1 + Math.round(h * ds);
        g.enableScissor(sx1, sy1, sx2, sy2);
        for (int i = 0; i < groups.size(); i++) {
            var grp = groups.get(i);
            int tileY = grid.tileY(i, listScroll.offset());
            if (tileY + TILE_H < y || tileY > y + h) continue;
            // mouseX/mouseY も tileY も dialog-relative なのでそのまま比較
            boolean hover = (hovered == i);
            boolean active = grp.id().equals(selectedId);
            int bg = active ? 0xFF1A3050 : (hover ? 0xFF2A2A40 : 0xFF1A1A2A);
            int border = active ? 0xFF4FC3F7 : 0xFF333344;
            // §2.4 SmoothRenderer 二層角丸 (border 5f + bg 4f inset 1px、 つよめ)。 旧 g.fill 4枚枠を置換
            belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, x, tileY, w, TILE_H, 5f, border);
            belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, x + 1, tileY + 1, w - 2, TILE_H - 2, 4f, bg);
            // 名前
            g.drawString(this.font, grp.name(), x + 6, tileY + 3,
                    active ? 0xFFFFFFFF : 0xFFE0E0E0, false);
            // 駅数
            String count = grp.stationBlockPositions().size() + "P";
            int cw = this.font.width(count);
            g.drawString(this.font, count, x + w - cw - 6, tileY + 12, 0xFF80DEEA, false);
        }
        g.disableScissor();
        if (groups.isEmpty()) {
            String empty = Component.translatable("tsu.station_tool.no_groups").getString();
            int tw = this.font.width(empty);
            g.drawString(this.font, empty, x + (w - tw) / 2, y + h / 2 - 4, 0xFF888888, false);
        }
    }

    private void drawPlatformList(GuiGraphics g, int x, int y, int w, int h) {
        StationGroup grp = selected();
        if (grp == null) return;
        float ds = dialogScale();
        int sx1 = dialogX() + Math.round(x * ds), sy1 = dialogY() + Math.round(y * ds);
        g.enableScissor(sx1, sy1, sx1 + Math.round(w * ds), sy1 + Math.round(h * ds));
        int rowH = 11;
        for (int i = 0; i < grp.stationBlockPositions().size(); i++) {
            int ry = y + i * rowH;
            if (ry > y + h) break;
            var p = grp.stationBlockPositions().get(i);
            String s = String.format("%d  →  (%d, %d, %d)", i + 1, p.getX(), p.getY(), p.getZ());
            g.drawString(this.font, s, x, ry, 0xFFAAAAAA, false);
        }
        g.disableScissor();
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!nameFocused) return super.charTyped(codePoint, modifiers);
        if (editingName.charTyped(codePoint)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 削除確認 overlay 中: Y = 削除, N/ESC = キャンセル
        if (showDeleteConfirm) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_N) {
                showDeleteConfirm = false; return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Y || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                deleteSelected(); showDeleteConfirm = false; return true;
            }
            return true;
        }
        // focus 中は controller に backspace/enter を委譲。ESC は screen の onClose を優先。
        if (nameFocused && keyCode != GLFW.GLFW_KEY_ESCAPE && editingName.keyPressed(keyCode)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void applyRename() {
        StationGroup g = selected();
        if (g == null) return;
        String newName = editingName.value().trim();
        if (newName.isEmpty() || newName.equals(g.name())) return;
        PacketDistributor.sendToServer(new StationGroupRenamePayload(g.id(), newName));
        nameFocused = false;
        // displayClientMessage で簡易フィードバック
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("tsu.station_tool.rename_sent_fmt", newName)
                            .withStyle(ChatFormatting.AQUA), true);
        }
    }

    private void deleteSelected() {
        StationGroup g = selected();
        if (g == null) return;
        PacketDistributor.sendToServer(new StationGroupDeletePayload(g.id()));
        selectedId = null;
        editingName.clear();
        nameFocused = false;
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
