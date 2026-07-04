package com.trainsystemutilities.client.structure;

import com.mojang.blaze3d.platform.NativeImage;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 「水色帯ピクセル」 を任意 ARGB で置換した動的テクスチャをキャッシュ。
 *
 * <p>NativeImage で実 ピクセルを bandColor に置換した PNG を動的生成 → TextureManager に
 * 登録 → GeoModel.getTextureResource で 各 BE の色から ResourceLocation を返す。
 *
 * <p>cache key = (base path, argb)。
 */
public final class BandTextureCache {

    private BandTextureCache() {}

    private record CacheKey(ResourceLocation base, int argb) {}

    private static final Map<CacheKey, ResourceLocation> CACHE = new HashMap<>();

    /** 汎用 API: 任意 base texture を bandColor で置換した動的 RL を返す。 */
    public static ResourceLocation get(ResourceLocation baseTexture, int argb) {
        CacheKey key = new CacheKey(baseTexture, argb);
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) return cached;
        ResourceLocation generated = generate(baseTexture, argb);
        CACHE.put(key, generated);
        return generated;
    }

    /** ホーム柵互換 API (= 既存呼び出し用)。 */
    public static ResourceLocation get(int length, int argb) {
        return get(ResourceLocation.fromNamespaceAndPath(
                TrainSystemUtilities.MOD_ID,
                "textures/block/platform_fence_" + length + "m.png"), argb);
    }

    private static ResourceLocation generate(ResourceLocation base, int argb) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Resource res = mc.getResourceManager().getResourceOrThrow(base);
            try (InputStream in = res.open()) {
                NativeImage img = NativeImage.read(in);
                replaceCyanWith(img, argb);
                DynamicTexture dt = new DynamicTexture(img);
                // ResourceLocation の path 制約 (= [a-z0-9_./-]) のため base path を hash 化して使う
                String safe = base.getPath().replaceAll("[^a-z0-9_./-]", "_");
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                        TrainSystemUtilities.MOD_ID,
                        String.format("dynamic/%s_%08x", safe, argb));
                mc.getTextureManager().register(rl, dt);
                return rl;
            }
        } catch (Throwable t) {
            return base;
        }
    }

    /** 水色ピクセル (= cyan-ish) を bandColor で置換。 */
    private static void replaceCyanWith(NativeImage img, int argb) {
        int newR = (argb >> 16) & 0xFF;
        int newG = (argb >>  8) & 0xFF;
        int newB =  argb        & 0xFF;
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = img.getPixelRGBA(x, y);
                int a = (abgr >> 24) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >>  8) & 0xFF;
                int r =  abgr        & 0xFF;
                // 不透明な水色のみ置換 (= alpha < 250 はガラス領域として skip)
                if (a >= 250 && isBandMarkerCyan(r, g, b)) {
                    float origBrightness = (b + g + r * 0.5f) / 700f;
                    if (origBrightness > 1f) origBrightness = 1f;
                    if (origBrightness < 0.2f) origBrightness = 0.2f;
                    int outR = (int) (newR * origBrightness);
                    int outG = (int) (newG * origBrightness);
                    int outB = (int) (newB * origBrightness);
                    int newAbgr = (a << 24) | (outB << 16) | (outG << 8) | outR;
                    img.setPixelRGBA(x, y, newAbgr);
                }
            }
        }
    }

    private static boolean isBandMarkerCyan(int r, int g, int b) {
        return r <= 100 && g >= 180 && b >= 230;
    }
}
