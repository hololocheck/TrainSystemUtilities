package com.trainsystemutilities.client.gui;

import belugalab.experience.controller.PixelScrollViewport;
import belugalab.experience.controller.TileGrid;
import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import com.trainsystemutilities.network.BuyTicketPayload;
import com.trainsystemutilities.network.OpenTicketVendingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 券売機 UI (= 実駅の券売機風)。 行き先 (= 販売可な着駅) をボタン格子で表示し、
 * クリックで切符を発券する。 canvas 1 枚に Java 側から行き先ボタンを動的描画 + click 判定
 * ({@link StationGroupManageScreen} のタイルリストと同じ方式)。
 *
 * <p>行き先データは {@link OpenTicketVendingPayload} 開封時に server から渡されたスナップショット。
 * 発券は {@link BuyTicketPayload} で server に再検証させる。
 */
@OnlyIn(Dist.CLIENT)
public class TicketVendingMachineScreen extends JsonLayoutPlainScreen {

    /** 行き先ボタン格子: 列数 / ボタン高さ / 間隔 (px)。 */
    private static final int COLS = 3;
    private static final int BTN_H = 40;
    private static final int GRID_GAP = 6;
    /** 行き先グリッド canvas の表示高 (= layouts/ticket-vending-machine.json の tvm-grid h)。 */
    private static final int GRID_VIEWPORT_H = 254;

    private final BlockPos machinePos;
    private final String originName;
    private final List<OpenTicketVendingPayload.Dest> destinations;
    /** 行き先グリッドのスクロール (= §4.19 標準 PixelScrollViewport)。 */
    private final PixelScrollViewport gridScroll =
            new PixelScrollViewport(this::gridContentHeight, GRID_VIEWPORT_H);

    public TicketVendingMachineScreen(BlockPos machinePos, String originName,
                                      List<OpenTicketVendingPayload.Dest> destinations) {
        super(Component.translatable("tsu.ticket.title_no_station"));
        this.machinePos = machinePos;
        this.originName = originName == null ? "" : originName;
        this.destinations = destinations;
    }

    public static void open(BlockPos machinePos, String originName,
                            List<OpenTicketVendingPayload.Dest> destinations) {
        Minecraft.getInstance().setScreen(
                new TicketVendingMachineScreen(machinePos, originName, destinations));
    }

    @Override
    protected String wikiPageId() { return "structure/ticket-vending-machine"; }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/ticket-vending-machine.json"); }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        for (String c : classes) {
            if ("tvm-title".equals(c)) {
                return originName.isEmpty()
                        ? Component.translatable("tsu.ticket.title_no_station").getString()
                        : Component.translatable("tsu.ticket.title_fmt", originName).getString();
            }
            if ("tvm-count".equals(c)) {
                return Component.translatable("tsu.ticket.count_fmt", destinations.size()).getString();
            }
        }
        return null;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) belugalab.mcss3.wiki.Wiki.open(pid);
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
        int btnW = (rect[2] - (COLS - 1) * GRID_GAP) / COLS;
        if (btnW <= 0) return;
        // mouseX/mouseY/rect は共に dialog-relative
        TileGrid grid = new TileGrid(rect[0], rect[1], COLS, btnW + GRID_GAP, BTN_H + GRID_GAP, btnW, BTN_H);
        int idx = grid.hitTest(mouseX, mouseY, gridScroll.offset(), destinations.size());
        if (idx < 0) return;
        var d = destinations.get(idx);
        PacketDistributor.sendToServer(new BuyTicketPayload(machinePos, d.id()));
    }

    /** 行き先グリッド全行の高さ (px) = PixelScrollViewport の contentHeight。 */
    private int gridContentHeight() {
        return ((destinations.size() + COLS - 1) / COLS) * (BTN_H + GRID_GAP);
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
            int tw = this.font.width(empty);
            g.drawString(this.font, empty, x + (w - tw) / 2, y + h / 2 - 4, 0xFF888888, false);
            return;
        }
        int btnW = (w - (COLS - 1) * GRID_GAP) / COLS;
        if (btnW <= 0) return;
        TileGrid grid = new TileGrid(x, y, COLS, btnW + GRID_GAP, BTN_H + GRID_GAP, btnW, BTN_H);
        float ds = dialogScale();
        int sx1 = dialogX() + Math.round(x * ds), sy1 = dialogY() + Math.round(y * ds);
        g.enableScissor(sx1, sy1, sx1 + Math.round(w * ds), sy1 + Math.round(h * ds));
        for (int i = 0; i < destinations.size(); i++) {
            int bx = grid.tileX(i);
            int by = grid.tileY(i, gridScroll.offset());
            if (by + BTN_H < y || by > y + h) continue;
            boolean hover = mouseX >= bx && mouseX < bx + btnW
                    && mouseY >= by && mouseY < by + BTN_H
                    && mouseY >= y && mouseY < y + h;
            // 行き先ボタン (= hover で点灯)。 SmoothRenderer 二層角丸 (border 5f + bg 4f inset 1px)
            int bg = hover ? 0xFF234A66 : 0xFF14202E;
            int border = hover ? 0xFF4FC3F7 : 0xFF35506A;
            belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, bx, by, btnW, BTN_H, 5f, border);
            belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, bx + 1, by + 1, btnW - 2, BTN_H - 2, 4f, bg);
            // 着駅名 (中央、 幅オーバーは省略)
            String name = trimToWidth(destinations.get(i).name(), btnW - 10);
            int nw = this.font.width(name);
            g.drawString(this.font, name, bx + (btnW - nw) / 2, by + BTN_H / 2 - 9,
                    hover ? 0xFFFFFFFF : 0xFFE0E0E0, false);
            // 「発券」サブラベル
            String sub = Component.translatable("tsu.ticket.btn_buy").getString();
            int sw = this.font.width(sub);
            g.drawString(this.font, sub, bx + (btnW - sw) / 2, by + BTN_H / 2 + 2,
                    hover ? 0xFF80DEEA : 0xFF5C7A92, false);
        }
        g.disableScissor();
    }

    /** 幅 maxW を超える文字列を "…" で省略する。 */
    private String trimToWidth(String s, int maxW) {
        if (this.font.width(s) <= maxW) return s;
        String ell = "…";
        int ew = this.font.width(ell);
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = this.font.width(String.valueOf(s.charAt(i)));
            if (acc + cw + ew > maxW) break;
            sb.append(s.charAt(i));
            acc += cw;
        }
        return sb.append(ell).toString();
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
