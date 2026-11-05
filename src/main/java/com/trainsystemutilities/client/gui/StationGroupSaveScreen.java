package com.trainsystemutilities.client.gui;

import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.manta.api.controller.TextInputController;
import com.manta.api.render.TextCaretRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * 駅グループ作成画面 (シンプル版: 駅グループ名のみ入力)。
 * <p>路線記号 / 枠色は管理用コンピューターで割り当てるため、ここでは扱わない。
 */
@OnlyIn(Dist.CLIENT)
public class StationGroupSaveScreen extends JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    @Override
    protected String wikiPageId() { return null; }

    private final TextInputController name =
            new TextInputController(64, "")
                    .onSubmit(this::doSave)
                    .onEscape(this::onClose);
    private final BlockPos pos1;
    private final BlockPos pos2;

    public StationGroupSaveScreen(BlockPos p1, BlockPos p2) {
        super(Component.translatable("tsu.station_tool.save_title"));
        this.pos1 = p1;
        this.pos2 = p2;
    }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/station-group-save.json"); }

    // ===== Manta 7 push (Phase 4, TSU batch 1, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when the page
    // opens: the screen overrides no getDynamic*. The hint toggle and the entrance are the base's.
    private MantaState pushed;
    private com.manta.api.state.TextSlot tName, tStatus;
    private com.manta.api.state.ColorSlot cBorder;

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tName = pushed.text("sg-name-input-value");
        tStatus = pushed.text("sg-status");
        cBorder = pushed.color("sg-name-input-border");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushAll() {
        if (pushed == null || !pushed.isOpen()) return;
        pushed.set(tName, name.value());
        pushed.set(tStatus, String.format("(%d,%d,%d) ~ (%d,%d,%d)",
                pos1.getX(), pos1.getY(), pos1.getZ(),
                pos2.getX(), pos2.getY(), pos2.getZ()));
        pushed.clear(cBorder);   // nothing answered it: the box's own border
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        for (String c : classes) {
            if ("mc-popup-close".equals(c) || "sg-cancel-btn".equals(c)) { onClose(); return; }
            if ("sg-save-btn".equals(c)) { doSave(); return; }
        }
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        if (!"sg-name-input-caret".equals(key)) return;
        TextCaretRenderer.draw(g, this.font, name.value(), x, y, w, h, 0xFF4FC3F7);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (name.charTyped(codePoint)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (name.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void doSave() {
        String n = name.value().trim();
        if (n.isEmpty()) return;
        // The range, the creator's position and the numbering direction are the server's (the held tool's state
        // and the player's position, TSU-NET-001): the create action carries the name alone.
        com.trainsystemutilities.client.StationsClient.create(n);
        onClose();
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
