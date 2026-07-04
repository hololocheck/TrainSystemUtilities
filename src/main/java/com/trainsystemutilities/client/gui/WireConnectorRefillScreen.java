package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutScreen;
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
public class WireConnectorRefillScreen extends JsonLayoutScreen<WireConnectorMenu> {

    /** タンクバーの内側幅 (= bar 幅 188 - border 1*2 = 186)。fill width 計算用。 */
    private static final int TANK_BAR_INNER_W = 186;

    public WireConnectorRefillScreen(WireConnectorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected String wikiPageId() { return "wire-connector"; }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/wire-connector-refill.json"); }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        for (String c : classes) {
            if ("refill-tank-label".equals(c)) {
                ItemStack tool = findHeldToolClient();
                int amount = tool.isEmpty() ? 0 : WireConnectorItem.readWireTank(tool);
                return amount + " / " + WireConnectorItem.WIRE_TANK_MAX + " m";
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("tank-fill".equals(key)) {
            ItemStack tool = findHeldToolClient();
            int amount = tool.isEmpty() ? 0 : WireConnectorItem.readWireTank(tool);
            int max = WireConnectorItem.WIRE_TANK_MAX;
            int pct = max == 0 ? 0 : amount * 100 / max;
            return Math.max(0, Math.min(TANK_BAR_INNER_W, TANK_BAR_INNER_W * pct / 100));
        }
        return null;
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
        var mc = Minecraft.getInstance();
        if (mc.player == null) return ItemStack.EMPTY;
        var main = mc.player.getMainHandItem();
        if (main.is(ModItems.WIRE_CONNECTOR.get())) return main;
        var off = mc.player.getOffhandItem();
        if (off.is(ModItems.WIRE_CONNECTOR.get())) return off;
        return ItemStack.EMPTY;
    }
}
