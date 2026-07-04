package com.trainsystemutilities.client.renderer;

/**
 * Railway monitor の layout constants。 IR template
 * ({@code layouts/renderers/railway-monitor.json}) と handler 側計算で共有される。
 */
public final class MonitorLayoutConstants {
    public static final int MAX_ARRIVED = 6;
    public static final int MAX_DEP_ARRIVED = 4;
    public static final int MAX_NEXT = 12;
    public static final int MAX_DEP_NEXT = 4;

    public static final int TRACK_PANEL_W = 128;
    public static final int PAD = 18;
    public static final int SECTION_GAP = 2;
    public static final int SECTION_TITLE_H = 12;
    public static final int TRACK_PANEL_TOP_PAD = 20;
    public static final int TRACK_PANEL_GAP = 8;
    /** 番線番号の text center 目標 Y (= 路線記号アイコン中心 y=61 と垂直整列)。 */
    public static final int NUMBER_CENTER_Y = 61;

    private MonitorLayoutConstants() {}
}
