package com.trainsystemutilities.client.extract;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Item / Block の inventory アイコンを off-screen FBO でレンダリングして PNG に保存する。
 *
 * <p>3D block model / BlockBench (GeckoLib) model も含めて、 ゲーム内で実際に
 * インベントリスロットに表示される見た目をそのまま PNG 出力する。
 * 平面テクスチャの単純コピーではなく、 {@link GuiGraphics#renderItem} 経由で
 * Minecraft の ItemRenderer を呼ぶため、 3D model や enchanted glint 等も再現される。
 *
 * <p>出力先: {@code <gamedir>/mcss3-cache/items/<namespace>/<item_id>.png}
 *
 * <p>使用例 (render thread から):
 * <pre>{@code
 *   int count = ItemIconExtractor.extractAll("trainsystemutilities", 128);
 * }</pre>
 */
public final class ItemIconExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger("TSU-ItemIconExtractor");

    private ItemIconExtractor() {}

    /** 1 item を FBO に render して PNG として保存。 戻り値 = 保存に成功すれば true。 */
    public static boolean extract(Item item, int size, Path outputPath) {
        if (item == null || size <= 0 || outputPath == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            LOGGER.warn("extract() called off render thread; aborting");
            return false;
        }
        NativeImage img = capture(item, size);
        if (img == null) return false;
        try {
            Files.createDirectories(outputPath.getParent());
            img.writeToFile(outputPath);
            return true;
        } catch (IOException e) {
            LOGGER.error("PNG save failed for {}: {}",
                    BuiltInRegistries.ITEM.getKey(item), e.getMessage());
            return false;
        } finally {
            img.close();
        }
    }

    /** Item を off-screen FBO に render して NativeImage で返す (= caller が close)。
     *
     *  <p>方針: GuiScreenCapture と同じ FBO + ortho projection 構造で
     *  {@link GuiGraphics#renderItem} を呼ぶ。 3D item / block model の lighting は
     *  GuiGraphics 内部の ItemRenderer が自前で setupFor3DItems() を呼ぶため、
     *  外部で lighting 操作する必要は無い。 modelView は変更せず default のまま使う
     *  (= GuiScreenCapture で動いている構造を踏襲)。 */
    public static NativeImage capture(Item item, int size) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        Matrix4f savedProj = RenderSystem.getProjectionMatrix();
        VertexSorting savedSort = RenderSystem.getVertexSorting();

        RenderTarget target = null;
        try {
            target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            // Ortho projection: 0..size 座標系、 Y 上下反転、 Z -1000..1000
            Matrix4f proj = new Matrix4f().setOrtho(0f, size, size, 0f, -1000f, 1000f);
            RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);

            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            // BlockBench / GeckoLib custom model は標準 16 unit より大きいことがある
            // (例: FE Inverter は Z 方向 48 unit + GUI display scale 0.625 + 45° rotation で
            // screen-space X ~28-30 unit に展開される)。 安全マージンで scale = size/32 にして
            // 中央寄せで描画。 標準 16 unit item は ~50% size になるが、 大型 model も切れない。
            //
            // Z 軸は scale せず 1 のまま — ItemRenderer 内部の Z=150 translate が
            // ortho projection 範囲 [-1000, 1000] からクリップアウトしないため。
            float scale = size / 32f;
            int center = size / 2;
            g.pose().pushPose();
            g.pose().translate(center, center, 0);
            g.pose().scale(scale, scale, 1f);
            ItemStack stack = new ItemStack(item);
            // renderItem(x, y) は内部で +8 offset するため、 -8 を渡すと scaled local (0,0) =
            // 我々の translate (center, center) と一致し、 item が FBO 中央に描画される。
            g.renderItem(stack, -8, -8);
            g.flush();
            g.pose().popPose();

            NativeImage img = new NativeImage(size, size, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            img.downloadTexture(0, false);
            img.flipY();
            return img;
        } catch (Throwable t) {
            LOGGER.error("capture failed for {}: {}",
                    BuiltInRegistries.ITEM.getKey(item), t.getMessage(), t);
            return null;
        } finally {
            if (target != null) target.destroyBuffers();
            mainTarget.bindWrite(true);
            RenderSystem.setProjectionMatrix(savedProj, savedSort);
        }
    }

    /** 指定 namespace の全 item を一括抽出。 戻り値 = 成功した件数。 */
    public static int extractAll(String namespace, int size) {
        if (namespace == null || namespace.isBlank()) return 0;
        Path baseDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mcss3-cache").resolve("items").resolve(namespace);

        int total = 0;
        int success = 0;
        int failed = 0;
        long startMs = System.currentTimeMillis();

        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation key = entry.getKey().location();
            if (!namespace.equals(key.getNamespace())) continue;
            total++;
            Item item = entry.getValue();
            Path out = baseDir.resolve(key.getPath() + ".png");
            if (extract(item, size, out)) success++;
            else failed++;
        }

        long elapsed = System.currentTimeMillis() - startMs;
        LOGGER.info("Extracted {} icons ({} success, {} failed) for namespace '{}' in {}ms → {}",
                total, success, failed, namespace, elapsed, baseDir);
        return success;
    }
}
