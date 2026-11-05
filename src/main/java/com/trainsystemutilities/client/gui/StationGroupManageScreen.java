package com.trainsystemutilities.client.gui;

import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.manta.api.controller.PixelScrollViewport;
import com.manta.api.controller.TextInputController;
import com.manta.api.controller.TileGrid;
import com.manta.api.render.TextCaretRenderer;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * 駅グループ管理画面 (左 = タイル一覧, 右 = 編集パネル)。
 * 列車プリセットツール browse 画面と同じパターン: canvas 1 枚で
 * Java 側から動的にタイル群を描画 + click 判定。
 */
@OnlyIn(Dist.CLIENT)
public class StationGroupManageScreen extends JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

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
            new PixelScrollViewport(() -> groups().size() * (TILE_H + TILE_GAP), LIST_VIEWPORT_H);
    /** 削除確認ダイアログ表示フラグ。 */
    /** 共通の確認ダイアログ (R4.27.1)。レイアウトは manta の 1 枚、ここは文言だけ。 */
    private final com.manta.api.screen.ConfirmDialog<Void> deleteConfirm =
            com.manta.api.screen.ConfirmDialog.<Void>danger()
                    .title("tsu.station_tool.delete_confirm_title")
                    .yes("tsu.station_tool.delete_yes")
                    .body(ignored -> {
                        StationGroup g = selected();
                        return g == null ? "" : Component.translatable(
                                "tsu.station_tool.delete_prompt_fmt", g.name()).getString();
                    });

    public StationGroupManageScreen() {
        super(Component.translatable("tsu.station_tool.manage_title"));
    }

    public static void open() {
        // The list is the server-wide station groups host's, kept current since login (client.StationsClient).
        Minecraft.getInstance().setScreen(new StationGroupManageScreen());
    }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/station-group-manage.json"); }

    @Override
    protected String overlayJson() {
        if (deleteConfirm.isOpen()) {
            return com.manta.api.screen.MantaLayouts.load(
                    com.manta.api.screen.ConfirmDialog.LAYOUT);
        }
        return null;
    }

    private StationGroup selected() {
        if (selectedId == null) return null;
        for (var g : groups()) if (g.id().equals(selectedId)) return g;
        return null;
    }

    /** wiki キャプチャのサンプル ({@link #wikiApplyState})。null = サーバー同期の一覧 (実データ)。 */
    private java.util.List<StationGroup> wikiGroups = null;

    /** 一覧の出どころ。wiki キャプチャの state だけ画面ローカルのサンプル (グローバルなキャッシュには入れない)。 */
    private java.util.List<StationGroup> groups() {
        return wikiGroups != null ? wikiGroups : StationGroupClientCache.all();
    }

    /**
     * wiki キャプチャ用に state を当てる (WikiLiveCapture)。 Every state lists the sample groups, each with its platforms
     * (改善1, 2026-10-07: the page shows the list being managed - a group chosen, renamed, deleted after its confirmation -
     * where it showed an empty list); a rename or a delete changes the sample here, and nothing is sent (no
     * StationsClient call, no chat line). "delete" = 削除確認: 本文は選択中のグループ名なので、 サンプルの一覧から 1 つ選んで開く。
     */
    public void wikiApplyState(String state) {
        java.nio.charset.Charset u = java.nio.charset.StandardCharsets.UTF_8;
        java.util.List<StationGroup> samples = new java.util.ArrayList<>();
        String[] names = {"東京", "品川", "新宿"};
        int[] platforms = {4, 2, 3};
        for (int i = 0; i < names.length; i++) {
            net.minecraft.core.BlockPos min = new net.minecraft.core.BlockPos(i * 200, 64, 0);
            java.util.List<net.minecraft.core.BlockPos> at = new java.util.ArrayList<>();
            for (int p = 0; p < platforms[i]; p++) at.add(min.offset(8, 1, 3 + p * 4));
            samples.add(new StationGroup(UUID.nameUUIDFromBytes(("tsu-wiki-station-group-" + i).getBytes(u)),
                    names[i], "minecraft:overworld", min, min.offset(40, 12, 16),
                    java.util.List.of(), java.util.List.copyOf(at), StationGroup.NAMING_TEMPLATE_JP, null));
        }
        wikiGroups = samples;
        if (!"delete".equals(state)) return;
        StationGroup g = wikiGroups.get(0);
        selectedId = g.id();
        editingName.setValue(g.name());
        deleteConfirm.ask();
    }

    /** A group's platform count: the sample's own positions in the wiki, else the stations host's answer. */
    private int stationCount(StationGroup g) {
        return wikiGroups != null ? g.stationBlockPositions().size()
                : com.trainsystemutilities.station.StationGroupClientCache.stationCount(g.id());
    }

    /** A group's platforms: the sample's own positions in the wiki, else the stations host's answer. */
    private java.util.List<net.minecraft.core.BlockPos> positions(StationGroup g) {
        return wikiGroups != null ? g.stationBlockPositions()
                : com.trainsystemutilities.station.StationGroupClientCache.positions(g.id());
    }

    // ===== Manta 7 push (Phase 4, TSU batch 2, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when a page
    // opens: the screen overrides no getDynamic*. The hint toggle and the entrances are the base's.
    // The delete confirmation's page is ConfirmDialog's (push). The button labels' widths are
    // LabelWidth's (R4.23.1: glyphs became icon + label).
    private MantaState pushed, confirmPage;
    private com.manta.api.state.TextSlot tName, tInfo, tRange;
    private com.manta.api.state.ColorSlot cNameBorder;
    private com.manta.api.state.BoolSlot bDetail, bNoSelection;
    private com.manta.api.state.NumberSlot nApplyW, nDeleteW;

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer == PageLayer.OVERLAY) {
            // The one overlay this screen shows is the delete confirmation (overlayJson).
            confirmPage = MantaState.of(page);
            deleteConfirm.push(confirmPage);
            return;
        }
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tName = pushed.text("sgm-name-value");
        tInfo = pushed.text("sgm-info");
        tRange = pushed.text("sgm-range");
        cNameBorder = pushed.color("sgm-name-border");
        bDetail = pushed.bool("sgm-detail-visible");
        bNoSelection = pushed.bool("sgm-no-selection-visible");
        nApplyW = pushed.number("btn-apply-label-w");
        nDeleteW = pushed.number("btn-delete-label-w");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushAll() {
        if (confirmPage != null && confirmPage.isOpen()) deleteConfirm.push(confirmPage);
        if (pushed == null || !pushed.isOpen()) return;
        StationGroup g = selected();
        pushed.set(tName, editingName.value());
        if (g == null) {
            pushed.clear(tInfo);
            pushed.clear(tRange);
        } else {
            pushed.set(tInfo, Component.translatable("tsu.station_tool.platforms_count_fmt", stationCount(g)).getString());
            pushed.set(tRange, String.format("(%d,%d,%d) ~ (%d,%d,%d)",
                    g.minPos().getX(), g.minPos().getY(), g.minPos().getZ(),
                    g.maxPos().getX(), g.maxPos().getY(), g.maxPos().getZ()));
        }
        if (nameFocused) {
            pushed.set(cNameBorder, 0xFF4FC3F7);
        } else {
            pushed.clear(cNameBorder);
        }
        pushed.set(bDetail, g != null);
        pushed.set(bNoSelection, g == null);
        pushed.set(nApplyW, LabelWidth.of("tsu.station_tool.btn_apply"));
        pushed.set(nDeleteW, LabelWidth.of("tsu.station_tool.btn_delete"));
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // 確認ダイアログが開いている間は背後に通さない (部品側の約束)。
        if (deleteConfirm.handleClick(classes, ignored -> deleteSelected())) return;
        if (com.manta.api.hud.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) com.manta.api.wiki.Wiki.open(pid);
                return;
            }
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            // name input の click も caret canvas の click も focus とみなす
            if ("sgm-name-input".equals(c) || "sgm-name-caret".equals(c)) {
                nameFocused = true;
                return;
            }
            if ("sgm-rename-btn".equals(c)) { applyRename(); return; }
            if ("sgm-delete-btn".equals(c)) { deleteConfirm.ask(); return; }
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
        var groups = groups();
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
            deleteConfirm.ask();
        } else {
            selectedId = g.id();
            editingName.setValue(g.name());
            nameFocused = false;
            // The group's station positions are not in the list; ask for this one's (a wiki sample carries its own).
            if (wikiGroups == null) com.trainsystemutilities.client.StationsClient.detail(g.id());
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
        var groups = groups();
        TileGrid grid = new TileGrid(x, y, 1, w, TILE_H + TILE_GAP, w, TILE_H);
        int hovered = grid.hitTest(mouseX, mouseY, listScroll.offset(), groups.size());
        // 外側にはみ出さないよう clip する。矩形は canvas-local のまま渡し、原点と縮尺は
        // pose から取る (原点を名前で書くと overlay 上で静かにずれる)。
        int[] clip = com.manta.api.preview.GuiPreviewCamera.scissorRect(g.pose().last().pose(), x, y, w, h);
        g.enableScissor(clip[0], clip[1], clip[2], clip[3]);
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
            com.manta.api.hud.HudChrome.innerRect(g, x, tileY, w, TILE_H, bg, border);
            // 名前
            com.manta.api.text.MantaText.draw(g, this.font, grp.name(), x + 6, tileY + 3,
                    active ? 0xFFFFFFFF : 0xFFE0E0E0);
            // 駅数
            String count = stationCount(grp) + "P";
            int cw = com.manta.api.text.MantaText.uiWidth(this.font, count);
            com.manta.api.text.MantaText.draw(g, this.font, count, x + w - cw - 6, tileY + 12, 0xFF80DEEA);
        }
        g.disableScissor();
        if (groups.isEmpty()) {
            String empty = Component.translatable("tsu.station_tool.no_groups").getString();
            int tw = com.manta.api.text.MantaText.uiWidth(this.font, empty);
            com.manta.api.text.MantaText.draw(g, this.font, empty, x + (w - tw) / 2, y + h / 2 - 4, 0xFF888888);
        }
    }

    private void drawPlatformList(GuiGraphics g, int x, int y, int w, int h) {
        StationGroup grp = selected();
        if (grp == null) return;
        int[] clip = com.manta.api.preview.GuiPreviewCamera.scissorRect(g.pose().last().pose(), x, y, w, h);
        g.enableScissor(clip[0], clip[1], clip[2], clip[3]);
        int rowH = 11;
        // The positions are the manage screen's own ask (selectGroup), answered on the player's stations host.
        var positions = positions(grp);
        for (int i = 0; i < positions.size(); i++) {
            int ry = y + i * rowH;
            if (ry > y + h) break;
            var p = positions.get(i);
            String s = String.format("%d  →  (%d, %d, %d)", i + 1, p.getX(), p.getY(), p.getZ());
            com.manta.api.text.MantaText.draw(g, this.font, s, x, ry, 0xFFAAAAAA);
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
        if (deleteConfirm.isOpen()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_N) {
                deleteConfirm.close(); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Y || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                deleteConfirm.close(); deleteSelected(); return true;
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
        if (wikiGroups != null) {
            // The wiki's sample: renamed here, nothing sent, no chat line.
            wikiGroups.set(wikiGroups.indexOf(g), new StationGroup(g.id(), newName, g.dimensionId(), g.minPos(),
                    g.maxPos(), g.stationBlockIds(), g.stationBlockPositions(), g.namingTemplate(), g.ownerUUID()));
            nameFocused = false;
            return;
        }
        com.trainsystemutilities.client.StationsClient.rename(g.id(), newName);
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
        if (wikiGroups != null) wikiGroups.remove(g); // the wiki's sample: nothing sent
        else com.trainsystemutilities.client.StationsClient.delete(g.id());
        selectedId = null;
        editingName.clear();
        nameFocused = false;
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
