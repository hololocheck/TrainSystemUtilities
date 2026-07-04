package com.trainsystemutilities.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 列車プリセットツール GUI のクライアント側設定 (ユーザ単位、ディスク永続化)。
 *
 * 場所: {@code config/trainsystemutilities/client_settings.json}
 *
 * 現状の設定:
 *   - persistDraggedModel: 列車プリセットブラウズ画面でドラッグして選択した車両モデルを
 *                           GUI を閉じても保持するか。
 */
public final class TrainPresetClientSettings {

    private static boolean persistDraggedModel = false;
    private static String lastSelectedPresetKey = "";
    private static String lastPendingPlaceKey = "";
    /** プリセットプレイス自動ログイン (true なら BrowseScreen 開時に未認証なら自動 authenticate)。 */
    private static boolean autoLogin = true;
    private static boolean loaded = false;

    private TrainPresetClientSettings() {}

    public static synchronized boolean isPersistDraggedModel() {
        ensureLoaded();
        return persistDraggedModel;
    }

    public static synchronized void setPersistDraggedModel(boolean v) {
        ensureLoaded();
        if (persistDraggedModel == v) return;
        persistDraggedModel = v;
        // OFF にしたら最後の選択 / pending もクリアする
        if (!v) {
            lastSelectedPresetKey = "";
            lastPendingPlaceKey = "";
        }
        save();
    }

    public static synchronized String getLastSelectedPresetKey() {
        ensureLoaded();
        return lastSelectedPresetKey;
    }

    public static synchronized void setLastSelectedPresetKey(String key) {
        ensureLoaded();
        String normalized = key == null ? "" : key;
        if (normalized.equals(lastSelectedPresetKey)) return;
        lastSelectedPresetKey = normalized;
        if (persistDraggedModel) save();
    }

    public static synchronized boolean isAutoLogin() {
        ensureLoaded();
        return autoLogin;
    }

    public static synchronized void setAutoLogin(boolean v) {
        ensureLoaded();
        if (autoLogin == v) return;
        autoLogin = v;
        save();
    }

    public static synchronized String getLastPendingPlaceKey() {
        ensureLoaded();
        return lastPendingPlaceKey;
    }

    public static synchronized void setLastPendingPlaceKey(String key) {
        ensureLoaded();
        String normalized = key == null ? "" : key;
        if (normalized.equals(lastPendingPlaceKey)) return;
        lastPendingPlaceKey = normalized;
        if (persistDraggedModel) save();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path file = settingsFile();
            if (!Files.exists(file)) return;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("persistDraggedModel"))
                persistDraggedModel = obj.get("persistDraggedModel").getAsBoolean();
            if (obj.has("lastSelectedPresetKey"))
                lastSelectedPresetKey = obj.get("lastSelectedPresetKey").getAsString();
            if (obj.has("lastPendingPlaceKey"))
                lastPendingPlaceKey = obj.get("lastPendingPlaceKey").getAsString();
            if (obj.has("autoLogin"))
                autoLogin = obj.get("autoLogin").getAsBoolean();
        } catch (Exception ex) {
            TrainSystemUtilities.LOGGER.warn("client settings load failed: {}", ex.getMessage());
        }
    }

    private static void save() {
        try {
            Path file = settingsFile();
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("persistDraggedModel", persistDraggedModel);
            obj.addProperty("lastSelectedPresetKey", lastSelectedPresetKey);
            obj.addProperty("lastPendingPlaceKey", lastPendingPlaceKey);
            obj.addProperty("autoLogin", autoLogin);
            Files.writeString(file, obj.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            TrainSystemUtilities.LOGGER.warn("client settings save failed: {}", ex.getMessage());
        }
    }

    private static Path settingsFile() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("config").resolve("trainsystemutilities").resolve("client_settings.json");
    }
}
