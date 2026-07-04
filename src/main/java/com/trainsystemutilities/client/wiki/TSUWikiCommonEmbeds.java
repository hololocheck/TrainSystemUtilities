package com.trainsystemutilities.client.wiki;

import belugalab.mcss3.wiki.WikiEmbedRegistry;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Wiki embed: ジェネリック embed (item icon / 3D model / screen capture)。
 *
 * <p>サポートされる Markdown 構文:
 * <pre>
 * ```embed:item id=trainsystemutilities:wire_connector size=48
 * ```
 *
 * ```embed:model id=fe_inverter size=128 ratio=1:1
 * ```
 *
 * ```embed:screen id=wire-connector ratio=4:3
 * ```
 * </pre>
 *
 * <p>{@link TSUWikiEmbeds#registerAll} から呼ばれる。
 */
public final class TSUWikiCommonEmbeds {

    private TSUWikiCommonEmbeds() {}

    public static void register() {
        registerItem();
        registerItemsGrid();
        registerModel();
        registerScreen();
    }

    // ===== Item icon embed =====

    /** {@code embed:item id=modid:itemid size=48} — インベントリスロット同様の ItemStack 描画。
     *  size: 16/32/48/64 等の表示寸法 (default 32)。
     *  label=true なら下に displayName を描画。 */
    private static void registerItem() {
        WikiEmbedRegistry.register("item", ctx -> {
            String id = ctx.option("id", "");
            int size = Math.max(16, ctx.optionInt("size", 32));
            boolean showLabel = "true".equalsIgnoreCase(ctx.option("label", "false"));
            ItemStack stack = resolveItemStack(id);
            int previewH = size + (showLabel ? 14 : 4);
            return WikiEmbedRegistry.EmbedBuilder.Result.custom(
                    (g, font, x, y, w, h, rctx, dt) ->
                            renderItemCentered(g, font, stack, x, y, w, h, size, showLabel),
                    previewH);
        });
    }

    // ===== Item grid embed =====

    /** {@code embed:items ids=tsu:wire_connector,tsu:fe_inverter,... size=32 cols=5 label=true
     *  [links=page-a,page-b,...]}
     *  — 複数の item icon を grid で表示。cols 列に達したら次の row に折返し。
     *  ids は comma separated。空白は trim される。
     *  links を渡すと該当 cell がクリック可能になり、対応する wiki ページに遷移する
     *  (空欄またはダッシュは「リンクなし」)。 */
    private static void registerItemsGrid() {
        WikiEmbedRegistry.register("items", ctx -> {
            String idsStr = ctx.option("ids", "");
            int size = Math.max(16, ctx.optionInt("size", 32));
            int cols = Math.max(1, ctx.optionInt("cols", 5));
            final int finalCols = cols;
            boolean showLabel = "true".equalsIgnoreCase(ctx.option("label", "true"));
            String[] rawIds = idsStr.split(",");
            int n = 0;
            for (String r : rawIds) if (!r.trim().isEmpty()) n++;
            final String[] ids = new String[n];
            int idx = 0;
            for (String r : rawIds) {
                String t = r.trim();
                if (!t.isEmpty()) ids[idx++] = t;
            }
            int rows = (n + finalCols - 1) / finalCols;
            int cellH = size + (showLabel ? 22 : 8);
            final int finalCellH = cellH;
            int previewH = Math.max(cellH, rows * cellH + 4);

            String linksStr = ctx.option("links", "");
            final String[] links;
            if (linksStr.isBlank()) {
                links = null;
            } else {
                String[] rawLinks = linksStr.split(",", -1);
                links = new String[ids.length];
                for (int i = 0; i < ids.length && i < rawLinks.length; i++) {
                    String t = rawLinks[i].trim();
                    if (!t.isEmpty() && !"-".equals(t)) links[i] = t;
                }
            }

            WikiEmbedRegistry.EmbedBuilder.CustomRenderer renderer =
                    (g, font, x, y, w, h, rctx, dt) -> {
                        int cellW = Math.max(1, w / finalCols);
                        for (int i = 0; i < ids.length; i++) {
                            int r = i / finalCols;
                            int c = i % finalCols;
                            int cx = x + c * cellW;
                            int cy = y + r * finalCellH;
                            ItemStack stack = resolveItemStack(ids[i]);
                            renderItemCentered(g, font, stack, cx, cy, cellW, finalCellH, size, showLabel);
                            if (links != null && i < links.length && links[i] != null) {
                                // 微細なアクセント (右下に小さなリンクマーカー)
                                int mx = cx + cellW - 6;
                                int my = cy + finalCellH - 6;
                                g.fill(mx, my, mx + 4, my + 4, 0xFF4FC3F7);
                            }
                        }
                    };

            if (links == null) {
                return WikiEmbedRegistry.EmbedBuilder.Result.custom(renderer, previewH);
            }

            WikiEmbedRegistry.EmbedBuilder.ClickHandler handler = (relX, relY, scaledW, scaledH, button) -> {
                if (button != 0) return false;
                // scaledH = rows * finalCellH の scale 倍。scaledCellH = scaledH / rows。
                int totalRows = (ids.length + finalCols - 1) / finalCols;
                if (totalRows <= 0 || scaledW <= 0 || scaledH <= 0) return false;
                int scaledCellW = Math.max(1, scaledW / finalCols);
                int scaledCellH = Math.max(1, scaledH / totalRows);
                int col = Math.min(finalCols - 1, Math.max(0, relX / scaledCellW));
                int row = Math.min(totalRows - 1, Math.max(0, relY / scaledCellH));
                int i = row * finalCols + col;
                if (i < 0 || i >= links.length || links[i] == null) return false;
                belugalab.mcss3.wiki.Wiki.open(links[i]);
                return true;
            };

            return WikiEmbedRegistry.EmbedBuilder.Result.custom(renderer, handler, previewH);
        });
    }

    private static void renderItemCentered(GuiGraphics g, Font font, ItemStack stack,
                                            int x, int y, int w, int h, int size, boolean label) {
        int ix = x + (w - size) / 2;
        int iy = y + 2;
        // 標準 16×16 → size に scale
        float scale = size / 16f;
        g.pose().pushPose();
        g.pose().translate(ix, iy, 100);
        g.pose().scale(scale, scale, 1f);
        g.renderItem(stack, 0, 0);
        g.renderItemDecorations(font, stack, 0, 0);
        g.pose().popPose();
        if (label) {
            String name = stack.isEmpty()
                    ? Component.literal("(unknown)").getString()
                    : stack.getHoverName().getString();
            // cell 幅を超える場合は省略 (...) で truncate
            int maxW = Math.max(20, w - 4);
            String display = name;
            if (font.width(display) > maxW) {
                while (display.length() > 1 && font.width(display + "...") > maxW) {
                    display = display.substring(0, display.length() - 1);
                }
                display = display + "...";
            }
            int textW = font.width(display);
            int tx = x + (w - textW) / 2;
            int ty = iy + size + 2;
            g.drawString(font, display, tx, ty, 0xFFE0E0E0, true);
        }
    }

    // ===== 3D model embed (uses item form for now, 3D viewer click-to-open in future) =====

    /** {@code embed:model id=fe_inverter size=128} — BlockItem の 3D 表示
     *  (vanilla item renderer 経由)。Geckolib 専用モデルの動的回転は将来拡張。 */
    private static void registerModel() {
        WikiEmbedRegistry.register("model", ctx -> {
            String id = ctx.option("id", "");
            // namespace 省略時は TSU と仮定
            if (!id.contains(":")) id = TrainSystemUtilities.MOD_ID + ":" + id;
            int size = Math.max(48, ctx.optionInt("size", 128));
            boolean rotate = "true".equalsIgnoreCase(ctx.option("rotate", "true"));
            ItemStack stack = resolveItemStack(id);
            return WikiEmbedRegistry.EmbedBuilder.Result.custom(
                    (g, font, x, y, w, h, rctx, dt) ->
                            renderModel(g, stack, x, y, w, h, size, rotate, rctx.elapsedSeconds()),
                    size + 8);
        });
    }

    private static void renderModel(GuiGraphics g, ItemStack stack,
                                     int x, int y, int w, int h, int size, boolean rotate,
                                     float elapsedSeconds) {
        int ix = x + (w - size) / 2;
        int iy = y + (h - size) / 2;
        float scale = size / 16f;
        g.pose().pushPose();
        g.pose().translate(ix + size / 2f, iy + size / 2f, 200);
        g.pose().scale(scale, scale, scale);
        if (rotate) {
            float angle = (elapsedSeconds * 20f) % 360f;
            g.pose().mulPose(com.mojang.math.Axis.YP.rotationDegrees(angle));
        }
        g.pose().translate(-8f, -8f, 0f);
        // 中心基準の描画
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    // ===== Screen capture embed (bundled PNG) =====

    /** {@code embed:screen id=wire-connector [state=main] [variant=auto|main|...] [modid=...]}
     *
     *  <p>解決順:
     *    1. {@code id__<state>__<currentLang>.png} (例: wire-connector__main__ja_jp)
     *    2. {@code id__<state>__en_us.png} (lang fallback)
     *    3. {@code id__<state>.png} (lang 無し版、後方互換)
     *    4. {@code id.png} (state 無し版、後方互換)
     *
     *  <p>WikiCaptureAuto が自動で 1 番のキーで DynamicTexture 登録するため、
     *  通常はマニュアル指定不要。 */
    private static void registerScreen() {
        WikiEmbedRegistry.register("screen", ctx -> {
            String id = ctx.option("id", "");
            String state = ctx.option("state", "main");
            String modid = ctx.option("modid", TrainSystemUtilities.MOD_ID);
            int w = ctx.renderWidth();
            // 1) explicit ratio (e.g. ratio=4:3)
            // 2) prebuild が記録した native dims から aspect ratio
            // 3) fallback 16:9
            int h;
            String ratio = ctx.option("ratio", "");
            int[] nativeDims = WikiPrebuildCapture.LAYOUT_DIMS.get(id + "__" + state);
            if (nativeDims == null) nativeDims = WikiPrebuildCapture.LAYOUT_DIMS.get(id);
            if (!ratio.isEmpty() && ratio.contains(":")) {
                String[] rs = ratio.split(":");
                try {
                    float rw = Float.parseFloat(rs[0].trim());
                    float rh = Float.parseFloat(rs[1].trim());
                    h = (rw > 0) ? Math.round(w * rh / rw) : Math.round(w * 9f / 16f);
                } catch (Throwable t) {
                    h = Math.round(w * 9f / 16f);
                }
            } else if (nativeDims != null && nativeDims[0] > 0) {
                h = Math.round(w * (nativeDims[1] / (float) nativeDims[0]));
            } else {
                h = Math.round(w * 9f / 16f);
            }
            // GUI が小さい layout の場合、wiki 幅一杯に拡大せず最大 (nativeW * 2) で抑える
            // → 鉄道管理 (236×316) などが横長すぎになるのを防ぐ
            final int displayW;
            final int displayH;
            if (nativeDims != null && nativeDims[0] > 0) {
                int maxW = nativeDims[0] * 2;
                if (w > maxW) {
                    displayW = maxW;
                    displayH = Math.round(maxW * (nativeDims[1] / (float) nativeDims[0]));
                } else {
                    displayW = w;
                    displayH = h;
                }
            } else {
                displayW = w;
                displayH = h;
            }
            return WikiEmbedRegistry.EmbedBuilder.Result.custom(
                    (gg, font, x, y, ww, hh, rctx, dt) -> {
                        // center horizontally if displayW < ww
                        int dx = x + Math.max(0, (ww - displayW) / 2);
                        renderScreenTextureWithFallback(gg, font, modid, id, state, dx, y, displayW, displayH);
                    },
                    displayH);
        });
    }

    /** 言語フォールバック付きテクスチャ描画。 */
    private static void renderScreenTextureWithFallback(GuiGraphics g, Font font,
                                                          String modid, String id, String state,
                                                          int x, int y, int w, int h) {
        String currentLang = Minecraft.getInstance().getLanguageManager().getSelected();
        if (currentLang == null || currentLang.isBlank()) currentLang = "en_us";

        // Try order:
        //   1. id__state__lang     (auto-capture: 古い形式 + state 区別)
        //   2. id__state__en_us    (lang fallback)
        //   3. id__lang            (prebuild の標準形式)
        //   4. id__en_us           (prebuild lang fallback)
        //   5. id__state           (legacy)
        //   6. id-state__lang      (legacy overlay capture)
        //   7. id-state__en_us     (legacy overlay fallback)
        //   8. id-state            (legacy overlay no-lang)
        //   9. id                  (legacy / no-lang prebuild)
        String[] candidates = {
                id + "__" + state + "__" + currentLang,
                id + "__" + state + "__en_us",
                id + "__" + currentLang,
                id + "__en_us",
                id + "__" + state,
                id + "-" + state + "__" + currentLang,
                id + "-" + state + "__en_us",
                id + "-" + state,
                id
        };
        for (String name : candidates) {
            ResourceLocation tex = safeResourceLocation(modid, "textures/wiki/screens/" + name + ".png");
            if (tex != null && tryBlit(g, tex, x, y, w, h)) return;
        }
        // 全て失敗 → エラー表示
        g.fill(x, y, x + w, y + h, 0xFF1a1a25);
        String msg = "[screen: " + id + "__" + state + " (no capture, press F8 in screen)]";
        g.drawString(font, msg, x + 6, y + 6, 0xFFFF8888, true);
    }

    /** texture が利用可能なら描画して true。利用不可なら false。
     *  Minecraft の TextureManager は missing texture を pink/black で返すが、
     *  ここではあくまで「利用可能性チェック」して即時 fallback できるようにしたい。
     *  シンプルに「最後の候補だけ無条件 blit」運用にする。 */
    private static boolean tryBlit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h) {
        try {
            // TextureManager.getTexture は missing 時に MissingTextureAtlasSprite を返す。
            // DynamicTexture が register 済かを判定するため registry を直接見る。
            var tm = Minecraft.getInstance().getTextureManager();
            var t = tm.getTexture(tex, null);
            if (t == null && Minecraft.getInstance().getResourceManager().getResource(tex).isEmpty()) return false;
            // missing texture (= MissingTexture) はピンク市松。これも一応描画して進む。
            g.blit(tex, x, y, 0, 0, w, h, w, h);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== Helpers =====

    private static ItemStack resolveItemStack(String id) {
        if (id == null || id.isEmpty()) return new ItemStack(Items.BARRIER);
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return new ItemStack(Items.BARRIER);
        Item item = BuiltInRegistries.ITEM.get(loc);
        if (item == null || item == Items.AIR) return new ItemStack(Items.BARRIER);
        return new ItemStack(item);
    }

    private static ResourceLocation safeResourceLocation(String namespace, String path) {
        try {
            return ResourceLocation.fromNamespaceAndPath(namespace, path);
        } catch (Throwable t) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/unknown_pack.png");
        }
    }
}
