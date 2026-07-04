package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import belugalab.experience.controller.TextInputController;
import belugalab.experience.render.TextCaretRenderer;
import com.trainsystemutilities.network.StationGroupCreatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 駅グループ作成画面 (シンプル版: 駅グループ名のみ入力)。
 * <p>路線記号 / 枠色は管理用コンピューターで割り当てるため、ここでは扱わない。
 */
@OnlyIn(Dist.CLIENT)
public class StationGroupSaveScreen extends JsonLayoutPlainScreen {

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

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        for (String c : classes) {
            if ("sg-name-input-value".equals(c)) return name.value();
            if ("sg-status".equals(c)) {
                return String.format("(%d,%d,%d) ~ (%d,%d,%d)",
                        pos1.getX(), pos1.getY(), pos1.getZ(),
                        pos2.getX(), pos2.getY(), pos2.getZ());
            }
        }
        return null;
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
        // creatorPos: 番線連番 AUTO モードの「内側 = 1 番線」基準点。
        // numberingDir: ツールに記録された LEFT/RIGHT/AUTO の選択値を一緒に送る。
        var mc = Minecraft.getInstance();
        net.minecraft.core.BlockPos creator = mc.player != null
                ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
        int numDir = 0;
        if (mc.player != null) {
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                var stack = mc.player.getItemInHand(hand);
                if (stack.is(com.trainsystemutilities.registry.ModItems.STATION_RANGE_TOOL.get())) {
                    numDir = com.trainsystemutilities.item.StationRangeToolItem.getNumberingDir(stack);
                    break;
                }
            }
        }
        PacketDistributor.sendToServer(new StationGroupCreatePayload(n, pos1, pos2, creator, numDir));
        onClose();
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
