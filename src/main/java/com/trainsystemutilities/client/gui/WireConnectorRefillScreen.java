package com.trainsystemutilities.client.gui;

import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.gui.WireConnectorMenu;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * 架線スプール装填画面 (= 架線設定の「架線を補充」ボタンから開く)。
 * 列車プリセットの粘着剤補充 ({@link TrainPresetRefillScreenV2}) と同じ JsonLayoutScreen + Menu 方式。
 * スロットに架線スプールを入れるとツール内タンクへ充填され、ゲージに反映される。
 */
public class WireConnectorRefillScreen extends JsonLayoutScreen<WireConnectorMenu>
        implements com.manta.api.wiki.WikiLocalMenu {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    /** タンクバーの内側幅 (= bar 幅 188 - border 1*2 = 186)。fill width 計算用。 */
    private static final int TANK_BAR_INNER_W = 186;

    public WireConnectorRefillScreen(WireConnectorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    /**
     * The wiki's stand-in (WikiLiveCapture): its tool is a demo wire connector, not the one the player holds, and a spool
     * put into the slot goes into that tool's tank here - the server's onInputChanged, which a client menu never runs
     * (改善1, 2026-10-07: the page refills the wire).
     */
    private ItemStack wikiTool = null;

    /** Makes this screen the wiki's stand-in, its tool a demo one holding some wire. */
    public void wikiStandIn() {
        wikiTool = new ItemStack(ModItems.WIRE_CONNECTOR.get());
        WireConnectorItem.addWireTank(wikiTool, 2 * com.trainsystemutilities.electrification.item.WireSpoolItem.METERS_PER_SPOOL);
    }

    /** The wiki's stand-in opens its throwaway menu to a playback: its slots are what the page presses. */
    @Override
    public boolean wikiLocalMenu() {
        return wikiTool != null;
    }

    private ItemStack tool() {
        return wikiTool != null ? wikiTool : findHeldToolClient();
    }

    /** On the stand-in, the slot's spools go into the demo tank as the server's menu puts them, one by one. */
    private void wikiTakeInput() {
        ItemStack input = getMenu().slots.get(0).getItem();
        if (input.isEmpty() || !input.is(ModItems.WIRE_SPOOL.get())) return;
        while (input.getCount() > 0 && WireConnectorItem.readWireTank(wikiTool) < WireConnectorItem.WIRE_TANK_MAX) {
            if (WireConnectorItem.addWireTank(wikiTool,
                    com.trainsystemutilities.electrification.item.WireSpoolItem.METERS_PER_SPOOL) <= 0) break;
            input.shrink(1);
        }
    }

    @Override
    protected String wikiPageId() { return "wire-connector"; }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (wikiTool != null) wikiTakeInput();
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/wire-connector-refill.json"); }

    // ===== Manta 7 push (Phase 4, TSU batch 1, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when the page
    // opens: the screen overrides no getDynamic*. The hint toggle and the entrance are the base's.
    private MantaState pushed;
    private com.manta.api.state.TextSlot tTank;
    private com.manta.api.state.NumberSlot nFill;

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tTank = pushed.text("refill-tank-label");
        nFill = pushed.number("tank-fill");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushAll() {
        if (pushed == null || !pushed.isOpen()) return;
        ItemStack tool = tool();
        int amount = tool.isEmpty() ? 0 : WireConnectorItem.readWireTank(tool);
        pushed.set(tTank, amount + " / " + WireConnectorItem.WIRE_TANK_MAX + " m");
        pushed.set(nFill, com.manta.api.render.Gauge.fillWidth(TANK_BAR_INNER_W, amount, WireConnectorItem.WIRE_TANK_MAX));
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        for (String c : classes) {
            if ("mc-popup-close".equals(c)) { onClose(); return; }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        var mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.keyInventory != null
                && mc.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 閉じたら架線設定画面に戻る。 */
    @Override
    protected void performClose() {
        super.performClose();
        Minecraft.getInstance().setScreen(new WireConnectorScreen());
    }

    private static ItemStack findHeldToolClient() {
        return com.manta.api.hud.HeldTools.find(Minecraft.getInstance().player, ModItems.WIRE_CONNECTOR.get());
    }
}
