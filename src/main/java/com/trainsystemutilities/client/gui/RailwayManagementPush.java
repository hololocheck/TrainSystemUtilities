package com.trainsystemutilities.client.gui;

import com.manta.api.anim.Animation;
import com.manta.api.anim.Easing;
import com.manta.api.anim.Transition;
import com.manta.api.screen.PageLayer;
import com.trainsystemutilities.schedule.TrainTypes;
import net.minecraft.network.chat.Component;

/**
 * The railway management screen's values, WRITTEN into its pages each frame before the engine draws
 * and once when a page opens (Manta 7 Phase 4, 2026-09-24): the screen overrides no
 * {@code getDynamic*}. Every value is the one {@code RailwayManagementDispatch} answered the pull, in
 * the same state; a row's value is the one it answered under that row's index. The hint toggle,
 * dialog-open, the *-popup-open entrances and the toggle transitions are the base's (FrameworkState).
 *
 * <p>Three pages: the primary; overlay 1, the settings popup; overlay 2, the screen door, the
 * announcement or the colour popup, by {@code overlayJson2()}'s precedence. Which layout an overlay
 * page is, is recorded when it opens.
 */
final class RailwayManagementPush {
    static final int NONE = 0, SETTINGS = 1, SCREEN_DOOR = 2, ANNOUNCEMENT = 3, COLOR = 4;

    /** The train rows slide in: the same pattern, different distances. */
    private static final Animation NEXT_ROW_ENTER = Animation.slideInFromRight(260, 80f);
    private static final Animation ARRIVED_ROW_ENTER = Animation.slideInFromRight(280, 60f);
    /**
     * The colour drop-down's list: the one node that carries its motion and holds its rows (Manta DropdownMotion,
     * 2026-10-07), 136 high - the preset this replaced anchored a 114-high panel, so the list's top drifted by 11 px.
     */
    private static final int COLOR_TARGET_LIST_H = 136;
    /** The condition drop-down's panel (ann-cond-dd-bg): three 11-px rows and the border. */
    private static final int ANN_COND_DD_H = 34;

    /**
     * The top of entry {@code entry}'s row on screen while the list is scrolled to {@code offset}, or null when that row is
     * outside the visible window. An entry index is the REAL one (scroll offset included - annEntryRealIdx), so a row's
     * place subtracts the offset: placing it at {@code entry * stride} drew the playing frame and the condition drop-down
     * {@code offset} rows too low once the list was scrolled (2026-10-16, the user's report of the drop-down).
     */
    static Integer annRowTop(int entry, int offset) {
        int row = entry - offset;
        if (entry < 0 || row < 0 || row >= RailwayManagementScreenV2.ANN_ENTRY_VISIBLE) {
            return null;
        }
        return RailwayManagementScreenV2.ANN_ENTRY_AREA_Y + row * RailwayManagementScreenV2.ANNOUNCEMENT_ENTRY_STRIDE;
    }
    /** The band colour picker (a popup on a popup): a light popIn. */
    private static final Animation SD_COLOR_PICKER_OPEN = Animation.popIn(200);
    /** The monitor preview's card flip (scaleX 0 -> 1). */
    private static final Animation PREVIEW_FLIP = Animation.flipX(500);
    private static final Transition ANN_ENTRY_ACTIVE = Transition.of(160, Easing.EASE_OUT);
    private static final Transition ANN_PLAYING_FRAME_MOVE = Transition.of(220, Easing.EASE_OUT);

    private final RailwayManagementScreenV2 scr;
    private PagePush primary, overlay1, overlay2;
    private int overlay2Kind = NONE;

    RailwayManagementPush(RailwayManagementScreenV2 scr) {
        this.scr = scr;
    }

    /** overlayJson2()'s pick, as a kind. */
    int overlay2Shown() {
        if (scr.showScreenDoor) return SCREEN_DOOR;
        if (scr.showAnnouncement) return ANNOUNCEMENT;
        if (scr.showColorSettings) return COLOR;
        return NONE;
    }

    void pageOpened(Object page, PageLayer layer) {
        PagePush p = new PagePush(page);
        switch (layer) {
            case PRIMARY -> {
                primary = p;
                primary.animation("next-row-enter", NEXT_ROW_ENTER);
                primary.animation("arrived-row-enter", ARRIVED_ROW_ENTER);
                primary.textByKeyOnly();
            }
            case OVERLAY -> {
                // overlayJson() shows the settings popup alone.
                overlay1 = p;
                overlay1.animation("preview-flip", PREVIEW_FLIP);
                overlay1.textByKeyOnly();
            }
            case OVERLAY2 -> {
                overlay2 = p;
                overlay2Kind = overlay2Shown();
                switch (overlay2Kind) {
                    case SCREEN_DOOR -> overlay2.animation("sd-color-picker-open", SD_COLOR_PICKER_OPEN);
                    case ANNOUNCEMENT -> {
                        // The share list's rows enter with nothing: the pull answered no animation for them.
                        overlay2.animation("ann-share-row-enter", null);
                        overlay2.transition("ann-entry-active", ANN_ENTRY_ACTIVE);
                        overlay2.transition("ann-playing-frame-move", ANN_PLAYING_FRAME_MOVE);
                    }
                    default -> { }
                }
                overlay2.textByKeyOnly();
            }
        }
        pushAll();
    }

    void pushAll() {
        if (primary != null && primary.isOpen()) pushPrimary(primary);
        if (overlay1 != null && overlay1.isOpen()) pushSettings(overlay1);
        if (overlay2 != null && overlay2.isOpen()) {
            switch (overlay2Kind) {
                case SCREEN_DOOR -> pushScreenDoor(overlay2);
                case ANNOUNCEMENT -> pushAnnouncement(overlay2);
                case COLOR -> pushColor(overlay2);
                default -> { }
            }
        }
    }

    // =========================================================================== the primary page

    private void pushPrimary(PagePush p) {
        var be = scr.be();
        p.text("mc-time", scr.getMinecraftTime());
        String station = be.getLinkedStationName();
        String badge = (station != null && !station.isEmpty()) ? station : Component.translatable("tsu.rm.station_unlinked").getString();
        p.text("header-badge", badge);
        int groups = be.getLinkedMonitorGroupCount();
        p.text("monitor-status-label", Component.translatable(scr.monitorEnabled() && groups > 0
                ? "tsu.poster.monitor_online" : "tsu.poster.monitor_offline").getString());
        p.text("monitor-info", groups > 0
                ? Component.translatable("tsu.poster.monitor_groups_linked_fmt", groups).getString()
                : Component.translatable("tsu.rm.monitor_disconnected").getString());
        var nextTrains = be.getNextTrains();
        int totalPages = nextTrains.isEmpty() ? 1
                : (nextTrains.size() + RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE - 1) / RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE;
        p.text("page-indicator", totalPages > 1 ? (scr.nextTrainPageIndex + 1) + "/" + totalPages : "");
        var arrived = be.getArrivedTrains();
        p.text("arrived-empty", arrived.isEmpty()
                ? (station != null ? Component.translatable("tsu.rm.no_train").getString()
                        : Component.translatable("tsu.rm.link_station_hint").getString())
                : "");
        p.text("next-empty", nextTrains.isEmpty() ? Component.translatable("tsu.rm.none").getString() : "");
        // The toggles are their controllers'; the monitor's derives its visual state (withVisualState).
        p.color("monitor-toggle-bg", scr.monitorToggle.trackBg());
        p.color("monitor-knob-bg", scr.monitorToggle.knobBg());
        p.color("monitor-indicator-bg", scr.monitorToggle.indicatorBg());
        p.color("monitor-status-color", scr.monitorToggle.statusText());
        // The owner's face box: private red, public green.
        p.color("owner-border", com.manta.api.hud.OwnerAccess.ringColor(be.isPrivateMode()));
        // R4.23.1: the settings button's glyph became icon + label; the label's width is LabelWidth's.
        p.number("settings-label-w", LabelWidth.of("tsu.common.settings"));
        // Offsets: knobX is default + delta, so its delta is its answer for 0.
        p.offset("monitor-knob-x", scr.monitorToggle.knobX(0));
        p.number("function-dd-bg-h", scr.functionDropdownPanelHeight());
        // The station badge: its width fits the name (6px padding each side), right-aligned in the
        // dialog (PAD + DLG_INNER_W - w). Without a font the layout's own box stands.
        var font = scr.fontOrNull();
        if (font == null) {
            p.number("header-badge-w", null);
            p.number("header-badge-x", null);
        } else {
            int w = com.manta.api.text.MantaText.uiWidth(font, badge) + 12;
            p.number("header-badge-w", w);
            p.number("header-badge-x", 14 + 236 - w);
        }
        p.bool("header-sym-visible", scr.getAssignedLineSymbol() != null);
        // The function drop-down: the announcement item only with SAS.
        boolean sas = com.trainsystemutilities.compat.sas.SasIntegration.isLoaded();
        // Drawn while open or folding; every press unfolds or folds it (Manta DropdownMotion, 2026-10-07).
        boolean fdShown = scr.functionDropdownMotion.isShown();
        p.bool("function-dd-visible-ann", fdShown && sas);
        p.bool("function-dd-visible-door", fdShown);
        p.animation("function-dd-open", scr.functionDropdownMotion.animation(scr.functionDropdownPanelHeight()));

        // The arrived trains' rows: every field cut to its width (font.width).
        p.number("arrived-count", arrived.size());
        long currentTick = be.getLevel() != null ? be.getLevel().getGameTime() : 0;
        for (int r = 0; r < arrived.size(); r++) {
            var t = arrived.get(r);
            p.text("train-name", r, scr.fit(t.name(), 70));
            p.text("train-cars", r, Component.translatable("tsu.mc.cars_unit_fmt", t.carriageCount()).getString());
            p.text("train-dest", r, (t.destination() != null && !t.destination().isEmpty())
                    ? scr.fit("→ " + t.destination(), 54) : "");
            p.text("train-route", r, (t.routeType() != null && !t.routeType().isEmpty())
                    ? scr.fit(RailwayManagementScreenV2.routeTypeText(t.routeType()), 56) : "");
            p.text("train-type-badge", r, TrainTypes.isSet(t.trainType())
                    ? scr.fit("[" + RailwayManagementScreenV2.trainTypeText(t.trainType()) + "]", 40) : "");
            p.text("train-arr-time", r, Component.translatable("tsu.rm.time_arr_fmt",
                    RailwayManagementScreenV2.formatDayTime(t.arrivalDayTime())).getString());
            p.text("train-dep-time", r, t.scheduledStopSec() > 0
                    ? Component.translatable("tsu.rm.time_dep_fmt",
                        RailwayManagementScreenV2.getDepartureTime(t.arrivalDayTime(), t.scheduledStopSec())).getString()
                    : "");
            int elapsedSec = (int) ((currentTick - t.arrivalTick()) / 20);
            String time;
            if (t.scheduledStopSec() > 0) {
                int remaining = t.scheduledStopSec() - elapsedSec;
                time = remaining > 0 ? Component.translatable("tsu.rm.time_remaining_fmt", remaining).getString()
                        : Component.translatable("tsu.rm.preparing_departure").getString();
            } else {
                time = Component.translatable("tsu.rm.time_stopped_fmt", elapsedSec).getString();
            }
            p.text("train-time", r, time);
        }

        // The next trains' rows, a page at a time: an approaching one hides its departure and stop
        // seconds (line 2 would crowd).
        int pageStart = scr.nextTrainPageIndex * RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE;
        int nextRows = nextTrains.isEmpty() ? 0 : Math.min(RailwayManagementScreenV2.NEXT_TRAIN_PER_PAGE, nextTrains.size() - pageStart);
        p.number("next-count", nextRows);
        for (int r = 0; r < nextRows; r++) {
            int real = pageStart + r;
            if (real >= nextTrains.size()) {
                for (String c : new String[] {"next-name", "next-cars", "next-route", "next-type-badge", "next-stop-info",
                        "next-arr-time", "next-dep-time", "next-stop-sec"}) {
                    p.text(c, r, null);
                }
                continue;
            }
            var n = nextTrains.get(real);
            p.text("next-name", r, scr.fit(n.name(), 70));
            p.text("next-cars", r, Component.translatable("tsu.mc.cars_unit_fmt", n.carriageCount()).getString());
            p.text("next-route", r, (n.routeType() != null && !n.routeType().isEmpty())
                    ? scr.fit(RailwayManagementScreenV2.routeTypeText(n.routeType()), 50) : "");
            p.text("next-type-badge", r, TrainTypes.isSet(n.trainType())
                    ? scr.fit("[" + RailwayManagementScreenV2.trainTypeText(n.trainType()) + "]", 50) : "");
            String stopInfo;
            if (n.currentStopStation() != null && !n.currentStopStation().isEmpty()) {
                stopInfo = scr.fit(Component.translatable("tsu.rm.stopping_at_fmt", n.currentStopStation()).getString(), 92);
            } else if (n.fromStation() != null && !n.fromStation().isEmpty()) {
                stopInfo = scr.fit(Component.translatable("tsu.rm.from_station_fmt", n.fromStation()).getString(), 92);
            } else {
                stopInfo = "";
            }
            p.text("next-stop-info", r, stopInfo);
            // Approaching: "(approaching)" in short, and dep / sec hidden; otherwise "HH:MM arrival (ETA)".
            p.text("next-arr-time", r, n.estimatedArrivalDayTime() <= 0 ? ""
                    : n.isApproaching()
                        ? Component.translatable("tsu.rm.time_approaching_fmt",
                            RailwayManagementScreenV2.formatDayTime(n.estimatedArrivalDayTime())).getString()
                        : Component.translatable("tsu.rm.time_arr_eta_fmt",
                            RailwayManagementScreenV2.formatDayTime(n.estimatedArrivalDayTime())).getString());
            p.text("next-dep-time", r, n.isApproaching() ? ""
                    : (n.estimatedArrivalDayTime() > 0 && n.scheduledStopSec() > 0)
                        ? Component.translatable("tsu.rm.time_dep_fmt",
                            RailwayManagementScreenV2.getDepartureTime(n.estimatedArrivalDayTime(), n.scheduledStopSec())).getString()
                        : "");
            p.text("next-stop-sec", r, n.isApproaching() ? ""
                    : n.scheduledStopSec() > 0 ? Component.translatable("tsu.rm.seconds_fmt", n.scheduledStopSec()).getString() : "");
        }
        pushTileColours(p, be, arrived.size(), nextRows);
    }

    /** The colour targets the tiles' rows show, each the suffix of a colorKey ("rm-color-<target>", railway-management.json). */
    private static final String[] TILE_ROW_TARGETS = {
            "trainName", "arrTime", "routeType", "depTime", "countdown", "nextName", "stopSec", "stopInfo"};

    /**
     * The tiles take the monitor's colours (the user's request, 2026-10-07): a text the monitor draws in a
     * target's colour (railway-monitor.json's binding for the same text) is that colour here too, on the face
     * being edited. A target with no colour leaves the tile its own - this screen's defaults are not the
     * monitor's (a next train's name is blue here, grey there).
     */
    private void pushTileColours(PagePush p, com.trainsystemutilities.blockentity.RailwayManagementBlockEntity be,
                                 int arrivedRows, int nextRows) {
        int rows = Math.max(arrivedRows, nextRows);
        for (String target : TILE_ROW_TARGETS) {
            Integer argb = monitorArgb(be, target);
            for (int r = 0; r < rows; r++) p.color("rm-color-" + target, r, argb);
        }
        p.color("rm-color-sectionTitle", monitorArgb(be, "sectionTitle"));
    }

    /** A target's colour on the face being edited, null for none (an unparsable colour is none, as the monitor's). */
    private Integer monitorArgb(com.trainsystemutilities.blockentity.RailwayManagementBlockEntity be, String target) {
        String hex = be.getMonitorColor(target, scr.showBackFace);
        int argb = hex.isEmpty() ? 0 : RailwayManagementScreenV2.parseHexArgb(hex, 0);
        return argb == 0 ? null : argb;
    }

    // =========================================================================== overlay 1: settings

    private void pushSettings(PagePush p) {
        int v = scr.currentTrackNumber();
        p.text("track-display-track", v == 0 ? Component.translatable("tsu.rm.none").getString()
                : Component.translatable("tsu.rm.track_number_fmt", v).getString());
        int fs = scr.currentTrackFontSize();
        p.text("track-display-font", fs == 0 ? Component.translatable("tsu.rm.auto").getString() : fs + "px");
        p.text("track-display-pos", Component.translatable(scr.currentTrackPosition() == 0 ? "tsu.rm.pos_left" : "tsu.rm.pos_right").getString());
        p.text("track-display-clock", Component.translatable(scr.currentClockVisible() == 1 ? "tsu.rm.show" : "tsu.rm.hide").getString());
        int cfs = scr.currentClockFontSize();
        p.text("track-display-clockfs", cfs == 0 ? Component.translatable("tsu.rm.auto").getString() : cfs + "px");
        p.text("settings-face-indicator", Component.translatable(scr.showBackFace ? "tsu.rm.face_back" : "tsu.rm.face_front").getString());
        var groups = scr.be().getMonitorGroups();
        if (groups.isEmpty()) {
            p.text("preview-dims", Component.translatable("tsu.rm.not_linked").getString());
        } else {
            var g = groups.get(scr.currentGroupIndex());
            p.text("preview-dims", g.height() + "×" + g.width());
        }
        p.color("batch-toggle-bg", scr.batchToggle.trackBg());
        p.color("batch-knob-bg", scr.batchToggle.knobBg());
        p.offset("batch-knob-x", scr.batchToggle.knobX(0));
        // The flip button only for a double-sided group; the group selector only when batch is off and
        // there is more than one group.
        p.bool("face-flip-visible", !groups.isEmpty() && groups.get(scr.currentGroupIndex()).doubleSided());
        p.bool("group-selector-visible", !scr.batchApply() && groups.size() > 1);
        // The group selector: "group 1 (HxW) track 5", the selected one marked.
        int rows = Math.min(RailwayManagementScreenV2.MAX_GROUPS, groups.size());
        p.number("group-count", rows);
        for (int r = 0; r < rows; r++) {
            var g = groups.get(r);
            String t = (r == scr.selectedGroupIndex ? "● " : "○ ")
                    + Component.translatable("tsu.rm.group_label_fmt", (r + 1), g.height(), g.width()).getString();
            if (g.trackNumber() > 0) t += " " + Component.translatable("tsu.rm.track_number_fmt", g.trackNumber()).getString();
            p.text("group-item", r, t);
        }
    }

    // =========================================================================== overlay 2

    private void pushScreenDoor(PagePush p) {
        var be = scr.be();
        boolean online = scr.isScreenDoorOnline();
        p.text("sd-status-label", Component.translatable(online ? "tsu.rm.sd_online" : "tsu.rm.sd_offline").getString());
        p.color("sd-status-indicator-bg", online ? 0xFF66BB6A : 0xFF555555);
        p.color("sd-status-color", online ? 0xFFFFFFFF : 0xFF888888);
        int band = be.getScreenDoorBandColorARGB();
        p.color("sd-color-bg", band);
        p.color("sd-color-picker-cur-bg", band);
        p.color("sd-highlight-toggle-bg", scr.sdHighlightToggle.trackBg());
        p.color("sd-highlight-knob-bg", scr.sdHighlightToggle.knobBg());
        p.offset("sd-highlight-knob-x", scr.sdHighlightToggle.knobX(0));
        var conds = be.getScreenDoorConditions();
        p.bool("sd-color-picker-visible", scr.showScreenDoor && scr.showScreenDoorColorPicker);
        p.bool("sd-cond-empty-visible", conds.isEmpty());
        p.bool("sd-cond-scroll-visible", scr.sdCondScroll.needsScrollbar());
        int total = conds.size();
        if (total <= RailwayManagementScreenV2.SD_COND_VISIBLE) {
            p.number("sd-cond-scroll-thumb-y", RailwayManagementScreenV2.SD_COND_AREA_Y);
            p.number("sd-cond-scroll-thumb-h", RailwayManagementScreenV2.SD_COND_AREA_H);
        } else {
            int thumbH = scr.sdCondScroll.thumbH(RailwayManagementScreenV2.SD_COND_AREA_H, 8);
            p.number("sd-cond-scroll-thumb-y", scr.sdCondScroll.thumbY(RailwayManagementScreenV2.SD_COND_AREA_Y,
                    RailwayManagementScreenV2.SD_COND_AREA_H, thumbH));
            p.number("sd-cond-scroll-thumb-h", thumbH);
        }
        // The conditions' rows (Phase 21), under the scroll.
        int rows = scr.sdCondScroll.rowCount();
        p.number("sd-cond-count", rows);
        for (int r = 0; r < rows; r++) {
            int idx = r + scr.sdCondScroll.offset();
            if (idx < 0 || idx >= conds.size()) {
                p.text("sd-cond-track", r, null);
                p.text("sd-cond-event", r, null);
                p.text("sd-cond-action", r, null);
                continue;
            }
            var c = conds.get(idx);
            p.text("sd-cond-track", r, String.valueOf(c.trackNumber()));
            p.text("sd-cond-event", r, switch (c.eventType()) {
                case com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP -> Component.translatable("tsu.rm.sd_event_stop").getString();
                case com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_DEPART -> Component.translatable("tsu.rm.sd_event_depart").getString();
                default -> null;
            });
            p.text("sd-cond-action", r, switch (c.actionType()) {
                case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_OPEN -> Component.translatable("tsu.rm.sd_action_open").getString();
                case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_CLOSE -> Component.translatable("tsu.rm.sd_action_close").getString();
                case com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_COLOR -> Component.translatable("tsu.rm.sd_action_color").getString();
                default -> null;
            });
        }
    }

    private void pushAnnouncement(PagePush p) {
        var be = scr.be();
        var cfg = scr.announcementConfig();
        boolean shareList = scr.showAnnouncement && scr.showAnnouncementShareList;
        boolean entries = scr.showAnnouncement && !scr.showAnnouncementShareList;
        var incoming = be.getIncomingShareSources();
        int playing = scr.announcementPlayingEntryIndex();

        // The master / range frame / attenuation toggles.
        boolean masterOn = cfg != null && cfg.isEnabled();
        p.color("ann-master-toggle-bg", scr.annMasterToggle.trackBg());
        p.color("ann-master-knob-bg", scr.annMasterToggle.knobBg());
        p.offset("ann-master-knob-x", masterOn ? 12 : 0);
        p.color("ann-rangeframe-toggle-bg", scr.annRangeFrameToggle.trackBg());
        p.color("ann-rangeframe-knob-bg", scr.annRangeFrameToggle.knobBg());
        p.offset("ann-rangeframe-knob-x", scr.annRangeFrameToggle.knobX(0));
        p.color("ann-attenuation-toggle-bg", scr.annAttenuationToggle.trackBg());
        p.color("ann-attenuation-knob-bg", scr.annAttenuationToggle.knobBg());
        p.offset("ann-attenuation-knob-x", scr.annAttenuationToggle.knobX(0));

        // Visibility: the share list, while in front, hides the entry list (and its scrollbar - R4.19.2).
        p.bool("ann-share-visible", shareList);
        p.bool("ann-entries-visible", entries);
        p.bool("ann-share-scroll-visible", shareList && scr.annShareScroll.needsScrollbar());
        p.bool("ann-entry-scroll-visible", entries && scr.annEntryScroll.needsScrollbar());
        // The playing frame and the condition drop-down sit on their entry's row - the row on screen now, hidden while
        // that row is scrolled out of the window (annRowTop).
        int annOffset = scr.annEntryScroll.offset();
        Integer playingTop = annRowTop(playing, annOffset);
        Integer ddTop = scr.conditionDropdown.isShown() ? annRowTop(scr.conditionDropdown.shownIdx(), annOffset) : null;
        p.bool("ann-cond-dd-visible", entries && ddTop != null);
        p.bool("ann-playing-frame-visible", entries && playingTop != null);
        // A station that is a share target hides its own share button (no two-step share) and shows
        // the source's banner instead.
        p.bool("ann-share-btn-visible", entries && incoming.isEmpty());
        p.bool("ann-incoming-share-visible", entries && !incoming.isEmpty());
        // The source banner: every source, detection / range / both.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < incoming.size(); i++) {
            var info = incoming.get(i);
            String key = info.detection && info.range ? "tsu.announcement.shared_from_both_fmt"
                    : info.range ? "tsu.announcement.shared_from_rng_fmt" : "tsu.announcement.shared_from_det_fmt";
            if (i > 0) sb.append(" / ");
            sb.append(Component.translatable(key, info.sourceStationName).getString());
        }
        p.text("ann-incoming-share-info", sb.toString());

        // The playing frame and the condition drop-down (placed at its entry's row).
        p.color("ann-playing-frame-bg", 0x124FC3F7);
        p.color("ann-playing-frame-border", 0xFF4FC3F7);
        p.number("ann-playing-frame-y", playingTop);
        // Placed at the row it opened on (as that row stands now), and kept there while it folds (Manta DropdownMotion,
        // 2026-10-07).
        p.number("ann-cond-dd-bg-y", ddTop != null ? ddTop + 14 : null);
        p.number("ann-cond-dd-item-0-y", ddTop != null ? ddTop + 15 : null);
        p.number("ann-cond-dd-item-1-y", ddTop != null ? ddTop + 26 : null);
        p.number("ann-cond-dd-item-2-y", ddTop != null ? ddTop + 37 : null);
        p.animation("ann-cond-dd-open", scr.conditionDropdown.motion().animation(ANN_COND_DD_H));

        // The entries: all rows visible, the rest by scroll (the count is the visible rows - 2026-07-26).
        int total = cfg != null ? cfg.size() : 0;
        if (total <= RailwayManagementScreenV2.ANN_ENTRY_VISIBLE) {
            p.number("ann-entry-scroll-thumb-y", RailwayManagementScreenV2.ANN_ENTRY_AREA_Y);
            p.number("ann-entry-scroll-thumb-h", RailwayManagementScreenV2.ANN_ENTRY_AREA_H);
        } else {
            int thumbH = scr.annEntryScroll.thumbH(RailwayManagementScreenV2.ANN_ENTRY_AREA_H, 8);
            p.number("ann-entry-scroll-thumb-y", scr.annEntryScroll.thumbY(RailwayManagementScreenV2.ANN_ENTRY_AREA_Y,
                    RailwayManagementScreenV2.ANN_ENTRY_AREA_H, thumbH));
            p.number("ann-entry-scroll-thumb-h", thumbH);
        }
        int rows = scr.showAnnouncement ? scr.annEntryScroll.rowCount() : 0;
        p.number("ann-entry-count", rows);
        for (int r = 0; r < rows; r++) {
            int idx = r + scr.annEntryScroll.offset();
            var entry = cfg != null && idx < cfg.size() ? cfg.get(idx) : null;
            p.text("ann-entry-index", r, "#" + (idx + 1));
            if (entry == null) {
                p.text("ann-cond-display", r, "");
                p.text("ann-delay-display", r, "0s");
                p.text("ann-count-display", r, "x1");
            } else {
                String key = switch (entry.condition().type) {
                    case NONE -> "tsu.announcement.cond_none";
                    case ON_DETECTION_PASS -> "tsu.announcement.cond_pass";
                    case ON_DETECTION_STOPPED -> "tsu.announcement.cond_stop";
                };
                // The drop-down's chevron is the layout's child icon (W7-1): the label alone here.
                p.text("ann-cond-display", r, Component.translatable(key).getString());
                // The wheel affordance is the child icon too: the value alone.
                int s = entry.condition().delaySeconds;
                p.text("ann-delay-display", r, (s > 0 ? "+" + s : String.valueOf(s)) + "s");
                p.text("ann-count-display", r, "x" + entry.playCount());
            }
            p.text("ann-media-info", r, mediaInfo(idx));
            // The playing entry's row highlighted.
            p.color("ann-entry-row-bg", r, idx == playing ? 0x224FC3F7 : null);
            p.color("ann-entry-row-border", r, idx == playing ? 0xFF4FC3F7 : null);
            // Only the two rows a swap moved slide in.
            float distance = scr.announcementEntryShuffleDistance(idx);
            p.animation("ann-entry-shuffle", r, distance == 0f ? null
                    : Animation.of(220).easing(Easing.EASE_OUT).translateY(distance, 0f).build());
        }

        // The share list
        var stations = scr.getShareCandidateStations();
        int shareTotal = stations.size();
        if (shareTotal <= RailwayManagementScreenV2.ANN_SHARE_VISIBLE) {
            p.number("ann-share-scroll-thumb-y", RailwayManagementScreenV2.ANN_SHARE_AREA_Y);
            p.number("ann-share-scroll-thumb-h", RailwayManagementScreenV2.ANN_SHARE_AREA_H);
        } else {
            int thumbH = scr.annShareScroll.thumbH(RailwayManagementScreenV2.ANN_SHARE_AREA_H, 8);
            p.number("ann-share-scroll-thumb-y", scr.annShareScroll.thumbY(RailwayManagementScreenV2.ANN_SHARE_AREA_Y,
                    RailwayManagementScreenV2.ANN_SHARE_AREA_H, thumbH));
            p.number("ann-share-scroll-thumb-h", thumbH);
        }
        int shareRows = shareList ? scr.annShareScroll.rowCount() : 0;
        p.number("ann-share-count", shareRows);
        p.offset("ann-share-det-knob-x", 0);
        p.offset("ann-share-rng-knob-x", 0);
        for (int r = 0; r < shareRows; r++) {
            int idx = r + scr.annShareScroll.offset();
            p.text("ann-share-station-name", r, idx < stations.size() ? stations.get(idx).name() : "");
            p.color("ann-share-det-bg", r, scr.annShareDetToggle.trackBgFor(idx));
            p.color("ann-share-det-knob-bg", r, scr.annShareDetToggle.knobBgFor(idx));
            p.offset("ann-share-det-knob-x", r, scr.annShareDetToggle.knobXFor(idx, 0));
            p.color("ann-share-rng-bg", r, scr.annShareRngToggle.trackBgFor(idx));
            p.color("ann-share-rng-knob-bg", r, scr.annShareRngToggle.knobBgFor(idx));
            p.offset("ann-share-rng-knob-x", r, scr.annShareRngToggle.knobXFor(idx, 0));
        }
    }

    /** An announcement entry's media: "name [format] m:ss", or "no media" for a slot with no audio. */
    private String mediaInfo(int idx) {
        if (idx < 0) return "";
        int slotIdx = com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE + idx;
        if (slotIdx >= scr.getMenu().slots.size()) return "";
        var stack = scr.getMenu().slots.get(slotIdx).getItem();
        if (stack.isEmpty() || !com.trainsystemutilities.compat.sas.SasIntegration.hasAudio(stack)) {
            return Component.translatable("tsu.announcement.no_media").getString();
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

    /**
     * The colour popup: the controller's own values (ColorTargetController.resolve*) for its nodes, and
     * the screen's swatch - which the pull answered before the controller, from the face being edited
     * ({@code getCurrentSelectedColorHex}, back-aware), while the hex text is the controller's.
     */
    private void pushColor(PagePush p) {
        var c = scr.colorPopup;
        p.text("color-face-label", Component.translatable(scr.showBackFace ? "tsu.rm.face_back_color" : "tsu.rm.face_front_color").getString());
        p.color("color-current-bg", RailwayManagementScreenV2.parseHexArgb(scr.getCurrentSelectedColorHex(), 0xFF000000));
        p.icon("color-target-caret", c.resolveText(new String[] {"color-target-caret"}, -1));
        p.text("color-target-dropdown", c.resolveText(new String[] {"color-target-dropdown"}, -1));
        p.text("color-current-hex", c.resolveText(new String[] {"color-current-hex"}, -1));
        p.bool("color-target-visible", c.resolveBool("color-target-visible"));
        p.animation("color-target-open", c.resolveAnimation("color-target-open", COLOR_TARGET_LIST_H));
        Integer rows = c.resolveNumber("color-target-count");
        p.number("color-target-count", rows);
        for (int r = 0; rows != null && r < rows; r++) {
            p.icon("color-target-marker", r, c.resolveText(new String[] {"color-target-marker"}, r));
            p.text("color-target-item", r, c.resolveText(new String[] {"color-target-item"}, r));
        }
    }
}
