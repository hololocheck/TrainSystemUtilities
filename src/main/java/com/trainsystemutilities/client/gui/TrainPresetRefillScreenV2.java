package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutScreen;
import com.trainsystemutilities.gui.TrainPresetRefillMenu;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.network.TrainPresetGlueDumpPayload;
import com.trainsystemutilities.network.TrainPresetListRequestPayload;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * プリセットツール充填画面 (JsonLayoutScreen ベース)。
 *
 * 動的要素の仕組み:
 * - タンク値ラベル "0 / 10000": getDynamicText() で .refill-tank-label を上書き
 * - タンクゲージ fill 幅:       getDynamicNumber("tank-fill") で px を返す
 * - × クローズボタン:           onElementClick() で .mc-popup-close を検知 → onClose()
 * - ダンプボタン:                onElementClick() で .refill-dump-btn を検知 → server payload
 * - hover 色変化 (× / ダンプ / slot): JSON 内 hoverBg / hoverColor / hoverBorderColor で表現
 */
public class TrainPresetRefillScreenV2 extends JsonLayoutScreen<TrainPresetRefillMenu> {

    @Override
    protected String wikiPageId() { return "train-preset-tool/refill"; }

    /** タンクバーの内側幅 (= bar 幅 60 - border 1*2 = 58)。fill width 計算に使う。 */
    private static final int TANK_BAR_INNER_W = 58;

    public TrainPresetRefillScreenV2(TrainPresetRefillMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    /** ダイアログ開封アニメ中はインベントリアイテム非表示。 */
    private long screenOpenNano = 0L;
    private static final long OPEN_ANIM_NS = 180_000_000L;

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (screenOpenNano == 0L) screenOpenNano = System.nanoTime();
        super.render(g, mouseX, mouseY, partialTick);
    }

    // Phase 5f++ FIX: renderSlot override 削除済 (base JsonLayoutScreen.renderSlot と
    // 完全同じ実装だったため重複)。 base が同 entrance anim を per-slot scale で適用する。

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/train-preset-refill.json"); }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        for (String c : classes) {
            if ("refill-tank-label".equals(c)) {
                ItemStack tool = findHeldToolClient();
                int amount = tool.isEmpty() ? 0 : TrainPresetToolItem.getGlueTank(tool);
                return amount + " / " + TrainPresetToolItem.GLUE_TANK_MAX;
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("tank-fill".equals(key)) {
            ItemStack tool = findHeldToolClient();
            int amount = tool.isEmpty() ? 0 : TrainPresetToolItem.getGlueTank(tool);
            int max = TrainPresetToolItem.GLUE_TANK_MAX;
            int pct = max == 0 ? 0 : amount * 100 / max;
            return Math.max(0, Math.min(TANK_BAR_INNER_W, TANK_BAR_INNER_W * pct / 100));
        }
        return null;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        for (String c : classes) {
            if ("mc-popup-close".equals(c)) {
                onClose();
                return;
            }
            if ("refill-dump-btn".equals(c)) {
                PacketDistributor.sendToServer(new TrainPresetGlueDumpPayload());
                return;
            }
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

    @Override
    protected void performClose() {
        super.performClose();
        var mc = Minecraft.getInstance();
        if (mc != null) {
            // closed (preset-place) 側の browse へ reflection で戻る — online 群を含まないビルドでは no-op
            try {
                Class.forName("com.trainsystemutilities.client.gui.TrainPresetBrowseScreenV2")
                        .getMethod("open").invoke(null);
            } catch (Throwable ignored) {
            }
            PacketDistributor.sendToServer(new TrainPresetListRequestPayload());
        }
    }

    private static ItemStack findHeldToolClient() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return ItemStack.EMPTY;
        var main = mc.player.getMainHandItem();
        if (main.is(ModItems.TRAIN_PRESET_TOOL.get())) return main;
        var off = mc.player.getOffhandItem();
        if (off.is(ModItems.TRAIN_PRESET_TOOL.get())) return off;
        return ItemStack.EMPTY;
    }
}
