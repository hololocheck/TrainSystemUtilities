package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import belugalab.experience.controller.TextInputController;
import belugalab.experience.render.TextCaretRenderer;
import com.trainsystemutilities.network.TrainPresetSavePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * プリセット保存画面 (JsonLayoutPlainScreen ベース)。
 *
 * 動的要素の解決:
 * - 入力欄テキスト / プレースホルダー: getDynamicText で .save-name-input-value
 *   と .save-name-input-placeholder を name の空/非空で出し分け
 * - 点滅カーソル: drawCanvas で TextCaretRenderer.draw に委譲 (500ms blink)
 * - 保存/キャンセル/× クリック: onElementClick で classes 判定
 * - 保存ボタン disabled: name 空時に doSave() を no-op (見た目変更は V1 と同じく無し)
 */
public class TrainPresetSaveScreenV2 extends JsonLayoutPlainScreen {

    @Override
    protected String wikiPageId() { return "train-preset-tool/save"; }

    private final TextInputController name =
            new TextInputController(64, "")
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

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        boolean empty = name.isEmpty();
        for (String c : classes) {
            // JSON は placeholder 1 要素のみ持ち、empty 時はヒント、非 empty 時は
            // タイプされた文字列をそのまま出す (テキスト色はそのまま)。
            if ("save-name-input-placeholder".equals(c)) {
                return empty ? defaultText : name.value();
            }
            if ("save-name-input-value".equals(c)) {
                return empty ? "" : name.value();
            }
        }
        return null;
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
        PacketDistributor.sendToServer(new TrainPresetSavePayload(trimmed));
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
