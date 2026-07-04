package com.trainsystemutilities.blockentity;

import net.minecraft.nbt.CompoundTag;

/**
 * {@link RailwayManagementBlockEntity} の monitor 表示設定 (front/back の track/clock global 値、
 * 路線記号、行ごとの色) を保持する純データホルダ。 god-class 分割 (v2) で BE から切り出した。
 *
 * <p>本クラスは値の保持と NBT 直列化のみを担い、 {@code setChanged()} / monitor 群への伝播 /
 * {@code sendBlockUpdated()} 等の副作用は呼び出し側 (BE) が担う。 NBT key と default 値は
 * 抽出前と完全一致 (= セーブ互換)。
 */
public final class MonitorDisplaySettings {

    // --- global track / clock (front + back) ---
    private int globalTrackNumber = 0;
    private int globalTrackFontSize = 0;
    private int globalTrackPosition = 0;
    private int globalBackTrackNumber = 0;
    private int globalBackTrackFontSize = 0;
    private int globalBackTrackPosition = 0;
    private int globalClockVisible = 1;
    private int globalClockFontSize = 0;
    private int globalBackClockVisible = 1;
    private int globalBackClockFontSize = 0;

    // --- 路線記号 ---
    private String lineSymbolLetters = "";
    private int lineSymbolNumber = 0;
    private String lineSymbolBorderColor = "";
    private float lineSymbolSize = 1.0f;  // 0.5〜2.0
    private int lineSymbolPosition = 0;   // 0=左上, 1=右上, 2=左下, 3=右下

    // --- 行ごとの色 (front) ---
    private String colorArrTime = "";
    private String colorDepTime = "";
    private String colorStopInfo = "";
    private String colorRouteType = "";
    private String colorStopSec = "";
    private String colorTrainName = "";
    private String colorNextName = "";
    private String colorSectionTitle = "";
    private String colorCountdown = "";
    private String colorTrackNumber = "";

    // --- 行ごとの色 (back) ---
    private String backColorArrTime = "";
    private String backColorDepTime = "";
    private String backColorStopInfo = "";
    private String backColorRouteType = "";
    private String backColorStopSec = "";
    private String backColorTrainName = "";
    private String backColorNextName = "";
    private String backColorSectionTitle = "";
    private String backColorCountdown = "";
    private String backColorTrackNumber = "";

    // === global track / clock accessors (pure; BE が副作用を付与) ===

    public int getGlobalTrackNumber() { return globalTrackNumber; }
    public void setGlobalTrackNumber(int v) { globalTrackNumber = v; }
    public int getGlobalTrackFontSize() { return globalTrackFontSize; }
    public void setGlobalTrackFontSize(int v) { globalTrackFontSize = v; }
    public int getGlobalTrackPosition() { return globalTrackPosition; }
    public void setGlobalTrackPosition(int v) { globalTrackPosition = v; }
    public int getGlobalBackTrackNumber() { return globalBackTrackNumber; }
    public void setGlobalBackTrackNumber(int v) { globalBackTrackNumber = v; }
    public int getGlobalBackTrackFontSize() { return globalBackTrackFontSize; }
    public void setGlobalBackTrackFontSize(int v) { globalBackTrackFontSize = v; }
    public int getGlobalBackTrackPosition() { return globalBackTrackPosition; }
    public void setGlobalBackTrackPosition(int v) { globalBackTrackPosition = v; }
    public int getGlobalClockVisible() { return globalClockVisible; }
    public void setGlobalClockVisible(int v) { globalClockVisible = v; }
    public int getGlobalClockFontSize() { return globalClockFontSize; }
    public void setGlobalClockFontSize(int v) { globalClockFontSize = v; }
    public int getGlobalBackClockVisible() { return globalBackClockVisible; }
    public void setGlobalBackClockVisible(int v) { globalBackClockVisible = v; }
    public int getGlobalBackClockFontSize() { return globalBackClockFontSize; }
    public void setGlobalBackClockFontSize(int v) { globalBackClockFontSize = v; }

    // === 路線記号 accessors ===

    public String getLineSymbolLetters() { return lineSymbolLetters; }
    public int getLineSymbolNumber() { return lineSymbolNumber; }
    public String getLineSymbolBorderColor() { return lineSymbolBorderColor; }
    public float getLineSymbolSize() { return lineSymbolSize; }
    public int getLineSymbolPosition() { return lineSymbolPosition; }
    public boolean hasLineSymbolLetters() { return lineSymbolLetters != null && !lineSymbolLetters.isEmpty(); }

    public void setLineSymbol(String letters, int number, String borderColor) {
        this.lineSymbolLetters = letters != null ? letters : "";
        this.lineSymbolNumber = number;
        this.lineSymbolBorderColor = borderColor != null ? borderColor : "";
    }
    public void setLineSymbolSize(float size) { this.lineSymbolSize = Math.max(0.5f, Math.min(2.0f, size)); }
    public void setLineSymbolPosition(int pos) { this.lineSymbolPosition = Math.max(0, Math.min(3, pos)); }

    // === 行ごとの色 (key 駆動) ===

    public String getColor(String key) {
        return switch (key) {
            case "arrTime" -> colorArrTime;
            case "depTime" -> colorDepTime;
            case "stopInfo" -> colorStopInfo;
            case "routeType" -> colorRouteType;
            case "stopSec" -> colorStopSec;
            case "trainName" -> colorTrainName;
            case "nextName" -> colorNextName;
            case "sectionTitle" -> colorSectionTitle;
            case "countdown" -> colorCountdown;
            case "trackNumber" -> colorTrackNumber;
            case "back.arrTime" -> backColorArrTime;
            case "back.depTime" -> backColorDepTime;
            case "back.stopInfo" -> backColorStopInfo;
            case "back.routeType" -> backColorRouteType;
            case "back.stopSec" -> backColorStopSec;
            case "back.trainName" -> backColorTrainName;
            case "back.nextName" -> backColorNextName;
            case "back.sectionTitle" -> backColorSectionTitle;
            case "back.countdown" -> backColorCountdown;
            case "back.trackNumber" -> backColorTrackNumber;
            default -> "";
        };
    }

    public void setColor(String key, String value) {
        switch (key) {
            case "arrTime" -> colorArrTime = value;
            case "depTime" -> colorDepTime = value;
            case "stopInfo" -> colorStopInfo = value;
            case "routeType" -> colorRouteType = value;
            case "stopSec" -> colorStopSec = value;
            case "trainName" -> colorTrainName = value;
            case "nextName" -> colorNextName = value;
            case "sectionTitle" -> colorSectionTitle = value;
            case "countdown" -> colorCountdown = value;
            case "trackNumber" -> colorTrackNumber = value;
            case "back.arrTime" -> backColorArrTime = value;
            case "back.depTime" -> backColorDepTime = value;
            case "back.stopInfo" -> backColorStopInfo = value;
            case "back.routeType" -> backColorRouteType = value;
            case "back.stopSec" -> backColorStopSec = value;
            case "back.trainName" -> backColorTrainName = value;
            case "back.nextName" -> backColorNextName = value;
            case "back.sectionTitle" -> backColorSectionTitle = value;
            case "back.countdown" -> backColorCountdown = value;
            case "back.trackNumber" -> backColorTrackNumber = value;
        }
    }

    public String getColorOrDefault(String key, String defaultColor) {
        String c = getColor(key);
        return (c != null && !c.isEmpty()) ? c : defaultColor;
    }

    // === NBT (key / default は抽出前と完全一致) ===

    public void writeNbt(CompoundTag tag) {
        tag.putInt("GlobalTrackNumber", globalTrackNumber);
        tag.putInt("GlobalTrackFontSize", globalTrackFontSize);
        tag.putInt("GlobalTrackPosition", globalTrackPosition);
        tag.putInt("GlobalBackTrackNumber", globalBackTrackNumber);
        tag.putInt("GlobalBackTrackFontSize", globalBackTrackFontSize);
        tag.putInt("GlobalBackTrackPosition", globalBackTrackPosition);
        tag.putInt("GlobalClockVisible", globalClockVisible);
        tag.putInt("GlobalClockFontSize", globalClockFontSize);
        tag.putInt("GlobalBackClockVisible", globalBackClockVisible);
        tag.putInt("GlobalBackClockFontSize", globalBackClockFontSize);

        // 路線記号 (空のときは保存しない = 抽出前の挙動を維持)
        if (!lineSymbolLetters.isEmpty()) {
            tag.putString("LSLetters", lineSymbolLetters);
            tag.putInt("LSNumber", lineSymbolNumber);
            tag.putString("LSColor", lineSymbolBorderColor);
            tag.putFloat("LSSize", lineSymbolSize);
            tag.putInt("LSPos", lineSymbolPosition);
        }
        // Color settings
        tag.putString("ColorArrTime", colorArrTime);
        tag.putString("ColorDepTime", colorDepTime);
        tag.putString("ColorStopInfo", colorStopInfo);
        tag.putString("ColorRouteType", colorRouteType);
        tag.putString("ColorStopSec", colorStopSec);
        tag.putString("ColorTrainName", colorTrainName);
        tag.putString("ColorNextName", colorNextName);
        tag.putString("ColorSectionTitle", colorSectionTitle);
        tag.putString("ColorCountdown", colorCountdown);
        tag.putString("ColorTrackNumber", colorTrackNumber);
        // Back face colors
        tag.putString("BackColorArrTime", backColorArrTime);
        tag.putString("BackColorDepTime", backColorDepTime);
        tag.putString("BackColorStopInfo", backColorStopInfo);
        tag.putString("BackColorRouteType", backColorRouteType);
        tag.putString("BackColorStopSec", backColorStopSec);
        tag.putString("BackColorTrainName", backColorTrainName);
        tag.putString("BackColorNextName", backColorNextName);
        tag.putString("BackColorSectionTitle", backColorSectionTitle);
        tag.putString("BackColorCountdown", backColorCountdown);
        tag.putString("BackColorTrackNumber", backColorTrackNumber);
    }

    public void readNbt(CompoundTag tag) {
        globalTrackNumber = tag.getInt("GlobalTrackNumber");
        globalTrackFontSize = tag.getInt("GlobalTrackFontSize");
        globalTrackPosition = tag.getInt("GlobalTrackPosition");
        globalBackTrackNumber = tag.getInt("GlobalBackTrackNumber");
        globalBackTrackFontSize = tag.getInt("GlobalBackTrackFontSize");
        globalBackTrackPosition = tag.getInt("GlobalBackTrackPosition");
        globalClockVisible = tag.contains("GlobalClockVisible") ? tag.getInt("GlobalClockVisible") : 1;
        globalClockFontSize = tag.getInt("GlobalClockFontSize");
        globalBackClockVisible = tag.contains("GlobalBackClockVisible") ? tag.getInt("GlobalBackClockVisible") : 1;
        globalBackClockFontSize = tag.getInt("GlobalBackClockFontSize");

        // 路線記号
        lineSymbolLetters = tag.getString("LSLetters");
        lineSymbolNumber = tag.getInt("LSNumber");
        lineSymbolBorderColor = tag.getString("LSColor");
        lineSymbolSize = tag.contains("LSSize") ? tag.getFloat("LSSize") : 1.0f;
        lineSymbolPosition = tag.getInt("LSPos");
        // Color settings
        colorArrTime = tag.getString("ColorArrTime");
        colorDepTime = tag.getString("ColorDepTime");
        colorStopInfo = tag.getString("ColorStopInfo");
        colorRouteType = tag.getString("ColorRouteType");
        colorStopSec = tag.getString("ColorStopSec");
        colorTrainName = tag.getString("ColorTrainName");
        colorNextName = tag.getString("ColorNextName");
        colorSectionTitle = tag.getString("ColorSectionTitle");
        colorCountdown = tag.getString("ColorCountdown");
        colorTrackNumber = tag.getString("ColorTrackNumber");
        // Back face colors
        backColorArrTime = tag.getString("BackColorArrTime");
        backColorDepTime = tag.getString("BackColorDepTime");
        backColorStopInfo = tag.getString("BackColorStopInfo");
        backColorRouteType = tag.getString("BackColorRouteType");
        backColorStopSec = tag.getString("BackColorStopSec");
        backColorTrainName = tag.getString("BackColorTrainName");
        backColorNextName = tag.getString("BackColorNextName");
        backColorSectionTitle = tag.getString("BackColorSectionTitle");
        backColorCountdown = tag.getString("BackColorCountdown");
        backColorTrackNumber = tag.getString("BackColorTrackNumber");
    }
}
