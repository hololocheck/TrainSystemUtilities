package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.network.TrainPresetSaveAction;
import com.trainsystemutilities.client.ToolsClient;

import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.manta.api.controller.TextInputController;
import com.manta.api.render.TextCaretRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * プリセット保存画面 (JsonLayoutPlainScreen ベース)。
 *
 * 動的要素の解決:
 * - input text / placeholder: pushAll() writes save-name-input-placeholder - the hint while the
 *   name is empty, the name after (Manta 7 push)
 * - 点滅カーソル: drawCanvas で TextCaretRenderer.draw に委譲 (500ms blink)
 * - 保存/キャンセル/× クリック: onElementClick で classes 判定
 * - 保存ボタン disabled: name 空時に doSave() を no-op (見た目変更は V1 と同じく無し)
 */
public class TrainPresetSaveScreenV2 extends JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    @Override
    protected String wikiPageId() { return "train-preset-tool/save"; }

    private final TextInputController name =
            new TextInputController(TrainPresetSaveAction.MAX_NAME_CODE_POINTS, "")
                    .onSubmit(this::doSave)
                    .onEscape(this::onClose);

    public TrainPresetSaveScreenV2() {
        super(Component.translatable("tsu.save.screen_title"));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new TrainPresetSaveScreenV2());
    }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/train-preset-save.json"); }

    // ===== Manta 7 push (Phase 4, TSU batch 1, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when the page
    // opens: the screen overrides no getDynamic*. The hint toggle and the entrance are the base's.
    // The one span is the hint while the name is empty and the name after (its colour stays): keyed, so
    // the push says both - the hint is the layout's own translatableKey, tsu.save.placeholder.
    private MantaState pushed;
    private com.manta.api.state.TextSlot tPlaceholder;

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tPlaceholder = pushed.text("save-name-input-placeholder");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushAll() {
        if (pushed == null || !pushed.isOpen()) return;
        pushed.set(tPlaceholder, name.isEmpty()
                ? Component.translatable("tsu.save.placeholder").getString() : name.value());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        for (String c : classes) {
            if ("mc-popup-close".equals(c)) { onClose(); return; }
            if ("cancel-btn".equals(c)) { onClose(); return; }
            if ("save-btn".equals(c)) { doSave(); return; }
        }
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        if (!"save-caret".equals(key)) return;
        TextCaretRenderer.draw(g, this.font, name.value(), x + 2, y, w, h, 0xFF4FC3F7);
    }

    private void doSave() {
        String trimmed = name.value().trim();
        if (trimmed.isEmpty()) return;
        ToolsClient.presetSave(trimmed);
        onClose();
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return name.charTyped(codePoint) || super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (name.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void performClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
