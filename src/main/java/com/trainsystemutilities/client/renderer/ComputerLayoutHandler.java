package com.trainsystemutilities.client.renderer;

import belugalab.mcss3.anim.Animation;
import belugalab.mcss3.anim.Easing;
import belugalab.mcss3.screen.JsonLayoutHandler;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.MonitorLayoutPanel;
import com.trainsystemutilities.network.TrackNetworkScanner;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V3 stable IR handler for {@link ComputerPanelIrBuilder} templates.
 *
 * <p>Per-panel state を {@link #updateForPanel} で update してから render する。
 * IR は静的 (per panel-type) なので {@link MonitorWorldRenderer#renderComputerLayout}
 * 内で panel ごとに handler state を入れ替えて使用。
 */
public final class ComputerLayoutHandler implements JsonLayoutHandler {

    public static final int MAX_TRAINS = 16;
    public static final int MAX_SCHEDULE_ENTRIES = 12;

    // panel-specific state (= updateForPanel で毎フレーム代入)
    private MonitorLayoutPanel panel;
    private ManagementComputerBlockEntity computer;
    private TrackNetworkScanner.NetworkData netData;
    private Level level;
    private int pw, ph, fs, gap, pad, clockFs;
    private int cardH;
    private int slideDistance;
    private int trainListPage;
    private boolean trainListSlideIn;
    private int schedulePage;
    private boolean scheduleSlideIn;
    private List<TrackNetworkScanner.TrainInfo> trainListVisible = Collections.emptyList();
    private String currentScheduleTrainName;
    private List<String> scheduleEntries = Collections.emptyList();
    private int currentScheduleEntryIdx = -1;
    private int totalScheduleEntries = 0;
    private int totalScheduleTrains = 0;

    public void updateForPanel(MonitorLayoutPanel panel,
                                ManagementComputerBlockEntity computer,
                                TrackNetworkScanner.NetworkData netData,
                                Level level,
                                int pw, int ph, int fs, int gap, int pad, int clockFs,
                                int trainListPage, boolean trainListSlideIn,
                                int schedulePage, boolean scheduleSlideIn) {
        this.panel = panel;
        this.computer = computer;
        this.netData = netData;
        this.level = level;
        this.pw = pw; this.ph = ph; this.fs = fs; this.gap = gap; this.pad = pad;
        this.clockFs = clockFs;
        this.cardH = fs * 3 + gap * 2 + pad * 2;
        this.slideDistance = pw;
        this.trainListPage = trainListPage;
        this.trainListSlideIn = trainListSlideIn;
        this.schedulePage = schedulePage;
        this.scheduleSlideIn = scheduleSlideIn;
        precomputeListData();
    }

    private void precomputeListData() {
        // TRAIN_LIST: ページネーション後の可視 train 計算
        trainListVisible = Collections.emptyList();
        if (panel != null && panel.getType() == MonitorLayoutPanel.PanelType.TRAIN_LIST
                && netData != null && !netData.trains().isEmpty()) {
            List<TrackNetworkScanner.TrainInfo> all = List.copyOf(netData.trains());
            int maxVisible = Math.max(1, (ph - fs * 2) / Math.max(1, cardH));
            int total = all.size();
            int totalPages = Math.max(1, (total + maxVisible - 1) / maxVisible);
            int page = ((trainListPage % totalPages) + totalPages) % totalPages;
            int start = page * maxVisible;
            int end = Math.min(total, Math.min(start + maxVisible, start + MAX_TRAINS));
            trainListVisible = all.subList(start, end);
        }
        // SCHEDULE: current train + entries
        currentScheduleTrainName = null;
        scheduleEntries = Collections.emptyList();
        currentScheduleEntryIdx = -1;
        totalScheduleEntries = 0;
        totalScheduleTrains = 0;
        if (panel != null && panel.getType() == MonitorLayoutPanel.PanelType.SCHEDULE
                && netData != null && !netData.trains().isEmpty()) {
            List<TrackNetworkScanner.TrainInfo> valid = new ArrayList<>();
            for (var t : netData.trains()) if (t.id() != null) valid.add(t);
            if (!valid.isEmpty()) {
                totalScheduleTrains = valid.size();
                int trainIdx = ((schedulePage % totalScheduleTrains) + totalScheduleTrains) % totalScheduleTrains;
                var info = valid.get(trainIdx);
                int maxEntries = Math.max(1, (ph - fs * 3) / Math.max(1, fs + gap));
                // サーバー権威 SchedView (BE 同期) を優先 — dedicated MP では client の live
                // Create schedule (runtime) が取れず時刻表が空になるため。 無い場合のみ live へ fallback (SP 互換)。
                var sv = computer != null ? computer.getSyncedSchedView(info.id()) : null;
                if (sv != null && !sv.entries().isEmpty()) {
                    currentScheduleTrainName = info.name();
                    totalScheduleEntries = sv.entries().size();
                    currentScheduleEntryIdx = sv.current();
                    int showCount = Math.min(totalScheduleEntries, Math.min(maxEntries, MAX_SCHEDULE_ENTRIES));
                    scheduleEntries = new ArrayList<>(sv.entries().subList(0, showCount));
                } else {
                    try {
                        var opt = TrackNetworkScanner.getTrainById(info.id());
                        if (opt.isPresent() && opt.get().runtime != null
                                && opt.get().runtime.getSchedule() != null) {
                            var liveTrain = opt.get();
                            currentScheduleTrainName = liveTrain.name.getString();
                            var sched = liveTrain.runtime.getSchedule();
                            totalScheduleEntries = sched.entries.size();
                            currentScheduleEntryIdx = liveTrain.runtime.currentEntry;
                            int showCount = Math.min(totalScheduleEntries, Math.min(maxEntries, MAX_SCHEDULE_ENTRIES));
                            List<String> entries = new ArrayList<>(showCount);
                            for (int j = 0; j < showCount; j++) {
                                String dest = "?";
                                try {
                                    if (sched.entries.get(j).instruction instanceof com.simibubi.create.content.trains.schedule.destination.DestinationInstruction d) {
                                        dest = d.getFilter();
                                    }
                                } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[LayoutHandler] schedule destination read failed", e); }
                                entries.add(dest);
                            }
                            scheduleEntries = entries;
                        }
                    } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[LayoutHandler] schedule entries build failed", e); }
                }
            }
        }
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int def) {
        return switch (key) {
            case "pw" -> pw;
            case "ph" -> ph;
            case "contentW" -> Math.max(0, pw - pad * 2);
            case "titleH" -> fs + 2;
            case "valueH" -> fs * 2;
            case "clockFs" -> clockFs;
            case "cardH" -> cardH;
            // fontScaleKey binding (×100 int → TextNode glyph scale)。 MC font 基準 9px を
            // fs px へ拡大する。 これが無いと行の高さ (titleH 等) だけ変わり glyph は 9px 固定
            // (= 「枠だけ広がって文字が大きくならない」不具合の真因)。
            case "fsScale" -> Math.round(fs * 100f / 9f);
            case "valueScale" -> Math.round(fs * 2 * 100f / 9f);
            case "clockScale" -> Math.round(clockFs * 100f / 9f);
            default -> null;
        };
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int def) {
        if (computer == null) return null;
        String hex = switch (key) {
            case "panelTitle" -> computer.getColorOrDefault("panelTitle", "#4fc3f7");
            case "trainName" -> computer.getColorOrDefault("trainName", "#4fc3f7");
            case "trainStatus" -> computer.getColorOrDefault("trainStatus", "#80deea");
            case "trainDest" -> computer.getColorOrDefault("trainDest", "#ffc107");
            case "clock" -> computer.getColorOrDefault("clock", "#4fc3f7");
            case "statValue" -> computer.getColorOrDefault("statValue", "#4fc3f7");
            case "signalGreen" -> computer.getColorOrDefault("signalGreen", "#2D6B30");
            case "signalRed" -> computer.getColorOrDefault("signalRed", "#9A2A22");
            default -> null;
        };
        return hex == null ? null : parseHex(hex);
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        if (classes == null) return null;
        for (String c : classes) {
            String t = resolveTextClass(c);
            if (t != null) return t;
        }
        return null;
    }

    private String resolveTextClass(String c) {
        if (panel == null) return null;
        // ===== single-value bindings =====
        switch (c) {
            case "stat-label": return statLabel();
            case "stat-value": return String.valueOf(statValue());
            case "signal-title": return tr("tsu.monitor.signal_label");
            case "signal-total": return String.valueOf(signalTotal());
            case "signal-green": return "● " + signalGreen();
            case "signal-red": return "● " + signalRed();
            case "clock-text": return clockText();
            case "train-list-title": return tr("tsu.monitor.train_list_label");
            case "train-list-page": return trainListPageText();
            case "schedule-title": return tr("tsu.monitor.schedule_label");
            case "schedule-train-name": return currentScheduleTrainName;
            case "schedule-overflow": return scheduleOverflow();
            case "schedule-page": return schedulePageText();
        }
        // ===== train{N}_* (TRAIN_LIST cards) =====
        if (c.startsWith("train") && c.contains("_")) {
            int us = c.indexOf('_');
            try {
                int idx = Integer.parseInt(c.substring(5, us));
                String field = c.substring(us + 1);
                if (idx >= 0 && idx < trainListVisible.size()) {
                    var t = trainListVisible.get(idx);
                    return resolveTrainField(t, field);
                }
            } catch (NumberFormatException ignored) {}
        }
        // ===== sched{N} (SCHEDULE entries) =====
        if (c.startsWith("sched") && c.length() > 5 && Character.isDigit(c.charAt(5))) {
            try {
                int idx = Integer.parseInt(c.substring(5));
                if (idx >= 0 && idx < scheduleEntries.size()) {
                    boolean current = (idx == currentScheduleEntryIdx);
                    return (current ? "▶ " : "○ ") + scheduleEntries.get(idx);
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String resolveTrainField(TrackNetworkScanner.TrainInfo t, String field) {
        return switch (field) {
            case "name" -> t.name();
            case "status" -> t.isStoppedAtStation()
                    ? "█ " + t.currentStationName()
                    : String.format("%.0f km/h", Math.abs(t.speed()) * 20 * 3.6);
            case "cars" -> trf("tsu.monitor.cars_fmt", t.carriageCount());
            case "dest" -> {
                if (t.id() == null) yield null;
                // サーバー権威 SchedView (BE 同期) を優先 (MP)。 無ければ live へ fallback (SP 互換)。
                var sv = computer != null ? computer.getSyncedSchedView(t.id()) : null;
                if (sv != null && !sv.entries().isEmpty()) {
                    int cur = sv.current();
                    if (cur < 0 || cur >= sv.entries().size()) yield null;
                    yield trf("tsu.monitor.dest_fmt", sv.entries().get(cur));
                }
                try {
                    var opt = TrackNetworkScanner.getTrainById(t.id());
                    if (opt.isEmpty() || opt.get().runtime == null
                            || opt.get().runtime.getSchedule() == null) yield null;
                    int cur = opt.get().runtime.currentEntry;
                    var sched = opt.get().runtime.getSchedule();
                    if (cur < 0 || cur >= sched.entries.size()) yield null;
                    if (sched.entries.get(cur).instruction instanceof com.simibubi.create.content.trains.schedule.destination.DestinationInstruction d) {
                        yield trf("tsu.monitor.dest_fmt", d.getFilter());
                    }
                    yield null;
                } catch (Exception ignored) { yield null; }
            }
            case "paused" -> {
                if (t.id() == null) yield null;
                // サーバー同期 paused フラグ優先 (MP)。 false は「非 paused か未同期」なので live で補完。
                if (computer != null && computer.hasSyncedPaused(t.id())) {
                    yield tr("tsu.monitor.train_paused");
                }
                try {
                    var opt = TrackNetworkScanner.getTrainById(t.id());
                    if (opt.isPresent() && opt.get().runtime != null && opt.get().runtime.paused) {
                        yield tr("tsu.monitor.train_paused");
                    }
                    yield null;
                } catch (Exception ignored) { yield null; }
            }
            default -> null;
        };
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean def) {
        if (panel == null) return null;
        switch (key) {
            case "signal_green_visible": return netData != null && signalGreen() > 0;
            case "signal_red_visible": return netData != null && signalRed() > 0;
            case "trainListPage_visible": return trainListPageText() != null;
            case "schedule_train_name_visible": return currentScheduleTrainName != null;
            case "schedule_overflow_visible": return scheduleOverflow() != null;
            case "schedule_page_visible": return schedulePageText() != null;
        }
        // train{N}_visible
        if (key.startsWith("train") && key.endsWith("_visible")) {
            String body = key.substring(5, key.length() - "_visible".length());
            int us = body.indexOf('_');
            try {
                int idx;
                String sub;
                if (us < 0) { idx = Integer.parseInt(body); sub = ""; }
                else { idx = Integer.parseInt(body.substring(0, us)); sub = body.substring(us + 1); }
                if (idx < 0 || idx >= trainListVisible.size()) return false;
                if (sub.isEmpty()) return true;
                var t = trainListVisible.get(idx);
                return switch (sub) {
                    case "dest" -> resolveTrainField(t, "dest") != null;
                    case "paused" -> resolveTrainField(t, "paused") != null;
                    default -> null;
                };
            } catch (NumberFormatException ignored) {}
        }
        // sched{N}_visible
        if (key.startsWith("sched") && key.endsWith("_visible")) {
            String body = key.substring(5, key.length() - "_visible".length());
            try {
                int idx = Integer.parseInt(body);
                return idx >= 0 && idx < scheduleEntries.size();
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    @Override
    public Animation getDynamicAnimation(String[] classes, String key) {
        if (key == null) return null;
        if ("schedule_anim".equals(key)) {
            return scheduleSlideIn
                    ? Animation.of(400).easing(Easing.EASE_OUT).translateX(slideDistance, 0).build()
                    : null;
        }
        if (key.startsWith("train") && key.endsWith("_anim")) {
            return trainListSlideIn
                    ? Animation.of(400).easing(Easing.EASE_OUT).translateX(slideDistance, 0).build()
                    : null;
        }
        return null;
    }

    // ===== helpers =====

    private String statLabel() {
        if (panel.getType() == MonitorLayoutPanel.PanelType.STATION_COUNT)
            return tr("tsu.monitor.station_label");
        if (panel.getType() == MonitorLayoutPanel.PanelType.TRAIN_COUNT)
            return tr("tsu.monitor.train_label");
        return "";
    }

    private int statValue() {
        if (panel.getType() == MonitorLayoutPanel.PanelType.STATION_COUNT)
            return netData != null ? netData.stations().size() : computer.getCachedStationCount();
        if (panel.getType() == MonitorLayoutPanel.PanelType.TRAIN_COUNT)
            return netData != null ? netData.trains().size() : computer.getCachedTrainCount();
        return 0;
    }

    private int signalTotal() {
        return netData != null ? netData.signals().size() : computer.getCachedSignalCount();
    }

    private int signalGreen() {
        if (netData == null) return 0;
        int g = 0;
        for (var s : netData.signals()) {
            if (s.state() == TrackNetworkScanner.SignalState.GREEN) g++;
        }
        return g;
    }

    private int signalRed() {
        if (netData == null) return 0;
        int r = 0;
        for (var s : netData.signals()) {
            if (s.state() == TrackNetworkScanner.SignalState.RED) r++;
        }
        return r;
    }

    private String clockText() {
        if (level == null) return "00:00";
        long dayTime = level.getDayTime() % 24000;
        int hour = (int) ((dayTime / 1000 + 6) % 24);
        int min = (int) ((dayTime % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hour, min);
    }

    private String trainListPageText() {
        if (netData == null || netData.trains().isEmpty()) return null;
        int maxVisible = Math.max(1, (ph - fs * 2) / Math.max(1, cardH));
        int total = netData.trains().size();
        int totalPages = Math.max(1, (total + maxVisible - 1) / maxVisible);
        if (totalPages <= 1) return null;
        int page = ((trainListPage % totalPages) + totalPages) % totalPages;
        return (page + 1) + "/" + totalPages;
    }

    private String scheduleOverflow() {
        if (currentScheduleTrainName == null) return null;
        int overflow = totalScheduleEntries - scheduleEntries.size();
        return overflow > 0 ? ("+" + overflow) : null;
    }

    private String schedulePageText() {
        if (totalScheduleTrains <= 1) return null;
        int trainIdx = ((schedulePage % totalScheduleTrains) + totalScheduleTrains) % totalScheduleTrains;
        return (trainIdx + 1) + "/" + totalScheduleTrains;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static String trf(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static int parseHex(String hex) {
        if (hex == null || hex.isEmpty()) return 0xFFFFFFFF;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) { return 0xFFFFFFFF; }
    }
}
