package com.trainsystemutilities.client.gui;

import com.manta.api.anim.Animation;
import com.manta.api.anim.Transition;
import com.manta.api.controller.ColorPickerController;
import com.manta.api.screen.PageLayer;
import com.trainsystemutilities.schedule.TrainTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * The management computer's values, WRITTEN into its pages each frame before the engine draws and
 * once when a page opens (Manta 7 Phase 4, 2026-09-24): the screen overrides no {@code getDynamic*}.
 * Every value is the one {@code ManagementComputerDispatch} answered the pull, in the same state; a
 * row's value is the one it answered under that row's index. The hint toggle, dialog-open, the
 * *-popup-open entrances and the toggle transitions are the base's (FrameworkState).
 *
 * <p>The screen shows up to three pages: the primary, overlay 1 and overlay 2. Which layout an overlay
 * page is, is recorded when it opens, by the precedence {@code overlayJson()} / {@code overlayJson2()}
 * pick it with.
 */
final class ManagementComputerPush {
    static final int NONE = 0;
    // overlay 1, in overlayJson()'s order
    static final int TAB_MENU = 1, SYMBOL_DELETE = 2, SYMBOL_EDITOR = 3, COLOR_PICKER = 4, SCHED_EDITOR = 5,
            SCHED_SHARE = 6, STATION_ASSIGN = 7, LAYOUT_EDIT = 8, MONITOR_COLOR = 9, TRAIN_DETAIL = 10;
    // overlay 2, in overlayJson2()'s order
    static final int PANEL_SETTINGS = 21, COLOR_PICKER_2 = 22, STATION_PICK = 23, ADD_COND = 24, ADD_ENTRY = 25,
            ELECTRIFICATION = 26;

    private static final Animation POPUP_POP = Animation.popIn(180);
    // The drop-downs and menus (the tab menu, the station assignment, the schedule editor's three pickers) declare
    // "animationKey": "dropdown" on their roots: the base unfolds and folds them (Manta DropdownMotion, 2026-10-07).
    private static final Animation DETAIL_SLIDE = Animation.slideInFromRight(280, 60f);
    /** The monitor colour popup's target list: the one node that carries its motion and holds its rows, 136 high. */
    private static final int MCOL_TARGET_LIST_H = 136;
    /** The door direction buttons' bg / border / text fade on a selection change. */
    private static final Transition DOOR_TRANSITION = Transition.of(150);

    private final ManagementComputerScreenV2 scr;
    private PagePush primary, overlay1, overlay2;
    private int overlay1Kind = NONE, overlay2Kind = NONE;

    ManagementComputerPush(ManagementComputerScreenV2 scr) {
        this.scr = scr;
    }

    /** overlayJson()'s pick, as a kind. */
    int overlay1Shown() {
        if (scr.tabDropdown.isOpen()) return TAB_MENU;
        if (scr.symbolDelete.isOpen()) return SYMBOL_DELETE;
        if (scr.symEditor.isOpen()) return SYMBOL_EDITOR;
        if (scr.showColorPicker) return COLOR_PICKER;
        if (scr.schedEditor.isOpen()) return SCHED_EDITOR;
        if (scr.showScheduleShare) return SCHED_SHARE;
        if (scr.stationAssign.isOpen()) return STATION_ASSIGN;
        if (scr.layoutEditor.isOpen()) return LAYOUT_EDIT;
        if (scr.showMonitorColorSettings) return MONITOR_COLOR;
        if (scr.selectedTrainId != null) return TRAIN_DETAIL;
        return NONE;
    }

    /** overlayJson2()'s pick, as a kind. */
    int overlay2Shown() {
        if (scr.layoutEditor.isOpen() && scr.layoutSettingsIdx >= 0) return PANEL_SETTINGS;
        if (scr.symEditor.isOpen() && scr.showColorPicker) return COLOR_PICKER_2;
        if (scr.schedEditor.isOpen() && scr.schedEditor.isStationDropdownOpen()) return STATION_PICK;
        if (scr.schedEditor.isOpen() && scr.schedEditor.addCondForEntry() >= 0) return ADD_COND;
        if (scr.schedEditor.isOpen() && scr.schedEditor.isAddEntryOpen()) return ADD_ENTRY;
        if (scr.edDetail.isOpen() && scr.selectedTrainId != null) return ELECTRIFICATION;
        return NONE;
    }

    void pageOpened(Object page, PageLayer layer) {
        PagePush p = new PagePush(page);
        switch (layer) {
            case PRIMARY -> {
                primary = p;
                primary.animation("sched-detail-slide", DETAIL_SLIDE);
                primary.animation("station-detail-slide", DETAIL_SLIDE);
                primary.transition("door-transition", DOOR_TRANSITION);
                primary.textByKeyOnly();
            }
            case OVERLAY -> {
                overlay1 = p;
                overlay1Kind = overlay1Shown();
                openedOverlay(overlay1, overlay1Kind);
            }
            case OVERLAY2 -> {
                overlay2 = p;
                overlay2Kind = overlay2Shown();
                openedOverlay(overlay2, overlay2Kind);
            }
        }
        pushAll();
    }

    /** The overlay's entrance (not a base default) and its text declared keyed; the values follow in pushAll. */
    private void openedOverlay(PagePush p, int kind) {
        switch (kind) {
            case SYMBOL_EDITOR, COLOR_PICKER, COLOR_PICKER_2, SCHED_EDITOR, SCHED_SHARE, LAYOUT_EDIT, TRAIN_DETAIL,
                    PANEL_SETTINGS, ELECTRIFICATION -> p.animation("popup-pop", POPUP_POP);
            default -> { }
        }
        if (kind == SCHED_SHARE) {
            // The candidates' rows enter with nothing: the pull answered no animation for them.
            p.animation("sched-share-row-enter", null);
        }
        if (kind != SYMBOL_DELETE && kind != NONE) {
            // SYMBOL_DELETE's page is ConfirmDialog's, which declares it itself.
            p.textByKeyOnly();
        }
    }

    void pushAll() {
        if (primary != null && primary.isOpen()) pushPrimary(primary);
        if (overlay1 != null && overlay1.isOpen()) pushOverlay(overlay1, overlay1Kind);
        if (overlay2 != null && overlay2.isOpen()) pushOverlay(overlay2, overlay2Kind);
    }

    // =========================================================================== the primary page

    private void pushPrimary(PagePush p) {
        // Header and footer
        p.text("tab-dropdown", scr.currentTabLabel());
        p.text("monitor-label", ManagementComputerScreenV2.tr("tsu.mc.monitor_label"));
        // online = a card is in and its link resolves; offline otherwise
        p.text("mc-monitor-info", ManagementComputerScreenV2.tr(scr.isOnline() ? "tsu.mc.online" : "tsu.mc.offline"));
        p.text("stat-station", Component.translatable("tsu.mc.stat_station_fmt", scr.be().getCachedStationCount()).getString());
        p.text("stat-train", Component.translatable("tsu.mc.stat_train_fmt", scr.be().getCachedTrainCount()).getString());
        p.text("stat-signal", Component.translatable("tsu.mc.stat_signal_fmt", scr.be().getCachedSignalCount()).getString());
        // The placeholder text of tabs that have a real implementation is hidden.
        p.text("tab-content-text", "");
        p.text("tab-content-hint", "");
        p.color("monitor-toggle-bg", scr.monitorToggle.trackBg());
        p.color("monitor-knob-bg", scr.monitorToggle.knobBg());
        p.color("monitor-status-color", scr.monitorToggle.statusText());
        p.color("export-all-toggle-bg", scr.exportAllToggle.trackBg());
        p.color("export-all-knob-bg", scr.exportAllToggle.knobBg());
        p.color("owner-border", com.manta.api.hud.OwnerAccess.ringColor(scr.be().isPrivateMode()));
        int onlineColour = scr.isOnline() ? 0xFF4caf50 : 0xFFef5350;
        p.color("mc-monitor-status-dot-bg", onlineColour);
        p.color("mc-monitor-info-color", onlineColour);
        // Offsets: knobX / thumbY are default + delta, so each delta is its answer for 0.
        p.offset("monitor-knob-x", scr.monitorToggle.knobX(0));
        p.offset("export-all-knob-x", scr.exportAllToggle.knobX(0));
        // The buttons whose glyphs became icon + label (R4.23.1): the labels' widths.
        p.number("stop-all-label-w", LabelWidth.of("tsu.mc.stop_all_trains"));
        p.number("resume-all-label-w", LabelWidth.of("tsu.mc.resume_all_trains"));
        p.number("back-label-w", LabelWidth.of("tsu.mc.back_btn"));
        // The procedural fallback placeholder - no tab is left un-migrated.
        p.bool("tab-procedural-active", false);
        p.bool("tab-map-active", scr.tabs.is("map"));
        pushTrainsTab(p);
        pushScheduleTab(p);
        pushStationsTab(p);
        pushSymbolTab(p);
        pushTicketsTab(p);
    }

    private void pushTrainsTab(PagePush p) {
        boolean inTrains = scr.tabs.is("trains");
        var live = scr.trainsForList();
        p.bool("tab-trains-active", inTrains);
        p.bool("tab-trains-empty", inTrains && live.isEmpty());
        p.bool("tab-trains-list", inTrains && !live.isEmpty());
        p.bool("trains-scrollbar-visible", scr.trainScroll.needsScrollbar());
        p.offset("trains-scrollbar-thumb-y", scr.trainScroll.thumbY(0,
                ManagementComputerScreenV2.TRAIN_LIST_TRACK_H - 2, ManagementComputerScreenV2.TRAIN_LIST_THUMB_H));
        p.number("trains-scrollbar-thumb-h", ManagementComputerScreenV2.TRAIN_LIST_THUMB_H);
        int rows = scr.trainScroll.rowCount();
        p.number("trains-row-count", rows);
        for (int r = 0; r < rows; r++) {
            int realIdx = r + scr.trainScroll.offset();
            if (!inTrains || realIdx >= live.size()) {
                p.text("train-row-name", r, null);
                p.text("train-row-status", r, null);
                p.text("train-row-cars", r, null);
                p.text("train-row-dest", r, null);
                continue;
            }
            var info = live.get(realIdx);
            Object[] liveOpt = scr.trySafeLiveTrain(info.id());
            String station = (String) liveOpt[0];
            double speed = (Double) liveOpt[1];
            p.text("train-row-name", r, info.name());
            p.text("train-row-status", r, !station.isEmpty() ? "█ " + station : String.format("%.0f km/h", speed));
            p.text("train-row-cars", r, Component.translatable("tsu.mc.cars_unit_fmt", (Integer) liveOpt[2]).getString());
            p.text("train-row-dest", r, (String) liveOpt[3]);
        }
    }

    private void pushScheduleTab(PagePush p) {
        boolean inSched = scr.tabs.is("schedule");
        boolean list = inSched && scr.scheduleSelectedTrainId == null;
        boolean detail = inSched && scr.scheduleSelectedTrainId != null;
        var live = scr.trainsForList();
        int liveCount = live.size();
        p.bool("tab-sched-empty", list && liveCount == 0);
        p.bool("tab-sched-list-rows", list && liveCount > 0);
        p.bool("tab-sched-detail", detail);
        p.bool("sched-share-btn-visible", detail && scr.selectedSchedTrainIsElectronic() && scr.selectedSchedTrainHasSchedule()
                && !scr.be().isTimetableFollower(scr.scheduleSelectedTrainId));
        p.bool("tab-sched-edit-visible", detail && scr.isSelectedSchedTrainPaused());
        // The stop-all bar, in list mode only: "stop" while nothing is paused, "resume" once something is.
        p.bool("sched-stopall-show-stop", list && !live.isEmpty() && !scr.anyTrainPaused());
        p.bool("sched-stopall-show-resume", list && scr.anyTrainPaused());
        p.bool("sched-entries-empty", scr.selectedSchedEntries.isEmpty());
        p.bool("sched-entries-scrollbar-visible", detail && scr.schedEntryScroll.needsScrollbar());
        p.offset("sched-entries-thumb-y", scr.schedEntryScroll.thumbY(0,
                ManagementComputerScreenV2.SCHED_ENTRIES_TRACK_H - 2, ManagementComputerScreenV2.SCHED_ENTRIES_THUMB_H));
        p.number("sched-entries-thumb-h", ManagementComputerScreenV2.SCHED_ENTRIES_THUMB_H);
        // In list mode, on the right (e.g. "5 trains / 3 stations").
        p.text("sched-tab-stat", Component.translatable("tsu.mc.sched_tab_stat_fmt",
                scr.trainsForList().size(), scr.stationsForList().size()).getString());
        int paused = scr.pausedTrainCount();
        p.text("sched-stopall-label", paused > 0
                ? Component.translatable("tsu.mc.all_stop_active_fmt", paused).getString()
                : ManagementComputerScreenV2.tr("tsu.mc.all_stop_done"));

        // The list's rows
        int rows = scr.schedListScroll.rowCount();
        p.number("sched-list-count", rows);
        for (int r = 0; r < rows; r++) {
            int realIdx = r + scr.schedListScroll.offset();
            if (realIdx >= live.size()) {
                p.text("sched-row-type", r, null);
                p.text("sched-row-name", r, null);
                p.text("sched-row-entries", r, null);
                p.color("sched-row-type-color", r, null);
                p.bool("sched-row-paused", r, false);
                continue;
            }
            var info = live.get(realIdx);
            // A paused train's row leads with manta:pause - a boolean (visibleKey), since an iconKey
            // cannot say "no icon". wikiMode carries no synced flags: never paused there.
            p.bool("sched-row-paused", r, !scr.wikiMode && scr.be().hasSyncedPaused(info.id()));
            p.color("sched-row-type-color", r, TrainTypes.colorArgb(scr.be().getSyncedTrainType(info.id())));
            if (!list) {
                p.text("sched-row-type", r, null);
                p.text("sched-row-name", r, null);
                p.text("sched-row-entries", r, null);
                continue;
            }
            int entries = 0;
            boolean elec = false;
            if (scr.wikiMode) {
                int[] meta = scr.wikiTrainMeta.get(info.id());
                if (meta != null) { entries = meta[0]; elec = meta[1] == 1; }
            } else {
                // The server's synced flags and SchedView (MP-safe: no client getTrainById).
                var sv = scr.be().getSyncedSchedView(info.id());
                if (sv != null) entries = sv.entries().size();
                elec = scr.isElectronicTimetable(info.id());
            }
            p.text("sched-row-type", r, TrainTypes.localize(scr.be().getSyncedTrainType(info.id())));
            p.text("sched-row-name", r, info.name());
            UUID shareSrc = scr.shareSource(info.id());
            String entriesText;
            if (shareSrc != null) {
                entriesText = Component.translatable("tsu.mc.tt_shared_from_fmt", scr.trainNameById(shareSrc)).getString();
            } else if (entries <= 0) {
                entriesText = ManagementComputerScreenV2.tr("tsu.mc.tt_state_none");
            } else {
                entriesText = ManagementComputerScreenV2.tr(elec ? "tsu.mc.tt_state_electronic" : "tsu.mc.tt_state_regular")
                        + " · " + Component.translatable("tsu.mc.entries_unit_fmt", entries).getString();
            }
            p.text("sched-row-entries", r, entriesText);
        }

        // The detail
        if (detail) {
            p.text("sched-type-val", TrainTypes.localizeForEditor(scr.selectedTrainTypeCode()));
            p.text("sched-detail-name", scr.selectedTrainName.isEmpty() ? "?" : scr.selectedTrainName);
            String status = !scr.selectedTrainStation.isEmpty() ? scr.selectedTrainStation
                    : (scr.isSelectedSchedTrainPaused() ? ManagementComputerScreenV2.tr("tsu.mc.train_stopped")
                            : ManagementComputerScreenV2.tr("tsu.mc.train_running"));
            String base = Component.translatable("tsu.mc.train_summary_fmt",
                    scr.selectedTrainCars, String.format("%.0f", scr.selectedTrainSpeed), status).getString();
            UUID src = scr.shareSource(scr.scheduleSelectedTrainId);
            if (src != null) {
                base += " · " + Component.translatable("tsu.mc.sched_shared_banner_fmt", scr.trainNameById(src)).getString();
            }
            p.text("sched-detail-info", base);
            p.text("sched-pause", ManagementComputerScreenV2.tr(scr.isSelectedSchedTrainPaused()
                    ? "tsu.mc.train_resume_btn" : "tsu.mc.train_stop_btn"));
            String edit;
            if (scr.be().isTimetableFollower(scr.scheduleSelectedTrainId)) edit = "tsu.mc.sched_edit_label_shared";
            else if (scr.selectedSchedTrainHasSchedule() && !scr.selectedSchedTrainIsElectronic()) edit = "tsu.mc.sched_edit_label_regular";
            else if (!scr.selectedSchedTrainHasConductor()) edit = "tsu.mc.sched_edit_label_conductor";
            else edit = "tsu.mc.sched_edit_label_edit";
            p.text("sched-edit", ManagementComputerScreenV2.tr(edit));
            Boolean cyc = scr.selectedSchedCyclic();
            p.text("sched-cyclic", cyc == null ? "" : ManagementComputerScreenV2.tr(cyc ? "tsu.mc.sched_cyclic_loop" : "tsu.mc.sched_cyclic_oneway"));
        } else {
            p.text("sched-type-val", null);
            p.text("sched-detail-name", null);
            p.text("sched-detail-info", null);
            p.text("sched-pause", null);
            p.text("sched-edit", null);
            p.text("sched-cyclic", null);
        }
        // The pause button: paused green, running red.
        boolean paused2 = scr.isSelectedSchedTrainPaused();
        p.color("sched-pause-bg", paused2 ? 0xFF1e5e2e : 0xFF5e1e1e);
        p.color("sched-pause-color", paused2 ? 0xFF80ffaa : 0xFFff8888);
        p.color("sched-pause-border", paused2 ? 0xFF66cc66 : 0xFFcc6666);
        p.color("sched-edit-color", scr.selectedSchedEditable() ? 0xFF4fc3f7 : 0xFF888888);
        p.color("sched-type-color", TrainTypes.colorArgb(scr.selectedTrainTypeCode()));

        // The detail's entries: the current one marked (play) and highlighted, the others a circle -
        // not an empty icon, so the rows' left edges line up.
        int entryRows = scr.schedEntryScroll.rowCount();
        p.number("sched-entries-count", entryRows);
        for (int r = 0; r < entryRows; r++) {
            int realIdx = r + scr.schedEntryScroll.offset();
            p.color("sched-entry-color", r, realIdx == scr.selectedSchedCurrent ? 0xFF4fc3f7 : 0xFFcccccc);
            if (detail && !scr.showScheduleShare && realIdx < scr.selectedSchedEntries.size()) {
                boolean cur = realIdx == scr.selectedSchedCurrent;
                p.icon("sched-entry-marker", r, cur ? "manta:play" : "manta:circle");
                p.text("sched-entry-row", r, (realIdx + 1) + ". " + scr.selectedSchedEntries.get(realIdx));
            } else {
                p.icon("sched-entry-marker", r, null);
                p.text("sched-entry-row", r, null);
            }
        }
    }

    private void pushStationsTab(PagePush p) {
        boolean inStations = scr.tabs.is("stations");
        boolean list = inStations && scr.selectedStationKey.isEmpty();
        boolean detail = inStations && !scr.selectedStationKey.isEmpty();
        // The client-side scan first (it does not wait on the server cache).
        var stations = scr.stationsForList();
        p.bool("tab-stations-empty", list && stations.isEmpty());
        p.bool("tab-stations-list", list);
        p.bool("tab-stations-list-rows", list && !stations.isEmpty());
        p.bool("stations-scrollbar-visible", scr.stationScroll.needsScrollbar());
        p.bool("tab-stations-detail", detail);
        p.bool("tab-stations-detail-rm", detail && scr.selectedStationHasRMBE());
        p.offset("stations-scrollbar-thumb-y", scr.stationScroll.thumbY(0,
                ManagementComputerScreenV2.STATION_LIST_TRACK_H - 2, ManagementComputerScreenV2.STATION_LIST_THUMB_H));
        p.number("stations-scrollbar-thumb-h", ManagementComputerScreenV2.STATION_LIST_THUMB_H);

        int rows = scr.stationScroll.rowCount();
        p.number("stations-row-count", rows);
        for (int r = 0; r < rows; r++) {
            int realIdx = r + scr.stationScroll.offset();
            if (realIdx >= stations.size()) {
                p.text("station-row-name", r, null);
                p.text("station-row-link", r, null);
                p.icon("station-row-assign-icon", r, null);
                p.color("station-row-link-color", r, null);
                continue;
            }
            var st = stations.get(realIdx);
            boolean linked = scr.be().hasManagerForStation(st.name(), st.position());
            p.color("station-row-link-color", r, linked ? 0xFF4caf50 : 0xFFef5350);
            if (!list) {
                p.text("station-row-name", r, null);
                p.text("station-row-link", r, null);
                p.icon("station-row-assign-icon", r, null);
                continue;
            }
            p.text("station-row-name", r, com.manta.api.hud.HudText.ellipsize(scr.fontOrNull(), st.name(), 128));
            p.text("station-row-link", r, linked ? "●" : "○");
            // With a symbol: edit (pencil); without: add (plus) - a registry id, as ColorTargetController's.
            var sym = scr.be().getSymbolForStation(st.name(), st.position());
            p.icon("station-row-assign-icon", r, sym != null ? "manta:pencil" : "manta:plus");
        }

        // The detail
        var s = detail ? scr.selectedStation() : null;
        if (s != null) {
            var sym = scr.be().getSymbolForStation(s.name(), s.position());
            BlockPos rmPos = scr.be().getManagerPosForStation(s.name(), s.position());
            p.text("station-detail-name", "🚉 " + s.name());
            p.text("station-detail-pos", Component.translatable("tsu.mc.station_pos_fmt",
                    s.position().getX(), s.position().getY(), s.position().getZ()).getString());
            p.text("station-detail-rm", rmPos != null
                    ? Component.translatable("tsu.mc.station_rm_linked_fmt", rmPos.getX(), rmPos.getY(), rmPos.getZ()).getString()
                    : ManagementComputerScreenV2.tr("tsu.mc.station_rm_unlinked"));
            p.text("station-detail-monitor", stationMonitorText(rmPos));
            p.text("station-detail-symbol", sym != null
                    ? Component.translatable("tsu.mc.station_symbol_fmt", sym.getLetters() + sym.getNumberStr(),
                        sym.getName().isEmpty() ? "" : " (" + sym.getName() + ")").getString()
                    : ManagementComputerScreenV2.tr("tsu.mc.station_symbol_none"));
        } else {
            p.text("station-detail-name", null);
            p.text("station-detail-pos", null);
            p.text("station-detail-rm", null);
            p.text("station-detail-monitor", null);
            p.text("station-detail-symbol", null);
        }
        // The RM line green when linked, red when not; the monitor line cyan when it has groups.
        p.color("station-detail-rm-color", scr.selectedStationHasRMBE() ? 0xFF4caf50 : 0xFFef5350);
        p.color("station-detail-monitor-color", stationMonitorColour());

        // The door direction buttons (selection-driven)
        int doors = ManagementComputerScreenV2.DOOR_OPTS.length;
        p.number("door-count", doors);
        var cur = scr.currentDoorSide();
        for (int r = 0; r < doors; r++) {
            boolean selected = cur != null && cur.name().equals(ManagementComputerScreenV2.DOOR_OPTS[r][0]);
            p.text("door-btn", r, ManagementComputerScreenV2.tr(ManagementComputerScreenV2.DOOR_OPTS[r][1]));
            p.color("door-btn-bg", r, selected ? 0xFF1e4e6e : 0xFF1a1a2e);
            p.color("door-btn-border", r, selected ? 0xFF4fc3f7 : 0xFF555555);
            p.color("door-btn-color", r, selected ? 0xFF4fc3f7 : 0xFFaaaaaa);
        }
    }

    private String stationMonitorText(BlockPos rmPos) {
        if (rmPos == null) return "";
        try {
            if (scr.be().getLevel() != null) {
                var bbe = scr.be().getLevel().getBlockEntity(rmPos);
                if (bbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                    int groups = rm.getLinkedMonitorGroupCount();
                    return groups > 0
                            ? Component.translatable("tsu.mc.station_monitor_groups_fmt", groups).getString()
                            : ManagementComputerScreenV2.tr("tsu.mc.station_monitor_unlinked");
                }
            }
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e);
        }
        return "";
    }

    private int stationMonitorColour() {
        var s = scr.selectedStation();
        if (s == null) return 0xFF888888;
        BlockPos rmPos = scr.be().getManagerPosForStation(s.name(), s.position());
        if (rmPos == null || scr.be().getLevel() == null) return 0xFF888888;
        try {
            var bbe = scr.be().getLevel().getBlockEntity(rmPos);
            if (bbe instanceof com.trainsystemutilities.blockentity.RailwayManagementBlockEntity rm) {
                return rm.getLinkedMonitorGroupCount() > 0 ? 0xFF80deea : 0xFF888888;
            }
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e);
        }
        return 0xFF888888;
    }

    private void pushSymbolTab(PagePush p) {
        boolean inSymbol = scr.tabs.is("symbol");
        var syms = scr.be().getLineSymbols();
        p.bool("tab-symbol-active", inSymbol);
        p.bool("tab-symbol-active-with-items", inSymbol && !syms.isEmpty());
        p.bool("tab-symbol-active-empty", inSymbol && syms.isEmpty());
        p.text("sym-tab-title", inSymbol
                ? Component.translatable("tsu.mc.line_symbols_fmt", syms.size()).getString() : null);
        p.number("sym-grid-count", syms.size());
        for (int r = 0; r < syms.size(); r++) {
            if (!inSymbol) {
                p.text("sym-tile-name", r, null);
                continue;
            }
            String name = syms.get(r).getName();
            // Cut to the tile's width (36 - 2).
            p.text("sym-tile-name", r, name.isEmpty() ? "" : com.manta.api.hud.HudText.ellipsize(scr.fontOrNull(), name, 34));
        }
    }

    private void pushTicketsTab(PagePush p) {
        boolean inTickets = scr.tabs.is("tickets");
        var groups = scr.ticketGroups();
        int groupCount = scr.wikiMode ? groups.size() : com.trainsystemutilities.station.StationGroupClientCache.all().size();
        p.bool("tab-tickets-list", inTickets);
        p.bool("tab-tickets-empty", inTickets && groupCount == 0);
        p.bool("tab-tickets-list-rows", inTickets && groupCount > 0);
        p.bool("tickets-scrollbar-visible", scr.ticketScroll.needsScrollbar());
        p.offset("tickets-scrollbar-thumb-y", scr.ticketScroll.thumbY(0,
                ManagementComputerScreenV2.TICKETS_LIST_TRACK_H - 2, ManagementComputerScreenV2.TICKETS_LIST_THUMB_H));
        p.number("tickets-scrollbar-thumb-h", ManagementComputerScreenV2.TICKETS_LIST_THUMB_H);
        if (inTickets) {
            int sell = 0;
            for (var g : groups) {
                if (scr.ticketSellable(g.id())) sell++;
            }
            p.text("tickets-title", Component.translatable("tsu.mc.tickets_title_fmt", sell, groups.size()).getString());
        } else {
            p.text("tickets-title", null);
        }
        int rows = scr.ticketScroll.rowCount();
        p.number("tickets-row-count", rows);
        // The rows' sale toggles (IndexedToggleSwitchController, indexed by the repeat's row).
        p.offset("ticket-toggle-knob-x", 0);
        for (int r = 0; r < rows; r++) {
            int realIdx = r + scr.ticketScroll.offset();
            if (inTickets) {
                p.text("ticket-row-name", r, realIdx < groups.size()
                        ? com.manta.api.hud.HudText.ellipsize(scr.fontOrNull(), groups.get(realIdx).name(), 132) : "");
            } else {
                p.text("ticket-row-name", r, null);
            }
            p.color("ticket-toggle-track-bg", r, scr.ticketToggle.trackBgFor(r));
            p.color("ticket-toggle-knob-bg", r, scr.ticketToggle.knobBgFor(r));
            p.offset("ticket-toggle-knob-x", r, scr.ticketToggle.knobXFor(r, 0));
        }
    }

    // =========================================================================== the overlays

    private void pushOverlay(PagePush p, int kind) {
        switch (kind) {
            case SYMBOL_DELETE -> scr.symbolDelete.push(p.state());
            case SYMBOL_EDITOR -> pushSymbolEditor(p);
            case COLOR_PICKER, COLOR_PICKER_2 -> pushColorPicker(p);
            case SCHED_EDITOR -> pushSchedEditor(p);
            case SCHED_SHARE -> pushSchedShare(p);
            case STATION_ASSIGN -> pushStationAssign(p);
            case LAYOUT_EDIT -> pushLayoutEdit(p);
            case MONITOR_COLOR -> scr.monitorColorPopup.push(p.state(), MCOL_TARGET_LIST_H);
            case TRAIN_DETAIL -> pushTrainDetail(p);
            case PANEL_SETTINGS -> pushPanelSettings(p);
            case STATION_PICK -> pushStationPick(p);
            case ADD_COND -> pushAddCond(p);
            case ADD_ENTRY -> pushAddEntry(p);
            case ELECTRIFICATION -> pushElectrification(p);
            default -> { }
        }
    }

    /** A controller's own value for one of its nodes: what it answered the pull for that class. */
    private static String[] cls(String c) {
        return new String[] {c};
    }

    private void pushSymbolEditor(PagePush p) {
        var e = scr.symEditor;
        // The fields' values are the editor's (SymbolEditorController.resolveText): the title, the two
        // letters, the number, the radius, the colour, and the delete button - hidden while creating.
        // Each key is spelt out: the vocabulary gate reads the keys a screen writes from its source.
        p.text("sym-edit-title", e.resolveText(cls("sym-edit-title")));
        p.text("sym-edit-letter1", e.resolveText(cls("sym-edit-letter1")));
        p.text("sym-edit-letter2", e.resolveText(cls("sym-edit-letter2")));
        p.text("sym-edit-number", e.resolveText(cls("sym-edit-number")));
        p.text("sym-edit-radius", e.resolveText(cls("sym-edit-radius")));
        p.text("sym-edit-color", e.resolveText(cls("sym-edit-color")));
        p.text("sym-edit-delete", e.resolveText(cls("sym-edit-delete")));
        int presets = SymbolEditorController.SYMBOL_COLOR_PRESETS.length;
        p.number("preset-count", presets);
        for (int r = 0; r < presets; r++) {
            p.color("preset-bg", r, ManagementComputerScreenV2.parseHexColor(SymbolEditorController.SYMBOL_COLOR_PRESETS[r]));
            p.color("preset-border", r, r == e.getColorIdx() ? 0xFFFFFFFF : 0xFF555555);
        }
        p.bool("sym-edit-has-custom", e.isOpen() && !scr.customColors.isEmpty());
        p.number("sym-edit-custom-count", scr.customColors.size());
        for (int r = 0; r < scr.customColors.size(); r++) {
            String hex = scr.customColors.get(r);
            p.color("sym-edit-custom-bg", r, ManagementComputerScreenV2.parseHexColor(hex));
            p.color("sym-edit-custom-border", r, hex.equalsIgnoreCase(e.getColor()) ? 0xFFFFFFFF : 0xFF555555);
        }
    }

    private void pushColorPicker(PagePush p) {
        // A 0.5 s caret blink while a field is focused (the buffer and the caret show).
        boolean caretOn = ((System.nanoTime() - scr.fieldFocusBlinkNano) / 500_000_000L) % 2 == 0;
        String caret = caretOn ? "_" : " ";
        p.text("cp-hex", scr.currentPickerHex());
        p.text("cp-info-hex", "hex".equals(scr.focusedField) ? scr.fieldEditBuffer + caret : scr.currentPickerHex().toUpperCase());
        if ("rgb".equals(scr.focusedField)) {
            p.text("cp-info-rgb", scr.fieldEditBuffer + caret);
        } else {
            int rgb = ColorPickerController.hsvToRgb(scr.picker.hue(), scr.picker.saturation(), scr.picker.value());
            p.text("cp-info-rgb", ((rgb >> 16) & 0xFF) + ", " + ((rgb >> 8) & 0xFF) + ", " + (rgb & 0xFF));
        }
        if ("hsl".equals(scr.focusedField)) {
            p.text("cp-info-hsl", scr.fieldEditBuffer + caret);
        } else {
            float h = scr.picker.hue() * 360f;
            float l = scr.picker.value() * (1f - scr.picker.saturation() / 2f);
            float s = (l == 0f || l == 1f) ? 0f : (scr.picker.value() - l) / Math.min(l, 1f - l);
            p.text("cp-info-hsl", Math.round(h) + "°, " + Math.round(s * 100) + "%, " + Math.round(l * 100) + "%");
        }
        p.color("cp-preview-bg", scr.picker.argb());
        p.bool("cp-pal-empty", scr.customColors.isEmpty());
        p.number("cp-pal-count", scr.customColors.size());
        for (int r = 0; r < scr.customColors.size(); r++) {
            String hex = scr.customColors.get(r);
            p.color("cp-chip-bg", r, ManagementComputerScreenV2.parseHexColor(hex));
            p.color("cp-chip-border", r, hex.equalsIgnoreCase(scr.symEditor.getColor()) ? 0xFFFFFFFF : 0xFF555555);
        }
        p.number("close-picker-label-w", LabelWidth.of("tsu.color.close_picker"));
    }

    private void pushSchedEditor(PagePush p) {
        var e = scr.schedEditor;
        // Create's infinite loop toggle, its colours, and the bottom buttons (apply / cancel) gated while
        // a drop-down is open - the editor's own values (ScheduleEditorController.resolve*).
        p.text("sched-edit-cyclic", e.resolveText(cls("sched-edit-cyclic"), -1, scr::schedStationNames, scr.fontOrNull()));
        p.color("sched-edit-cyclic-bg", e.resolveColor("sched-edit-cyclic-bg"));
        p.color("sched-edit-cyclic-color", e.resolveColor("sched-edit-cyclic-color"));
        p.color("sched-edit-cyclic-border", e.resolveColor("sched-edit-cyclic-border"));
        p.bool("sched-edit-bottom-visible", e.resolveBool("sched-edit-bottom-visible", scr::schedStationNames));
        p.number("apply-label-w", LabelWidth.of("tsu.mc.apply"));
        p.number("sym-cancel-label-w", LabelWidth.of("tsu.mc.sym_cancel"));
    }

    private void pushSchedShare(PagePush p) {
        p.text("sched-share-title", Component.translatable("tsu.mc.sched_share_title_fmt",
                scr.selectedTrainName.isEmpty() ? "?" : scr.selectedTrainName).getString());
        var cand = scr.schedShareCandidates();
        p.bool("sched-share-empty", scr.showScheduleShare && cand.isEmpty());
        p.bool("sched-share-scroll-visible", scr.showScheduleShare && scr.schedShareScroll.needsScrollbar());
        p.number("sched-share-scroll-thumb-y", scr.schedShareScroll.thumbY(ManagementComputerScreenV2.SCHED_SHARE_AREA_Y,
                ManagementComputerScreenV2.SCHED_SHARE_AREA_H, scr.schedShareThumbH()));
        p.number("sched-share-scroll-thumb-h", scr.schedShareThumbH());
        int rows = scr.showScheduleShare ? scr.schedShareScroll.rowCount() : 0;
        p.number("sched-share-count", rows);
        // The candidates' follow toggles (IndexedToggleSwitchController, indexed by the candidate).
        p.offset("sched-share-toggle-knob-x", 0);
        for (int r = 0; r < rows; r++) {
            int sIdx = r + scr.schedShareScroll.offset();
            p.text("sched-share-train-name", r, sIdx < cand.size() ? cand.get(sIdx).name() : null);
            p.color("sched-share-toggle-bg", r, scr.schedShareToggle.trackBgFor(sIdx));
            p.color("sched-share-toggle-knob-bg", r, scr.schedShareToggle.knobBgFor(sIdx));
            p.offset("sched-share-toggle-knob-x", r, scr.schedShareToggle.knobXFor(sIdx, 0));
        }
    }

    private void pushStationAssign(PagePush p) {
        var syms = scr.be().getLineSymbols();
        int rows = Math.min(12, syms.size());
        p.number("assign-count", rows);
        for (int r = 0; r < rows; r++) {
            var sym = syms.get(r);
            String label = sym.getLetters() + sym.getNumberStr();
            if (!sym.getName().isEmpty()) label += " " + sym.getName();
            p.text("assign-item-name", r, label);
        }
    }

    private void pushLayoutEdit(PagePush p) {
        int monW = scr.be().getMonitorW();
        int monH = scr.be().getMonitorH();
        p.text("layout-info", monW <= 0 || monH <= 0
                ? ManagementComputerScreenV2.tr("tsu.mc.monitor_unlinked")
                : Component.translatable("tsu.mc.monitor_size_panels_fmt", monW, monH, scr.layoutEditor.getLayout().size()).getString());
        p.number("layout-save-label-w", LabelWidth.of("tsu.mc.layout_save"));
    }

    private void pushTrainDetail(PagePush p) {
        p.text("train-detail-title", scr.selectedTrainName.isEmpty()
                ? ManagementComputerScreenV2.tr("tsu.mc.train_default_name") : scr.selectedTrainName);
        p.text("train-detail-cars", Component.translatable("tsu.mc.train_cars_fmt", String.valueOf(scr.selectedTrainCars)).getString());
        p.text("train-detail-speed", Component.translatable("tsu.mc.train_speed_fmt", String.format("%.1f", scr.selectedTrainSpeed)).getString());
        p.text("train-detail-station", Component.translatable("tsu.mc.train_current_fmt",
                scr.selectedTrainStation.isEmpty() ? ManagementComputerScreenV2.tr("tsu.mc.train_running") : scr.selectedTrainStation).getString());
        p.text("train-detail-sched-header", scr.selectedSchedEntries.isEmpty()
                ? ManagementComputerScreenV2.tr("tsu.mc.train_sched_empty") : ManagementComputerScreenV2.tr("tsu.mc.train_schedule_label"));
        // The electrification button shows whenever the popup is open (a train not electrified yet
        // says so in the detail), so a sync delay never hides it.
        p.bool("electrification-btn-visible", scr.selectedTrainId != null);
        // The current entry marked (play), the others a circle: both icons, so the rows' edges line up.
        int rows = Math.min(8, scr.selectedSchedEntries.size());
        p.number("sched-count", rows);
        for (int r = 0; r < rows; r++) {
            p.icon("train-detail-sched-marker", r, r == scr.selectedSchedCurrent ? "manta:play" : "manta:circle");
            p.text("train-detail-sched-item", r, scr.selectedSchedEntries.get(r));
        }
    }

    private void pushPanelSettings(PagePush p) {
        var pp = scr.psetPanel();
        p.text("pset-title", pp == null ? "" : Component.translatable("tsu.mc.pset_title_fmt", pp.getType().getDisplayName()).getString());
        p.text("pset-font-val", pp == null ? null : scr.psetValText(pp.getFontSize()));
        p.text("pset-maptext-val", pp == null ? null : scr.psetValText(pp.getMapTextSize()));
        p.text("pset-trainicon-val", pp == null ? null : scr.psetValText(pp.getTrainIconSize()));
        p.text("pset-stationicon-val", pp == null ? null : scr.psetValText(pp.getStationIconSize()));
        p.text("pset-signalicon-val", pp == null ? null : scr.psetValText(pp.getSignalIconSize()));
        // ROUTE_MAP has four size rows; every other panel the font size row alone.
        boolean map = pp != null && pp.getType() == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP;
        p.bool("pset-map-visible", map);
        p.bool("pset-font-visible", pp != null && !map);
    }

    /** The station pick drop-down: the editor's own count, height, rows and scrollbar (ScheduleEditorController.resolve*). */
    private void pushStationPick(PagePush p) {
        var e = scr.schedEditor;
        Integer rows = e.resolveNumber("sched-station-pick-count", scr::schedStationNames);
        p.number("sched-station-pick-count", rows);
        p.number("sched-station-pick-h", e.resolveNumber("sched-station-pick-h", scr::schedStationNames));
        p.bool("sched-station-pick-empty", e.resolveBool("sched-station-pick-empty", scr::schedStationNames));
        p.bool("sched-station-scrollbar-visible", e.resolveBool("sched-station-scrollbar-visible", scr::schedStationNames));
        p.offset("sched-station-thumb-y", e.stationThumbY(0));
        p.number("sched-station-thumb-h", e.stationThumbH());
        for (int r = 0; rows != null && r < rows; r++) {
            p.text("sched-station-pick-item", r,
                    e.resolveText(cls("sched-station-pick-item"), r, scr::schedStationNames, scr.fontOrNull()));
        }
    }

    private void pushAddCond(PagePush p) {
        var e = scr.schedEditor;
        Integer n = e.resolveNumber("sched-add-cond-count", scr::schedStationNames);
        p.number("sched-add-cond-count", n);
        for (int r = 0; n != null && r < n; r++) {
            p.text("sched-add-cond-item", r, e.resolveText(cls("sched-add-cond-item"), r, scr::schedStationNames, scr.fontOrNull()));
        }
    }

    private void pushAddEntry(PagePush p) {
        var e = scr.schedEditor;
        Integer n = e.resolveNumber("sched-add-entry-count", scr::schedStationNames);
        p.number("sched-add-entry-count", n);
        for (int r = 0; n != null && r < n; r++) {
            p.text("sched-add-entry-item", r, e.resolveText(cls("sched-add-entry-item"), r, scr::schedStationNames, scr.fontOrNull()));
        }
    }

    /** The electrification detail: the controller's own title and summary (ElectrificationDetailController.resolveText). */
    private void pushElectrification(PagePush p) {
        UUID trainId = scr.selectedTrainId;
        p.text("ed-title", trainId == null ? null : scr.edDetail.resolveText(cls("ed-title"), trainId));
        p.text("ed-summary", trainId == null ? null : scr.edDetail.resolveText(cls("ed-summary"), trainId));
    }
}
