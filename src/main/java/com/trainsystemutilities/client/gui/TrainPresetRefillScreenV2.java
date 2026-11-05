package com.trainsystemutilities.client.gui;

import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.trainsystemutilities.gui.TrainPresetRefillMenu;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.client.ToolsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * プリセットツール充填画面 (JsonLayoutScreen ベース)。
 *
 * 動的要素の仕組み:
 * - tank label "0 / 10000":    pushAll() writes refill-tank-label (Manta 7 push)
 * - tank gauge fill width:     pushAll() writes tank-fill in px
 * - × クローズボタン:           onElementClick() で .mc-popup-close を検知 → onClose()
 * - ダンプボタン:                onElementClick() で .refill-dump-btn を検知 → server payload
 * - hover 色変化 (× / ダンプ / slot): JSON 内 hoverBg / hoverColor / hoverBorderColor で表現
 */
public class TrainPresetRefillScreenV2 extends JsonLayoutScreen<TrainPresetRefillMenu>
        implements com.manta.api.wiki.WikiLocalMenu {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    @Override
    protected String wikiPageId() { return "train-preset-tool/refill"; }

    /** タンクバーの内側幅 (= bar 幅 60 - border 1*2 = 58)。fill width 計算に使う。 */
    private static final int TANK_BAR_INNER_W = 58;

    public TrainPresetRefillScreenV2(TrainPresetRefillMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    /**
     * The wiki's stand-in (WikiLiveCapture.createRefill): its tool is the demo one (TrainPresetWikiDemo), not the one the
     * player holds, and the glue an input takes is added to it here - the server's onInputChanged, which a client menu
     * never runs (改善1, 2026-10-07: the page puts slime into the slot and the tank fills).
     */
    private ItemStack wikiTool = null;

    /** Makes this screen the wiki's stand-in, its tool the demo one. */
    public void wikiStandIn() {
        wikiTool = TrainPresetWikiDemo.tool();
    }

    /** The wiki's stand-in opens its throwaway menu to a playback: its slots are what the page presses. */
    @Override
    public boolean wikiLocalMenu() {
        return wikiTool != null;
    }

    /** The tool this screen shows the tank of: the demo one on the wiki's stand-in, else the one the player holds. */
    private ItemStack tool() {
        return wikiTool != null ? wikiTool : findHeldToolClient();
    }

    /** On the stand-in, the input slot's slime goes into the demo tank as the server's menu would put it (per item). */
    private void wikiTakeInput() {
        ItemStack input = getMenu().slots.get(0).getItem();
        if (input.isEmpty()) return;
        int perItem = input.is(net.minecraft.world.item.Items.SLIME_BALL) ? TrainPresetToolItem.GLUE_PER_SLIME_BALL
                : input.is(net.minecraft.world.item.Items.SLIME_BLOCK) ? TrainPresetToolItem.GLUE_PER_SLIME_BLOCK : 0;
        if (perItem == 0) return;
        while (input.getCount() > 0
                && TrainPresetToolItem.GLUE_TANK_MAX - TrainPresetToolItem.getGlueTank(wikiTool) >= perItem) {
            if (TrainPresetToolItem.addGlueTank(wikiTool, perItem) <= 0) break;
            input.shrink(1);
        }
    }

    /** ダイアログ開封アニメ中はインベントリアイテム非表示。 */
    private long screenOpenNano = 0L;
    private static final long OPEN_ANIM_NS = 180_000_000L;

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (screenOpenNano == 0L) screenOpenNano = System.nanoTime();
        if (wikiTool != null) wikiTakeInput();
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    // Phase 5f++ FIX: renderSlot override 削除済 (base JsonLayoutScreen.renderSlot と
    // 完全同じ実装だったため重複)。 base が同 entrance anim を per-slot scale で適用する。

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/train-preset-refill.json"); }

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
        int amount = tool.isEmpty() ? 0 : TrainPresetToolItem.getGlueTank(tool);
        pushed.set(tTank, amount + " / " + TrainPresetToolItem.GLUE_TANK_MAX);
        pushed.set(nFill, com.manta.api.render.Gauge.fillWidth(TANK_BAR_INNER_W, amount, TrainPresetToolItem.GLUE_TANK_MAX));
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        for (String c : classes) {
            if ("mc-popup-close".equals(c)) {
                onClose();
                return;
            }
            if ("refill-dump-btn".equals(c)) {
                ToolsClient.presetGlueDump();
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
            com.trainsystemutilities.client.PresetsClient.requestList();
        }
    }

    private static ItemStack findHeldToolClient() {
        return com.manta.api.hud.HeldTools.find(
                Minecraft.getInstance().player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
