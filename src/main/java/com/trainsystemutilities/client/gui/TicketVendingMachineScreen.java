package com.trainsystemutilities.client.gui;

import com.manta.api.controller.PixelScrollViewport;
import com.manta.api.controller.TileGrid;
import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.trainsystemutilities.network.VendingDest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 券売機 UI (= 実駅の券売機風)。 行き先 (= 販売可な着駅) をボタン格子で表示し、
 * クリックで切符を発券する。 canvas 1 枚に Java 側から行き先ボタンを動的描画 + click 判定
 * ({@link StationGroupManageScreen} のタイルリストと同じ方式)。
 *
 * <p>行き先データは開封時に server から渡されたスナップショット (本人の stations host の vending-* field、
 * network.StationsData.openVending — MANTA_7_CONCEPT C4 まで OpenTicketVendingPayload)。
 * 発券は buy action (network.BuyTicketAction) で server に再検証させる。
 */
@OnlyIn(Dist.CLIENT)
public class TicketVendingMachineScreen extends JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    /** 行き先ボタン格子: 列数 / ボタン高さ / 間隔 (px)。 */
    private static final int COLS = 3;
    private static final int BTN_H = 40;
    private static final int GRID_GAP = 6;
    /** 行き先グリッド canvas の表示高 (= layouts/ticket-vending-machine.json の tvm-grid h)。 */
    private static final int GRID_VIEWPORT_H = 254;

    private final BlockPos machinePos;
    private final String originName;
    private final List<VendingDest> destinations;
    /** 行き先グリッドのスクロール (= §4.19 標準 PixelScrollViewport)。 */
    private final PixelScrollViewport gridScroll =
            new PixelScrollViewport(this::gridContentHeight, GRID_VIEWPORT_H);

    public TicketVendingMachineScreen(BlockPos machinePos, String originName,
                                      List<VendingDest> destinations) {
        super(Component.translatable("tsu.ticket.title_no_station"));
        this.machinePos = machinePos;
        this.originName = originName == null ? "" : originName;
        this.destinations = destinations;
    }

    /** The wiki's stand-in: no machine, so a press on a destination sends nothing (改善1, 2026-10-07). */
    private boolean wikiMode = false;

    /**
     * The wiki's stand-in (WikiLiveCapture): from 東京, every other station of the 山手線 and the 中央線快速 as far as 四ツ谷 -
     * more destinations than the board shows at once, so the page can scroll it.
     */
    public static TicketVendingMachineScreen wikiCreate() {
        java.nio.charset.Charset u = java.nio.charset.StandardCharsets.UTF_8;
        List<VendingDest> dests = new java.util.ArrayList<>();
        for (String n : new String[] {"有楽町", "新橋", "浜松町", "田町", "高輪ゲートウェイ", "品川", "大崎", "五反田", "目黒",
                "恵比寿", "渋谷", "原宿", "代々木", "新宿", "新大久保", "高田馬場", "目白", "池袋", "大塚", "巣鴨", "駒込", "田端",
                "西日暮里", "日暮里", "鶯谷", "上野", "御徒町", "秋葉原", "神田", "御茶ノ水", "四ツ谷"}) {
            dests.add(new VendingDest(java.util.UUID.nameUUIDFromBytes(("tsu-wiki-ticket-" + n).getBytes(u)), n));
        }
        TicketVendingMachineScreen s = new TicketVendingMachineScreen(BlockPos.ZERO, "東京", dests);
        s.wikiMode = true;
        return s;
    }

    public static void open(BlockPos machinePos, String originName,
                            List<VendingDest> destinations) {
        Minecraft.getInstance().setScreen(
                new TicketVendingMachineScreen(machinePos, originName, destinations));
    }

    @Override
    protected String wikiPageId() { return "structure/ticket-vending-machine"; }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/ticket-vending-machine.json"); }

    // ===== Manta 7 push (Phase 4, TSU batch 1, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when the page
    // opens: the screen overrides no getDynamic*. The hint toggle and the entrance are the base's.
    private MantaState pushed;
    private com.manta.api.state.TextSlot tTitle, tCount;

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tTitle = pushed.text("tvm-title");
        tCount = pushed.text("tvm-count");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushAll() {
        if (pushed == null || !pushed.isOpen()) return;
        pushed.set(tTitle, originName.isEmpty()
                ? Component.translatable("tsu.ticket.title_no_station").getString()
                : Component.translatable("tsu.ticket.title_fmt", originName).getString());
        pushed.set(tCount, wikiBought != null && System.currentTimeMillis() < wikiBoughtUntil ? wikiBought
                : Component.translatable("tsu.ticket.count_fmt", destinations.size()).getString());
    }

    /**
     * The stand-in's purchase (改善1, second reading): what a real machine says in the action bar - the reader's HUD,
     * which the wiki must not write - said for a moment where the count is. The ticket itself goes nowhere.
     */
    private String wikiBought = null;
    private long wikiBoughtUntil = 0L;

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        if (com.manta.api.hud.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) com.manta.api.wiki.Wiki.open(pid);
                return;
            }
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            if ("tvm-grid".equals(c)) { handleGridClick(mouseX, mouseY, button); return; }
        }
    }

    private void handleGridClick(int mouseX, int mouseY, int button) {
        if (button != 0) return; // 左クリックのみ発券
        int[] rect = findElementByClass("tvm-grid");
        if (rect == null) return;
        // mouseX/mouseY/rect は共に dialog-relative
        TileGrid grid = grid(rect[0], rect[1], rect[2]);
        if (grid == null) return;
        int idx = grid.hitTest(mouseX, mouseY, gridScroll.offset(), destinations.size());
        if (idx < 0) return;
        var d = destinations.get(idx);
        if (wikiMode) {
            // The stand-in has no machine to buy from: it says what the real one says (BuyTicketAction), and sells nothing.
            wikiBought = Component.translatable("tsu.ticket.bought_fmt", d.name()).getString();
            wikiBoughtUntil = System.currentTimeMillis() + 3000L;
            return;
        }
        com.trainsystemutilities.client.StationsClient.buy(machinePos, d.id());
    }

    /** 行き先グリッド全行の高さ (px) = PixelScrollViewport の contentHeight。 */
    private int gridContentHeight() {
        // 幅は行数に関係しないので、ここだけは rect を引かない幅で良い。
        TileGrid grid = grid(0, 0, COLS * (BTN_H + GRID_GAP));
        return grid == null ? 0 : grid.contentHeight(destinations.size());
    }

    /** 行き先グリッドの幾何。3 箇所が同じ式を書いていたので 1 本にした。 */
    private TileGrid grid(int originX, int originY, int gridW) {
        int btnW = (gridW - (COLS - 1) * GRID_GAP) / COLS;
        if (btnW <= 0) return null;
        return new TileGrid(originX, originY, COLS, btnW + GRID_GAP, BTN_H + GRID_GAP, btnW, BTN_H);
    }

    @Override
    public boolean onElementWheel(String[] classes, String key,
                                  int mouseX, int mouseY, double scrollY) {
        if ("tvm-grid-scroll".equals(key)) {
            gridScroll.scroll(scrollY > 0 ? -16 : 16);  // PixelScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        return false;
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        if ("tvm-grid".equals(key)) {
            drawGrid(g, x, y, w, h, mouseX, mouseY);
        }
    }

    private void drawGrid(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (destinations.isEmpty()) {
            String empty = Component.translatable("tsu.ticket.no_destinations").getString();
            int tw = com.manta.api.text.MantaText.uiWidth(this.font, empty);
            com.manta.api.text.MantaText.draw(g, this.font, empty, x + (w - tw) / 2, y + h / 2 - 4, 0xFF888888);
            return;
        }
        TileGrid grid = grid(x, y, w);
        if (grid == null) return;
        int btnW = grid.tileW();
        int[] clip = com.manta.api.preview.GuiPreviewCamera.scissorRect(g.pose().last().pose(), x, y, w, h);
        g.enableScissor(clip[0], clip[1], clip[2], clip[3]);
        for (int i = 0; i < destinations.size(); i++) {
            int bx = grid.tileX(i);
            int by = grid.tileY(i, gridScroll.offset());
            if (by + BTN_H < y || by > y + h) continue;
            boolean hover = com.manta.api.hud.Rects.contains(mouseX, mouseY, bx, by, btnW, BTN_H)
                    && mouseY >= y && mouseY < y + h;
            // 行き先ボタン (= hover で点灯)。 SmoothRenderer 二層角丸 (border 5f + bg 4f inset 1px)
            int bg = hover ? 0xFF234A66 : 0xFF14202E;
            int border = hover ? 0xFF4FC3F7 : 0xFF35506A;
            com.manta.api.hud.HudChrome.innerRect(g, bx, by, btnW, BTN_H, bg, border);
            // 着駅名 (中央、 幅オーバーは省略)
            String name = trimToWidth(destinations.get(i).name(), btnW - 10);
            int nw = com.manta.api.text.MantaText.uiWidth(this.font, name);
            com.manta.api.text.MantaText.draw(g, this.font, name, bx + (btnW - nw) / 2, by + BTN_H / 2 - 9, hover ? 0xFFFFFFFF : 0xFFE0E0E0);
            // 「発券」サブラベル
            String sub = Component.translatable("tsu.ticket.btn_buy").getString();
            int sw = com.manta.api.text.MantaText.uiWidth(this.font, sub);
            com.manta.api.text.MantaText.draw(g, this.font, sub, bx + (btnW - sw) / 2, by + BTN_H / 2 + 2, hover ? 0xFF80DEEA : 0xFF5C7A92);
        }
        g.disableScissor();
    }

    /** 幅 maxW を超える文字列を "…" で省略する。
     *
     *  <p>一文字ずつ `uiWidth` を足してはいけない: `uiWidth` は呼ぶたびに切り上げるので、
     *  8 文字なら最大 8 px 手前で切れる (vanilla の advance は整数なので移行前は正確だった)。
     *  raster の advance でまとめて切る `MantaText.trimToWidth` に任せる (二次読み 2026-09-12)。 */
    private String trimToWidth(String s, int maxW) {
        if (com.manta.api.text.MantaText.uiWidth(this.font, s) <= maxW) return s;
        String ell = "…";
        int ew = com.manta.api.text.MantaText.uiWidth(this.font, ell);
        return com.manta.api.text.MantaText.trimToWidth(this.font, s, Math.max(0, maxW - ew)) + ell;
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
