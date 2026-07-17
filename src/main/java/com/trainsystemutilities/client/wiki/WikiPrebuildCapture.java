package com.trainsystemutilities.client.wiki;

import belugalab.mcss3.screen.JsonLayoutEngine;
import belugalab.mcss3.screen.JsonLayoutHandler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pre-build wiki captures from JSON layouts — no need to actually open each
 * V2 screen. ResourceManager で {@code assets/<modid>/layouts/*.json} を
 * 列挙し、それぞれを {@link JsonLayoutEngine} で off-screen FBO に render し
 * DynamicTexture + PNG として登録する。
 *
 * <p>キャプチャ id 形式: {@code <layout-name>__<lang>} (例: wire-connector__ja_jp)
 * 各 JSON ファイルは個別キャプチャ。overlay 用 JSON (= 主画面と別ファイル)
 * も自動で発見される。
 *
 * <p>呼び出し:
 *   - {@link #prebuildAll()} 全 layout を現在言語で prebuild
 *   - {@link #prebuildOne(String)} 単一 layout (filename without .json)
 *   - 自動: client tick で「未 prebuild かつ render thread」のとき 1 layout/tick ずつ処理
 *
 * <p>dynamic key 解決は {@link PrebuildHandler} (no-op + default 返却) — 動的データは
 * 反映されないが、layout の chrome / static 要素は正確に描画される。
 */
public final class WikiPrebuildCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger("TSU-WikiPrebuild");

    /** 2x super-sample: FBO は (w*SCALE)×(h*SCALE) で render → texture が高解像度。
     *  embed:screen の bilinear downsample で文字が鮮明になる。 */
    private static final int SCALE = 2;

    // === SECURITY (TSU-WIKI-001): allocation-before-validation を塞ぐ budget ===
    // accepted resource pack が layouts/*.json を上書き/追加でき、root の w/h や file サイズは
    // untrusted 入力。検証前に FBO/NativeImage/disk へ到達させない。
    /** layout 単位 (super-sample 前) の 1 辺上限。 */
    private static final int MAX_LAYOUT_DIM = 4096;
    /** super-sample 後 (targetW×targetH) の総 pixel 上限 (= 4096² 相当、約 67MB @ RGBA)。 */
    private static final long MAX_TARGET_PIXELS = 16_777_216L;
    /** 1 layout JSON の byte 上限 (これを超える resource は読まずに拒否)。 */
    private static final int MAX_RESOURCE_BYTES = 512 * 1024;

    /** Prebuild 済 key の cache (= <layout>__<lang>)。再起動でリセット。 */
    private static final Set<String> prebuilt = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(Boolean.TRUE);

    /** layout 名 → native dimensions [w, h]。embed:screen が aspect ratio に使用。
     *  prebuild が走った layout のみ entries が入る。 */
    public static final Map<String, int[]> LAYOUT_DIMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** SECURITY/lifecycle (TSU-WIKI-001): prebuild が register した DynamicTexture の location。
     *  clearCache / reload で解放するため追跡する (旧実装は Set しか clear せず texture が leak した)。 */
    private static final Set<ResourceLocation> registeredTextures =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WikiPrebuildCapture() {}

    /** 既知の prebuild cache をクリアし、登録済 DynamicTexture と dims を解放 (= 次回トリガで再 prebuild)。 */
    public static void clearCache() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : registeredTextures) {
            try { mc.getTextureManager().release(loc); } catch (Throwable ignored) {}
        }
        registeredTextures.clear();
        LAYOUT_DIMS.clear();
        prebuilt.clear();
    }

    /** 既に prebuild 済か。 */
    public static boolean isPrebuilt(String layoutName, String lang) {
        return prebuilt.contains(layoutName + "__" + lang);
    }

    /** 全 layout を現在言語で prebuild。render thread で実行する必要あり。 */
    public static int prebuildAll() {
        Minecraft mc = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            mc.execute(WikiPrebuildCapture::prebuildAll);
            return 0;
        }
        String lang = currentLang();
        Set<ResourceLocation> layouts = discoverLayouts();
        int ok = 0, fail = 0;
        for (ResourceLocation loc : layouts) {
            String name = extractName(loc);
            if (prebuilt.contains(name + "__" + lang)) continue;
            if (prebuildOne(loc, lang)) ok++; else fail++;
        }
        LOGGER.info("[WikiPrebuild] lang={} ok={} fail={} total_discovered={}",
                lang, ok, fail, layouts.size());
        return ok;
    }

    /** 指定 layout 名 (拡張子なし) を現在言語で 1 件 prebuild。 */
    public static boolean prebuildOne(String layoutName) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                TrainSystemUtilities.MOD_ID, "layouts/" + layoutName + ".json");
        return prebuildOne(loc, currentLang());
    }

    /** layout 名 → 撮るべき variant 名のリスト。
     *  variant ごとに別 texture key (= name__variant__lang) で登録される。
     *  default variant "main" は name__lang として登録 (後方互換)。 */
    private static final Map<String, java.util.List<String>> LAYOUT_VARIANTS = Map.of(
            "management-computer", java.util.List.of("map", "trains", "schedule", "stations", "symbol"),
            "transit-terminal", java.util.List.of("top", "schedule", "map", "settings")
    );

    private static boolean prebuildOne(ResourceLocation layoutLoc, String lang) {
        String name = extractName(layoutLoc);
        String key = name + "__" + lang;
        try {
            String json = loadResource(layoutLoc);
            if (json == null || json.isBlank()) return false;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int w = root.has("w") ? root.get("w").getAsInt() : 256;
            int h = root.has("h") ? root.get("h").getAsInt() : 256;
            // SECURITY (TSU-WIKI-001): dimension / total-pixel を allocation 前に検証。
            // 巨大 w/h (例 height=1000000) が FBO/NativeImage 確保へ到達するのを防ぐ。
            if (!dimensionsWithinBudget(name, w, h)) return false;
            // embed:screen が aspect ratio に使うため layout の native dims を保存
            LAYOUT_DIMS.put(name, new int[]{w, h});

            java.util.List<String> variants = LAYOUT_VARIANTS.get(name);
            if (variants == null) {
                if (hasBundledCapture(name, lang)) {
                    prebuilt.add(key);
                    return true;
                }
                // 単一 variant — currentVariant=default "map" で 1 回 render
                currentVariant = "map";
                NativeImage img = renderLayoutOffscreen(root, w, h);
                if (img == null) return false;
                registerDynamicTexture(name, lang, img);
            } else {
                // 複数 variants — それぞれ別 texture key で登録
                // 1 件目を main texture としても登録 (= name__lang)
                boolean first = true;
                for (String variant : variants) {
                    LAYOUT_DIMS.put(name + "__" + variant, new int[]{w, h});
                    if (hasBundledCapture(name + "__" + variant, lang)) {
                        first = false;
                        continue;
                    }
                    currentVariant = variant;
                    NativeImage img = renderLayoutOffscreen(root, w, h);
                    if (img == null) continue;
                    registerVariantTexture(name, variant, lang, img, first);
                    first = false;
                }
            }
            prebuilt.add(key);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[WikiPrebuild] {} failed: {}", name, t.getMessage());
            return false;
        } finally {
            currentVariant = "map";
        }
    }

    private static boolean hasBundledCapture(String stem, String lang) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                TrainSystemUtilities.MOD_ID, "textures/wiki/screens/" + stem + "__" + lang + ".png");
        return Minecraft.getInstance().getResourceManager().getResource(loc).isPresent();
    }

    /** ResourceManager で {@code assets/<modid>/layouts/*.json} を列挙。 */
    private static Set<ResourceLocation> discoverLayouts() {
        var rm = Minecraft.getInstance().getResourceManager();
        Map<ResourceLocation, Resource> all = rm.listResources(
                "layouts", loc -> loc.getNamespace().equals(TrainSystemUtilities.MOD_ID)
                        && loc.getPath().endsWith(".json"));
        return new LinkedHashSet<>(all.keySet());
    }

    private static String loadResource(ResourceLocation loc) {
        var rm = Minecraft.getInstance().getResourceManager();
        try {
            var opt = rm.getResource(loc);
            if (opt.isEmpty()) return null;
            try (var br = new BufferedReader(
                    new InputStreamReader(opt.get().open(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    // SECURITY (TSU-WIKI-001): untrusted resource pack が巨大 JSON を
                    // heap へ丸読みするのを防ぐ。上限超過は読まずに破棄。
                    if (sb.length() > MAX_RESOURCE_BYTES) {
                        LOGGER.warn("[WikiPrebuild] {} exceeds {} bytes — skipped",
                                loc, MAX_RESOURCE_BYTES);
                        return null;
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SECURITY (TSU-WIKI-001): layout root の w/h が有限・正・上限内で、super-sample 後の
     * 総 pixel が budget 内かを checked math で検証する。超過は allocation 前に拒否。
     */
    private static boolean dimensionsWithinBudget(String name, int w, int h) {
        if (w <= 0 || h <= 0 || w > MAX_LAYOUT_DIM || h > MAX_LAYOUT_DIM) {
            LOGGER.warn("[WikiPrebuild] {} rejected — dimensions {}x{} out of [1,{}]",
                    name, w, h, MAX_LAYOUT_DIM);
            return false;
        }
        try {
            long targetW = Math.multiplyExact((long) w, SCALE);
            long targetH = Math.multiplyExact((long) h, SCALE);
            long pixels = Math.multiplyExact(targetW, targetH);
            if (pixels > MAX_TARGET_PIXELS) {
                LOGGER.warn("[WikiPrebuild] {} rejected — target pixels {} > cap {}",
                        name, pixels, MAX_TARGET_PIXELS);
                return false;
            }
        } catch (ArithmeticException overflow) {
            LOGGER.warn("[WikiPrebuild] {} rejected — dimension overflow {}x{}", name, w, h);
            return false;
        }
        return true;
    }

    /** "layouts/wire-connector.json" → "wire-connector" */
    private static String extractName(ResourceLocation loc) {
        String p = loc.getPath();
        int slash = p.lastIndexOf('/');
        String f = slash >= 0 ? p.substring(slash + 1) : p;
        if (f.endsWith(".json")) f = f.substring(0, f.length() - 5);
        return f;
    }

    private static String currentLang() {
        String l = Minecraft.getInstance().getLanguageManager().getSelected();
        return l == null || l.isBlank() ? "en_us" : l;
    }

    /** off-screen FBO で JSON layout を render → NativeImage。
     *  SCALE 倍の解像度で render し、bilinear downsample で文字を鮮明にする。 */
    private static NativeImage renderLayoutOffscreen(JsonObject root, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        Matrix4f savedProj = RenderSystem.getProjectionMatrix();
        VertexSorting savedSort = RenderSystem.getVertexSorting();
        RenderTarget target = null;
        int targetW = w * SCALE;
        int targetH = h * SCALE;
        try {
            target = new TextureTarget(targetW, targetH, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            // projection は layout 単位 (0..w, 0..h) のまま。viewport が targetW×targetH
            // なので 1 layout 単位 = SCALE pixel で rasterize される。
            // Z range は -1000..1000 (= GUI 描画は Z=0 付近)。
            Matrix4f proj = new Matrix4f().setOrtho(
                    0.0f, w, h, 0.0f, -1000.0f, 1000.0f);
            RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);

            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            // Phase 5e: V3 RenderGraph 経由 (V2 JsonLayoutEngine.renderElement 削除)
            // facade 化 Phase 2-b: 内部 V3LayoutCache / RenderGraph / RenderCtx を com.manta.api 経由に
            com.manta.api.render.WikiPrebuild.renderLayout(root, g, mc.font, PREBUILD_HANDLER);
            g.flush();

            NativeImage img = new NativeImage(targetW, targetH, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            img.downloadTexture(0, false);
            img.flipY();
            return img;
        } catch (Throwable t) {
            LOGGER.error("[WikiPrebuild] off-screen render failed: {}", t.getMessage(), t);
            return null;
        } finally {
            if (target != null) target.destroyBuffers();
            mainTarget.bindWrite(true);
            RenderSystem.setProjectionMatrix(savedProj, savedSort);
        }
    }

    private static void registerDynamicTexture(String name, String lang, NativeImage img) {
        // 言語付き id と (互換用) 言語なし id の両方で登録 → embed:screen が
        // どちらの形式でも引ける
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation locLang = ResourceLocation.fromNamespaceAndPath(
                TrainSystemUtilities.MOD_ID, "textures/wiki/screens/" + name + "__" + lang + ".png");
        // NativeImage 1 枚を 2 つの texture で共有するため、複製する
        NativeImage copy = copyImage(img);
        DynamicTexture dt1 = new DynamicTexture(img);
        mc.getTextureManager().register(locLang, dt1);
        registeredTextures.add(locLang);
        // 高解像度キャプチャの bilinear downsample → 鮮明な wiki preview
        applyBilinear(dt1);

        if (copy != null) {
            // legacy id (lang なし) — 当該言語で最後に prebuild したものが残る
            ResourceLocation locLegacy = ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "textures/wiki/screens/" + name + ".png");
            DynamicTexture dt2 = new DynamicTexture(copy);
            mc.getTextureManager().register(locLegacy, dt2);
            registeredTextures.add(locLegacy);
            applyBilinear(dt2);
        }

        // PNG として gamedir/screenshots/wiki に永続保存 (assets bundling 用、dev 限定)。
        // SECURITY (TSU-WIKI-001): production では untrusted resource から disk 書込みしない。
        if (net.neoforged.fml.loading.FMLEnvironment.production) return;
        try {
            Path output = Paths.get(mc.gameDirectory.getPath(),
                    "screenshots", "wiki", name + "__" + lang + ".png");
            java.nio.file.Files.createDirectories(output.getParent());
            // img は DynamicTexture が保持 → ここでは copy from texture せず
            // 既に bound されている img の native data を file 書出し用に別途 copy
            NativeImage saveCopy = copyImage(img);
            if (saveCopy != null) {
                try { saveCopy.writeToFile(output); }
                finally { saveCopy.close(); }
            }
        } catch (Throwable ignored) {}
    }

    /** variant 付き texture を name__variant__lang として登録。
     *  alsoAsMain=true の場合は name__lang としても登録 (= main texture)。
     *  PNG 永続保存も同様に variant 名を含める。 */
    private static void registerVariantTexture(String name, String variant, String lang,
                                                 NativeImage img, boolean alsoAsMain) {
        Minecraft mc = Minecraft.getInstance();
        String fullName = name + "__" + variant;
        ResourceLocation locVariant = ResourceLocation.fromNamespaceAndPath(
                TrainSystemUtilities.MOD_ID, "textures/wiki/screens/" + fullName + "__" + lang + ".png");
        NativeImage variantCopy = copyImage(img);
        NativeImage mainCopy = alsoAsMain ? copyImage(img) : null;
        // variant texture
        DynamicTexture dtv = new DynamicTexture(img);
        mc.getTextureManager().register(locVariant, dtv);
        registeredTextures.add(locVariant);
        applyBilinear(dtv);
        // main texture (= variant なし) — 1 件目 variant のみ
        if (alsoAsMain && mainCopy != null) {
            ResourceLocation locMain = ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "textures/wiki/screens/" + name + "__" + lang + ".png");
            DynamicTexture dtm = new DynamicTexture(mainCopy);
            mc.getTextureManager().register(locMain, dtm);
            registeredTextures.add(locMain);
            applyBilinear(dtm);
            // legacy lang なし
            ResourceLocation locLegacy = ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "textures/wiki/screens/" + name + ".png");
            NativeImage legacyCopy = copyImage(variantCopy);
            if (legacyCopy != null) {
                DynamicTexture dtl = new DynamicTexture(legacyCopy);
                mc.getTextureManager().register(locLegacy, dtl);
                registeredTextures.add(locLegacy);
                applyBilinear(dtl);
            }
        }
        // PNG 永続保存 (dev 限定)。SECURITY (TSU-WIKI-001): production では disk 書込みしない。
        if (net.neoforged.fml.loading.FMLEnvironment.production) {
            if (variantCopy != null) variantCopy.close();
            return;
        }
        try {
            Path output = Paths.get(mc.gameDirectory.getPath(),
                    "screenshots", "wiki", fullName + "__" + lang + ".png");
            java.nio.file.Files.createDirectories(output.getParent());
            if (variantCopy != null) {
                try { variantCopy.writeToFile(output); }
                finally { variantCopy.close(); }
            }
        } catch (Throwable ignored) {}
    }

    private static NativeImage copyImage(NativeImage src) {
        if (src == null) return null;
        NativeImage dst = new NativeImage(src.format(), src.getWidth(), src.getHeight(), false);
        dst.copyFrom(src);
        return dst;
    }

    /** DynamicTexture に bilinear filter を設定。GL state を一時的に切替えて
     *  texParameter を発行する。失敗しても致命傷ではないので silently 無視。 */
    private static void applyBilinear(DynamicTexture tex) {
        try {
            int id = tex.getId();
            if (id <= 0) return;
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(id);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
        } catch (Throwable ignored) {}
    }

    // ===== PrebuildHandler — JsonLayoutEngine が要求する dynamic key を全て default で返す =====

    /** Wiki capture を「クリーンな main 画面」で撮るために、popup/dropdown/modal
     *  系の visibility flag は強制的に false を返す。これらは default では visible
     *  扱いされ得るため、prebuild 時にドロップダウンが開いた状態でキャプチャされる
     *  問題を防ぐ。
     *
     *  main content (entry list / button bar / share button 等) は含めない。 */
    private static final Set<String> FORCE_HIDDEN_KEYS = Set.of(
            "ann-cond-dd-visible",
            "ann-share-visible",
            "ann-incoming-share-visible",
            "ann-playing-frame-visible",
            "icon-editor-visible",
            "icon-target-dropdown-open",
            "pending-action-visible",
            "tab-sched-edit-visible",
            "group-selector-visible",
            "drop-hint-visible"
    );

    /** 各 tab の active flag → どの variant の時 true を返すか。
     *  default variant = "map" (route map tab)。
     *  variant が登録外なら全 false。 */
    private static final Map<String, String> TAB_ACTIVE_KEYS;
    static {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        // management-computer
        m.put("tab-map-active", "map");
        m.put("tab-trains-active", "trains");
        m.put("tab-trains-list", "trains");
        m.put("tab-procedural-active", "schedule");
        m.put("tab-sched-detail", "schedule");
        m.put("tab-sched-list-rows", "schedule");
        m.put("tab-stations-list", "stations");
        m.put("tab-stations-list-rows", "stations");
        m.put("tab-symbol-active", "symbol");
        m.put("tab-symbol-active-with-items", "symbol");
        // transit-terminal
        m.put("tt-tab-top", "top");
        m.put("tt-tab-schedule", "schedule");
        m.put("tt-tab-map", "map");
        m.put("tt-tab-settings", "settings");
        TAB_ACTIVE_KEYS = java.util.Collections.unmodifiableMap(m);
    }

    /** prebuild render 中の variant context。render thread での single-threaded
     *  動作前提のため、ThreadLocal なし。 */
    private static volatile String currentVariant = "map";

    private static boolean isHiddenByPattern(String key) {
        if (key == null) return false;
        String k = key.toLowerCase();
        return k.contains("dropdown")
                || k.contains("-popup")
                || k.contains("-modal")
                || k.contains("-dd-")
                || k.endsWith("-dd")
                || k.endsWith("-open");
    }

    private static final JsonLayoutHandler PREBUILD_HANDLER = new JsonLayoutHandler() {
        @Override public String getDynamicText(String[] classes, String defaultText) { return defaultText; }
        @Override public Integer getDynamicNumber(String[] classes, String key, int defaultValue) { return defaultValue; }
        @Override public Integer getDynamicColor(String[] classes, String key, int defaultArgb) { return null; }
        @Override public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
            if (FORCE_HIDDEN_KEYS.contains(key) || isHiddenByPattern(key)) return false;
            // tab activation: 現在 variant のタブだけ true。それ以外は false。
            // これで全タブの内容が重なってキャプチャされる問題を防ぐ。
            String variantFor = TAB_ACTIVE_KEYS.get(key);
            if (variantFor != null) {
                return variantFor.equals(currentVariant);
            }
            return defaultValue;
        }
        @Override public ImageRef getDynamicImage(String[] classes, String key) { return null; }
        @Override public void drawCanvas(GuiGraphics g, String[] classes, String key,
                                          int x, int y, int w, int h, int mouseX, int mouseY) {}
        @Override public void onElementClick(String[] classes, int mouseX, int mouseY) {}
        @Override public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {}
    };
}
