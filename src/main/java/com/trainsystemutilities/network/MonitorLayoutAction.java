package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.MonitorLayoutPanel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * モニターのレイアウト保存 / 色 / 有効化 — the management computer host's {@code monitor-layout} action
 * ({@link ManagementComputerData}; MonitorLayoutPayload until MANTA_7_CONCEPT C4). The data travels as SNBT.
 */
public final class MonitorLayoutAction {

    public static final int ACTION_SAVE_LAYOUT = 0; // data.L = MonitorLayoutPanel ListTag
    public static final int ACTION_SET_COLOR = 1;   // data.K / data.V (V 空文字 = リセット)
    public static final int ACTION_SET_ENABLED = 2; // data.E = boolean
    public static final int MAX_PANELS = 32;

    private MonitorLayoutAction() {}

    public static void apply(ManagementComputerBlockEntity mc, ServerLevel level, int action, CompoundTag data) {
        switch (action) {
            case ACTION_SAVE_LAYOUT -> {
                var list = MonitorLayoutPanel.loadList(
                        data.getList("L", net.minecraft.nbt.Tag.TAG_COMPOUND));
                if (list.size() > MAX_PANELS) return;
                for (var panel : list) {
                    panel.setWidth(panel.getWidth());
                    panel.setHeight(panel.getHeight());
                    panel.setX(panel.getX());
                    panel.setY(panel.getY());
                    panel.setFontSize(panel.getFontSize());
                    panel.setTrainIconSize(panel.getTrainIconSize());
                    panel.setStationIconSize(panel.getStationIconSize());
                    panel.setSignalIconSize(panel.getSignalIconSize());
                    panel.setMapTextSize(panel.getMapTextSize());
                }
                mc.getMonitorLayout().clear();
                mc.getMonitorLayout().addAll(list);
            }
            case ACTION_SET_COLOR -> {
                String k = data.getString("K");
                String v = data.getString("V");
                if (k.length() > 32 || v.length() > 16) return;
                mc.setColor(k, v);
            }
            case ACTION_SET_ENABLED -> mc.setMonitorEnabled(data.getBoolean("E"));
            default -> { /* action は宣言で 0..2 */ }
        }
        mc.setChanged();
        level.sendBlockUpdated(mc.getBlockPos(), mc.getBlockState(), mc.getBlockState(), 3);
    }
}
