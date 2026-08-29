package com.trainsystemutilities.client.wiki;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * アイテムアイコンの一括キャプチャ — 各アイテムをゲーム内 GUI と同じ描画経路
 * ({@link GuiGraphics#renderItem}) でオフスクリーン FBO に描き、PNG として保存する。
 *
 * <p>用途は web wiki の embed:item(s) チップ。平面テクスチャを持たない 3D モデル
 * アイテム (パンタグラフ・変電所など) も、ゲーム内インベントリと同じ見た目で写る。
 *
 * <p>{@code /tsu-wiki-itemicons} で対象名前空間 (TSU + ASC) の全アイテムを
 * {@code <gamedir>/screenshots/wiki/items/<namespace>__<path>.png} (64x64) に保存する。
 * FBO の確保・projection の退避/復元は {@code GuiScreenCapture} と同じイディオム。
 *
 * <p><b>2 パスの自動フィット</b>: まず現行と同一の 64x64 (論理 16px セル) で描き、
 * 内容 bbox がセルを概ね満たし縁に触れないもの (平面アイテムの典型) はそのまま保存
 * — アイテムの見た目は一切変えない。GUI 変換の都合でセル内で極端に小さいか
 * (management_computer / substation 等)、はみ出してクリップする (pantograph) ブロックは
 * 3 セル分のオーバースキャン (384x384) で描き直し、bbox を余白 4px で正方形に収めて
 * 面積平均で 64x64 に落とす。レンダリング自体はどちらも {@code renderItem} そのもの
 * (= ゲーム内スロットと同一) で、変わるのはフレーミングだけ。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class WikiItemIconCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger("TSU-WikiItemIcon");

    /** 論理 16px (= GUI アイテム描画の既定) を 4 倍密度で読み出して 64px PNG にする。 */
    static final int LOGICAL_SIZE = 16;
    private static final int SCALE = 4;

    /** web wiki の embed:item(s) が参照する名前空間。ASC は同じ Prism インスタンスに
     *  入っているので、未登録なら captureNamespace が 0 件で空振りするだけ。 */
    private static final String[] CAPTURE_NAMESPACES = {
            TrainSystemUtilities.MOD_ID, "advancedschematicannon" };

    /** 自動フィットの判定: 内容の最大辺がこれ未満 (64px 中) なら「小さすぎ」。 */
    private static final int FIT_MIN_DIM = 44;
    /** 内容中心がセル中心からこれを超えてずれていたら再フレーミング (64px 基準)。
     *  管理系 3 ブロック + poster は GUI 変換の都合で 8.5px 上寄り、
     *  平面アイテムは実測で全て 3.5px 以内 (2026-08-28の 23 件全数調査)。 */
    private static final int FIT_MAX_OFFCENTER = 6;
    /** オーバースキャン描画: 3 セル (中央に描く)、物理 8x = 384px。 */
    private static final int OVERSCAN_CELLS = 3;
    private static final int OVERSCAN_SCALE = 8;
    /** 出力 64px に対する内容の余白。 */
    private static final int FIT_MARGIN = 4;

    private WikiItemIconCapture() {}

    /** アイコンパスが各 id に適用した枠取り (plain / recenter / fit とそのパラメータ)。
     *  mesh export が読んで mesh.json の初期ビューにする — ビューワの既定表示が
     *  出荷アイコンと**構築で**一致する (別実装の第 3 の枠取りを作らない。
     *  2026-08-28 レビュー: 幾何中心 pan は fence_1m で 1.08 GUI 単位ずれていた)。
     *  値: {mode, a, b, c} — recenter は a=dx b=dy (64px 単位)、fit は
     *  a=cx b=cy c=srcWindow (オーバースキャン 384px 空間)。 */
    static final class Framing {
        final String mode;
        final double a, b, c;
        Framing(String mode, double a, double b, double c) {
            this.mode = mode; this.a = a; this.b = b; this.c = c;
        }
    }

    static final java.util.Map<ResourceLocation, Framing> FRAMINGS =
            new java.util.HashMap<>();

    /** 自動実行モード: 起動前に {@code screenshots/wiki/items/_request.txt} を置いておくと、
     *  タイトル画面到達時 (= リソース確定後、world 不要) に全キャプチャ →
     *  {@code _done.txt} (件数) を書いてクライアントを終了する。runClient 全自動用。 */
    private static boolean autoChecked = false;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (autoChecked) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null
                || !(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) {
            return;
        }
        autoChecked = true;
        Path req = Paths.get(mc.gameDirectory.getPath(),
                "screenshots", "wiki", "items", "_request.txt");
        try {
            if (!Files.deleteIfExists(req)) return; // リクエスト無し → 通常起動
        } catch (Throwable t) {
            LOGGER.warn("[WikiItemIcon] request check failed: {}", t.getMessage());
            return;
        }
        // 前回の _done を先に消す (途中クラッシュで古い件数が残る罠)。削除失敗でも
        // 続行する — 末尾の writeString が上書きするので、ここで中断すると
        // 「request 消費済み・古い _done 残存・キャプチャ未実行」という最悪形になる。
        try {
            Files.deleteIfExists(Paths.get(mc.gameDirectory.getPath(),
                    "screenshots", "wiki", "items", "_done.txt"));
        } catch (Throwable t) {
            LOGGER.warn("[WikiItemIcon] stale _done cleanup failed (continuing): {}",
                    t.getMessage());
        }
        int n = 0;
        for (String ns : CAPTURE_NAMESPACES) {
            deleteNamespaceIcons(mc, ns); // stale-PNG silent-shipping path: kill it
            n += captureNamespace(ns);
        }
        int meshes = WikiItemMeshExport.exportNamespaces(mc, CAPTURE_NAMESPACES);
        try {
            Files.writeString(Paths.get(mc.gameDirectory.getPath(),
                    "screenshots", "wiki", "items", "_done.txt"), n + " " + meshes);
        } catch (Throwable t) {
            LOGGER.warn("[WikiItemIcon] done marker write failed: {}", t.getMessage());
        }
        LOGGER.info("[WikiItemIcon] auto mode captured {} icons + {} meshes; stopping client",
                n, meshes);
        mc.stop();
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tsu-wiki-itemicons").executes(ctx -> {
            int total = 0;
            for (String ns : CAPTURE_NAMESPACES) {
                deleteNamespaceIcons(Minecraft.getInstance(), ns);
                total += captureNamespace(ns);
            }
            int meshes = WikiItemMeshExport.exportNamespaces(
                    Minecraft.getInstance(), CAPTURE_NAMESPACES);
            final int n = total;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[TSU Wiki] captured " + n + " item icons + " + meshes
                    + " meshes (screenshots/wiki)"), false);
            return n;
        }));
    }

    /** namespace の全登録アイテムを描画して保存。render thread 必須。 */
    public static int captureNamespace(String namespace) {
        Minecraft mc = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            mc.execute(() -> captureNamespace(namespace));
            return 0;
        }
        Path dir = Paths.get(mc.gameDirectory.getPath(), "screenshots", "wiki", "items");
        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            LOGGER.warn("[WikiItemIcon] cannot create {}: {}", dir, t.getMessage());
            return 0;
        }
        int n = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (!namespace.equals(key.getNamespace())) continue;
            NativeImage img = renderFitted(mc, new ItemStack(item), key);
            if (img == null) {
                LOGGER.warn("[WikiItemIcon] render failed: {}", key);
                continue;
            }
            try {
                img.writeToFile(dir.resolve(namespace + "__" + key.getPath() + ".png"));
                n++;
            } catch (Throwable t) {
                LOGGER.warn("[WikiItemIcon] save failed for {}: {}", key, t.getMessage());
            } finally {
                img.close();
            }
        }
        LOGGER.info("[WikiItemIcon] captured {} icons for {}", n, namespace);
        return n;
    }

    /** Classify in OVERSCAN space, then render. Content confined to the central cell keeps
     *  the legacy 64px render; off-centre or tiny content is centred by an INTEGER
     *  translation of those very pixels (renderItem output untouched, relative item sizes
     *  preserved). Only content that physically does not fit the cell (outside=true:
     *  pantograph, substation, the ASC cannons) is scale-fitted, which resamples. */
    private static NativeImage renderFitted(Minecraft mc, ItemStack stack, ResourceLocation key) {
        final int out = LOGICAL_SIZE * SCALE; // 64
        NativeImage big = renderCell(mc, stack,
                LOGICAL_SIZE * OVERSCAN_CELLS, OVERSCAN_SCALE, LOGICAL_SIZE);
        if (big == null) return null;
        boolean needsRecenter;
        try {
            int[] bb = alphaBounds(big);
            if (bb == null) {
                FRAMINGS.put(key, new Framing("plain", 0, 0, 0));
                return renderCell(mc, stack, LOGICAL_SIZE, SCALE, 0);
            }
            int ovMax = LOGICAL_SIZE * OVERSCAN_CELLS * OVERSCAN_SCALE - 1;
            if (bb[0] == 0 || bb[1] == 0 || bb[2] == ovMax || bb[3] == ovMax) {
                LOGGER.warn("[WikiItemIcon] {} touches the OVERSCAN edge - model larger than "
                        + "{} cells, the fitted icon is itself clipped", key, OVERSCAN_CELLS);
            }
            int cellMin = LOGICAL_SIZE * OVERSCAN_SCALE;          // 128
            int cellMax = 2 * LOGICAL_SIZE * OVERSCAN_SCALE - 1;  // 255
            int tol = OVERSCAN_SCALE;
            boolean outsideCell = bb[0] < cellMin - tol || bb[1] < cellMin - tol
                    || bb[2] > cellMax + tol || bb[3] > cellMax + tol;
            int ovPerOut = (LOGICAL_SIZE * OVERSCAN_SCALE) / out; // 2
            int maxDim = Math.max(bb[2] - bb[0] + 1, bb[3] - bb[1] + 1);
            boolean tooSmall = maxDim < FIT_MIN_DIM * ovPerOut;
            double cellCenter = LOGICAL_SIZE * OVERSCAN_SCALE * 1.5; // 192
            double cxOff = Math.abs((bb[0] + bb[2] + 1) / 2.0 - cellCenter);
            double cyOff = Math.abs((bb[1] + bb[3] + 1) / 2.0 - cellCenter);
            boolean offCenter = Math.max(cxOff, cyOff)
                    > FIT_MAX_OFFCENTER * ovPerOut;
            needsRecenter = tooSmall || offCenter;
            if (outsideCell) {
                // Only the models that physically do not fit the cell are scale-fitted:
                // resampling is the one deliberate departure from slot-identical pixels,
                // traded against shipping a clipped model.
                LOGGER.info("[WikiItemIcon] autofit {} (outside=true)", key);
                // fitToSquare と同じ窓 (中心 + srcWindow) を framing として記録
                int bw = bb[2] - bb[0] + 1, bh = bb[3] - bb[1] + 1;
                double win = Math.max(bw, bh) * (double) out / (out - 2.0 * FIT_MARGIN);
                FRAMINGS.put(key, new Framing("fit",
                        bb[0] + bw / 2.0, bb[1] + bh / 2.0, win));
                return fitToSquare(big, bb, out, FIT_MARGIN);
            }
        } finally {
            big.close();
        }
        // Everything that fits the cell keeps the legacy 64px render. Off-centre or tiny
        // content is moved to the centre by an INTEGER translation of those very pixels -
        // renderItem output untouched, and relative item sizes stay as the game shows them
        // (2026-08-28 review: normalising scale had inverted fence 1m/3m and pole/insulator).
        NativeImage plain = renderCell(mc, stack, LOGICAL_SIZE, SCALE, 0);
        FRAMINGS.put(key, new Framing("plain", 0, 0, 0));
        if (plain == null || !needsRecenter) return plain;
        int[] pb = alphaBounds(plain);
        if (pb == null) return plain;
        int dx = (out - (pb[2] - pb[0] + 1)) / 2 - pb[0];
        int dy = (out - (pb[3] - pb[1] + 1)) / 2 - pb[1];
        if (dx == 0 && dy == 0) return plain;
        FRAMINGS.put(key, new Framing("recenter", dx, dy, 0));
        LOGGER.info("[WikiItemIcon] recenter {} (dx={} dy={})", key, dx, dy);
        NativeImage moved = new NativeImage(out, out, false);
        moved.fillRect(0, 0, out, out, 0);
        for (int y = pb[1]; y <= pb[3]; y++) {
            for (int x = pb[0]; x <= pb[2]; x++) {
                int tx = x + dx, ty = y + dy;
                if (tx < 0 || ty < 0 || tx >= out || ty >= out) continue;
                moved.setPixelRGBA(tx, ty, plain.getPixelRGBA(x, y));
            }
        }
        plain.close();
        return moved;
    }

    /** Cleanroom: delete this namespace's existing PNGs so a render failure surfaces
     *  as a MISSING capture instead of silently shipping last run's image. NOTE the
     *  sync side then falls back to flat textures for 21 of the 38 referenced ids -
     *  that fallback is a REPLACEMENT, not a detector; the visible signal is the
     *  "captures N" figure in sync-wiki's item-icons breakdown line dropping. */
    private static void deleteNamespaceIcons(Minecraft mc, String namespace) {
        Path dir = Paths.get(mc.gameDirectory.getPath(), "screenshots", "wiki", "items");
        try (var files = Files.newDirectoryStream(dir, namespace + "__*.png")) {
            for (Path f : files) Files.deleteIfExists(f);
        } catch (Throwable t) {
            LOGGER.warn("[WikiItemIcon] clean failed for {}: {}", namespace, t.getMessage());
        }
    }

    /** Render one item into a logicalSize cell at physScale density, item at (offset, offset). */
    private static NativeImage renderCell(Minecraft mc, ItemStack stack,
            int logicalSize, int physScale, int offset) {
        final int physical = logicalSize * physScale;
        RenderTarget target = null;
        RenderTarget mainTarget = mc.getMainRenderTarget();
        Matrix4f savedProj = RenderSystem.getProjectionMatrix();
        VertexSorting savedSort = RenderSystem.getVertexSorting();
        try {
            target = new TextureTarget(physical, physical, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            Matrix4f proj = new Matrix4f().setOrtho(
                    0.0f, logicalSize, logicalSize, 0.0f, -1000.0f, 1000.0f);
            RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);

            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            g.renderItem(stack, offset, offset);
            g.flush();

            NativeImage img = new NativeImage(physical, physical, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            img.downloadTexture(0, false);
            img.flipY();
            return img;
        } catch (Throwable t) {
            LOGGER.error("[WikiItemIcon] capture failed: {}", t.getMessage(), t);
            return null;
        } finally {
            if (target != null) target.destroyBuffers();
            mainTarget.bindWrite(true);
            RenderSystem.setProjectionMatrix(savedProj, savedSort);
        }
    }

    /** Bounding box {minX,minY,maxX,maxY} of alpha>0 pixels, or null when fully transparent. */
    private static int[] alphaBounds(NativeImage img) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getPixelRGBA(x, y) >>> 24) & 0xFF) != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return maxX < 0 ? null : new int[] { minX, minY, maxX, maxY };
    }

    /** Scale the bbox (as a centered square window plus margin) down to out x out using an
     *  alpha-weighted area average, so transparent pixels never bleed dark fringes in. */
    private static NativeImage fitToSquare(NativeImage src, int[] bb, int out, int margin) {
        int bw = bb[2] - bb[0] + 1;
        int bh = bb[3] - bb[1] + 1;
        int side = Math.max(bw, bh);
        double srcWindow = side * (double) out / (out - 2.0 * margin);
        double cx = bb[0] + bw / 2.0;
        double cy = bb[1] + bh / 2.0;
        double x0 = cx - srcWindow / 2.0;
        double y0 = cy - srcWindow / 2.0;
        double step = srcWindow / out;
        NativeImage dst = new NativeImage(out, out, false);
        int sw = src.getWidth(), sh = src.getHeight();
        for (int dy = 0; dy < out; dy++) {
            for (int dx = 0; dx < out; dx++) {
                double sx0 = x0 + dx * step, sx1 = sx0 + step;
                double sy0 = y0 + dy * step, sy1 = sy0 + step;
                int ix0 = (int) Math.floor(sx0), ix1 = (int) Math.ceil(sx1);
                int iy0 = (int) Math.floor(sy0), iy1 = (int) Math.ceil(sy1);
                double r = 0, g = 0, b = 0, a = 0, area = 0;
                for (int sy = iy0; sy < iy1; sy++) {
                    if (sy < 0 || sy >= sh) continue;
                    double covY = Math.min(sy1, sy + 1.0) - Math.max(sy0, sy);
                    if (covY <= 0) continue;
                    for (int sx = ix0; sx < ix1; sx++) {
                        if (sx < 0 || sx >= sw) continue;
                        double covX = Math.min(sx1, sx + 1.0) - Math.max(sx0, sx);
                        if (covX <= 0) continue;
                        double w = covX * covY;
                        int p = src.getPixelRGBA(sx, sy); // ABGR packed
                        double pa = ((p >>> 24) & 0xFF) / 255.0;
                        r += ((p) & 0xFF) * pa * w;
                        g += ((p >>> 8) & 0xFF) * pa * w;
                        b += ((p >>> 16) & 0xFF) * pa * w;
                        a += pa * w;
                        area += w;
                    }
                }
                int outA = area <= 0 ? 0 : (int) Math.round(255.0 * a / area);
                int outR = a <= 0 ? 0 : (int) Math.round(r / a);
                int outG = a <= 0 ? 0 : (int) Math.round(g / a);
                int outB = a <= 0 ? 0 : (int) Math.round(b / a);
                dst.setPixelRGBA(dx, dy,
                        (outA << 24) | (outB << 16) | (outG << 8) | outR);
            }
        }
        return dst;
    }
}
