package com.trainsystemutilities.client.gui;

import belugalab.experience.controller.ColorPickerController;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.schedule.TrainTypes;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/** ManagementComputerScreenV2 の getDynamic* / click dispatcher 本体 (god-class 分割)。
 *  挙動は screen 在置時代と同一 — bodies は verbatim 移設で、screen メンバーは scr. 経由で参照する。 */
final class ManagementComputerDispatch {

    private ManagementComputerDispatch() {}

    static String getDynamicText(ManagementComputerScreenV2 scr, String[] classes, String defaultText) {
        // 券売機タブ: タイトル (販売可 N/M) + 行ごとの駅名
        if (scr.tabs.is("tickets")) {
            int rt = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            var groups = scr.ticketGroups();
            for (String c : classes) {
                if ("tickets-title".equals(c)) {
                    int sell = 0;
                    for (var g : groups)
                        if (com.trainsystemutilities.station.TicketConfigClientCache.isSellable(g.id())) sell++;
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.tickets_title_fmt", sell, groups.size()).getString();
                }
                if ("ticket-row-name".equals(c) && rt >= 0) {
                    int realIdx = rt + scr.ticketScroll.offset();
                    if (realIdx < groups.size()) {
                        String name = groups.get(realIdx).name();
                        int maxW = 132;
                        if (scr.fontOrNull().width(name) <= maxW) return name;
                        while (name.length() > 0 && scr.fontOrNull().width(name + "…") > maxW)
                            name = name.substring(0, name.length() - 1);
                        return name + "…";
                    }
                    return "";
                }
            }
        }
        if (scr.layoutEditor.isOpen()) {
            for (String c : classes) {
                if ("layout-info".equals(c)) {
                    int monW = scr.serverBE().getMonitorW();
                    int monH = scr.serverBE().getMonitorH();
                    if (monW <= 0 || monH <= 0) return ManagementComputerScreenV2.tr("tsu.mc.monitor_unlinked");
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.monitor_size_panels_fmt", monW, monH, scr.layoutEditor.getLayout().size()).getString();
                }
                // タイル機能別設定 popup (overlay2) のタイトル / 値
                if ("pset-title".equals(c)) {
                    var pp = scr.psetPanel();
                    return pp == null ? "" : net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.pset_title_fmt", pp.getType().getDisplayName()).getString();
                }
                var pp = scr.psetPanel();
                if (pp != null) {
                    switch (c) {
                        case "pset-font-val":        return scr.psetValText(pp.getFontSize());
                        case "pset-maptext-val":     return scr.psetValText(pp.getMapTextSize());
                        case "pset-trainicon-val":   return scr.psetValText(pp.getTrainIconSize());
                        case "pset-stationicon-val": return scr.psetValText(pp.getStationIconSize());
                        case "pset-signalicon-val":  return scr.psetValText(pp.getSignalIconSize());
                    }
                }
            }
        }
        // Monitor 色設定 popup texts — controller に委譲
        if (scr.showMonitorColorSettings) {
            int rIdx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            String t = scr.monitorColorPopup.resolveText(classes, rIdx);
            if (t != null) return t;
        }
        // Train detail popup repeat-context texts
        int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
        if (ri >= 0 && scr.selectedTrainId != null) {
            for (String c : classes) {
                // W7-1 (R4.23.1): 現在行マーカー ▶ / 他行 ○ の**対**を registry icon へ。
                // 片方だけ icon 化すると行頭の幅が揃わないので、両方を icon で返す。
                if ("train-detail-sched-marker".equals(c)) {
                    if (ri < scr.selectedSchedEntries.size()) {
                        return (ri == scr.selectedSchedCurrent) ? "manta:play" : "manta:circle";
                    }
                }
                if ("train-detail-sched-item".equals(c)) {
                    if (ri < scr.selectedSchedEntries.size()) {
                        return scr.selectedSchedEntries.get(ri);
                    }
                }
            }
        }
        // Station assign dropdown items (controller 委譲)
        if (ri >= 0 && scr.stationAssign.isOpen()) {
            String t = scr.stationAssign.resolveItemText(classes, ri, () -> scr.serverBE().getLineSymbols());
            if (t != null) return t;
        }
        // Symbol editor field values (controller 委譲)
        if (scr.symEditor.isOpen()) {
            String t = scr.symEditor.resolveText(classes);
            if (t != null) return t;
        }
        // Symbol delete confirm (controller 委譲)
        if (scr.symbolDelete.isOpen()) {
            String t = scr.symbolDelete.resolveText(classes, () -> scr.serverBE().getLineSymbols());
            if (t != null) return t;
        }
        // Station assign title (per target, controller 委譲)
        if (scr.stationAssign.isOpen()) {
            String t = scr.stationAssign.resolveTitleText(classes);
            if (t != null) return t;
        }
        // HSV picker hex / RGB / HSL display (フォーカス中はバッファ + caret を返す)
        if (scr.showColorPicker) {
            // 0.5 秒周期の caret 点滅
            boolean caretOn = ((System.nanoTime() - scr.fieldFocusBlinkNano) / 500_000_000L) % 2 == 0;
            String caret = caretOn ? "_" : " ";
            for (String c : classes) {
                if ("cp-hex".equals(c)) return scr.currentPickerHex();
                if ("cp-info-hex".equals(c)) {
                    if ("hex".equals(scr.focusedField)) return scr.fieldEditBuffer + caret;
                    return scr.currentPickerHex().toUpperCase();
                }
                if ("cp-info-rgb".equals(c)) {
                    if ("rgb".equals(scr.focusedField)) return scr.fieldEditBuffer + caret;
                    int rgb = ColorPickerController.hsvToRgb(scr.picker.hue(), scr.picker.saturation(), scr.picker.value());
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    return r + ", " + g + ", " + b;
                }
                if ("cp-info-hsl".equals(c)) {
                    if ("hsl".equals(scr.focusedField)) return scr.fieldEditBuffer + caret;
                    float h = scr.picker.hue() * 360f;
                    float l = scr.picker.value() * (1f - scr.picker.saturation() / 2f);
                    float s = (l == 0f || l == 1f) ? 0f
                            : (scr.picker.value() - l) / Math.min(l, 1f - l);
                    return Math.round(h) + "°, " + Math.round(s * 100) + "%, "
                            + Math.round(l * 100) + "%";
                }
            }
        }
        // Symbol tab: dynamic title + per-tile name (repeat-context)
        if (scr.tabs.is("symbol")) {
            for (String c : classes) {
                if ("sym-tab-title".equals(c)) {
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.line_symbols_fmt", scr.serverBE().getLineSymbols().size()).getString();
                }
                if ("sym-tile-name".equals(c) && ri >= 0) {
                    var syms = scr.serverBE().getLineSymbols();
                    if (ri < syms.size()) {
                        String name = syms.get(ri).getName();
                        if (name.isEmpty()) return "";
                        // Truncate to fit tile width (36 - 2)
                        int maxW = 34;
                        if (scr.fontOrNull().width(name) <= maxW) return name;
                        while (name.length() > 0 && scr.fontOrNull().width(name + "…") > maxW)
                            name = name.substring(0, name.length() - 1);
                        return name + "…";
                    }
                    return "";
                }
            }
        }
        // Trains tab list rows (repeat context, with trainScroll offset)
        if (scr.tabs.is("trains") && ri >= 0) {
            var live = scr.trainsForList();
            int realIdx = ri + scr.trainScroll.offset();
            if (realIdx < live.size()) {
                var info = live.get(realIdx);
                var liveOpt = scr.trySafeLiveTrain(info.id());
                for (String c : classes) {
                    if ("train-row-name".equals(c)) return info.name();
                    if ("train-row-status".equals(c)) {
                        String station = (String) liveOpt[0];
                        double speed = (Double) liveOpt[1];
                        return !station.isEmpty() ? "█ " + station
                                : String.format("%.0f km/h", speed);
                    }
                    if ("train-row-cars".equals(c)) {
                        return net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.cars_unit_fmt", (Integer) liveOpt[2]).getString();
                    }
                    if ("train-row-dest".equals(c)) {
                        return (String) liveOpt[3];
                    }
                }
            }
        }
        // Schedule tab list rows
        if (scr.tabs.is("schedule") && scr.scheduleSelectedTrainId == null && ri >= 0) {
            var live = scr.trainsForList();
            int realIdx = ri + scr.schedListScroll.offset();
            if (realIdx < live.size()) {
                var info = live.get(realIdx);
                int entries = 0;
                boolean elec = false;
                if (scr.wikiMode) {
                    int[] meta = scr.wikiTrainMeta.get(info.id());
                    if (meta != null) { entries = meta[0]; elec = meta[1] == 1; }
                } else {
                    // server 同期フラグ + SchedView から (MP-safe; client getTrainById は使わない)
                    // W7-1 (R4.23.1): 一時停止マーカー ⏸ は行頭の registry icon
                    // (manta:pause) へ移した。**「停止していない行では出さない」**は
                    // iconKey では表せない (空を返すと defaultIconId に fallback する) ので
                    // visibleKey = "sched-row-paused" の boolean binding で制御する。
                    var sv = scr.be().getSyncedSchedView(info.id());
                    if (sv != null) entries = sv.entries().size();
                    elec = scr.isElectronicTimetable(info.id());
                }
                for (String c : classes) {
                    if ("sched-row-type".equals(c)) {
                        return TrainTypes.localize(scr.be().getSyncedTrainType(info.id()));
                    }
                    if ("sched-row-name".equals(c)) return info.name();
                    if ("sched-row-entries".equals(c)) {
                        UUID shareSrc = scr.be().getTimetableShareSource(info.id());
                        if (shareSrc != null) {
                            return net.minecraft.network.chat.Component.translatable(
                                    "tsu.mc.tt_shared_from_fmt", scr.trainNameById(shareSrc)).getString();
                        }
                        if (entries <= 0) return ManagementComputerScreenV2.tr("tsu.mc.tt_state_none");
                        return ManagementComputerScreenV2.tr(elec ? "tsu.mc.tt_state_electronic" : "tsu.mc.tt_state_regular")
                                + " · " + net.minecraft.network.chat.Component.translatable("tsu.mc.entries_unit_fmt", entries).getString();
                    }
                }
            }
        }
        // Schedule tab detail (no repeat context except for entries)
        if (scr.tabs.is("schedule") && scr.scheduleSelectedTrainId != null) {
            for (String c : classes) {
                if ("sched-type-val".equals(c)) return TrainTypes.localizeForEditor(scr.selectedTrainTypeCode());
                if ("sched-detail-name".equals(c)) return scr.selectedTrainName.isEmpty() ? "?" : scr.selectedTrainName;
                if ("sched-detail-info".equals(c)) {
                    String status = !scr.selectedTrainStation.isEmpty() ? scr.selectedTrainStation
                            : (scr.isSelectedSchedTrainPaused() ? ManagementComputerScreenV2.tr("tsu.mc.train_stopped") : ManagementComputerScreenV2.tr("tsu.mc.train_running"));
                    String base = net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_summary_fmt",
                            scr.selectedTrainCars, String.format("%.0f", scr.selectedTrainSpeed), status).getString();
                    UUID src = scr.be().getTimetableShareSource(scr.scheduleSelectedTrainId);
                    if (src != null) {
                        base += " · " + net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.sched_shared_banner_fmt", scr.trainNameById(src)).getString();
                    }
                    return base;
                }
                if ("sched-pause".equals(c)) {
                    return ManagementComputerScreenV2.tr(scr.isSelectedSchedTrainPaused() ? "tsu.mc.train_resume_btn" : "tsu.mc.train_stop_btn");
                }
                if ("sched-edit".equals(c)) {
                    if (scr.be().isTimetableFollower(scr.scheduleSelectedTrainId))
                        return ManagementComputerScreenV2.tr("tsu.mc.sched_edit_label_shared");
                    if (scr.selectedSchedTrainHasSchedule() && !scr.selectedSchedTrainIsElectronic())
                        return ManagementComputerScreenV2.tr("tsu.mc.sched_edit_label_regular");
                    if (!scr.selectedSchedTrainHasConductor())
                        return ManagementComputerScreenV2.tr("tsu.mc.sched_edit_label_conductor");
                    return ManagementComputerScreenV2.tr("tsu.mc.sched_edit_label_edit");
                }
                if ("sched-cyclic".equals(c)) {
                    Boolean cyc = scr.selectedSchedCyclic();
                    if (cyc == null) return "";
                    return ManagementComputerScreenV2.tr(cyc ? "tsu.mc.sched_cyclic_loop" : "tsu.mc.sched_cyclic_oneway");
                }
                if ("sched-share-title".equals(c)) {
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.sched_share_title_fmt",
                            scr.selectedTrainName.isEmpty() ? "?" : scr.selectedTrainName).getString();
                }
            }
            // Entries repeat (詳細) / 共有候補 repeat (popup)
            if (ri >= 0) {
                if (scr.showScheduleShare) {
                    int sIdx = ri + scr.schedShareScroll.offset();
                    var cand = scr.schedShareCandidates();
                    if (sIdx < cand.size()) {
                        for (String c : classes) {
                            if ("sched-share-train-name".equals(c)) return cand.get(sIdx).name();
                        }
                    }
                } else {
                    int realIdx = ri + scr.schedEntryScroll.offset();
                    if (realIdx < scr.selectedSchedEntries.size()) {
                        boolean cur = (realIdx == scr.selectedSchedCurrent);
                        for (String c : classes) {
                            // W7-1 (R4.23.1): 現在行マーカーは registry icon。
                            // 非現在行は「見えない円」ではなく **circle** を返して
                            // 行頭の幅を揃える (旧実装の空白 2 文字と同じ役割)。
                            if ("sched-entry-marker".equals(c)) {
                                return cur ? "manta:play" : "manta:circle";
                            }
                            if ("sched-entry-row".equals(c)) {
                                return (realIdx + 1) + ". " + scr.selectedSchedEntries.get(realIdx);
                            }
                        }
                    }
                }
            }
        }
        // Stations tab list rows
        if (scr.tabs.is("stations") && scr.selectedStationKey.isEmpty() && ri >= 0) {
            var stations = scr.stationsForList();
            int realIdx = ri + scr.stationScroll.offset();
            if (realIdx < stations.size()) {
                var st = stations.get(realIdx);
                for (String c : classes) {
                    if ("station-row-name".equals(c)) {
                        String name = st.name();
                        int maxW = 128;
                        if (scr.fontOrNull().width(name) <= maxW) return name;
                        while (name.length() > 0 && scr.fontOrNull().width(name + "…") > maxW)
                            name = name.substring(0, name.length() - 1);
                        return name + "…";
                    }
                    if ("station-row-pos".equals(c)) {
                        return "(" + st.position().getX() + "," + st.position().getZ() + ")";
                    }
                    if ("station-row-link".equals(c)) {
                        return scr.be().hasManagerForStation(st.name(), st.position()) ? "●" : "○";
                    }
                    // W7-1 (R4.23.1): 「記号あり=編集 / なし=追加」の対を registry icon で。
                    // layout 側は <icon iconKey="station-row-assign-icon"> で、
                    // ここは **registry ID** を返す (ColorTargetController と同じ契約)。
                    if ("station-row-assign-icon".equals(c)) {
                        var sym = scr.be().getSymbolForStation(st.name(), st.position());
                        return sym != null ? "manta:pencil" : "manta:plus";
                    }
                }
            }
        }
        // Stations tab detail
        if (scr.tabs.is("stations") && !scr.selectedStationKey.isEmpty()) {
            var s = scr.selectedStation();
            if (s != null) {
                var sym = scr.be().getSymbolForStation(s.name(), s.position());
                BlockPos rmPos = scr.be().getManagerPosForStation(s.name(), s.position());
                for (String c : classes) {
                    if ("station-detail-name".equals(c)) return "🚉 " + s.name();
                    if ("station-detail-pos".equals(c)) {
                        return net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.station_pos_fmt",
                                s.position().getX(), s.position().getY(), s.position().getZ()).getString();
                    }
                    if ("station-detail-rm".equals(c)) {
                        return rmPos != null
                                ? net.minecraft.network.chat.Component.translatable(
                                    "tsu.mc.station_rm_linked_fmt",
                                    rmPos.getX(), rmPos.getY(), rmPos.getZ()).getString()
                                : ManagementComputerScreenV2.tr("tsu.mc.station_rm_unlinked");
                    }
                    if ("station-detail-monitor".equals(c)) {
                        if (rmPos == null) return "";
                        try {
                            if (scr.be().getLevel() != null) {
                                var bbe = scr.be().getLevel().getBlockEntity(rmPos);
                                if (bbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                                    int groups = rm.getLinkedMonitorGroupCount();
                                    return groups > 0
                                            ? net.minecraft.network.chat.Component.translatable(
                                                "tsu.mc.station_monitor_groups_fmt", groups).getString()
                                            : ManagementComputerScreenV2.tr("tsu.mc.station_monitor_unlinked");
                                }
                            }
                        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
                        return "";
                    }
                    if ("station-detail-symbol".equals(c)) {
                        return sym != null
                                ? net.minecraft.network.chat.Component.translatable(
                                    "tsu.mc.station_symbol_fmt", sym.getLetters() + sym.getNumberStr(),
                                    sym.getName().isEmpty() ? "" : " (" + sym.getName() + ")").getString()
                                : ManagementComputerScreenV2.tr("tsu.mc.station_symbol_none");
                    }
                }
            }
        }
        // Door direction button labels (repeat context)
        if (ri >= 0 && ri < ManagementComputerScreenV2.DOOR_OPTS.length) {
            for (String c : classes) {
                if ("door-btn".equals(c)) return ManagementComputerScreenV2.tr(ManagementComputerScreenV2.DOOR_OPTS[ri][1]);
            }
        }
        // Schedule editor の dynamic text (controller 委譲)
        {
            String st = scr.schedEditor.resolveText(classes, ri, scr::schedStationNames, scr.fontOrNull());
            if (st != null) return st;
        }
        for (String c : classes) {
            switch (c) {
                case "tab-dropdown":
                    return scr.currentTabLabel();
                case "mc-monitor-status-label":
                    return ManagementComputerScreenV2.tr(scr.monitorEnabled() ? "tsu.mc.monitor_on" : "tsu.mc.monitor_off");
                case "monitor-label":
                    return ManagementComputerScreenV2.tr("tsu.mc.monitor_label");
                case "mc-monitor-info":
                    // online = card 入っていてリンク先が解決できる、それ以外は offline
                    return ManagementComputerScreenV2.tr(scr.isOnline() ? "tsu.mc.online" : "tsu.mc.offline");
                case "stat-station":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.stat_station_fmt", scr.serverBE().getCachedStationCount()).getString();
                case "stat-train":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.stat_train_fmt", scr.serverBE().getCachedTrainCount()).getString();
                case "stat-signal":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.stat_signal_fmt", scr.serverBE().getCachedSignalCount()).getString();
                case "sched-tab-stat":
                    // 通常モード時に右側に表示 (例: "5列車 / 3駅")
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.sched_tab_stat_fmt", scr.trainsForList().size(), scr.stationsForList().size()).getString();
                case "sched-stopall-label": {
                    int paused = scr.pausedTrainCount();
                    return paused > 0
                            ? net.minecraft.network.chat.Component.translatable(
                                "tsu.mc.all_stop_active_fmt", paused).getString()
                            : ManagementComputerScreenV2.tr("tsu.mc.all_stop_done");
                }
                case "tab-content-text":
                case "tab-content-hint":
                    // Hide placeholder text on tabs that have a real implementation
                    return "";
                case "train-detail-title":
                    return scr.selectedTrainName.isEmpty() ? ManagementComputerScreenV2.tr("tsu.mc.train_default_name") : scr.selectedTrainName;
                case "train-detail-cars":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_cars_fmt", String.valueOf(scr.selectedTrainCars)).getString();
                case "train-detail-speed":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_speed_fmt", String.format("%.1f", scr.selectedTrainSpeed)).getString();
                case "train-detail-station":
                    return net.minecraft.network.chat.Component.translatable(
                            "tsu.mc.train_current_fmt",
                            scr.selectedTrainStation.isEmpty() ? ManagementComputerScreenV2.tr("tsu.mc.train_running") : scr.selectedTrainStation).getString();
                case "train-detail-sched-header":
                    return scr.selectedSchedEntries.isEmpty() ? ManagementComputerScreenV2.tr("tsu.mc.train_sched_empty") : ManagementComputerScreenV2.tr("tsu.mc.train_schedule_label");
            }
        }
        // Phase 24: 電化詳細 popup の動的テキスト (controller 委譲)
        if (scr.edDetail.isOpen() && scr.selectedTrainId != null) {
            String t = scr.edDetail.resolveText(classes, scr.selectedTrainId);
            if (t != null) return t;
        }
        return null;
    }

    /** @see LabelWidth 2026-08-29 に 8 画面へ広がったので共有クラスへ出した。 */
    private static int labelWidth(String langKey) {
        return LabelWidth.of(langKey);
    }

    static Integer getDynamicNumber(ManagementComputerScreenV2 scr, String[] classes, String key, int defaultValue) {
        if ("monitor-knob-x".equals(key)) return scr.monitorToggle.knobX(defaultValue);
        if ("export-all-knob-x".equals(key)) return scr.exportAllToggle.knobX(defaultValue);
        // hint-knob-x は JsonLayoutEngine が HintToggleHelper にルート (解決不要)
        // glyph を外して icon + label に分けたボタンのラベル幅 (labelWidth の javadoc 参照)。
        // sched-back と station-back は同じ lang key なので dynamicW の key も共用する。
        if ("stop-all-label-w".equals(key)) return labelWidth("tsu.mc.stop_all_trains");
        if ("resume-all-label-w".equals(key)) return labelWidth("tsu.mc.resume_all_trains");
        if ("back-label-w".equals(key)) return labelWidth("tsu.mc.back_btn");
        // 2026-08-29: lang から control glyph を外して icon + label にしたボタン群。
        // sym-cancel は sched-editor と symbol-delete の 2 layout で同じ lang key を使う
        // ので dynamicW の key も共用する (sched-back / station-back と同じ理由)。
        if ("layout-save-label-w".equals(key)) return labelWidth("tsu.mc.layout_save");
        if ("apply-label-w".equals(key)) return labelWidth("tsu.mc.apply");
        if ("sym-cancel-label-w".equals(key)) return labelWidth("tsu.mc.sym_cancel");
        if ("close-picker-label-w".equals(key)) return labelWidth("tsu.color.close_picker");
        if ("sched-count".equals(key)) {
            return Math.min(8, scr.selectedSchedEntries.size());
        }
        if ("sched-share-count".equals(key)) {
            return scr.showScheduleShare ? scr.schedShareScroll.rowCount() : 0;
        }
        if ("sched-share-toggle-knob-x".equals(key)) {
            return scr.schedShareToggle.knobXFor(scr.schedShareRealIdx(), defaultValue);
        }
        if ("sched-share-scroll-thumb-y".equals(key)) {
            return scr.schedShareScroll.thumbY(ManagementComputerScreenV2.SCHED_SHARE_AREA_Y, ManagementComputerScreenV2.SCHED_SHARE_AREA_H, scr.schedShareThumbH());
        }
        if ("sched-share-scroll-thumb-h".equals(key)) {
            return scr.schedShareThumbH();
        }
        if (scr.showMonitorColorSettings) {
            Integer n = scr.monitorColorPopup.resolveNumber(key);
            if (n != null) return n;
        }
        if ("assign-count".equals(key)) {
            return Math.min(12, scr.serverBE().getLineSymbols().size());
        }
        if ("preset-count".equals(key)) {
            return SymbolEditorController.SYMBOL_COLOR_PRESETS.length;
        }
        if ("cp-pal-count".equals(key)) {
            return scr.customColors.size();
        }
        if ("sym-edit-custom-count".equals(key)) {
            return scr.customColors.size();
        }
        if ("sym-grid-count".equals(key)) {
            return scr.serverBE().getLineSymbols().size();
        }
        if ("trains-row-count".equals(key)) {
            return scr.trainScroll.rowCount();
        }
        if ("sched-list-count".equals(key)) {
            return scr.schedListScroll.rowCount();
        }
        if ("sched-entries-count".equals(key)) {
            return scr.schedEntryScroll.rowCount();
        }
        if ("stations-row-count".equals(key)) {
            return scr.stationScroll.rowCount();
        }
        if ("tickets-row-count".equals(key)) {
            return scr.ticketScroll.rowCount();
        }
        if ("ticket-toggle-knob-x".equals(key)) {
            int rt = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (rt >= 0) return scr.ticketToggle.knobXFor(rt, defaultValue);
        }
        if ("tickets-scrollbar-thumb-y".equals(key)) {
            return scr.ticketScroll.thumbY(defaultValue, ManagementComputerScreenV2.TICKETS_LIST_TRACK_H - 2, ManagementComputerScreenV2.TICKETS_LIST_THUMB_H);
        }
        if ("tickets-scrollbar-thumb-h".equals(key)) return ManagementComputerScreenV2.TICKETS_LIST_THUMB_H;
        if ("stations-scrollbar-thumb-y".equals(key)) {
            return scr.stationScroll.thumbY(defaultValue, ManagementComputerScreenV2.STATION_LIST_TRACK_H - 2, ManagementComputerScreenV2.STATION_LIST_THUMB_H);
        }
        if ("stations-scrollbar-thumb-h".equals(key)) return ManagementComputerScreenV2.STATION_LIST_THUMB_H;
        if ("door-count".equals(key)) {
            return ManagementComputerScreenV2.DOOR_OPTS.length;
        }
        {
            Integer sn = scr.schedEditor.resolveNumber(key, scr::schedStationNames);
            if (sn != null) return sn;
        }
        if ("sched-station-thumb-y".equals(key)) return scr.schedEditor.stationThumbY(defaultValue);
        if ("sched-station-thumb-h".equals(key)) return scr.schedEditor.stationThumbH();
        // Trains list scrollbar (track + thumb dynamic y/h)
        if ("trains-scrollbar-thumb-y".equals(key)) {
            return scr.trainScroll.thumbY(defaultValue, ManagementComputerScreenV2.TRAIN_LIST_TRACK_H - 2, ManagementComputerScreenV2.TRAIN_LIST_THUMB_H);
        }
        if ("trains-scrollbar-thumb-h".equals(key)) return ManagementComputerScreenV2.TRAIN_LIST_THUMB_H;
        if ("sched-entries-thumb-y".equals(key)) {
            return scr.schedEntryScroll.thumbY(defaultValue, ManagementComputerScreenV2.SCHED_ENTRIES_TRACK_H - 2, ManagementComputerScreenV2.SCHED_ENTRIES_THUMB_H);
        }
        if ("sched-entries-thumb-h".equals(key)) return ManagementComputerScreenV2.SCHED_ENTRIES_THUMB_H;
        return null;
    }

    static Boolean getDynamicBool(ManagementComputerScreenV2 scr, String[] classes, String key, boolean defaultValue) {
        if (scr.showMonitorColorSettings) {
            Boolean b = scr.monitorColorPopup.resolveBool(key);
            if (b != null) return b;
        }
        // W7-1 (R4.23.1): 一時停止中の列車だけ行頭に manta:pause を出す。
        // repeat 行なので currentRepeatIndex + scroll offset で実 index を出す
        // (旧実装は "⏸ " を列車名へ前置していた)。wikiMode は同期フラグを持たないので false。
        if ("sched-row-paused".equals(key)) {
            int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (ri < 0 || scr.wikiMode) return false;
            var live = scr.trainsForList();
            int realIdx = ri + scr.schedListScroll.offset();
            if (realIdx < 0 || realIdx >= live.size()) return false;
            return scr.be().hasSyncedPaused(live.get(realIdx).id());
        }
        if ("cp-pal-empty".equals(key)) return scr.customColors.isEmpty();
        if ("sym-edit-has-custom".equals(key))
            return scr.symEditor.isOpen() && !scr.customColors.isEmpty();
        // タイル機能別設定 popup: ROUTE_MAP は 4 サイズ行、それ以外は文字サイズ行のみ
        if ("pset-map-visible".equals(key)) {
            var pp = scr.psetPanel();
            return pp != null && pp.getType()
                    == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP;
        }
        if ("pset-font-visible".equals(key)) {
            var pp = scr.psetPanel();
            return pp != null && pp.getType()
                    != com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP;
        }
        // Phase 24: 電化ボタンは popup が開いていれば常に表示 (= 非電化列車でも
        // クリック時に詳細スクリーン側で「未同期」状態を表示する)。
        // 同期遅延やキャッシュ未到達でボタンが消える UX を防止。
        if ("electrification-btn-visible".equals(key)) {
            return scr.selectedTrainId != null;
        }
        if ("trains-scrollbar-visible".equals(key)) {
            return scr.trainScroll.needsScrollbar();  // activeWhen(trains) 内包 (§4.19 R4.19.2)
        }
        if ("sched-entries-scrollbar-visible".equals(key)) {
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId != null && scr.schedEntryScroll.needsScrollbar();
        }
        // Tab visibility (one-shot per tab)
        if ("tab-symbol-active".equals(key)) return scr.tabs.is("symbol");
        if ("tab-symbol-active-with-items".equals(key))
            return scr.tabs.is("symbol") && !scr.serverBE().getLineSymbols().isEmpty();
        if ("tab-symbol-active-empty".equals(key))
            return scr.tabs.is("symbol") && scr.serverBE().getLineSymbols().isEmpty();
        if ("tab-map-active".equals(key)) return scr.tabs.is("map");
        // Trains tab
        int liveTrainCount = scr.trainsForList().size();
        if ("tab-trains-active".equals(key)) return scr.tabs.is("trains");
        if ("tab-trains-empty".equals(key)) return scr.tabs.is("trains") && liveTrainCount == 0;
        if ("tab-trains-list".equals(key)) return scr.tabs.is("trains") && liveTrainCount > 0;
        // Schedule tab
        if ("tab-sched-empty".equals(key))
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId == null && liveTrainCount == 0;
        if ("tab-sched-list".equals(key))
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId == null && liveTrainCount > 0;
        if ("tab-sched-list-rows".equals(key))
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId == null && liveTrainCount > 0;
        if ("tab-sched-detail".equals(key))
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId != null;
        if ("sched-share-btn-visible".equals(key))
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId != null
                    && scr.selectedSchedTrainIsElectronic() && scr.selectedSchedTrainHasSchedule()
                    && !scr.be().isTimetableFollower(scr.scheduleSelectedTrainId);
        if ("sched-share-scroll-visible".equals(key))
            return scr.showScheduleShare && scr.schedShareScroll.needsScrollbar();
        if ("sched-share-empty".equals(key))
            return scr.showScheduleShare && scr.schedShareCandidates().isEmpty();
        if ("tab-sched-edit-visible".equals(key))
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId != null && scr.isSelectedSchedTrainPaused();
        // editor 内 inline + entry dropdown の visibility + station-pick empty (controller 委譲)
        {
            Boolean sb = scr.schedEditor.resolveBool(key, scr::schedStationNames);
            if (sb != null) return sb;
        }
        // 全列車停止コントロールバー: list mode の時のみ表示。
        // - paused が無い → "停止" ボタン表示
        // - paused が存在 → "再開" ボタン表示
        if ("sched-stopall-show-stop".equals(key)) {
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId == null
                    && !scr.trainsForList().isEmpty() && !scr.anyTrainPaused();
        }
        if ("sched-stopall-show-resume".equals(key)) {
            return scr.tabs.is("schedule") && scr.scheduleSelectedTrainId == null
                    && scr.anyTrainPaused();
        }
        if ("sched-entries-empty".equals(key)) return scr.selectedSchedEntries.isEmpty();
        // Stations tab — client-side scan を優先 (server cache の同期遅延を回避)
        var stationList = scr.stationsForList();
        boolean inStations = scr.tabs.is("stations");
        if ("tab-stations-empty".equals(key))
            return inStations && scr.selectedStationKey.isEmpty() && stationList.isEmpty();
        if ("tab-stations-list".equals(key))
            return inStations && scr.selectedStationKey.isEmpty();
        if ("tab-stations-list-rows".equals(key))
            return inStations && scr.selectedStationKey.isEmpty() && !stationList.isEmpty();
        if ("stations-scrollbar-visible".equals(key))
            return scr.stationScroll.needsScrollbar();  // activeWhen(stations & list) 内包 (§4.19 R4.19.2/R4.19.3)
        if ("tab-stations-detail".equals(key))
            return inStations && !scr.selectedStationKey.isEmpty();
        if ("tab-stations-detail-rm".equals(key))
            return inStations && !scr.selectedStationKey.isEmpty() && scr.selectedStationHasRMBE();
        // 券売機タブ (ネットワーク駅の販売可選択)
        boolean inTickets = scr.tabs.is("tickets");
        int ticketGroupCount = com.trainsystemutilities.station.StationGroupClientCache.all().size();
        if ("tab-tickets-list".equals(key)) return inTickets;
        if ("tab-tickets-empty".equals(key)) return inTickets && ticketGroupCount == 0;
        if ("tab-tickets-list-rows".equals(key)) return inTickets && ticketGroupCount > 0;
        if ("tickets-scrollbar-visible".equals(key)) return scr.ticketScroll.needsScrollbar();
        // Procedural fallback placeholder — only for tabs not yet JSON-migrated (none left)
        if ("tab-procedural-active".equals(key)) return false;
        return null;
    }

    static Integer getDynamicColor(ManagementComputerScreenV2 scr, String[] classes, String key, int defaultArgb) {
        // Monitor 色設定 popup の dynamic color
        if (scr.showMonitorColorSettings) {
            Integer c = scr.monitorColorPopup.resolveColor(key);
            if (c != null) return c;
        }
        // === hint-toggle-bg / hint-knob-bg は JsonLayoutEngine が HintToggleHelper にルート ===
        switch (key) {
            case "monitor-toggle-bg":    return scr.monitorToggle.trackBg();
            case "monitor-knob-bg":      return scr.monitorToggle.knobBg();
            case "monitor-status-color": return scr.monitorToggle.statusText();
            case "export-all-toggle-bg": return scr.exportAllToggle.trackBg();
            case "export-all-knob-bg":   return scr.exportAllToggle.knobBg();
            case "owner-border":
                return belugalab.tsu.api.OwnerAccess.ringColor(scr.be().isPrivateMode());
            case "mc-monitor-status-dot-bg":
            case "mc-monitor-info-color":
                return scr.isOnline() ? 0xFF4caf50 : 0xFFef5350;
        }
        int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
        // 券売機タブ: per-row 販売可トグル
        if ("ticket-toggle-track-bg".equals(key) && ri >= 0) return scr.ticketToggle.trackBgFor(ri);
        if ("ticket-toggle-knob-bg".equals(key) && ri >= 0) return scr.ticketToggle.knobBgFor(ri);
        // Symbol editor preset palette (chip bg + selection border)
        if ("preset-bg".equals(key) && ri >= 0 && ri < SymbolEditorController.SYMBOL_COLOR_PRESETS.length) {
            return ManagementComputerScreenV2.parseHexColor(SymbolEditorController.SYMBOL_COLOR_PRESETS[ri]);
        }
        if ("preset-border".equals(key) && ri >= 0) {
            return ri == scr.symEditor.getColorIdx() ? 0xFFFFFFFF : 0xFF555555;
        }
        // HSV color picker
        if ("cp-preview-bg".equals(key)) {
            return scr.picker.argb();
        }
        if ("cp-chip-bg".equals(key) && ri >= 0 && ri < scr.customColors.size()) {
            return ManagementComputerScreenV2.parseHexColor(scr.customColors.get(ri));
        }
        if ("cp-chip-border".equals(key) && ri >= 0 && ri < scr.customColors.size()) {
            return scr.customColors.get(ri).equalsIgnoreCase(scr.symEditor.getColor()) ? 0xFFFFFFFF : 0xFF555555;
        }
        if ("sym-edit-custom-bg".equals(key) && ri >= 0 && ri < scr.customColors.size()) {
            return ManagementComputerScreenV2.parseHexColor(scr.customColors.get(ri));
        }
        if ("sym-edit-custom-border".equals(key) && ri >= 0 && ri < scr.customColors.size()) {
            return scr.customColors.get(ri).equalsIgnoreCase(scr.symEditor.getColor()) ? 0xFFFFFFFF : 0xFF555555;
        }
        // Schedule detail pause button colors (paused = green, running = red)
        boolean paused = scr.isSelectedSchedTrainPaused();
        if ("sched-pause-bg".equals(key)) return paused ? 0xFF1e5e2e : 0xFF5e1e1e;
        if ("sched-pause-color".equals(key)) return paused ? 0xFF80ffaa : 0xFFff8888;
        if ("sched-pause-border".equals(key)) return paused ? 0xFF66cc66 : 0xFFcc6666;
        if ("sched-edit-color".equals(key)) return scr.selectedSchedEditable() ? 0xFF4fc3f7 : 0xFF888888;
        if ("sched-type-color".equals(key)) return TrainTypes.colorArgb(scr.selectedTrainTypeCode());
        if ("sched-share-toggle-bg".equals(key)) return scr.schedShareToggle.trackBgFor(scr.schedShareRealIdx());
        if ("sched-share-toggle-knob-bg".equals(key)) return scr.schedShareToggle.knobBgFor(scr.schedShareRealIdx());
        // 車両タイルの種別バッジ色
        if ("sched-row-type-color".equals(key) && ri >= 0) {
            var live = scr.trainsForList();
            int realIdx = ri + scr.schedListScroll.offset();
            if (realIdx < live.size()) {
                return TrainTypes.colorArgb(scr.be().getSyncedTrainType(live.get(realIdx).id()));
            }
        }
        // Schedule entry: highlight current entry
        if ("sched-entry-color".equals(key) && ri >= 0) {
            int realIdx = ri + scr.schedEntryScroll.offset();
            return realIdx == scr.selectedSchedCurrent ? 0xFF4fc3f7 : 0xFFcccccc;
        }
        // Station row link indicator (green/red)
        if ("station-row-link-color".equals(key) && ri >= 0) {
            var stations = scr.stationsForList();
            int realIdx = ri + scr.stationScroll.offset();
            if (realIdx < stations.size()) {
                var st = stations.get(realIdx);
                return scr.be().hasManagerForStation(st.name(), st.position()) ? 0xFF4caf50 : 0xFFef5350;
            }
        }
        // Station detail RM line (green when linked, red when not)
        if ("station-detail-rm-color".equals(key)) {
            return scr.selectedStationHasRMBE() ? 0xFF4caf50 : 0xFFef5350;
        }
        if ("station-detail-monitor-color".equals(key)) {
            var s = scr.selectedStation();
            if (s == null) return 0xFF888888;
            BlockPos rmPos = scr.be().getManagerPosForStation(s.name(), s.position());
            if (rmPos == null || scr.be().getLevel() == null) return 0xFF888888;
            try {
                var bbe = scr.be().getLevel().getBlockEntity(rmPos);
                if (bbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                    return rm.getLinkedMonitorGroupCount() > 0 ? 0xFF80deea : 0xFF888888;
                }
            } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
            return 0xFF888888;
        }
        // Door direction button colors (selection-driven)
        if (ri >= 0 && ri < ManagementComputerScreenV2.DOOR_OPTS.length) {
            var cur = scr.currentDoorSide();
            boolean selected = cur != null && cur.name().equals(ManagementComputerScreenV2.DOOR_OPTS[ri][0]);
            if ("door-btn-bg".equals(key)) return selected ? 0xFF1e4e6e : 0xFF1a1a2e;
            if ("door-btn-border".equals(key)) return selected ? 0xFF4fc3f7 : 0xFF555555;
            if ("door-btn-color".equals(key)) return selected ? 0xFF4fc3f7 : 0xFFaaaaaa;
        }
        // Schedule editor cyclic toggle colors (controller 委譲)
        {
            Integer sc = scr.schedEditor.resolveColor(key);
            if (sc != null) return sc;
        }
        return null;
    }

    static boolean onElementDrag(ManagementComputerScreenV2 scr, String[] classes, String key, int mouseX, int mouseY,
                                 int elX, int elY, int elW, int elH, boolean pressed) {
        if ("cp-hue-bar".equals(key)) {
            scr.picker.setHueFromX(mouseX - elX, elW);
            return true;
        }
        if ("cp-sv-panel".equals(key)) {
            scr.picker.setSvFromXY(mouseX - elX, mouseY - elY, elW, elH);
            return true;
        }
        if ("map-pan".equals(key)) {
            if (pressed) {
                scr.mapRenderer.onPanDrag(mouseX, mouseY, true);
            } else if (scr.mapRenderer.wasClick(mouseX, mouseY)) {
                // #15: タップ (ドラッグでない) = 駅/列車アイコンのクリック
                var hit = scr.mapRenderer.hitTest(mouseX, mouseY);
                if (hit != null) scr.handleMapHit(hit);
            } else {
                scr.mapRenderer.onPanDrag(mouseX, mouseY, false);
            }
            return true;
        }
        // Layout editor: パレットタイルからの drag-and-drop で追加 (DragDropPalette に委譲)
        if ("layout-tile-drag".equals(key)) {
            if (pressed) {
                String type = null;
                for (String c : classes) {
                    if (c.startsWith("layout-tile-") && !"layout-tile-item".equals(c)) {
                        type = c.substring("layout-tile-".length());
                        break;
                    }
                }
                scr.palette.onPress(type);
            }
            scr.palette.update(mouseX, mouseY);
            return true;
        }
        // Layout editor: preview canvas のドラッグでパネル選択 + 移動。
        if ("layout-preview-drag".equals(key)) {
            if (scr.layoutPrevW <= 0 || scr.layoutPrevH <= 0) return true;
            // mouseX/Y は popup-local 座標。preview canvas の絶対座標を引いてキャンバス内座標に。
            int cx = mouseX - scr.layoutPrevX;
            int cy = mouseY - scr.layoutPrevY;
            if (pressed) {
                // クリック位置のパネルを選択 (一番上=後追加分から検査)
                scr.layoutEditor.clearSelection();
                for (int i = scr.layoutEditor.getLayout().size() - 1; i >= 0; i--) {
                    var p = scr.layoutEditor.getLayout().get(i);
                    int px = (int)(p.getX() * scr.layoutPrevW);
                    int py = (int)(p.getY() * scr.layoutPrevH);
                    int pw = Math.max(8, (int)(p.getWidth() * scr.layoutPrevW));
                    int ph = Math.max(8, (int)(p.getHeight() * scr.layoutPrevH));
                    if (cx >= px && cx < px + pw && cy >= py && cy < py + ph) {
                        scr.layoutEditor.select(i);
                        scr.layoutDragStartPanelX = p.getX();
                        scr.layoutDragStartPanelY = p.getY();
                        scr.layoutDragStartMouseX = mouseX;
                        scr.layoutDragStartMouseY = mouseY;
                        break;
                    }
                }
            } else if (scr.layoutEditor.selectedIndex() >= 0 && scr.layoutEditor.selectedIndex() < scr.layoutEditor.getLayout().size()) {
                var p = scr.layoutEditor.getLayout().get(scr.layoutEditor.selectedIndex());
                float dx = (float)((mouseX - scr.layoutDragStartMouseX) / (double) scr.layoutPrevW);
                float dy = (float)((mouseY - scr.layoutDragStartMouseY) / (double) scr.layoutPrevH);
                p.setX(scr.layoutDragStartPanelX + dx);
                p.setY(scr.layoutDragStartPanelY + dy);
            }
            return true;
        }
        // 列車詳細 popup の 3D モデル: 左ドラッグで回転 (shift で pan) — TrainModelRenderer へ委譲。
        if ("train-rotate".equals(key)) {
            scr.trainModel.onRotateDrag(mouseX, mouseY, pressed,
                    net.minecraft.client.gui.screens.Screen.hasShiftDown());
            return true;
        }
        return false;
    }

    static boolean onElementWheel(ManagementComputerScreenV2 scr, String[] classes, String key,
                                  int mouseX, int mouseY, double scrollY) {
        if ("map-zoom".equals(key)) {
            scr.mapRenderer.onZoomWheel(scrollY);
            return true;
        }
        if ("train-zoom".equals(key)) {
            scr.trainModel.onZoomWheel(scrollY);
            return true;
        }
        // Layout editor: preview 上のタイルを hover + wheel で拡大縮小 (中心アンカー)
        if ("layout-preview-wheel".equals(key)) {
            if (scr.layoutEditor.isOpen()) {
                int idx = scr.layoutPanelAt(mouseX, mouseY);
                if (idx >= 0) {
                    var p = scr.layoutEditor.getLayout().get(idx);
                    float f = scrollY > 0 ? 1.08f : 1f / 1.08f;
                    float cxN = p.getX() + p.getWidth() / 2f;
                    float cyN = p.getY() + p.getHeight() / 2f;
                    p.setWidth(p.getWidth() * f);
                    p.setHeight(p.getHeight() * f);
                    p.setX(cxN - p.getWidth() / 2f);   // setX/setY が bezel 内へ clamp
                    p.setY(cyN - p.getHeight() / 2f);
                    scr.layoutEditor.select(idx);
                }
            }
            return true;
        }
        // 列車種別: 値 hover + wheel で循環 (R4.13.0 / R4.13.0.8)。 値ピッカーなので非反転。
        if ("sched-type-val".equals(key)) {
            scr.cycleSelectedTrainType(scrollY > 0 ? 1 : -1);
            return true;
        }
        // Panel settings popup: 値 hover + wheel で増減 (R4.13.0)。 0 = 自動 (推奨)。
        if (key != null && key.startsWith("pset-")) {
            var pp = scr.psetPanel();
            if (pp != null) {
                int d = scrollY > 0 ? 1 : -1;
                switch (key) {
                    case "pset-font-val":        pp.setFontSize(ManagementComputerScreenV2.adjustPsetValue(pp.getFontSize(), d)); return true;
                    case "pset-maptext-val":     pp.setMapTextSize(ManagementComputerScreenV2.adjustPsetValue(pp.getMapTextSize(), d)); return true;
                    case "pset-trainicon-val":   pp.setTrainIconSize(ManagementComputerScreenV2.adjustPsetValue(pp.getTrainIconSize(), d)); return true;
                    case "pset-stationicon-val": pp.setStationIconSize(ManagementComputerScreenV2.adjustPsetValue(pp.getStationIconSize(), d)); return true;
                    case "pset-signalicon-val":  pp.setSignalIconSize(ManagementComputerScreenV2.adjustPsetValue(pp.getSignalIconSize(), d)); return true;
                }
            }
            return true;
        }
        int delta = scrollY > 0 ? -1 : 1;
        if ("trains-scroll".equals(key)) {
            scr.trainScroll.scroll(delta);
            return true;
        }
        if ("sched-list-scroll".equals(key)) {
            scr.schedListScroll.scroll(delta);
            return true;
        }
        if ("sched-entries-scroll".equals(key)) {
            scr.schedEntryScroll.scroll(delta);
            return true;
        }
        if ("sched-share-scroll".equals(key)) {
            scr.schedShareScroll.scroll(delta);
            return true;
        }
        if ("stations-list-scroll".equals(key)) {
            scr.stationScroll.scroll(delta);
            return true;
        }
        if ("tickets-list-scroll".equals(key)) {
            scr.ticketScroll.scroll(delta);  // ScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        // Symbol editor field wheel-edit (wheel-up = increase) — controller 委譲
        if (scr.symEditor.handleWheel(key, scrollY)) return true;
        if ("sched-station-pick-scroll".equals(key)) {
            return scr.schedEditor.handleStationWheel(delta);
        }
        if ("sched-edit-body-scroll".equals(key)) {
            if (scr.schedEditor.handleWheel(mouseX, mouseY, delta)) return true;
        }
        return false;
    }

    static void onElementClick(ManagementComputerScreenV2 scr, String[] classes, int mouseX, int mouseY, int button) {
        // Phase 9: 4-arg を完全 override しているため base の hint/wiki 処理を明示呼び出し
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        // Monitor toggle (= track/knob + alias 旧名)
        if (scr.monitorToggle.handleClick(classes)) return;
        if (scr.exportAllToggle.handleClick(classes)) return;
        if (scr.showScheduleShare && scr.schedShareToggle.handleClick(classes, scr.schedShareRealIdx())) return;
        // 券売機タブ: 販売可トグル (repeat 行ごと)
        if (scr.ticketToggle.handleClick(classes, belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex())) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = scr.wikiPageId();
                if (pid != null && !pid.isEmpty()) {
                    belugalab.mcss3.wiki.Wiki.open(pid);
                }
                return;
            }
        }
        // === Schedule editor (sub-dropdown chain + frame、controller 委譲) ===
        if (scr.schedEditor.handleClick(classes, mouseX, mouseY, scr.overlayX(), scr.overlayY(),
                scr::schedStationNames, scr::applyScheduleEdit, scr::clearOverlayAnimByClass)) return;
        // HSV picker (highest popup priority)
        if (scr.showColorPicker) {
            int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("cp-close".equals(c) || "cp-close-btn".equals(c) || "mc-popup-close".equals(c)) {
                    scr.showColorPicker = false; return;
                }
                if ("cp-info-hex".equals(c)) { scr.focusField("hex"); return; }
                if ("cp-info-rgb".equals(c)) { scr.focusField("rgb"); return; }
                if ("cp-info-hsl".equals(c)) { scr.focusField("hsl"); return; }
                if ("cp-add".equals(c)) {
                    String hex = scr.currentPickerHex();
                    if (!scr.customColors.contains(hex)) scr.customColors.add(hex);
                    scr.symEditor.setColorCustom(hex);
                    return;
                }
                if ("cp-chip".equals(c) && ri >= 0 && ri < scr.customColors.size()) {
                    if (button == 1) {
                        scr.customColors.remove(ri);
                    } else {
                        String picked = scr.customColors.get(ri);
                        scr.symEditor.setColorCustom(picked);
                        scr.setPickerFromColor(picked);
                    }
                    return;
                }
            }
            // fallthrough → main GUI
        }
        // Symbol delete confirm popup (controller 委譲)
        if (scr.symbolDelete.isOpen()) {
            if (scr.symbolDelete.handleClick(classes, scr::confirmDeleteSymbol)) return;
            // fallthrough → main GUI
        }
        // Symbol editor popup (save/delete/cp-btn は screen helper 結合ゆえ screen、他は controller 委譲)
        if (scr.symEditor.isOpen()) {
            for (String c : classes) {
                if ("sym-edit-save".equals(c)) {
                    scr.saveEditedSymbol();
                    scr.symEditor.close();
                    return;
                }
                if ("sym-edit-delete".equals(c) && scr.symEditor.getIndex() >= 0) {
                    scr.symbolDelete.open(scr.symEditor.getIndex());
                    scr.symEditor.close();
                    return;
                }
                if ("sym-edit-cp-btn".equals(c)) {
                    scr.showColorPicker = true;
                    scr.setPickerFromColor(scr.symEditor.getColor());
                    return;
                }
            }
            if (scr.symEditor.handleClick(classes, () -> scr.customColors)) return;
            // fallthrough → main GUI
        }
        // Station assign dropdown (controller 委譲)
        if (scr.stationAssign.isOpen()) {
            int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            if (scr.stationAssign.handleClick(classes, ri, () -> scr.serverBE().getLineSymbols(),
                    (n, p, sym) -> scr.assignSymbolOnServer(n, p, sym == null ? null : sym.getId()))) return;
            // fallthrough → main GUI
        }
        // Monitor color settings popup — ColorTargetController に dispatch
        if (scr.showMonitorColorSettings) {
            int dIdx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
            // popup-close は controller では false を返す (popup 自体を閉じる責務は screen)
            for (String c : classes) {
                if ("mcol-popup-close".equals(c) || "mc-popup-close".equals(c)) {
                    scr.showMonitorColorSettings = false;
                    scr.monitorColorPopup.resetTransientState();
                    return;
                }
            }
            // dropdown open 直後の anim re-trigger (controller では行わないので screen 側で)
            boolean wasOpen = scr.monitorColorPopup.isDropdownOpen();
            if (scr.monitorColorPopup.handleClick(classes, dIdx)) {
                if (!wasOpen && scr.monitorColorPopup.isDropdownOpen()) {
                    scr.clearOverlayAnimByClass("mcol-target-list");
                }
                return;
            }
            // 一致なし → main GUI ハンドラに落とす
        }
        // Layout editor popup clicks
        if (scr.layoutEditor.isOpen()) {
            // タイル上で中ボタン押し込み → パネル機能別設定 popup (overlay2)
            if (button == 2) {
                for (String c : classes) {
                    if ("layout-preview".equals(c)) {
                        int idx = scr.layoutPanelAt(mouseX, mouseY);
                        if (idx >= 0) { scr.layoutEditor.select(idx); scr.layoutSettingsIdx = idx; }
                        return;
                    }
                }
            }
            for (String c : classes) {
                if ("pset-close-btn".equals(c)) { scr.layoutSettingsIdx = -1; return; }
                if ("pset-auto-btn".equals(c)) {
                    var pp = scr.psetPanel();
                    if (pp != null) {
                        // おすすめ = 全て 0 (自動)。 renderer がモニター/パネルの px サイズから最適値を算出
                        pp.setFontSize(0); pp.setMapTextSize(0);
                        pp.setTrainIconSize(0); pp.setStationIconSize(0); pp.setSignalIconSize(0);
                    }
                    return;
                }
                if ("layout-edit-close".equals(c) || "mc-popup-close".equals(c)) {
                    scr.layoutSettingsIdx = -1;
                    scr.layoutEditor.close();
                    return;
                }
                if ("layout-clear-btn".equals(c)) {
                    scr.layoutEditor.getLayout().clear();
                    scr.layoutEditor.clearSelection();
                    scr.layoutSettingsIdx = -1;
                    return;
                }
                if ("layout-recommend-btn".equals(c)) {
                    scr.applyRecommendedLayout();
                    scr.layoutSettingsIdx = -1;
                    return;
                }
                if ("layout-save-btn".equals(c)) {
                    scr.saveLayoutToServer();
                    scr.layoutSettingsIdx = -1;
                    scr.layoutEditor.close();
                    return;
                }
                if (c.startsWith("layout-tile-") && !"layout-tile-item".equals(c)) {
                    String typeName = c.substring("layout-tile-".length());
                    scr.addLayoutPanel(typeName);
                    return;
                }
            }
            // 一致なし → main GUI ハンドラに落とす (popup 中も main の他ボタン操作可)
        }
        // Phase 24: 電化詳細 popup clicks (列車詳細 popup と並存しているため、
        // mc-popup-close は ed-close-btn 経由の場合のみ受ける = ユニーククラス判定)
        if (scr.edDetail.isOpen() && scr.selectedTrainId != null && !scr.tabDropdown.isOpen()) {
            if (scr.edDetail.handleClick(classes, scr.selectedTrainId)) return;
            // パンタ個別 toggle は edPantoHits (drawCanvas が populate する hit-box) の
            // hit-test ゆえ controller でなく screen 側で処理する。
            for (String c : classes) {
                if ("ed-car-list-canvas".equals(c)) {
                    // canvas 内のパンタ hit-box を順にチェック
                    for (ElectrificationCarListRenderer.EdPantoHit ph : scr.edCarList.pantoHits()) {
                        if (mouseX >= ph.x0() && mouseX <= ph.x1()
                                && mouseY >= ph.y0() && mouseY <= ph.y1()) {
                            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                                    "[PantoToggle-DEBUG] CLIENT click toggle-one train={} car={} pos={}",
                                    scr.selectedTrainId, ph.carriageIndex(), ph.pos());
                            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                    new com.trainsystemutilities.network.PantographTogglePayload(
                                            scr.selectedTrainId,
                                            com.trainsystemutilities.network.PantographTogglePayload.ACTION_TOGGLE_ONE,
                                            ph.carriageIndex(), ph.pos()));
                            return;
                        }
                    }
                    com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                            "[PantoToggle-DEBUG] CLIENT canvas-click missed hits ({} pantos cached)",
                            scr.edCarList.pantoHits().size());
                    return;
                }
            }
            // 電化詳細のクラスにマッチしなかった click は train-detail / main GUI へ落とす
            // (= 列車詳細 popup と並存しているため両方クリック可能)
        }
        // Train detail popup clicks
        if (scr.selectedTrainId != null && !scr.tabDropdown.isOpen()) {
            for (String c : classes) {
                if ("mc-popup-close".equals(c) || "train-detail-close".equals(c)) {
                    scr.selectedTrainId = null;
                    scr.edDetail.close();
                    return;
                }
                if ("train-detail-electrification-btn".equals(c)) {
                    // 列車詳細 popup を閉じて電化詳細 popup を表示
                    scr.edDetail.open();
                    return;
                }
            }
            // fallthrough → main GUI
        }

        if (scr.tabDropdown.isOpen()) {
            for (String c : classes) {
                if (c.startsWith("tab-item-")) {
                    String key = c.substring("tab-item-".length());
                    for (String[] t : ManagementComputerScreenV2.TABS) {
                        if (t[0].equals(key)) {
                            scr.tabs.switchTo(key);
                            scr.tabDropdown.close();
                            return;
                        }
                    }
                }
            }
            scr.tabDropdown.close();
            // fallthrough → main GUI
        }

        for (String c : classes) {
            switch (c) {
                case "tab-dropdown":
                    scr.tabDropdown.setOpen(true);
                    return;
                case "mc-popup-close":
                case "mc-header-close":
                    scr.onClose();
                    return;
                // hint-toggle-track/knob は base class HintToggleHelper が自動処理
                // monitor toggle (new + alias) は下の controller dispatch で処理
                case "layout-edit-btn":
                    scr.openLayoutEditor();
                    return;
                case "monitor-color-btn":
                    scr.showMonitorColorSettings = !scr.showMonitorColorSettings;
                    if (!scr.showMonitorColorSettings) scr.monitorColorPopup.resetTransientState();
                    return;
                case "symbol-edit-btn":
                    scr.tabs.switchTo("symbol");
                    scr.tabDropdown.close();
                    return;
                case "color-edit-btn":
                    scr.showColorPicker = !scr.showColorPicker;
                    return;
                case "owner-face-box":
                case "owner-face":
                case "owner-face-canvas": // canvas の class は "owner-face-canvas" (canvasKey "owner-face" ではない)。innermost auto-clickable で実クリックはこちらに来る
                    if (scr.minecraftAccess() != null && scr.minecraftAccess().gameMode != null) {
                        scr.minecraftAccess().gameMode.handleInventoryButtonClick(scr.getMenu().containerId, 9000);
                    }
                    return;
                case "sym-create-btn":
                    scr.openSymbolEditorNew();
                    return;
                case "sym-tile":
                case "sym-tile-badge":   // canvas は auto-clickable のため innermost-rule でこちらに来る
                case "sym-tile-name": {
                    int ri = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (ri >= 0 && ri < scr.serverBE().getLineSymbols().size()) {
                        if (button == 1) {
                            scr.symbolDelete.open(ri);
                        } else {
                            scr.openSymbolEditorExisting(ri);
                        }
                    }
                    return;
                }
                case "train-row": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var live = scr.trainsForList();
                        int realIdx = idx + scr.trainScroll.offset();
                        if (realIdx < live.size()) scr.selectedTrainId = live.get(realIdx).id();
                    }
                    return;
                }
                case "sched-row": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var live = scr.trainsForList();
                        int realIdx = idx + scr.schedListScroll.offset();
                        if (realIdx < live.size()) {
                            scr.scheduleSelectedTrainId = live.get(realIdx).id();
                            scr.scheduleSelectNano = System.nanoTime();
                            scr.schedEntryScroll.setOffset(0);
                            // 詳細ビュー要素の slide-in animation を再トリガー
                            scr.clearMainAnimByClass("sched-detail-name");
                        }
                    }
                    return;
                }
                case "sched-stop-all-btn":
                    scr.startAllStop();
                    return;
                case "sched-resume-all-btn":
                    scr.resumeAllStop();
                    return;
                case "sched-back":
                    scr.scheduleSelectedTrainId = null;
                    scr.showScheduleShare = false;
                    return;
                case "sched-pause":
                    scr.togglePauseSelected();
                    return;
                case "sched-edit":
                    scr.openScheduleEditor();
                    return;
                case "sched-share-btn":
                    scr.showScheduleShare = true;
                    scr.schedShareScroll.clamp();
                    return;
                case "sched-share-close":
                    scr.showScheduleShare = false;
                    return;
                case "station-row": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var stations = scr.stationsForList();
                        int realIdx = idx + scr.stationScroll.offset();
                        if (realIdx < stations.size()) {
                            var st = stations.get(realIdx);
                            scr.selectedStationKey = ManagementComputerBlockEntity.stationKey(st.name(), st.position());
                            // 詳細ビュー要素の slide-in animation を再トリガー
                            scr.clearMainAnimByClass("station-detail-name");
                            scr.clearMainAnimByClass("station-detail-badge");
                            scr.clearMainAnimByClass("station-detail-pos");
                            scr.clearMainAnimByClass("station-detail-rm");
                            scr.clearMainAnimByClass("station-detail-monitor");
                            scr.clearMainAnimByClass("station-detail-door-label");
                        }
                    }
                    return;
                }
                case "station-row-assign": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var stations = scr.stationsForList();
                        int realIdx = idx + scr.stationScroll.offset();
                        if (realIdx < stations.size()) {
                            var st = stations.get(realIdx);
                            scr.stationAssign.open(st.name(), st.position());
                            // + ボタンの screen 座標を記録 → overlayDefaultPosition で参照。
                            // findElementByClass は repeat の template も走査して row-0 の rect を返す
                            // ので、idx 行は y に stride (= STATION_ROW_H + 1 = 23) を加算する。
                            // dialog は viewport 中心 pivot で scale するため、論理→screen は
                            // leftPos 加算ではなく manta の変換を通す (scale != 1 でずれる)。
                            int[] r = scr.findElementByClassAccess("station-row-assign");
                            if (r != null) {
                                scr.assignBtnScreenX = scr.dialogLocalToScreenXAccess(r[0]);
                                scr.assignBtnScreenY = scr.dialogLocalToScreenYAccess(r[1] + idx * (22 + 1) + r[3]);
                            } else {
                                scr.assignBtnScreenX = scr.dialogLocalToScreenXAccess((int) mouseX);
                                scr.assignBtnScreenY = scr.dialogLocalToScreenYAccess((int) mouseY + 8);
                            }
                        }
                    }
                    return;
                }
                case "station-back":
                    scr.selectedStationKey = "";
                    return;
                case "door-btn": {
                    int idx = belugalab.mcss3.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0 && idx < ManagementComputerScreenV2.DOOR_OPTS.length) {
                        var s = scr.selectedStation();
                        if (s != null) {
                            var side = com.trainsystemutilities.blockentity
                                    .RailwayManagementBlockEntity.DoorSide.valueOf(ManagementComputerScreenV2.DOOR_OPTS[idx][0]);
                            scr.setDoorSideOnServer(s.name(), s.position(), side);
                        }
                    }
                    return;
                }
            }
        }
    }
}
