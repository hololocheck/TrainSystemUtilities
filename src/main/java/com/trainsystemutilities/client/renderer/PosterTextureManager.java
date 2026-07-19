package com.trainsystemutilities.client.renderer;

import com.manta.api.image.MantaImage;
import com.mojang.blaze3d.platform.NativeImage;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.ImageDownloadRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クライアント側の画像テクスチャ管理。
 * サーバーからダウンロードした画像をDynamicTextureとしてキャッシュ。
 */
public class PosterTextureManager {

    // LinkedHashMap(accessOrder=true)でLRU順序を維持
    private static final Map<UUID, CachedTexture> textureCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true));
    private static final Set<UUID> pendingRequests = ConcurrentHashMap.newKeySet();
    private static final int MAX_CACHE_SIZE = 32;
    /** decode に失敗した画像は {@value FAILED_RETRY_MS} ms 間は再要求しない (毎フレーム失敗ループを避ける)。 */
    private static final long FAILED_RETRY_MS = 30_000;
    private static final Map<UUID, Long> failedAt = new ConcurrentHashMap<>();

    private record CachedTexture(ResourceLocation location, int width, int height) {}

    /**
     * UUIDからテクスチャを取得。キャッシュになければサーバーにダウンロード要求を送信。
     */
    public static ResourceLocation getOrRequest(UUID imageId) {
        CachedTexture cached = textureCache.get(imageId);
        if (cached != null) return cached.location;

        // 直近で失敗した画像は 30 秒は再要求しない。
        // これが無いと decode 失敗した画像 1 枚が視界にあるだけで毎フレーム要求を投げ続ける。
        Long failed = failedAt.get(imageId);
        if (failed != null && System.currentTimeMillis() - failed < FAILED_RETRY_MS) return null;

        // まだ要求していなければダウンロード要求
        if (pendingRequests.add(imageId)) {
            PacketDistributor.sendToServer(new ImageDownloadRequestPayload(imageId));
        }
        return null; // ダウンロード中
    }

    /**
     * 画像のサイズを取得（キャッシュ済みの場合のみ）。
     */
    public static int[] getDimensions(UUID imageId) {
        CachedTexture cached = textureCache.get(imageId);
        if (cached != null) return new int[]{cached.width, cached.height};
        return null;
    }

    /**
     * サーバーから画像データを受信した時にクライアント側で呼ばれる。
     */
    public static void onImageReceived(UUID imageId, byte[] data) {
        pendingRequests.remove(imageId);
        try {
            // LRU的にキャッシュサイズ制限
            if (textureCache.size() >= MAX_CACHE_SIZE) {
                var oldest = textureCache.entrySet().iterator().next();
                releaseTexture(oldest.getKey());
            }

            NativeImage image = null;
            try {
                // MantaImage 経由 (NativeImage.read 単体は PNG signature ゲートで JPEG を弾く)。
                image = MantaImage.decode(data);
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.error("[Poster] image decode failed for {} ({} bytes, magic={})",
                        imageId, data.length, MantaImage.magic(data), e);
                failedAt.put(imageId, System.currentTimeMillis());
                return;
            }
            DynamicTexture dynamicTexture = new DynamicTexture(image);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    "trainsystemutilities", "poster/" + imageId.toString().replace("-", ""));
            Minecraft.getInstance().getTextureManager().register(loc, dynamicTexture);

            textureCache.put(imageId, new CachedTexture(loc, image.getWidth(), image.getHeight()));
            failedAt.remove(imageId);
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Poster] onImageReceived failed for {}", imageId, e);
        }
    }

    private static void releaseTexture(UUID imageId) {
        CachedTexture cached = textureCache.remove(imageId);
        if (cached != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(cached.location);
            } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Poster] texture release failed", e); }
        }
    }

    public static void cleanup() {
        for (UUID id : new ArrayList<>(textureCache.keySet())) {
            releaseTexture(id);
        }
        pendingRequests.clear();
        failedAt.clear();
    }
}
