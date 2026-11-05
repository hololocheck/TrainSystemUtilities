package com.trainsystemutilities.client.wiki;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * 3D model viewer (full-screen popup)。インライン wiki 埋め込み thumbnail から
 * 開かれることを想定。
 *
 * <ul>
 *   <li>左ドラッグ: モデル回転 (YP / XP)</li>
 *   <li>右ドラッグ: モデル平行移動 (pan)</li>
 *   <li>ホイール: ズーム</li>
 *   <li>ESC / 背景クリック: 閉じる</li>
 * </ul>
 *
 * <p>対象は {@link ItemStack} (= 通常 BlockItem)。Geckolib エンティティモデルは
 * 別途専用ビューア要 — このクラスは vanilla item renderer 経由なので bake された
 * BakedModel または item form を持つすべての block で動作。
 */
// BelugaExperience exception (§13.2): フルスクリーン 3D モデルビューア (= dialog/form ではなく
// 没入型の回転/パン/ズーム ビューア) のため、 §4.1 の JsonLayoutPlainScreen 基底には乗らない。
// 背景 dim の g.fill (render 内) も角丸不要の flat fill で §2.4 対象外。
// 本格的な標準化は BelugaExperience の ModelViewerScreen 標準 (監査 B-②) 新設時に移行予定。
@OnlyIn(Dist.CLIENT)
public class WikiModelViewerScreen extends Screen {

    private final ItemStack stack;
    private final Screen parent;

    /** 視点の状態は部品が持つ (B9)。数値は移行前と同じ。 */
    private final com.manta.api.preview.OrbitView view =
            new com.manta.api.preview.OrbitView(30f, 25f, 1.5f)
                    .degreesPerPixel(0.5f)
                    .pitchRange(-90f, 90f)
                    .zoomRange(0.2f, 8f)
                    .zoomStep(1.15f, 1f / 1.15f)
                    .invertPitch(true);

    public WikiModelViewerScreen(ItemStack stack, Screen parent) {
        super(Component.translatable("tsu.wiki.model_viewer"));
        this.stack = stack;
        this.parent = parent;
    }

    public static void open(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        mc.setScreen(new WikiModelViewerScreen(stack, current));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景: 半透明黒で親画面を暗転
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, 0xC0000000);

        // モデル描画 (中央配置)
        float cx = this.width / 2f + view.panX();
        float cy = this.height / 2f + view.panY();
        float baseSize = Math.min(this.width, this.height) * 0.4f;
        float modelScale = baseSize * view.zoom() / 16f;

        g.pose().pushPose();
        g.pose().translate(cx, cy, 200);
        g.pose().scale(modelScale, modelScale, modelScale);
        g.pose().mulPose(com.mojang.math.Axis.XP.rotationDegrees(view.pitch()));
        g.pose().mulPose(com.mojang.math.Axis.YP.rotationDegrees(view.yaw()));
        g.pose().translate(-8f, -8f, 0f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();

        // 操作説明
        Minecraft mc = Minecraft.getInstance();
        String hint = Component.translatable("tsu.wiki.model_viewer_hint").getString();
        int hintW = com.manta.api.text.MantaText.uiWidth(mc.font, hint);
        com.manta.api.text.MantaText.draw(g, mc.font, hint, (this.width - hintW) / 2, this.height - 24, 0xFFAAAAAA, true);

        // タイトル
        String title = stack.getHoverName().getString();
        com.manta.api.text.MantaText.draw(g, mc.font, title, 12, 12, 0xFFFFFFFF, true);

        // 閉じるボタンヒント
        com.manta.api.text.MantaText.draw(g, mc.font, "ESC / Right-click to close", 12, this.height - 14, 0xFF888888);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 || button == 1) {
            view.beginDrag(button, mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (view.drag(mouseX, mouseY, button)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        view.endDrag(button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        view.zoomBy(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
