package com.trainsystemutilities.storage;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * サーバー側の画像ファイルストレージ。
 * 画像データは <world>/trainsystemutilities_images/<uuid>.img に保存。
 */
public class ImageStorage {

    private static final String STORAGE_DIR = "trainsystemutilities_images";
    public static final int MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5MB

    // === SECURITY (TSU-STORAGE-001): quota / dimension budget ===
    /** 1 poster あたりの画像枚数上限。 */
    public static final int MAX_POSTER_IMAGES = 64;
    /** world 全体の画像 total byte 上限 (= poster 画像による disk 枯渇防止)。 */
    public static final long MAX_WORLD_IMAGE_BYTES = 512L * 1024 * 1024;
    /** decode 後 pixel 面積の上限 (= 巨大 dimension 画像による client native allocation 防止、~4K)。 */
    public static final long MAX_IMAGE_PIXELS = 8_294_400L;

    private static Path getStorageDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(STORAGE_DIR);
    }

    @Nullable
    public static UUID save(MinecraftServer server, byte[] imageData, String fileName) {
        UUID id = UUID.randomUUID();
        Path dir = getStorageDir(server);
        Path img = dir.resolve(id + ".img");
        Path meta = dir.resolve(id + ".meta");
        Path tmp = dir.resolve(id + ".img.tmp");
        try {
            Files.createDirectories(dir);
            // SECURITY (TSU-STORAGE-001): temp へ書いてから move、meta も書く。途中失敗時は
            // 両方削除して partial pair (.img だけ / .meta だけ) を残さない。
            Files.write(tmp, imageData);
            try {
                Files.move(tmp, img, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, img, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(meta, fileName);
            TrainSystemUtilities.LOGGER.info("[ImageStorage] Saved image {} ({} bytes) as {}", fileName, imageData.length, id);
            return id;
        } catch (IOException e) {
            TrainSystemUtilities.LOGGER.error("[ImageStorage] Failed to save image {}", id, e);
            // rollback: partial file を残さない
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            try { Files.deleteIfExists(img); } catch (IOException ignored) {}
            try { Files.deleteIfExists(meta); } catch (IOException ignored) {}
            return null;
        }
    }

    /** SECURITY (TSU-STORAGE-001): world 全体の画像 total byte (.img の合計)。quota 判定用。 */
    public static long totalBytes(MinecraftServer server) {
        Path dir = getStorageDir(server);
        if (!Files.isDirectory(dir)) return 0L;
        try (var stream = Files.newDirectoryStream(dir, "*.img")) {
            long sum = 0L;
            for (Path p : stream) {
                try { sum += Files.size(p); } catch (IOException ignored) {}
            }
            return sum;
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * SECURITY (TSU-STORAGE-001): magic + decode 後 dimension を検証する。
     * 未認識フォーマットや面積超過は error 文字列を返す (= 保存拒否)。header のみ読むため軽量。
     */
    @Nullable
    public static String validateImageContent(byte[] data) {
        try (var iis = javax.imageio.ImageIO.createImageInputStream(
                new java.io.ByteArrayInputStream(data))) {
            if (iis == null) return "unreadable image";
            var readers = javax.imageio.ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return "unrecognized image format";
            var reader = readers.next();
            try {
                reader.setInput(iis);
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                if (w <= 0 || h <= 0) return "invalid image dimensions";
                if (w * h > MAX_IMAGE_PIXELS) {
                    return "image dimensions too large (" + w + "x" + h + ")";
                }
            } finally {
                reader.dispose();
            }
            return null;
        } catch (Exception e) {
            return "image validation failed";
        }
    }

    @Nullable
    public static byte[] load(MinecraftServer server, UUID id) {
        Path file = getStorageDir(server).resolve(id + ".img");
        if (!Files.exists(file)) return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            TrainSystemUtilities.LOGGER.error("[ImageStorage] Failed to load image {}", id, e);
            return null;
        }
    }

    @Nullable
    public static String getFileName(MinecraftServer server, UUID id) {
        Path meta = getStorageDir(server).resolve(id + ".meta");
        if (!Files.exists(meta)) return null;
        try {
            return Files.readString(meta).trim();
        } catch (IOException e) {
            return null;
        }
    }

    public static void delete(MinecraftServer server, UUID id) {
        try {
            Files.deleteIfExists(getStorageDir(server).resolve(id + ".img"));
            Files.deleteIfExists(getStorageDir(server).resolve(id + ".meta"));
        } catch (IOException e) {
            TrainSystemUtilities.LOGGER.error("[ImageStorage] Failed to delete image {}", id, e);
        }
    }

    @Nullable
    public static String validateSize(byte[] data) {
        if (data.length > MAX_IMAGE_SIZE) {
            return "Image too large (" + (data.length / 1024 / 1024) + " MB). Maximum is 5 MB.";
        }
        return null;
    }
}
