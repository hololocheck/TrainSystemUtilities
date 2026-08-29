package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutEngine;
import com.trainsystemutilities.schedule.TrainTypes;
import net.minecraft.network.chat.Component;

/** RailwayManagementScreenV2 の getDynamic* / onElementClick dispatcher + popup slot chrome (god-class 分割 増分 2〜4)。
 * 挙動は screen 在置時代と同一 — bodies は verbatim 移設で、screen メンバーは scr. 経由で参照する。 */
final class RailwayManagementDispatch {
    private RailwayManagementDispatch() {}

    static String text(RailwayManagementScreenV2 scr, String[] classes, String defaultText) {
        var be = scr.be();
        // Phase 21: ホームドア条件 repeat の per-entry テキスト
        for (String c : classes) {
            if ("sd-status-label".equals(c)) {
                return Component.translatable(scr.isScreenDoorOnline()
                        ? "tsu.rm.sd_online" : "tsu.rm.sd_offline").getString();
            }
            if ("sd-cond-track".equals(c)) {
                int idx = scr.sdCondRealIdx();
                var conds = be.getScreenDoorConditions();
                if (idx < 0 || idx >= conds.size()) return defaultText;
                return String.valueOf(conds.get(idx).trackNumber());
            }
            if ("sd-cond-event".equals(c)) {
                int idx = scr.sdCondRealIdx();
                var conds = be.getScreenDoorConditions();
                if (idx < 0 || idx >= conds.size()) return defaultText;
                return switch (conds.get(idx).eventType()) {
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP -> Component.translatable("tsu.rm.sd_event_stop").getString();
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_DEPART -> Component.translatable("tsu.rm.sd_event_depart").getString();
                    default -> defaultText;
                };
            }
            if ("sd-cond-action".equals(c)) {
                int idx = scr.sdCondRealIdx();
                var conds = be.getScreenDoorConditions();
                if (idx < 0 || idx >= conds.size()) return defaultText;
                return switch (conds.get(idx).actionType()) {
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_OPEN -> Component.translatable("tsu.rm.sd_action_open").getString();
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_CLOSE -> Component.translatable("tsu.rm.sd_action_close").getString();
                    case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_COLOR -> Component.translatable("tsu.rm.sd_action_color").getString();
                    default -> defaultText;
                };
            }
        }
        // Phase 18: アナウンス popup 内 repeat の per-entry テキスト
        for (String c : classes) {
            if ("ann-entry-index".equals(c)) {
                int idx = scr.annEntryRealIdx();
                return idx >= 0 ? "#" + (idx + 1) : "";
            }
            if ("ann-cond-display".equals(c)) {
                int idx = scr.annEntryRealIdx();
                var cfg = scr.announcementConfig();
                if (cfg == null || idx < 0 || idx >= cfg.size()) return "";
                var entry = cfg.get(idx);
                if (entry == null) return "";
                String key = switch (entry.condition().type) {
                    case NONE -> "tsu.announcement.cond_none";
                    case ON_DETECTION_PASS -> "tsu.announcement.cond_pass";
                    case ON_DETECTION_STOPPED -> "tsu.announcement.cond_stop";
                };
                // W7-1 (R4.23.1): dropdown 可能を示す ▾ は layout 側の子 icon
                // (manta:chevron-down) へ移した。ここは条件ラベルだけを返す。
                // ▾(U+25BE) は gate に ▼(U+25BC) しか無く**異体字として見逃されていた**。
                return net.minecraft.network.chat.Component.translatable(key).getString();
            }
            if ("ann-delay-display".equals(c)) {
                int idx = scr.annEntryRealIdx();
                var cfg = scr.announcementConfig();
                // W7-1 (R4.23.1): 先頭の ↕ は「ホイールで変更できる」control affordance
                // なので layout 側の子 icon (manta:arrow-up-down) へ移した。値だけを返す。
                if (cfg == null || idx < 0 || idx >= cfg.size()) return "0s";
                var entry = cfg.get(idx);
                if (entry == null) return "0s";
                int s = entry.condition().delaySeconds;
                return (s > 0 ? "+" + s : String.valueOf(s)) + "s";
            }
            if ("ann-count-display".equals(c)) {
                int idx = scr.annEntryRealIdx();
                var cfg = scr.announcementConfig();
                // 同上 — ↕ は子 icon へ。"x1" の x は乗算表記 (content typography)。
                if (cfg == null || idx < 0 || idx >= cfg.size()) return "x1";
                var entry = cfg.get(idx);
                if (entry == null) return "x1";
                return "x" + entry.playCount();
            }
            if ("ann-share-station-name".equals(c)) {
                int idx = scr.annShareRealIdx();
                if (idx < 0) return "";
                var stations = scr.getShareCandidateStations();
                if (idx >= stations.size()) return "";
                return stations.get(idx).name();
            }
            if ("ann-incoming-share-info".equals(c)) {
                var sources = be.getIncomingShareSources();
                if (sources.isEmpty()) return "";
                // 共有元 1 件目だけを優先表示 (複数は通常稀)。
                // 検知 / 範囲 / 両方 の組み合わせで翻訳キーを切替。
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sources.size(); i++) {
                    var info = sources.get(i);
                    String key;
                    if (info.detection && info.range) key = "tsu.announcement.shared_from_both_fmt";
                    else if (info.range) key = "tsu.announcement.shared_from_rng_fmt";
                    else key = "tsu.announcement.shared_from_det_fmt";
                    if (i > 0) sb.append(" / ");
                    sb.append(net.minecraft.network.chat.Component
                            .translatable(key, info.sourceStationName).getString());
                }
                return sb.toString();
            }
            if ("ann-media-info".equals(c)) {
                int idx = scr.annEntryRealIdx();
                if (idx < 0) return "";
                int slotIdx = com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE + idx;
                if (slotIdx >= scr.getMenu().slots.size()) return "";
                var stack = scr.getMenu().slots.get(slotIdx).getItem();
                if (stack.isEmpty() || !com.trainsystemutilities.compat.sas.SasIntegration.hasAudio(stack)) {
                    return net.minecraft.network.chat.Component.translatable("tsu.announcement.no_media").getString();
                }
                String name = belugalab.sas.api.SasApi.getAudioFileName(stack);
                String fmt = belugalab.sas.api.SasApi.getAudioFormat(stack);
                int durSec = belugalab.sas.api.SasApi.getAudioDurationSeconds(stack);
                if (name == null || name.isEmpty()) name = "audio";
                StringBuilder sb = new StringBuilder(name);
                if (fmt != null) sb.append(" [").append(fmt).append("]");
                if (durSec > 0) {
                    sb.append(" ").append(durSec / 60).append(":");
                    int s = durSec % 60;
                    if (s < 10) sb.append('0');
                    sb.append(s);
                }
                return sb.toString();
            }
        }
        for (String c : classes) {
            switch (c) {
                case "mc-time":
                    return scr.getMinecraftTime();
                case "header-badge": {
                    String name = be.getLinkedStationName();
                    return (name != null && !name.isEmpty()) ? name : Component.translatable("tsu.rm.station_unlinked").getString();
                }
                case "monitor-status-label": {
                    int g = be.getLinkedMonitorGroupCount();
                    boolean on = scr.monitorEnabled() && g > 0;
                    return Component.translatable(on ? "tsu.poster.monitor_online" : "tsu.poster.monitor_offline").getString();
                }
                case "monitor-info": {
                    int g = be.getLinkedMonitorGroupCount();
                    return g > 0
                            ? Component.translatable("tsu.poster.monitor_groups_linked_fmt", g).getString()
                            : Component.translatable("tsu.rm.monitor_disconnected").getString();
                }
                case "page-indicator": {
                    var nextTrains = be.getNextTrains();
                    int totalPages = nextTrains.isEmpty() ? 1
                            : (nextTrains.size() + RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE - 1) / RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE;
                    return totalPages > 1 ? (scr.nextTrainPageIndex + 1) + "/" + totalPages : "";
                }
                case "arrived-empty":
                    return be.getArrivedTrains().isEmpty()
                            ? (be.getLinkedStationName() != null ? Component.translatable("tsu.rm.no_train").getString() : Component.translatable("tsu.rm.link_station_hint").getString())
                            : "";
                case "next-empty":
                    return be.getNextTrains().isEmpty() ? Component.translatable("tsu.rm.none").getString() : "";
                // settings popup displays
                case "track-display-track": {
                    int v = scr.currentTrackNumber();
                    return v == 0 ? Component.translatable("tsu.rm.none").getString() : Component.translatable("tsu.rm.track_number_fmt", v).getString();
                }
                case "track-display-font": {
                    int v = scr.currentTrackFontSize();
                    return v == 0 ? Component.translatable("tsu.rm.auto").getString() : v + "px";
                }
                case "track-display-pos":
                    return Component.translatable(scr.currentTrackPosition() == 0 ? "tsu.rm.pos_left" : "tsu.rm.pos_right").getString();
                case "track-display-clock":
                    return Component.translatable(scr.currentClockVisible() == 1 ? "tsu.rm.show" : "tsu.rm.hide").getString();
                case "track-display-clockfs": {
                    int v = scr.currentClockFontSize();
                    return v == 0 ? Component.translatable("tsu.rm.auto").getString() : v + "px";
                }
                // settings popup: face indicator + symbol display
                case "settings-face-indicator":
                    return Component.translatable(scr.showBackFace ? "tsu.rm.face_back" : "tsu.rm.face_front").getString();
                case "preview-dims": {
                    var groups = scr.be().getMonitorGroups();
                    if (groups.isEmpty()) return Component.translatable("tsu.rm.not_linked").getString();
                    var g = groups.get(scr.currentGroupIndex());
                    return g.height() + "×" + g.width();
                }
                case "sym-display": {
                    var rbe = scr.be();
                    return rbe.hasLineSymbol()
                            ? rbe.getLineSymbolLetters() + " " + String.format("%02d", rbe.getLineSymbolNumber())
                            : Component.translatable("tsu.rm.none").getString();
                }
                // color popup (controller delegate)
                case "color-face-label":
                    return Component.translatable(scr.showBackFace ? "tsu.rm.face_back_color" : "tsu.rm.face_front_color").getString();
            }
        }
        // arrived row
        int idx = JsonLayoutEngine.currentRepeatIndex();
        // Color popup の dynamic text は controller に委譲 (target-dropdown / current-hex / target-item)
        if (scr.showColorSettings) {
            String t = scr.colorPopup.resolveText(classes, idx);
            if (t != null) return t;
        }
        if (idx < 0) return null;
        // 停車中の列車行: 全フィールドを font.width でクリップ (overflow 防止)。
        var arrived = be.getArrivedTrains();
        if (idx < arrived.size()) {
            var t = arrived.get(idx);
            for (String c : classes) {
                switch (c) {
                    case "train-name":      return scr.fit(t.name(), 70);
                    case "train-cars":      return Component.translatable("tsu.mc.cars_unit_fmt", t.carriageCount()).getString();
                    case "train-dest":
                        return (t.destination() != null && !t.destination().isEmpty())
                                ? scr.fit("→ " + t.destination(), 54) : "";
                    case "train-route":
                        return (t.routeType() != null && !t.routeType().isEmpty())
                                ? scr.fit(RailwayManagementScreenV2.routeTypeText(t.routeType()), 56) : "";
                    case "train-type-badge":
                        return TrainTypes.isSet(t.trainType())
                                ? scr.fit("[" + RailwayManagementScreenV2.trainTypeText(t.trainType()) + "]", 40) : "";
                    case "train-arr-time":
                        return Component.translatable("tsu.rm.time_arr_fmt", RailwayManagementScreenV2.formatDayTime(t.arrivalDayTime())).getString();
                    case "train-dep-time":
                        return t.scheduledStopSec() > 0
                                ? Component.translatable("tsu.rm.time_dep_fmt", RailwayManagementScreenV2.getDepartureTime(t.arrivalDayTime(), t.scheduledStopSec())).getString()
                                : "";
                    case "train-time": {
                        long currentTick = be.getLevel() != null ? be.getLevel().getGameTime() : 0;
                        int elapsedSec = (int)((currentTick - t.arrivalTick()) / 20);
                        if (t.scheduledStopSec() > 0) {
                            int remaining = t.scheduledStopSec() - elapsedSec;
                            return remaining > 0 ? Component.translatable("tsu.rm.time_remaining_fmt", remaining).getString() : Component.translatable("tsu.rm.preparing_departure").getString();
                        }
                        return Component.translatable("tsu.rm.time_stopped_fmt", elapsedSec).getString();
                    }
                }
            }
        }
        // 次に停車する列車行: 接近中の場合 dep-time/stop-sec は隠す (line 2 の混雑回避)。
        int pageStart = scr.nextTrainPageIndex * RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE;
        int realNextIdx = pageStart + idx;
        var next = be.getNextTrains();
        if (realNextIdx < next.size()) {
            var n = next.get(realNextIdx);
            for (String c : classes) {
                switch (c) {
                    case "next-name":      return scr.fit(n.name(), 70);
                    case "next-cars":      return Component.translatable("tsu.mc.cars_unit_fmt", n.carriageCount()).getString();
                    case "next-route":
                        return (n.routeType() != null && !n.routeType().isEmpty())
                                ? scr.fit(RailwayManagementScreenV2.routeTypeText(n.routeType()), 50) : "";
                    case "next-type-badge":
                        return TrainTypes.isSet(n.trainType())
                                ? scr.fit("[" + RailwayManagementScreenV2.trainTypeText(n.trainType()) + "]", 50) : "";
                    case "next-stop-info":
                        if (n.currentStopStation() != null && !n.currentStopStation().isEmpty())
                            return scr.fit(Component.translatable("tsu.rm.stopping_at_fmt", n.currentStopStation()).getString(), 92);
                        if (n.fromStation() != null && !n.fromStation().isEmpty())
                            return scr.fit(Component.translatable("tsu.rm.from_station_fmt", n.fromStation()).getString(), 92);
                        return "";
                    case "next-arr-time":
                        if (n.estimatedArrivalDayTime() <= 0) return "";
                        // 接近中なら "(接近)" を簡略表示にして dep/sec を隠す側で
                        // 衝突回避。通常時は "HH:MM着予定" のみ。
                        return n.isApproaching()
                                ? Component.translatable("tsu.rm.time_approaching_fmt", RailwayManagementScreenV2.formatDayTime(n.estimatedArrivalDayTime())).getString()
                                : Component.translatable("tsu.rm.time_arr_eta_fmt", RailwayManagementScreenV2.formatDayTime(n.estimatedArrivalDayTime())).getString();
                    case "next-dep-time":
                        // 接近中は dep-time を隠す (line 2 重複防止)
                        if (n.isApproaching()) return "";
                        return (n.estimatedArrivalDayTime() > 0 && n.scheduledStopSec() > 0)
                                ? Component.translatable("tsu.rm.time_dep_fmt", RailwayManagementScreenV2.getDepartureTime(n.estimatedArrivalDayTime(), n.scheduledStopSec())).getString()
                                : "";
                    case "next-stop-sec":
                        if (n.isApproaching()) return "";
                        return n.scheduledStopSec() > 0
                                ? Component.translatable("tsu.rm.seconds_fmt", n.scheduledStopSec()).getString()
                                : "";
                }
            }
        }
        // Group selector: 「グループ1 (HxW) 5番線」 形式
        if (scr.showSettings) {
            for (String c : classes) {
                if ("group-item".equals(c)) {
                    var groups = scr.be().getMonitorGroups();
                    if (idx >= groups.size()) return null;
                    var g = groups.get(idx);
                    String prefix = (idx == scr.selectedGroupIndex) ? "● " : "○ ";
                    String t = prefix + Component.translatable("tsu.rm.group_label_fmt", (idx + 1), g.height(), g.width()).getString();
                    if (g.trackNumber() > 0) t += " " + Component.translatable("tsu.rm.track_number_fmt", g.trackNumber()).getString();
                    return t;
                }
                if ("sym-dropdown-item".equals(c)) {
                    if (idx == 0) return Component.translatable("tsu.rm.none").getString();
                    var symbols = scr.getAvailableSymbols();
                    int symIdx = idx - 1;
                    if (symIdx >= symbols.size()) return null;
                    var sym = symbols.get(symIdx);
                    String t = sym.getLetters() + " " + sym.getNumberStr();
                    if (sym.getName() != null && !sym.getName().isEmpty()) t += "  " + sym.getName();
                    return t;
                }
            }
        }
        return null;
    }

    static Integer number(RailwayManagementScreenV2 scr, String[] classes, String key, int defaultValue) {
        if (scr.showColorSettings) {
            Integer n = scr.colorPopup.resolveNumber(key);
            if (n != null) return n;
        }
        // R4.23.1 で glyph を外して icon + label にしたボタンのラベル幅 (LabelWidth 参照)。
        if ("settings-label-w".equals(key)) return LabelWidth.of("tsu.common.settings");
        switch (key) {
            case "monitor-knob-x":     return scr.monitorToggle.knobX(defaultValue);
            // hint-knob-x は JsonLayoutEngine が HintToggleHelper にルート (解決不要)
            case "batch-knob-x":
                return scr.batchToggle.knobX(defaultValue);
            case "arrived-count":
                return scr.be().getArrivedTrains().size();
            case "next-count": {
                var nextTrains = scr.be().getNextTrains();
                int total = nextTrains.size();
                if (total == 0) return 0;
                int pageStart = scr.nextTrainPageIndex * RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE;
                return Math.min(RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE, total - pageStart);
            }
            case "group-count":
                return Math.min(RailwayManagementScreenV2.MAX_GROUPS, scr.be().getMonitorGroups().size());
            case "sd-cond-count":
                return scr.sdCondScroll.rowCount();
            case "sd-cond-scroll-thumb-y": {
                int total = scr.be().getScreenDoorConditions().size();
                if (total <= RailwayManagementScreenV2.SD_COND_VISIBLE) return RailwayManagementScreenV2.SD_COND_AREA_Y;
                int thumbH = Math.max(8, RailwayManagementScreenV2.SD_COND_AREA_H * RailwayManagementScreenV2.SD_COND_VISIBLE / total);
                return scr.sdCondScroll.thumbY(RailwayManagementScreenV2.SD_COND_AREA_Y, RailwayManagementScreenV2.SD_COND_AREA_H, thumbH);
            }
            case "sd-cond-scroll-thumb-h": {
                int total = scr.be().getScreenDoorConditions().size();
                if (total <= RailwayManagementScreenV2.SD_COND_VISIBLE) return RailwayManagementScreenV2.SD_COND_AREA_H;
                return Math.max(8, RailwayManagementScreenV2.SD_COND_AREA_H * RailwayManagementScreenV2.SD_COND_VISIBLE / total);
            }
            case "ann-share-scroll-thumb-y": {
                int total = scr.getShareCandidateStations().size();
                if (total <= RailwayManagementScreenV2.ANN_SHARE_VISIBLE) return RailwayManagementScreenV2.ANN_SHARE_AREA_Y;
                int thumbH = Math.max(8, RailwayManagementScreenV2.ANN_SHARE_AREA_H * RailwayManagementScreenV2.ANN_SHARE_VISIBLE / total);
                return scr.annShareScroll.thumbY(RailwayManagementScreenV2.ANN_SHARE_AREA_Y, RailwayManagementScreenV2.ANN_SHARE_AREA_H, thumbH);
            }
            case "ann-share-scroll-thumb-h": {
                int total = scr.getShareCandidateStations().size();
                if (total <= RailwayManagementScreenV2.ANN_SHARE_VISIBLE) return RailwayManagementScreenV2.ANN_SHARE_AREA_H;
                return Math.max(8, RailwayManagementScreenV2.ANN_SHARE_AREA_H * RailwayManagementScreenV2.ANN_SHARE_VISIBLE / total);
            }
            case "sd-highlight-knob-x":
                return scr.sdHighlightToggle.knobX(defaultValue);
            case "function-dd-bg-h":
                return scr.functionDropdownPanelHeight();
            // color-target-count は controller に委譲 (下で resolveNumber 経由)
            case "header-badge-w": {
                String name = scr.be().getLinkedStationName();
                String text = (name != null && !name.isEmpty()) ? name : Component.translatable("tsu.rm.station_unlinked").getString();
                if (scr.fontOrNull() == null) return defaultValue;
                return scr.fontOrNull().width(text) + 12; // 左右 padding 6px ずつ
            }
            case "header-badge-x": {
                String name = scr.be().getLinkedStationName();
                String text = (name != null && !name.isEmpty()) ? name : Component.translatable("tsu.rm.station_unlinked").getString();
                if (scr.fontOrNull() == null) return defaultValue;
                int w = scr.fontOrNull().width(text) + 12;
                // ダイアログ内 right-align (PAD + DLG_INNER_W - w)
                return 14 + 236 - w;
            }
            case "sym-dropdown-count":
                // +1 for "なし"
                return scr.showSymbolDropdown
                        ? Math.min(RailwayManagementScreenV2.MAX_SYMBOLS, scr.getAvailableSymbols().size()) + 1
                        : 0;
            case "ann-entry-count": {
                if (!scr.showAnnouncement) return 0;
                // **全件ではなく表示行数**を返す。全件を返していたため 6 個目以降が
                // popup の枠外へはみ出していた (2026-07-26 実機報告)。溢れた分は scroll で見せる。
                return scr.annEntryScroll.rowCount();
            }
            case "ann-entry-scroll-thumb-y": {
                var cfg = scr.announcementConfig();
                int total = cfg != null ? cfg.size() : 0;
                if (total <= RailwayManagementScreenV2.ANN_ENTRY_VISIBLE) {
                    return RailwayManagementScreenV2.ANN_ENTRY_AREA_Y;
                }
                int thumbH = Math.max(8, RailwayManagementScreenV2.ANN_ENTRY_AREA_H
                        * RailwayManagementScreenV2.ANN_ENTRY_VISIBLE / total);
                return scr.annEntryScroll.thumbY(RailwayManagementScreenV2.ANN_ENTRY_AREA_Y,
                        RailwayManagementScreenV2.ANN_ENTRY_AREA_H, thumbH);
            }
            case "ann-entry-scroll-thumb-h": {
                var cfg = scr.announcementConfig();
                int total = cfg != null ? cfg.size() : 0;
                if (total <= RailwayManagementScreenV2.ANN_ENTRY_VISIBLE) {
                    return RailwayManagementScreenV2.ANN_ENTRY_AREA_H;
                }
                return Math.max(8, RailwayManagementScreenV2.ANN_ENTRY_AREA_H
                        * RailwayManagementScreenV2.ANN_ENTRY_VISIBLE / total);
            }
            case "ann-master-knob-x": {
                var cfg = scr.announcementConfig();
                boolean on = cfg != null && cfg.isEnabled();
                return on ? defaultValue + 12 : defaultValue;
            }
            case "ann-cond-dd-bg-y": {
                if (!scr.conditionDropdown.isOpen()) return defaultValue;
                return 98 + scr.conditionDropdown.openIdx() * 35 + 14;
            }
            case "ann-cond-dd-item-0-y": {
                if (!scr.conditionDropdown.isOpen()) return defaultValue;
                return 98 + scr.conditionDropdown.openIdx() * 35 + 15;
            }
            case "ann-cond-dd-item-1-y": {
                if (!scr.conditionDropdown.isOpen()) return defaultValue;
                return 98 + scr.conditionDropdown.openIdx() * 35 + 26;
            }
            case "ann-cond-dd-item-2-y": {
                if (!scr.conditionDropdown.isOpen()) return defaultValue;
                return 98 + scr.conditionDropdown.openIdx() * 35 + 37;
            }
            case "ann-playing-frame-y": {
                int idx = scr.announcementPlayingEntryIndex();
                return idx >= 0 ? 98 + idx * RailwayManagementScreenV2.ANNOUNCEMENT_ENTRY_STRIDE : defaultValue;
            }
            case "ann-rangeframe-knob-x":  return scr.annRangeFrameToggle.knobX(defaultValue);
            case "ann-attenuation-knob-x": return scr.annAttenuationToggle.knobX(defaultValue);
            case "ann-share-count": {
                if (!scr.showAnnouncement || !scr.showAnnouncementShareList) return 0;
                return scr.annShareScroll.rowCount();
            }
            case "ann-share-det-knob-x": return scr.annShareDetToggle.knobXFor(scr.annShareRealIdx(), defaultValue);
            case "ann-share-rng-knob-x": return scr.annShareRngToggle.knobXFor(scr.annShareRealIdx(), defaultValue);
        }
        return null;
    }

    static Boolean bool(RailwayManagementScreenV2 scr, String[] classes, String key, boolean defaultValue) {
        switch (key) {
            case "header-sym-visible":
                return scr.getAssignedLineSymbol() != null;
            case "face-flip-visible": {
                // Only show flip button when current group is double-sided
                var groups = scr.be().getMonitorGroups();
                if (groups.isEmpty()) return false;
                int gi = scr.currentGroupIndex();
                return groups.get(gi).doubleSided();
            }
            case "group-selector-visible":
                // Show only when batch=false AND multiple groups exist
                return !scr.batchApply() && scr.be().getMonitorGroups().size() > 1;
            case "sym-dropdown-visible":
                return scr.showSymbolDropdown;
            case "announcement-btn-visible":
                return com.trainsystemutilities.compat.sas.SasIntegration.isLoaded();
            case "function-dd-visible-ann":
                // SAS 連携時のみ「アナウンス」 項目表示 + dropdown 展開中
                return scr.showFunctionDropdown
                        && com.trainsystemutilities.compat.sas.SasIntegration.isLoaded();
            case "function-dd-visible-door":
                return scr.showFunctionDropdown;
            case "sd-color-picker-visible":
                return scr.showScreenDoor && scr.showScreenDoorColorPicker;
            case "sd-cond-empty-visible":
                return scr.be().getScreenDoorConditions().isEmpty();
            case "sd-cond-scroll-visible":
                return scr.sdCondScroll.needsScrollbar();
            case "ann-share-scroll-visible":
                return scr.showAnnouncement && scr.showAnnouncementShareList && scr.annShareScroll.needsScrollbar();
            // 共有リストが手前に出ている間は一覧が隠れるので scrollbar も出さない
            // (R4.19.2: 別 view に scrollbar が残存する典型)。
            case "ann-entry-scroll-visible":
                return scr.showAnnouncement && !scr.showAnnouncementShareList
                        && scr.annEntryScroll.needsScrollbar();
            case "ann-cond-dd-visible":
                return scr.showAnnouncement && scr.conditionDropdown.isOpen()
                        && !scr.showAnnouncementShareList;
            case "ann-share-visible":
                return scr.showAnnouncement && scr.showAnnouncementShareList;
            case "ann-entries-visible":
                // share popup 中は背後の entry list / add/test ボタンを隠す
                return scr.showAnnouncement && !scr.showAnnouncementShareList;
            case "ann-playing-frame-visible":
                return scr.showAnnouncement && !scr.showAnnouncementShareList
                        && scr.announcementPlayingEntryIndex() >= 0;
            case "ann-share-btn-visible":
                // 共有先になっている rmbe からは「共有」ボタンを隠す (二段階共有を防ぐ)
                return scr.showAnnouncement && !scr.showAnnouncementShareList
                        && scr.be().getIncomingShareSources().isEmpty();
            case "ann-incoming-share-visible":
                // 共有元の駅名 banner: 共有先になっていて、かつ共有 popup を開いていない時のみ
                return scr.showAnnouncement && !scr.showAnnouncementShareList
                        && !scr.be().getIncomingShareSources().isEmpty();
            // color-target-visible は colorPopup controller に委譲 (下で resolveBool 経由)
        }
        if (scr.showColorSettings) {
            Boolean b = scr.colorPopup.resolveBool(key);
            if (b != null) return b;
        }
        return null;
    }

    static Integer color(RailwayManagementScreenV2 scr, String[] classes, String key, int defaultArgb) {
        if ("color-current-bg".equals(key)) {
            return RailwayManagementScreenV2.parseHexArgb(scr.getCurrentSelectedColorHex(), 0xFF000000);
        }
        // hint-toggle-bg / hint-knob-bg は JsonLayoutEngine が HintToggleHelper にルートするので解決不要。
        // 全 toggle (monitor/batch/ann*) は controller、monitor は withVisualState で derived 解決。
        switch (key) {
            case "monitor-toggle-bg":   return scr.monitorToggle.trackBg();
            case "monitor-knob-bg":     return scr.monitorToggle.knobBg();
            case "monitor-indicator-bg":return scr.monitorToggle.indicatorBg();
            case "monitor-status-color":return scr.monitorToggle.statusText();
            case "batch-toggle-bg":     return scr.batchToggle.trackBg();
            case "batch-knob-bg":       return scr.batchToggle.knobBg();
            case "ann-master-toggle-bg":      return scr.annMasterToggle.trackBg();
            case "ann-master-knob-bg":        return scr.annMasterToggle.knobBg();
            case "ann-rangeframe-toggle-bg":  return scr.annRangeFrameToggle.trackBg();
            case "ann-rangeframe-knob-bg":    return scr.annRangeFrameToggle.knobBg();
            case "ann-attenuation-toggle-bg": return scr.annAttenuationToggle.trackBg();
            case "ann-attenuation-knob-bg":   return scr.annAttenuationToggle.knobBg();
            case "ann-entry-row-bg": {
                int idx = scr.annEntryRealIdx();
                return idx == scr.announcementPlayingEntryIndex() ? 0x224FC3F7 : null;
            }
            case "ann-entry-row-border": {
                int idx = scr.annEntryRealIdx();
                return idx == scr.announcementPlayingEntryIndex() ? 0xFF4FC3F7 : null;
            }
            case "ann-playing-frame-bg":
                return 0x124FC3F7;
            case "ann-playing-frame-border":
                return 0xFF4FC3F7;
            case "ann-share-det-bg":      return scr.annShareDetToggle.trackBgFor(scr.annShareRealIdx());
            case "ann-share-det-knob-bg": return scr.annShareDetToggle.knobBgFor(scr.annShareRealIdx());
            case "ann-share-rng-bg":      return scr.annShareRngToggle.trackBgFor(scr.annShareRealIdx());
            case "ann-share-rng-knob-bg": return scr.annShareRngToggle.knobBgFor(scr.annShareRealIdx());
            case "sd-color-bg":
            case "sd-color-picker-cur-bg":
                return scr.be().getScreenDoorBandColorARGB();
            case "sd-status-indicator-bg":
                return scr.isScreenDoorOnline() ? 0xFF66BB6A : 0xFF555555;
            case "sd-status-color":
                return scr.isScreenDoorOnline() ? 0xFFFFFFFF : 0xFF888888;
            case "sd-highlight-toggle-bg": return scr.sdHighlightToggle.trackBg();
            case "sd-highlight-knob-bg":   return scr.sdHighlightToggle.knobBg();
        }
        // Owner face box border: Private = 赤、Public = 緑
        if ("owner-border".equals(key)) {
            return belugalab.tsu.api.OwnerAccess.ringColor(scr.be().isPrivateMode());
        }
        return null;
    }

    static belugalab.mcss3.anim.Animation animation(RailwayManagementScreenV2 scr, String[] classes, String key) {
        return switch (key) {
            // 列車行のスライドイン: 距離だけ違う同パターン → preset 1 行で完結。
            case "next-row-enter"     -> belugalab.mcss3.anim.Animation.slideInFromRight(260, 80f);
            case "arrived-row-enter"  -> belugalab.mcss3.anim.Animation.slideInFromRight(280, 60f);
            // 色ドロップダウン: scaleY + translateY -h/2 で上端固定の下方向展開 + バウンス。
            // dropdown panel h = 11*10 + 4 = 114。
            // dropdown helper の prefix="color-target" → animationKey は "color-target-open"
            case "color-target-open" -> belugalab.mcss3.anim.Animation.dropdownDown(280, 114);
            // 条件 dropdown (NONE / PASS / STOP の 3 項目): panel h = 34
            case "ann-cond-dd-open"  -> belugalab.mcss3.anim.Animation.dropdownDown(220 + (scr.conditionDropdownOpenSerial & 1), 34);
            // 機能 dropdown (ホームドア / アナウンス 2 項目): 下展開、 anchor top、 panel h = 36
            case "function-dd-open"  -> belugalab.mcss3.anim.Animation.dropdownDown(220 + (scr.functionDropdownOpenSerial & 1), 36);
            // 帯色 picker (= popup on popup): popIn 軽量
            case "sd-color-picker-open" -> belugalab.mcss3.anim.Animation.popIn(200);
            case "ann-entry-shuffle" -> {
                int repeatIdx = scr.annEntryRealIdx();
                float distance = scr.announcementEntryShuffleDistance(repeatIdx);
                if (distance == 0f) yield null;
                yield belugalab.mcss3.anim.Animation.of(220)
                        .easing(belugalab.mcss3.anim.Easing.EASE_OUT)
                        .translateY(distance, 0f)
                        .build();
            }
            // モニタープレビューのカードフリップ (scaleX 0→1)。
            case "preview-flip"        -> belugalab.mcss3.anim.Animation.flipX(500);
            default -> null;
        };
    }

    static void click(RailwayManagementScreenV2 scr, String[] classes, int mouseX, int mouseY) {
        // batch / monitor toggle は popup 中も操作可
        if (scr.batchToggle.handleClick(classes)) return;
        if (scr.monitorToggle.handleClick(classes)) return;
        // popup が開いていても、popup 内クラスでなければ main ハンドラに落とす。
        // (V1 と同じく popup 開中もメイン GUI 操作可。Settings + Color 同時表示にも必要。)
        if (scr.showColorSettings) {
            // 色対象ドロップダウン項目クリック
            // Color popup click → ColorTargetController に委譲
            int dIdx = JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("color-popup-close".equals(c)) {
                    scr.showColorSettings = false;
                    scr.colorPopup.resetTransientState();
                    return;
                }
            }
            boolean wasOpen = scr.colorPopup.isDropdownOpen();
            if (scr.colorPopup.handleClick(classes, dIdx)) {
                if (!wasOpen && scr.colorPopup.isDropdownOpen()) {
                    // dropdown 開く瞬間にリストアニメを再トリガー (popup 全体は pop し直さない)
                    scr.clearOverlay2AnimByClass("color-target-list");
                }
                return;
            }
            // 一致なし → fall through to main (色 popup 中も main の他ボタンが押せる)
        }
        if (scr.showSettings) {
            // Group / symbol-dropdown items inside repeat
            int idx = JsonLayoutEngine.currentRepeatIndex();
            if (idx >= 0) {
                for (String c : classes) {
                    if ("group-item".equals(c)) {
                        var groups = scr.be().getMonitorGroups();
                        if (idx < groups.size()) {
                            scr.selectedGroupIndex = idx;
                            scr.resetLocalOverrides();
                        }
                        return;
                    }
                    if ("sym-dropdown-item".equals(c)) {
                        var rbe = scr.be();
                        if (idx == 0) {
                            rbe.clearLineSymbol();
                            // server に同期するための button id (V1 と同じ)
                            scr.clickButton(7000);
                        } else {
                            var symbols = scr.getAvailableSymbols();
                            int symIdx = idx - 1;
                            if (symIdx < symbols.size()) {
                                var sym = symbols.get(symIdx);
                                rbe.setLineSymbol(sym.getLetters(), sym.getNumber(), sym.getBorderColor());
                                // V1 はクライアント直接 set + サーバ同期は別チャネル。
                                // ここでは button id 7001+ で server に通知
                                scr.clickButton(7001 + symIdx);
                            }
                        }
                        scr.showSymbolDropdown = false;
                        return;
                    }
                }
            }
            for (String c : classes) {
                switch (c) {
                    case "settings-popup-close":
                        // mc-popup-close は使わない (両 popup 共通クラスのため)
                        scr.showSettings = false; scr.showSymbolDropdown = false;
                        scr.resetLocalOverrides(); return;
                    case "face-flip-btn":
                        scr.showBackFace = !scr.showBackFace;
                        scr.resetLocalOverrides();
                        // プレビュー canvas だけを flip 演出対象にする (500ms scaleX from 0.05)。
                        // popup root には触らないので popup 全体は pop 再生されない。
                        scr.clearOverlayAnimByClass("monitor-preview-canvas");
                        return;
                    // batch-toggle-track/knob は batchToggle controller が下で処理
                    case "sym-display":
                        scr.showSymbolDropdown = !scr.showSymbolDropdown;
                        return;
                    case "track-display-pos": {
                        // クリックで左/右切替
                        int cur = scr.currentTrackPosition();
                        scr.localTrackPosition = cur == 0 ? 1 : 0;
                        int gi = scr.currentGroupIndex();
                        if (scr.showBackFace) {
                            scr.clickButton(scr.batchApply() ? 3000 : 3001 + gi);
                        } else {
                            scr.clickButton(scr.batchApply() ? 1000 : 1001 + gi);
                        }
                        return;
                    }
                    case "track-display-clock": {
                        int cur = scr.currentClockVisible();
                        scr.localClockVisible = cur == 1 ? 0 : 1;
                        int gi = scr.currentGroupIndex();
                        if (scr.showBackFace) {
                            scr.clickButton(scr.batchApply() ? 5000 : 5001 + gi);
                        } else {
                            scr.clickButton(scr.batchApply() ? 4000 : 4001 + gi);
                        }
                        return;
                    }
                }
            }
            // 一致なし → main ハンドラに落とす (popup 中も main 操作可)
        }
        for (String c : classes) {
            switch (c) {
                // hint-toggle-track/knob は base class HintToggleHelper が自動処理
                // monitor-toggle-track/knob は下の controller dispatch で処理
                case "settings-btn":
                    // 同じボタンで開閉トグル
                    scr.setFunctionDropdownOpen(false);
                    scr.showSettings = !scr.showSettings;
                    if (!scr.showSettings) { scr.showSymbolDropdown = false; scr.resetLocalOverrides(); }
                    return;
                case "color-btn":
                    scr.setFunctionDropdownOpen(false);
                    // 排他: アナウンス / ホームドア popup を閉じてから Color popup を開く
                    if (scr.showAnnouncement) {
                        scr.showAnnouncement = false; scr.resetAnnouncementTransientState();
                        scr.hideAnnouncementSlots();
                    }
                    if (scr.showScreenDoor) { scr.showScreenDoor = false; scr.hideScreenDoorSlot(); }
                    scr.showColorSettings = !scr.showColorSettings;
                    return;
                case "announcement-btn": {
                    scr.setFunctionDropdownOpen(false);
                    if (!com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()) return;
                    if (scr.showColorSettings) { scr.showColorSettings = false; scr.colorPopup.resetTransientState(); }
                    if (scr.showScreenDoor) { scr.showScreenDoor = false; scr.hideScreenDoorSlot(); }
                    scr.showAnnouncement = !scr.showAnnouncement;
                    if (scr.showAnnouncement) {
                        // 開いた時刻は render() 内で popup 初出現と同フレームで set される。
                        scr.resetAnnouncementTransientState();
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.trainsystemutilities.network.AnnouncementCommandPayload(
                                        scr.be().getBlockPos(),
                                        com.trainsystemutilities.network.AnnouncementCommandPayload.OP_SYNC_REQUEST,
                                        0, 0, 0));
                        scr.positionAnnouncementSlots();
                    } else {
                        scr.resetAnnouncementTransientState();
                        scr.hideAnnouncementSlots();
                    }
                    return;
                }
                case "announcement-popup-close":
                    scr.showAnnouncement = false;
                    scr.resetAnnouncementTransientState();
                    scr.hideAnnouncementSlots();
                    return;
                case "function-dd-trigger":
                    scr.toggleFunctionDropdown();
                    return;
                case "screen-door-btn": {
                    // ドロップダウン項目: ホームドア popup を開く
                    scr.setFunctionDropdownOpen(false);
                    // 排他: アナウンス / Color popup を閉じてから開く
                    if (scr.showColorSettings) { scr.showColorSettings = false; scr.colorPopup.resetTransientState(); }
                    if (scr.showAnnouncement) {
                        scr.showAnnouncement = false; scr.resetAnnouncementTransientState();
                        scr.hideAnnouncementSlots();
                    }
                    scr.showScreenDoor = !scr.showScreenDoor;
                    if (scr.showScreenDoor) {
                        scr.resetScreenDoorPreviewView();
                        scr.positionScreenDoorSlot();
                    } else {
                        scr.hideScreenDoorSlot();
                    }
                    return;
                }
                case "screen-door-popup-close":
                    scr.showScreenDoor = false;
                    scr.showScreenDoorColorPicker = false;
                    scr.hideScreenDoorSlot();
                    return;
                case "sd-color-swatch":
                    // popup 内 picker をトグル開閉
                    if (scr.showScreenDoor) scr.showScreenDoorColorPicker = !scr.showScreenDoorColorPicker;
                    return;
                case "sd-color-picker-close":
                case "sd-color-picker-modal":
                    scr.showScreenDoorColorPicker = false;
                    return;
                case "sd-test-open-btn":
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.trainsystemutilities.network.ScreenDoorTestActionPayload(
                                    scr.be().getBlockPos(),
                                    com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_OPEN));
                    return;
                case "sd-test-close-btn":
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.trainsystemutilities.network.ScreenDoorTestActionPayload(
                                    scr.be().getBlockPos(),
                                    com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_CLOSE));
                    return;
                case "sd-cond-add-btn":
                    scr.sendScreenDoorCondAdd();
                    return;
                case "sd-cond-event": {
                    int idx = scr.sdCondRealIdx();
                    var conds = scr.be().getScreenDoorConditions();
                    if (idx < 0 || idx >= conds.size()) return;
                    var cur = conds.get(idx);
                    int next = (cur.eventType()
                            == com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP)
                            ? com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_DEPART
                            : com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP;
                    scr.sendScreenDoorCondUpdate(idx, cur.withEvent(next));
                    return;
                }
                case "sd-cond-action": {
                    int idx = scr.sdCondRealIdx();
                    var conds = scr.be().getScreenDoorConditions();
                    if (idx < 0 || idx >= conds.size()) return;
                    var cur = conds.get(idx);
                    int next = (cur.actionType() + 1) % 3;
                    scr.sendScreenDoorCondUpdate(idx, cur.withAction(next));
                    return;
                }
                case "sd-cond-del-btn": {
                    int idx = scr.sdCondRealIdx();
                    if (idx < 0) return;
                    scr.sendScreenDoorCondRemove(idx);
                    return;
                }
                default:
                    // sd-preset-N (= N: 0..11) → 帯色を BE に反映 + picker 閉じる
                    if (c.startsWith("sd-preset-")) {
                        try {
                            int idx = Integer.parseInt(c.substring("sd-preset-".length()));
                            if (idx >= 0 && idx < RailwayManagementScreenV2.SCREEN_DOOR_BAND_PRESETS.length) {
                                int argb = RailwayManagementScreenV2.SCREEN_DOOR_BAND_PRESETS[idx];
                                // server 同期
                                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                        new com.trainsystemutilities.network.ScreenDoorBandColorPayload(
                                                scr.be().getBlockPos(), argb));
                                // client 即時反映 (= getDynamicColor が次フレームで新色を返す)
                                scr.be().setScreenDoorBandColorARGB(argb);
                                scr.showScreenDoorColorPicker = false;
                            }
                        } catch (NumberFormatException ignored) {}
                        return;
                    }
                    break;
                // ann-master / ann-rangeframe / ann-attenuation toggle は下の controller dispatch で処理
                case "ann-add-entry-btn":
                    scr.sendAnnouncementCmd(com.trainsystemutilities.network.AnnouncementCommandPayload.OP_ADD_ENTRY, 0, 0, 0);
                    return;
                case "ann-test-play-btn": {
                    var cfg = scr.announcementConfig();
                    if (cfg != null && cfg.size() > 0) {
                        scr.sendAnnouncementCmd(com.trainsystemutilities.network.AnnouncementCommandPayload.OP_TEST_PLAY, 0, 0, 0);
                    }
                    return;
                }
                case "ann-cond-display": {
                    int idx = scr.annEntryRealIdx();
                    if (idx < 0) return;
                    scr.conditionDropdown.toggleFor(idx);
                    if (scr.conditionDropdown.isOpen()) {
                        // 再 open ごとに anim spec を変えて AnimationNode の animChanged restart を発火
                        // (= function dropdown と同手法。clearAnimStateForClass は V3 で no-op のため依存不可)。
                        scr.conditionDropdownOpenSerial++;
                    }
                    return;
                }
                case "ann-cond-dd-item-0":
                case "ann-cond-dd-item-1":
                case "ann-cond-dd-item-2": {
                    int typeOrd = c.charAt(c.length() - 1) - '0';
                    if (scr.conditionDropdown.isOpen()) {
                        int eIdx = scr.conditionDropdown.openIdx();
                        scr.sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandPayload.OP_SET_ENTRY_CONDITION,
                                eIdx, typeOrd, 0);
                        scr.applyConditionTypeLocally(eIdx, typeOrd);
                    }
                    scr.conditionDropdown.close();
                    return;
                }
                case "ann-entry-pause-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx >= 0) scr.sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_STOP_PLAYBACK,
                            idx, 0, 0);
                    return;
                }
                case "ann-entry-up-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx > 0) {
                        scr.triggerAnnouncementSwap(idx, idx - 1);
                        scr.sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandPayload.OP_REORDER_ENTRY,
                                idx, idx - 1, 0);
                    }
                    return;
                }
                case "ann-entry-down-btn": {
                    int idx = scr.annEntryRealIdx();
                    var cfg = scr.announcementConfig();
                    if (idx >= 0 && cfg != null && idx + 1 < cfg.size()) {
                        scr.triggerAnnouncementSwap(idx, idx + 1);
                        scr.sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandPayload.OP_REORDER_ENTRY,
                                idx, idx + 1, 0);
                    }
                    return;
                }
                case "ann-entry-test-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx >= 0) scr.sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_TEST_PLAY, idx, 0, 0);
                    return;
                }
                case "ann-entry-del-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx >= 0) scr.sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandPayload.OP_REMOVE_ENTRY, idx, 0, 0);
                    return;
                }
                case "ann-share-btn": {
                    if (!scr.showAnnouncement) return;
                    scr.showAnnouncementShareList = !scr.showAnnouncementShareList;
                    if (scr.showAnnouncementShareList) {
                        // 開く瞬間に share panel の popIn だけを再トリガー。
                        scr.clearOverlay2AnimByClass("ann-share-list-bg");
                        // dropdown が開いていたら閉じる
                        scr.conditionDropdown.close();
                        scr.annShareScroll.clamp();
                    }
                    return;
                }
                case "ann-share-close-btn":
                    scr.showAnnouncementShareList = false;
                    return;
                // ann-share-det / ann-share-rng toggle は下の controller dispatch で処理
                case "owner-face-box":
                case "owner-face-canvas": // 中央の顔 canvas が innermost auto-clickable で実クリックはこちらに来る
                    // サーバ側で button id 9000 経由で togglePrivateMode 呼び出し。
                    // client は getUpdateTag 同期で反映 (client 直 mutate を廃止)。
                    scr.clickButton(belugalab.tsu.api.OwnerAccess.TOGGLE_BUTTON);
                    return;
            }
        }
        // Announcement-related toggles (= controller dispatch、popup 中のみ意味あり)
        if (scr.showAnnouncement) {
            if (scr.annMasterToggle.handleClick(classes)) return;
            if (scr.annRangeFrameToggle.handleClick(classes)) return;
            if (scr.annAttenuationToggle.handleClick(classes)) return;
        }
        if (scr.showScreenDoor) {
            if (scr.sdHighlightToggle.handleClick(classes)) return;
        }
        if (scr.showAnnouncement && scr.showAnnouncementShareList) {
            int idx = scr.annShareRealIdx();
            if (scr.annShareDetToggle.handleClick(classes, idx)) return;
            if (scr.annShareRngToggle.handleClick(classes, idx)) return;
        }
    }

    // ===== popup slot chrome (render 側) =====

    /**
     * popup (overlay2) 上の slot の当たり判定。
     *
     * <p>{@link #renderPopupOverlayItems} は 16px の中身を popup の scale で拡大して描くため、
     * 見えている slot は {@code 16 * scale} px。vanilla の {@code isHovering} は 16px 固定なので、
     * dialog が拡大されているとき外周がハイライトされず・クリックも効かない。判定も同じ縮尺で行う。
     * scale は manta の overlay 変換 ({@code overlay2Scale()}) を単一の情報源にする。
     */
    static boolean isOverPopupSlot(RailwayManagementScreenV2 scr, net.minecraft.world.inventory.Slot slot,
                                    double mouseX, double mouseY) {
        float s = scr.overlay2Scale() > 0f ? scr.overlay2Scale() : 1f;
        float x0 = scr.leftPosAccess() + slot.x;
        float y0 = scr.topPosAccess() + slot.y;
        float size = 16f * s;
        return mouseX >= x0 && mouseX < x0 + size
                && mouseY >= y0 && mouseY < y0 + size;
    }

    /** popup の上 (z=700) に slot icon + hover highlight + 手持ちアイテムを再描画。
     *  popup 開放アニメと同期して scale-in (popIn と同じ 0.7→1.0 + EASE_OUT_BACK) を適用し、
     *  popup と「同時に」アイテムが拡大されて見えるようにする (他 GUI と同等の挙動)。 */
    static void renderPopupOverlayItems(RailwayManagementScreenV2 scr, net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY) {
        // popup 開放アニメ進捗 (0..1)。完了後は scale=1.0 で固定。
        long elapsedNs = scr.announcementOpenedAtNanos > 0
                ? System.nanoTime() - scr.announcementOpenedAtNanos : RailwayManagementScreenV2.ANNOUNCEMENT_OPEN_ANIM_NS;
        float t = elapsedNs >= RailwayManagementScreenV2.ANNOUNCEMENT_OPEN_ANIM_NS
                ? 1f
                : Math.max(0f, Math.min(1f, elapsedNs / (float) RailwayManagementScreenV2.ANNOUNCEMENT_OPEN_ANIM_NS));
        float ease = belugalab.mcss3.anim.Easing.EASE_OUT_BACK.apply(t);
        float scale = 0.7f + (1.0f - 0.7f) * ease;

        // popup の中心 (アニメの anchor) — overlay2X/Y + popup root size。
        // overlay2W/H は論理サイズなので、screen 中心は manta の変換を通す
        // (overlay2X() + overlay2W()/2 は screen 原点 + 論理長の混在で scale != 1 のときずれる)。
        int popupW = scr.overlay2W() > 0 ? scr.overlay2W() : 240;
        int popupH = scr.overlay2H() > 0 ? scr.overlay2H() : 340;
        int popupCx = Math.round(scr.overlay2LocalToScreenX(popupW / 2f));
        int popupCy = Math.round(scr.overlay2LocalToScreenY(popupH / 2f));

        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        // 開放アニメ中は overshoot (>1.0) も含めて popup と同じ pose に乗せる。
        if (Math.abs(scale - 1f) > 0.001f) {
            g.pose().translate(popupCx, popupCy, 0);
            g.pose().scale(scale, scale, 1f);
            g.pose().translate(-popupCx, -popupCy, 0);
        }
        // slot に格納されたアイテム + hover highlight + lock 表示 (× 線)
        boolean lockDet = scr.be().isSharedDetectionTarget();
        boolean lockRng = scr.be().isSharedRangeTarget();
        for (int i = com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE;
             i < scr.getMenu().slots.size(); i++) {
            var slot = scr.getMenu().slots.get(i);
            if (!slot.isActive()) continue;
            // off-screen (-1000) の slot はスキップ
            if (slot.x < -500) continue;
            float entryOffsetY = 0f;
            if (scr.showAnnouncement
                    && i >= com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE
                    && i < com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD) {
                entryOffsetY = scr.announcementEntryShuffleOffset(
                        i - com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE);
            }
            int sx = scr.leftPosAccess() + slot.x;
            int sy = Math.round(scr.topPosAccess() + slot.y + entryOffsetY);
            // popup の scale に合わせて 16px の中身も縮尺する (位置は slot 座標で確定済み)。
            // hover 判定も同じ縮尺の矩形で行い、見た目とハイライトを一致させる。
            float slotScale = scr.overlay2Scale() > 0f ? scr.overlay2Scale() : 1f;
            boolean hovered = isOverPopupSlot(scr, slot, mouseX, mouseY);
            g.pose().pushPose();
            g.pose().translate(sx, sy, 0);
            g.pose().scale(slotScale, slotScale, 1f);
            g.pose().translate(-sx, -sy, 0);
            try {
            boolean screenDoorCardSlot = slot.index
                    == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD;
            if (screenDoorCardSlot) {
                drawScreenDoorCardSlotFrame(g, sx, sy, hovered);
            }
            if (hovered && !screenDoorCardSlot) {
                g.fillGradient(sx, sy, sx + 16, sy + 16, 0x80FFFFFF, 0x80FFFFFF);
            }
            var stack = slot.getItem();
            if (!stack.isEmpty()) {
                g.renderItem(stack, sx, sy);
                g.renderItemDecorations(scr.fontOrNull(), stack, sx, sy);
            }
            // 共有先になっているスロットには × 線を描画 (アイテム配置不可だと視覚的に示す)。
            boolean drawLock = false;
            if (slot.index == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_DETECTION_CARD
                    && lockDet) drawLock = true;
            else if (slot.index == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_RANGE_BOARD
                    && lockRng) drawLock = true;
            if (drawLock) drawSlotLockedHatch(g, sx, sy);
            } finally {
                g.pose().popPose();
            }
        }
        g.pose().popPose();

        // 手持ち (drag 中) アイテムは scale 影響を受けない z=700 layer に描画。
        // ツールチップは render() の最後に z=900 で再描画する (popup の上に表示)。
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        var carried = scr.getMenu().getCarried();
        if (!carried.isEmpty()) {
            int cx = mouseX - 8;
            int cy = mouseY - 8;
            g.renderItem(carried, cx, cy);
            g.renderItemDecorations(scr.fontOrNull(), carried, cx, cy);
        }
        g.pose().popPose();
    }

    static void drawScreenDoorCardSlotFrame(net.minecraft.client.gui.GuiGraphics g,
                                             int slotX, int slotY, boolean hovered) {
        int left = slotX - 1;
        int top = slotY - 1;
        int right = left + 18;
        int bottom = top + 18;
        int border = hovered ? 0xFF4FC3F7 : 0xFF2A2A3A;
        belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, left, top, 18, 18, 5f, 0x8C000000);
        if (hovered) {
            belugalab.mcss3.draw.SmoothRenderer.fillRoundedRect(g, left + 1, top + 1, 16, 16, 4f, 0x264FC3F7);
        }
        belugalab.mcss3.draw.SmoothRenderer.strokeRoundedRect(g, left, top, 18, 18, 5f, 1f, border);
    }

    /** 共有先になっているスロット (16x16) に × 状の斜線を描画する。
     *  ユーザにアイテムが配置できないことを視覚的に伝える。 */
    static void drawSlotLockedHatch(net.minecraft.client.gui.GuiGraphics g, int x, int y) {
        // 半透明の暗いオーバーレイで「使用不可」の雰囲気を出す
        g.fill(x, y, x + 16, y + 16, 0x80101020);
        // 赤い斜線 (\\ と /) を 16 ドット分プロット。各ドットは 1x1 px の塗り。
        int line = 0xFFff5555;
        for (int i = 0; i < 16; i++) {
            // \ : (i, i)
            g.fill(x + i, y + i, x + i + 1, y + i + 1, line);
            // / : (15-i, i)
            g.fill(x + 15 - i, y + i, x + 16 - i, y + i + 1, line);
        }
    }

    /** マウス位置からアクティブな slot を探す (popup slot 含む)。tooltip 再描画 / hit 判定で利用。 */
    static net.minecraft.world.inventory.Slot findHoveredSlot(RailwayManagementScreenV2 scr, int mouseX, int mouseY) {
        for (int i = 0; i < scr.getMenu().slots.size(); i++) {
            var slot = scr.getMenu().slots.get(i);
            if (!slot.isActive()) continue;
            if (slot.x < -500) continue;
            boolean hit = i >= com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE
                    ? RailwayManagementDispatch.isOverPopupSlot(scr, slot, mouseX, mouseY)
                    : scr.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY);
            if (hit) {
                return slot;
            }
        }
        return null;
    }

    /** Menu slot の x/y を reflection 経由で書き換える (Slot.x/y は package-private)。 */
    static void setMenuSlotPos(net.minecraft.world.inventory.AbstractContainerMenu menu, int slotIndex, int x, int y) {
        if (slotIndex < 0 || slotIndex >= menu.slots.size()) return;
        var slot = menu.slots.get(slotIndex);
        try {
            java.lang.reflect.Field fx = net.minecraft.world.inventory.Slot.class.getDeclaredField("x");
            java.lang.reflect.Field fy = net.minecraft.world.inventory.Slot.class.getDeclaredField("y");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setInt(slot, x);
            fy.setInt(slot, y);
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[RailwayScreen] GUI op failed", e); }
    }
}
