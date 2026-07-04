package com.trainsystemutilities.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.ImageDownloadRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
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

    private record CachedTexture(ResourceLocation location, int width, int height) {}

    /**
     * UUIDからテクスチャを取得。キャッシュになければサーバーにダウンロード要求を送信。
     */
    public static ResourceLocation getOrRequest(UUID imageId) {
        CachedTexture cached = textureCache.get(imageId);
        if (cached != null) return cached.location;

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
                image = NativeImage.read(new ByteArrayInputStream(data));
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.error("[Poster] image decode failed for {} ({} bytes)", imageId, data.length, e);
                return;
            }
            DynamicTexture dynamicTexture = new DynamicTexture(image);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    "trainsystemutilities", "poster/" + imageId.toString().replace("-", ""));
            Minecraft.getInstance().getTextureManager().register(loc, dynamicTexture);

            textureCache.put(imageId, new CachedTexture(loc, image.getWidth(), image.getHeight()));
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
    }
}
