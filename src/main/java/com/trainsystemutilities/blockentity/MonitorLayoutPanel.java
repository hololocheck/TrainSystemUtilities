package com.trainsystemutilities.blockentity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * モニターレイアウトのパネル配置データ。
 * 各パネルはモニター上の位置・サイズ・表示項目・枠表示を持つ。
 */
public class MonitorLayoutPanel {

    public enum PanelType {
        ROUTE_MAP("tsu.monitor.route_map"),
        TRAIN_LIST("tsu.monitor.train_list"),
        SCHEDULE("tsu.monitor.schedule"),
        STATION_COUNT("tsu.monitor.station_count"),
        TRAIN_COUNT("tsu.monitor.train_count"),
        SIGNAL_COUNT("tsu.monitor.signal_count"),
        CLOCK("tsu.monitor.clock");

        private final String translationKey;
        PanelType(String translationKey) { this.translationKey = translationKey; }
        public String getTranslationKey() { return translationKey; }
        public String getDisplayName() {
            return net.minecraft.network.chat.Component.translatable(translationKey).getString();
        }
    }

    private PanelType type;
    private float x, y;
    private float width, height;
    private boolean showBorder = true;
    private int fontSize = 0; // 0=auto
    // 路線マップ用個別サイズ設定
    private int trainIconSize = 0;   // 0=auto
    private int stationIconSize = 0; // 0=auto
    private int signalIconSize = 0;  // 0=auto
    private int mapTextSize = 0;     // 0=auto
    private boolean mapShowText = false; // 路線マップのテキスト表示（デフォルトOFF）

    public MonitorLayoutPanel(PanelType type, float x, float y, float width, float height) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public MonitorLayoutPanel(PanelType type, float x, float y, float width, float height, boolean showBorder) {
        this(type, x, y, width, height);
        this.showBorder = showBorder;
    }

    public MonitorLayoutPanel(PanelType type, float x, float y, float width, float height, boolean showBorder, int fontSize) {
        this(type, x, y, width, height, showBorder);
        this.fontSize = fontSize;
    }

    /** フルコピー用 */
    public MonitorLayoutPanel copy() {
        var p = new MonitorLayoutPanel(type, x, y, width, height, showBorder, fontSize);
        p.trainIconSize = trainIconSize;
        p.stationIconSize = stationIconSize;
        p.signalIconSize = signalIconSize;
        p.mapTextSize = mapTextSize;
        p.mapShowText = mapShowText;
        return p;
    }

    // Getters/Setters
    public PanelType getType() { return type; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }
    public boolean isShowBorder() { return showBorder; }
    public void setShowBorder(boolean show) { this.showBorder = show; }
    public void toggleBorder() { this.showBorder = !this.showBorder; }
    public int getFontSize() { return fontSize; }
    public void setFontSize(int size) { this.fontSize = Math.max(0, Math.min(256, size)); }
    public int getTrainIconSize() { return trainIconSize; }
    public void setTrainIconSize(int s) { this.trainIconSize = Math.max(0, Math.min(32, s)); }
    public int getStationIconSize() { return stationIconSize; }
    public void setStationIconSize(int s) { this.stationIconSize = Math.max(0, Math.min(32, s)); }
    public int getSignalIconSize() { return signalIconSize; }
    public void setSignalIconSize(int s) { this.signalIconSize = Math.max(0, Math.min(32, s)); }
    public int getMapTextSize() { return mapTextSize; }
    public void setMapTextSize(int s) { this.mapTextSize = Math.max(0, Math.min(64, s)); }
    public boolean isMapShowText() { return mapShowText; }
    public void setMapShowText(boolean show) { this.mapShowText = show; }
    public void toggleMapShowText() { this.mapShowText = !this.mapShowText; }

    // ベゼル範囲を考慮した位置制限（ベゼル = 各辺の約3%）
    private static final float BEZEL = 0.03f;
    public void setX(float x) { this.x = Math.max(BEZEL, Math.min(1 - BEZEL - width, x)); }
    public void setY(float y) { this.y = Math.max(BEZEL, Math.min(1 - BEZEL - height, y)); }
    public void setWidth(float w) { this.width = Math.max(0.05f, Math.min(1 - BEZEL * 2, w)); }
    public void setHeight(float h) { this.height = Math.max(0.05f, Math.min(1 - BEZEL * 2, h)); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type.name());
        tag.putFloat("X", x);
        tag.putFloat("Y", y);
        tag.putFloat("W", width);
        tag.putFloat("H", height);
        tag.putBoolean("Border", showBorder);
        tag.putInt("FontSize", fontSize);
        tag.putInt("TrainIcon", trainIconSize);
        tag.putInt("StationIcon", stationIconSize);
        tag.putInt("SignalIcon", signalIconSize);
        tag.putInt("MapText", mapTextSize);
        tag.putBoolean("MapShowText", mapShowText);
        return tag;
    }

    public static MonitorLayoutPanel load(CompoundTag tag) {
        try {
            PanelType type = PanelType.valueOf(tag.getString("Type"));
            var p = new MonitorLayoutPanel(type,
                    tag.getFloat("X"), tag.getFloat("Y"),
                    tag.getFloat("W"), tag.getFloat("H"));
            p.showBorder = !tag.contains("Border") || tag.getBoolean("Border");
            p.fontSize = tag.getInt("FontSize");
            p.trainIconSize = tag.getInt("TrainIcon");
            p.stationIconSize = tag.getInt("StationIcon");
            p.signalIconSize = tag.getInt("SignalIcon");
            p.mapTextSize = tag.getInt("MapText");
            p.mapShowText = tag.contains("MapShowText") && tag.getBoolean("MapShowText");
            return p;
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MonitorLayout] panel NBT load failed", e);
            return null;
        }
    }

    public static ListTag saveList(List<MonitorLayoutPanel> panels) {
        ListTag list = new ListTag();
        for (var p : panels) list.add(p.save());
        return list;
    }

    public static List<MonitorLayoutPanel> loadList(ListTag list) {
        List<MonitorLayoutPanel> panels = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            var p = load(list.getCompound(i));
            if (p != null) panels.add(p);
        }
        return panels;
    }
}
