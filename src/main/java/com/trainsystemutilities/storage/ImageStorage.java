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

    private static Path getStorageDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(STORAGE_DIR);
    }

    @Nullable
    public static UUID save(MinecraftServer server, byte[] imageData, String fileName) {
        UUID id = UUID.randomUUID();
        try {
            Path dir = getStorageDir(server);
            Files.createDirectories(dir);
            Files.write(dir.resolve(id + ".img"), imageData);
            Files.writeString(dir.resolve(id + ".meta"), fileName);
            TrainSystemUtilities.LOGGER.info("[ImageStorage] Saved image {} ({} bytes) as {}", fileName, imageData.length, id);
            return id;
        } catch (IOException e) {
            TrainSystemUtilities.LOGGER.error("[ImageStorage] Failed to save image {}", id, e);
            return null;
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
