package com.trainsystemutilities.client.renderer;
import belugalab.mcss3.anim.Animation;
import belugalab.mcss3.anim.Easing;
import belugalab.mcss3.ir.IrNode;
import belugalab.mcss3.screen.JsonLayoutHandler;

import belugalab.mcss3.world.CSSWorldRenderer;
import belugalab.mcss3.draw.VectorRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.block.MonitorBlock;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.MonitorBlockEntity;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.*;

@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class MonitorWorldRenderer {

    private static final boolean TSU_COLOR_DIAG = false;

    /** Lang リソースから翻訳済みテキストを取得するショートヘルパ。 */
    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }
    private static String trf(String key, Object... args) {
        return net.minecraft.network.chat.Component.translatable(key, args).getString();
    }
    /** train type コード (LOCAL/RAPID/EXPRESS) → ローカライズ済みテキスト。 */
    private static String trainTypeText(String code) {
        return switch (code) {
            case "RAPID" -> tr("tsu.monitor.train_type_rapid");
            case "EXPRESS" -> tr("tsu.monitor.train_type_express");
            case "LOCAL" -> tr("tsu.monitor.train_type_local");
            default -> code;
        };
    }
    /** route type コード (SHUTTLE/CIRCULAR) → ローカライズ済みテキスト。 */
    private static String routeTypeText(String code) {
        return switch (code) {
            case "SHUTTLE" -> tr("tsu.monitor.route_type_shuttle");
            case "CIRCULAR" -> tr("tsu.monitor.route_type_circular");
            default -> code;
        };
    }
    /** coupling status コード (COUPLE_xxx / DECOUPLE_xxx) → ローカライズ済みテキスト。 */
    private static String couplingStatusText(String code) {
        return switch (code) {
            case "COUPLE_WAIT" -> tr("tsu.monitor.couple_wait");
            case "COUPLE_DOING" -> tr("tsu.monitor.couple_doing");
            case "DECOUPLE_PREP" -> tr("tsu.monitor.decouple_prep");
            case "DECOUPLE_DOING" -> tr("tsu.monitor.decouple_doing");
            default -> code;
        };
    }
    private static boolean isCouplingCode(String code) {
        return code != null && code.startsWith("COUPLE_");
    }

    // モニターグループごとの独立CSSWorldRenderer（AnimationEngine競合回避）
    private static final Map<Long, CSSWorldRenderer> cssRenderers = new HashMap<>();

    private static final Map<Long, MonitorMainHandler> monitorHandlers = new HashMap<>();
    /** V3 stable IR: JSON load 1 度のみ、 全 monitor で共有。 */
    private static volatile IrNode sharedMonitorIr;

    private static IrNode getSharedMonitorIr() {
        IrNode ir = sharedMonitorIr;
        if (ir == null) {
            synchronized (MonitorWorldRenderer.class) {
                ir = sharedMonitorIr;
                if (ir == null) {
                    String json = com.trainsystemutilities.client.gui.TsuLayouts.load(
                            "layouts/renderers/railway-monitor.json");
                    com.google.gson.JsonObject root =
                            com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    ir = belugalab.mcss3.ir.compiler.JsonToIrCompiler.compile(root).root();
                    sharedMonitorIr = ir;
                }
            }
        }
        return ir;
    }

    /**
     * V3 Stable IR 用 handler。
     *
     * <p>IR は構造のみ build され、 per-frame の text/visibility/animation はこの handler が
     * class-based binding で解決する。 これにより IR の identity が安定化し
     * AnimationNode の slide-in/slide-out が 1 度だけ trigger されるようになる。
     *
     * <p>renderFace から毎フレーム公開フィールドを更新する。
     */
    private static final class MonitorMainHandler implements JsonLayoutHandler {
        int slideDistance = 128;
        volatile String clockText = "";
        // Per-frame state snapshot — renderFace が毎フレーム再代入する。
        List<RailwayManagementBlockEntity.ArrivedTrain> arrived = java.util.Collections.emptyList();
        List<RailwayManagementBlockEntity.NextTrain> nextVisible = java.util.Collections.emptyList();
        Set<UUID> newTrainIds = java.util.Collections.emptySet();
        Set<String> newNextNames = java.util.Collections.emptySet();
        List<CachedTrain> departedArrived = java.util.Collections.emptyList();
        List<String> departedNext = java.util.Collections.emptyList();
        boolean pageSlideOut = false;
        long currentTick = 0;
        boolean arrEmpty = true;
        boolean nextEmpty = true;
        String stationNameOrNull = null;
        // V3 stable IR: geometry / colors / fontScale を handler 経由で解決するための per-frame state
        int cssW, cssH;
        int trackNumber, trackFontSize, trackPosition;
        int clockVisibleVal, clockFontSize;
        boolean hasSymbol;
        boolean monitorEnabled;
        boolean isBack;
        RailwayManagementBlockEntity manager;

        @Override
        public String getDynamicText(String[] classes, String defaultText) {
            if (classes == null) return null;
            for (String c : classes) {
                int us = c.indexOf('_');
                if (us > 0) {
                    String slotPart = c.substring(0, us);
                    String field = c.substring(us + 1);
                    if (slotPart.startsWith("arr") && isDigits(slotPart, 3)) {
                        int slot = parseInt(slotPart.substring(3));
                        if (slot >= 0 && slot < arrived.size()) {
                            String r = resolveArrivedText(arrived.get(slot), field);
                            if (r != null) return r;
                        }
                        continue;
                    }
                    if (slotPart.startsWith("depArr") && isDigits(slotPart, 6)) {
                        int slot = parseInt(slotPart.substring(6));
                        if (slot >= 0 && slot < departedArrived.size()) {
                            String r = resolveDepartedArrivedText(departedArrived.get(slot), field);
                            if (r != null) return r;
                        }
                        continue;
                    }
                    if (slotPart.startsWith("next") && isDigits(slotPart, 4)) {
                        int slot = parseInt(slotPart.substring(4));
                        if (slot >= 0 && slot < nextVisible.size()) {
                            String r = resolveNextText(nextVisible.get(slot), field);
                            if (r != null) return r;
                        }
                        continue;
                    }
                    if (slotPart.startsWith("depNext") && isDigits(slotPart, 7)) {
                        int slot = parseInt(slotPart.substring(7));
                        if (slot >= 0 && slot < departedNext.size()) {
                            return departedNext.get(slot);
                        }
                        continue;
                    }
                }
            }
            // Standalone classes
            for (String c : classes) {
                if ("track-clock".equals(c)) return clockText;
                if ("track-number".equals(c)) return trackNumber > 0 ? String.valueOf(trackNumber) : null;
                if ("arr-empty-text".equals(c)) {
                    if (!arrEmpty) return null;
                    return stationNameOrNull != null
                            ? tr("tsu.monitor.no_train")
                            : tr("tsu.rm.link_station_hint");
                }
                if ("next-empty-text".equals(c)) {
                    if (!nextEmpty) return null;
                    return tr("tsu.monitor.empty");
                }
            }
            return null;
        }

        @Override
        public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
            if (key == null) return null;
            // V3 stable IR: top-level visibility bindings
            switch (key) {
                case "monitorEnabled": return monitorEnabled;
                case "leftSidePanelVisible": return monitorEnabled
                        && (trackNumber > 0 || hasSymbol) && trackPosition == 0;
                case "rightSidePanelVisible": return monitorEnabled
                        && (trackNumber > 0 || hasSymbol) && trackPosition == 1;
                case "trackNumberVisible": return trackNumber > 0;
                case "symIconSpacerVisible": return trackNumber <= 0 && hasSymbol;
                case "clockVisible": return clockVisibleVal == 1;
            }
            // arr{N}_..._visible / arr{N}_visible
            if (key.startsWith("arr") && key.length() > 3 && Character.isDigit(key.charAt(3))) {
                return resolveSlotBool(key, 3, /*kind=*/0);
            }
            if (key.startsWith("depArr") && key.length() > 6 && Character.isDigit(key.charAt(6))) {
                return resolveSlotBool(key, 6, /*kind=*/1);
            }
            if (key.startsWith("next") && key.length() > 4 && Character.isDigit(key.charAt(4))) {
                return resolveSlotBool(key, 4, /*kind=*/2);
            }
            if (key.startsWith("depNext") && key.length() > 7 && Character.isDigit(key.charAt(7))) {
                return resolveSlotBool(key, 7, /*kind=*/3);
            }
            if ("arr_empty_visible".equals(key)) return arrEmpty;
            if ("next_empty_visible".equals(key)) return nextEmpty;
            return null;
        }

        @Override
        public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
            if (key == null) return null;
            int autoFontSize = trackFontSize > 0 ? trackFontSize : 64;
            int trackClockSize = clockFontSize > 0 ? clockFontSize : 21;
            int clockRectH = Math.max(9, trackClockSize);
            int clockY = clockVisibleVal == 1
                    ? Math.max(0, cssH - MonitorLayoutConstants.PAD - clockRectH) : cssH;
            int numberAreaTop = MonitorLayoutConstants.TRACK_PANEL_TOP_PAD;
            int numberAreaBottom = clockVisibleVal == 1
                    ? clockY - MonitorLayoutConstants.TRACK_PANEL_GAP
                    : cssH - MonitorLayoutConstants.PAD;
            if (numberAreaBottom < numberAreaTop) numberAreaBottom = numberAreaTop;
            int numRectH = Math.max(9, autoFontSize);
            int idealNumY = MonitorLayoutConstants.NUMBER_CENTER_Y - numRectH / 2;
            int numY = Math.max(numberAreaTop, idealNumY);
            int maxNumY = Math.max(numberAreaTop, numberAreaBottom - numRectH);
            numY = Math.min(numY, maxNumY);
            boolean hasSidePanel = trackNumber > 0 || hasSymbol;
            int infoW = hasSidePanel ? cssW - MonitorLayoutConstants.TRACK_PANEL_W : cssW;
            int contentW = Math.max(0, infoW - MonitorLayoutConstants.PAD * 2);
            int sectionH = Math.max(20,
                    (cssH - MonitorLayoutConstants.PAD * 2 - MonitorLayoutConstants.SECTION_GAP) / 2);
            int sectionContentH = Math.max(0, sectionH - MonitorLayoutConstants.SECTION_TITLE_H);
            return switch (key) {
                case "cssW" -> cssW;
                case "cssH" -> cssH;
                case "infoW" -> infoW;
                case "contentW" -> contentW;
                case "sectionH" -> sectionH;
                case "sectionContentH" -> sectionContentH;
                case "trackNumberY" -> numY;
                case "trackNumberH" -> numRectH;
                case "clockY" -> clockY;
                case "clockH" -> clockRectH;
                // fontScale は ×100 で int 渡し (TextNode 側で /100 して float に)
                case "trackNumberFontScale" -> Math.max(1, (int) (autoFontSize / 20f * 100));
                case "clockFontScale" -> Math.max(1, (int) (trackClockSize / 20f * 100));
                default -> null;
            };
        }

        @Override
        public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
            if (key == null || manager == null) return null;
            String prefix = isBack ? "back." : "";
            String hex = switch (key) {
                case "colorTrainName" -> manager.getColorOrDefault(prefix + "trainName", "#4fc3f7");
                case "colorArrTime" -> manager.getColorOrDefault(prefix + "arrTime", "#80deea");
                case "colorDepTime" -> manager.getColorOrDefault(prefix + "depTime", "#ff8a65");
                case "colorStopInfo" -> manager.getColorOrDefault(prefix + "stopInfo", "#ffc107");
                case "colorRouteType" -> manager.getColorOrDefault(prefix + "routeType", "#555555");
                case "colorStopSec" -> manager.getColorOrDefault(prefix + "stopSec", "#444444");
                case "colorNextName" -> manager.getColorOrDefault(prefix + "nextName", "#555555");
                case "colorSectionTitle" -> manager.getColorOrDefault(prefix + "sectionTitle", "#4fc3f7");
                case "colorCountdown" -> manager.getColorOrDefault(prefix + "countdown", "#ffc107");
                case "colorTrackNum" -> manager.getColorOrDefault(prefix + "trackNumber", "#4fc3f7");
                default -> null;
            };
            return hex == null ? null : parseHexColor(hex, defaultArgb);
        }

        @Override
        public Animation getDynamicAnimation(String[] classes, String key) {
            if (key == null) return null;
            if (!key.endsWith("_anim")) return null;
            String slotPart = key.substring(0, key.length() - "_anim".length());
            if (slotPart.startsWith("arr") && isDigits(slotPart, 3)) {
                int slot = parseInt(slotPart.substring(3));
                if (slot >= 0 && slot < arrived.size()
                        && newTrainIds.contains(arrived.get(slot).id())) {
                    return Animation.of(400).easing(Easing.EASE_OUT)
                            .translateX(slideDistance, 0).build();
                }
                return null;
            }
            if (slotPart.startsWith("depArr") && isDigits(slotPart, 6)) {
                int slot = parseInt(slotPart.substring(6));
                if (slot >= 0 && slot < departedArrived.size()) {
                    return Animation.of(1200).easing(Easing.EASE_IN)
                            .translateX(0, -slideDistance).build();
                }
                return null;
            }
            if (slotPart.startsWith("next") && isDigits(slotPart, 4)) {
                int slot = parseInt(slotPart.substring(4));
                if (slot >= 0 && slot < nextVisible.size()) {
                    if (pageSlideOut) {
                        return Animation.of(1200).easing(Easing.EASE_IN)
                                .translateX(0, -slideDistance).build();
                    }
                    String name = nextVisible.get(slot).name();
                    if (newNextNames.contains(name)) {
                        return Animation.of(400).easing(Easing.EASE_OUT)
                                .translateX(slideDistance, 0).build();
                    }
                }
                return null;
            }
            if (slotPart.startsWith("depNext") && isDigits(slotPart, 7)) {
                int slot = parseInt(slotPart.substring(7));
                if (slot >= 0 && slot < departedNext.size()) {
                    return Animation.of(1200).easing(Easing.EASE_IN)
                            .translateX(0, -slideDistance).build();
                }
                return null;
            }
            return null;
        }

        /** kind: 0=arr, 1=depArr, 2=next, 3=depNext. */
        private Boolean resolveSlotBool(String key, int prefixLen, int kind) {
            // key = "<prefix><slot>(_<sub>)?_visible"
            if (!key.endsWith("_visible")) return null;
            String body = key.substring(prefixLen, key.length() - "_visible".length());
            int us = body.indexOf('_');
            int slot;
            String sub;
            if (us < 0) {
                slot = parseInt(body);
                sub = "visible";
            } else {
                slot = parseInt(body.substring(0, us));
                sub = body.substring(us + 1) + "_visible";
            }
            if (slot < 0) return null;
            return switch (kind) {
                case 0 -> resolveArrivedBool(slot, sub);
                case 1 -> resolveDepartedArrivedBool(slot, sub);
                case 2 -> resolveNextBool(slot, sub);
                case 3 -> resolveDepartedNextBool(slot, sub);
                default -> null;
            };
        }

        private String resolveArrivedText(RailwayManagementBlockEntity.ArrivedTrain t, String field) {
            return switch (field) {
                case "name" -> t.name();
                case "detail" -> trf("tsu.monitor.cars_fmt", t.carriageCount());
                case "dest" -> (t.destination() != null && !t.destination().isEmpty())
                        ? trf("tsu.monitor.dest_fmt", t.destination()) : null;
                case "badge_local" -> "LOCAL".equals(t.trainType()) ? trainTypeText("LOCAL") : null;
                case "badge_rapid" -> "RAPID".equals(t.trainType()) ? trainTypeText("RAPID") : null;
                case "badge_express" -> "EXPRESS".equals(t.trainType()) ? trainTypeText("EXPRESS") : null;
                case "route" -> (t.routeType() != null && !t.routeType().isEmpty())
                        ? routeTypeText(t.routeType()) : null;
                case "coupling" -> isCouplingCode(t.couplingStatus())
                        ? couplingStatusText(t.couplingStatus()) : null;
                case "decoupling" -> (t.couplingStatus() != null && !t.couplingStatus().isEmpty()
                        && !isCouplingCode(t.couplingStatus()))
                        ? couplingStatusText(t.couplingStatus()) : null;
                case "partner" -> (t.couplingPartner() != null && !t.couplingPartner().isEmpty())
                        ? trf("tsu.monitor.partner_fmt", t.couplingPartner()) : null;
                case "arrtime" -> trf("tsu.monitor.arr_time_fmt", formatDayTime(t.arrivalDayTime()));
                case "deptime" -> t.scheduledStopSec() > 0
                        ? trf("tsu.monitor.dep_time_fmt",
                                getDepartureTime(t.arrivalDayTime(), t.scheduledStopSec()))
                        : null;
                case "countdown" -> {
                    long elapsed = currentTick - t.arrivalTick();
                    int elapsedSec = (int) (elapsed / 20);
                    if (t.scheduledStopSec() > 0) {
                        int remaining = t.scheduledStopSec() - elapsedSec;
                        yield remaining > 0
                                ? trf("tsu.monitor.in_seconds_fmt", remaining)
                                : tr("tsu.rm.preparing_departure");
                    }
                    yield trf("tsu.monitor.stopping_sec_fmt", elapsedSec);
                }
                default -> null;
            };
        }

        private Boolean resolveArrivedBool(int slot, String sub) {
            boolean slotHasTrain = slot < arrived.size();
            if ("visible".equals(sub)) return slotHasTrain;
            if (!slotHasTrain) return false;
            var t = arrived.get(slot);
            boolean hasCoupling = t.couplingStatus() != null && !t.couplingStatus().isEmpty();
            boolean isCouple = isCouplingCode(t.couplingStatus());
            return switch (sub) {
                case "dest_visible" -> t.destination() != null && !t.destination().isEmpty();
                case "badge_local_visible" -> "LOCAL".equals(t.trainType());
                case "badge_rapid_visible" -> "RAPID".equals(t.trainType());
                case "badge_express_visible" -> "EXPRESS".equals(t.trainType());
                case "route_visible" -> t.routeType() != null && !t.routeType().isEmpty();
                case "coupling_visible" -> hasCoupling && isCouple;
                case "decoupling_visible" -> hasCoupling && !isCouple;
                case "partner_visible" -> hasCoupling
                        && t.couplingPartner() != null && !t.couplingPartner().isEmpty();
                case "arrtime_visible" -> !hasCoupling && t.scheduledStopSec() > 0;
                case "deptime_visible" -> !hasCoupling && t.scheduledStopSec() > 0;
                case "stopinfo_visible" -> false;
                case "countdown_visible" -> !hasCoupling;
                default -> null;
            };
        }

        private String resolveDepartedArrivedText(CachedTrain t, String field) {
            return switch (field) {
                case "name" -> t.name();
                case "detail" -> trf("tsu.monitor.cars_fmt", t.carriageCount());
                default -> null;
            };
        }

        private Boolean resolveDepartedArrivedBool(int slot, String sub) {
            boolean slotHas = slot < departedArrived.size();
            if ("visible".equals(sub)) return slotHas;
            return null;
        }

        private String resolveNextText(RailwayManagementBlockEntity.NextTrain nt, String field) {
            return switch (field) {
                case "name" -> nt.name();
                case "detail" -> trf("tsu.monitor.cars_fmt", nt.carriageCount());
                case "badge_local" -> "LOCAL".equals(nt.trainType()) ? trainTypeText("LOCAL") : null;
                case "badge_rapid" -> "RAPID".equals(nt.trainType()) ? trainTypeText("RAPID") : null;
                case "badge_express" -> "EXPRESS".equals(nt.trainType()) ? trainTypeText("EXPRESS") : null;
                case "route" -> (nt.routeType() != null && !nt.routeType().isEmpty())
                        ? routeTypeText(nt.routeType()) : null;
                case "stopinfo" -> (nt.currentStopStation() != null && !nt.currentStopStation().isEmpty())
                        ? trf("tsu.monitor.stopping_at_fmt", nt.currentStopStation()) : null;
                case "from" -> {
                    boolean hasCurrent = nt.currentStopStation() != null && !nt.currentStopStation().isEmpty();
                    if (hasCurrent) yield null;
                    yield (nt.fromStation() != null && !nt.fromStation().isEmpty())
                            ? trf("tsu.monitor.from_fmt", nt.fromStation()) : null;
                }
                case "arrtime" -> nt.estimatedArrivalDayTime() > 0
                        ? trf("tsu.monitor.eta_fmt", formatDayTime(nt.estimatedArrivalDayTime())) : null;
                case "deptime" -> (nt.estimatedArrivalDayTime() > 0 && nt.scheduledStopSec() > 0)
                        ? trf("tsu.monitor.dep_time_fmt",
                                getDepartureTime(nt.estimatedArrivalDayTime(), nt.scheduledStopSec()))
                        : null;
                case "stopsec" -> nt.scheduledStopSec() > 0
                        ? trf("tsu.monitor.stop_seconds_fmt", nt.scheduledStopSec()) : null;
                default -> null;
            };
        }

        private Boolean resolveNextBool(int slot, String sub) {
            boolean slotHas = slot < nextVisible.size();
            if ("visible".equals(sub)) return slotHas;
            if (!slotHas) return false;
            var nt = nextVisible.get(slot);
            boolean hasCurrent = nt.currentStopStation() != null && !nt.currentStopStation().isEmpty();
            return switch (sub) {
                case "badge_local_visible" -> "LOCAL".equals(nt.trainType());
                case "badge_rapid_visible" -> "RAPID".equals(nt.trainType());
                case "badge_express_visible" -> "EXPRESS".equals(nt.trainType());
                case "route_visible" -> nt.routeType() != null && !nt.routeType().isEmpty();
                case "stopinfo_visible" -> hasCurrent;
                case "from_visible" -> !hasCurrent
                        && nt.fromStation() != null && !nt.fromStation().isEmpty();
                case "arrtime_visible" -> nt.estimatedArrivalDayTime() > 0;
                case "deptime_visible" -> nt.estimatedArrivalDayTime() > 0 && nt.scheduledStopSec() > 0;
                case "stopsec_visible" -> nt.scheduledStopSec() > 0;
                default -> null;
            };
        }

        private Boolean resolveDepartedNextBool(int slot, String sub) {
            boolean slotHas = slot < departedNext.size();
            if ("visible".equals(sub)) return slotHas;
            return null;
        }

        private static boolean isDigits(String s, int from) {
            if (s.length() <= from) return false;
            for (int i = from; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) return false;
            }
            return true;
        }

        private static int parseInt(String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { return -1; }
        }
    }

    /**
     * V3 Stable IR structural hash。
     *
     * <p>IR は構造のみで build されるため hash も「構造に影響するパラメータ」のみ含める:
     * 表示サイズ、 番線/時計の表示設定、 monitor 有効/無効、 路線記号の有無、 色設定 10 個。
     *
     * <p>列車データ (列車一覧の内容、 countdown、 ETA、 clockText 等) は IR rebuild の trigger
     * にしない (= handler binding で per-frame に解決)。
     */
    private static final class MonitorContentHash {
        static long compute(RailwayManagementBlockEntity manager,
                            int cssW, int cssH,
                            int trackNumber, int trackFontSize, int trackPosition,
                            int clockVisible, int clockFontSize, boolean isBack,
                            int arrCount, int depArrCount, int nxtCount, int depNxtCount) {
            long h = 1469598103934665603L;
            h = mix(h, cssW); h = mix(h, cssH);
            h = mix(h, trackNumber); h = mix(h, trackFontSize); h = mix(h, trackPosition);
            h = mix(h, clockVisible); h = mix(h, clockFontSize);
            h = mix(h, isBack ? 1 : 0);
            // slot 数 — IR の構造変化 trigger (= 列車 add/remove で rebuild)
            h = mix(h, arrCount); h = mix(h, depArrCount);
            h = mix(h, nxtCount); h = mix(h, depNxtCount);
            if (manager != null) {
                h = mix(h, manager.isMonitorEnabled() ? 1 : 0);
                h = mix(h, manager.getAssignedLineSymbol() != null ? 1 : 0);
                String prefix = isBack ? "back." : "";
                for (String key : COLOR_KEYS) {
                    h = mix(h, manager.getColorOrDefault(prefix + key, "").hashCode());
                }
            }
            return h;
        }

        private static long mix(long h, int v) {
            h ^= v;
            return h * 1099511628211L;
        }

        private static final String[] COLOR_KEYS = {
                "trainName", "arrTime", "depTime", "stopInfo", "routeType",
                "stopSec", "nextName", "sectionTitle", "countdown", "trackNumber"
        };
    }

    // Animation state per monitor group (keyed by frontOrigin position as long)
    private static final Map<Long, MonitorAnimState> animStates = new HashMap<>();
    private static final Map<Long, Long> monitorToGroupKeys = new HashMap<>();
    private static final Map<Long, Set<Long>> groupMembers = new HashMap<>();
    private static final long PAGE_ROTATE_INTERVAL_NS = 5_000_000_000L; // 5 seconds
    private static final long DEPARTED_DISPLAY_DURATION_NS = 1_300_000_000L; // 出発列車表示持続時間 1.3秒 (slide-out 1.2s + バッファ)

    // Per-scanPos NetworkData cache (TTL 約 200ms)。毎フレーム scanFromPosition すると
    // フレームレートが致命的に下がるため、近接フレームではキャッシュを再利用する。
    private static final Map<BlockPos, CachedNetData>
            netDataCache = new HashMap<>();
    private static final long NET_DATA_TTL_NS = 200_000_000L; // 200ms

    private record CachedNetData(com.trainsystemutilities.network.TrackNetworkScanner.NetworkData data,
                                  long expiresAtNano) {}

    private static com.trainsystemutilities.network.TrackNetworkScanner.NetworkData
            getNetworkDataCached(Level level, BlockPos scanPos) {
        if (scanPos == null) return null;
        long now = System.nanoTime();
        netDataCache.entrySet().removeIf(e -> e.getValue().expiresAtNano <= now);
        CachedNetData cached = netDataCache.get(scanPos);
        if (cached != null && cached.expiresAtNano > now) return cached.data;
        com.trainsystemutilities.network.TrackNetworkScanner.NetworkData fresh = null;
        try {
            fresh = com.trainsystemutilities.network.TrackNetworkScanner.scanFromPosition(level, scanPos);
        } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MonitorWorld] op failed", ignored); }
        netDataCache.put(scanPos, new CachedNetData(fresh, now + NET_DATA_TTL_NS));
        // 期限切れエントリを軽く間引く
        if (netDataCache.size() > 32) {
            Iterator<BlockPos> iter = netDataCache.keySet().iterator();
            while (netDataCache.size() > 32 && iter.hasNext()) {
                iter.next();
                iter.remove();
            }
        }
        return fresh;
    }

    /** サーバー権威の BE cache (updateNetworkCache → getUpdateTag 同期) から NetworkData を構築。
     *  cache が全て空 (未 scan / 旧サーバー) なら null → 呼び元が旧 client scan へ fallback。 */
    private static com.trainsystemutilities.network.TrackNetworkScanner.NetworkData
            netDataFromComputerCache(ManagementComputerBlockEntity computer) {
        var nodes = computer.getCachedNodes();
        var stations = computer.getCachedStations();
        var trains = computer.getCachedTrains();
        if (nodes.isEmpty() && stations.isEmpty() && trains.isEmpty()) return null;
        var signals = computer.getCachedSignals();
        List<BlockPos> stationPositions = new ArrayList<>(stations.size());
        for (var s : stations) stationPositions.add(s.position());
        List<BlockPos> signalPositions = new ArrayList<>(signals.size());
        for (var s : signals) signalPositions.add(s.position());
        List<java.util.UUID> trainIds = new ArrayList<>(trains.size());
        for (var t : trains) if (t.id() != null) trainIds.add(t.id());
        return new com.trainsystemutilities.network.TrackNetworkScanner.NetworkData(
                stationPositions, signalPositions, trainIds,
                stations, signals, trains, nodes, computer.getCachedEdges());
    }

    /** 路線マップ fallback 描画の列車 lerp 平滑化 state (trainId → {x, z, dirX, dirZ})。
     *  broadcast (TrainPositionPayload 1Hz) を補間し、移動ベクトルから heading を導出する
     *  (= 画面 MapRenderer.smoothTrainPos と同方式)。 */
    private static final Map<java.util.UUID, double[]> fallbackSmoothPos = new HashMap<>();

    // === Iris shadow pass 検出 (soft-dependency: reflection、 Iris 不在なら常に false) ===
    private static Object irisApi;
    private static java.lang.reflect.Method irisShadowMethod;
    private static boolean irisChecked;

    private static boolean isIrisShadowPass() {
        if (!irisChecked) {
            irisChecked = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = c.getMethod("getInstance").invoke(null);
                irisShadowMethod = c.getMethod("isRenderingShadowPass");
            } catch (Throwable ignored) { /* Iris 不在 */ }
        }
        if (irisShadowMethod == null) return false;
        try {
            return (boolean) irisShadowMethod.invoke(irisApi);
        } catch (Throwable t) {
            irisShadowMethod = null;   // 失敗したら以後 skip
            return false;
        }
    }

    /** 白枠はみ出し診断用 (5s throttle)。 原因確定後に撤去する。 */
    private static long lastTrainListDiagNano;

    /** モニターブロック撤去/アンロード時に該当グループのキャッシュを破棄 (BlockEntity から呼ぶ)。 */
    public static void invalidateMonitorGroup(BlockPos monitorPos) {
        long lookupKey = monitorPos.asLong();
        long groupKey = monitorToGroupKeys.getOrDefault(lookupKey, lookupKey);
        discardMonitorGroupCache(groupKey);
        netDataCache.clear();
    }

    private static void registerMonitorGroup(BlockPos frontOrigin, List<BlockPos> group) {
        long groupKey = frontOrigin.asLong();
        Set<Long> previousMembers = groupMembers.get(groupKey);
        if (previousMembers != null) {
            for (Long memberKey : previousMembers) {
                monitorToGroupKeys.remove(memberKey);
            }
        }

        Set<Long> members = new HashSet<>();
        for (BlockPos memberPos : group) {
            long memberKey = memberPos.asLong();
            monitorToGroupKeys.put(memberKey, groupKey);
            members.add(memberKey);
        }
        groupMembers.put(groupKey, members);
    }

    private static void discardMonitorGroupCache(long groupKey) {
        cssRenderers.remove(groupKey);
        animStates.remove(groupKey);
        long frontKey = (((long) 0) << 1) ^ groupKey;
        long backKey = (((long) 1) << 1) ^ groupKey;
        monitorHandlers.remove(frontKey);
        monitorHandlers.remove(backKey);
        Set<Long> members = groupMembers.remove(groupKey);
        if (members != null) {
            for (Long memberKey : members) {
                monitorToGroupKeys.remove(memberKey);
            }
        }
    }

    // Cached train info for slide-out animation after departure
    record CachedTrain(String name, int carriageCount, String destination,
                                String routeType, long arrivalDayTime, int scheduledStopSec) {}

    static class MonitorAnimState {
        final Set<UUID> knownTrains = new HashSet<>();
        final Set<String> knownNextTrains = new HashSet<>();
        // V3: newTrains は train が arrived list から外れるまで残置 (= retainAll で自動 clean)。
        // animation は AnimationNode fill-mode forwards + IR identity 安定で 1 度しか発火しない。
        final Set<UUID> newTrains = new HashSet<>();
        final Set<UUID> departedTrains = new HashSet<>();
        final Map<UUID, CachedTrain> departedTrainCache = new HashMap<>();
        final Set<String> newNextTrains = new HashSet<>();
        final Set<String> departedNextTrains = new HashSet<>();
        // Cache current arrived trains for departure detection
        final Map<UUID, CachedTrain> arrivedCache = new HashMap<>();
        boolean initialized = false;
        // 出発列車の表示持続タイマー
        long departedTimestampNano = 0;
        // 次に停車する列車のローテーション
        int nextTrainPage = 0;
        long nextTrainPageChangeNano = System.nanoTime();
        boolean nextTrainPinned = false;
        boolean pageSlideOut = false;
        // 停車中列車のスライドローテーション（per-monitor）
        int arrivedSlideIndex = 0;
        long arrivedSlideChangeNano = System.nanoTime();
        boolean arrivedSlideChanged = false;
        // 1ブロック高さ時のセクションローテーション
        long sectionRotateNano = 0;
        boolean sectionShowNext = false;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        // Iris shadow pass 中は描画しない (= シェーダー使用時にモニター内容が
        // 影として地面へ投影される問題の防止)。 Iris 不在なら常に false。
        if (isIrisShadowPass()) return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return;

        // cssRendererはモニターグループごとに作成（renderFace内で取得）

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        BlockPos playerPos = mc.player.blockPosition();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        Set<BlockPos> rendered = new HashSet<>();
        boolean didRender = false;

        for (BlockEntity be : getMonitorBlockEntities(level, playerPos, 64)) {
            if (!(be instanceof MonitorBlockEntity monitor)) continue;
            if (rendered.contains(monitor.getBlockPos())) continue;
            // ポスター管理ブロックにリンクされているモニターはスキップ
            if (PosterMonitorRenderer.isPosterManaged(monitor.getBlockPos())) continue;

            BlockState state = monitor.getBlockState();
            if (!MonitorBlock.isMonitorBlock(state)) continue;

            Direction facing = state.getValue(MonitorBlock.FACING);
            List<BlockPos> group = findConnectedMonitors(level, monitor.getBlockPos(), facing);
            rendered.addAll(group);

            // Calculate group bounds in facing-relative coordinates
            Direction right = facing.getClockWise();
            int minH = 0, maxH = 0, minV = 0, maxV = 0;
            if (group.isEmpty()) continue;
            BlockPos origin = group.get(0);
            for (BlockPos gp : group) {
                BlockPos diff = gp.subtract(origin);
                int h = diff.getX() * right.getStepX() + diff.getZ() * right.getStepZ();
                int v = diff.getY();
                minH = Math.min(minH, h); maxH = Math.max(maxH, h);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            int groupWidth = maxH - minH + 1;
            int groupHeight = maxV - minV + 1;

            // Front and back face origins are at opposite corners
            BlockPos frontOrigin = origin.offset(
                    right.getStepX() * maxH, minV, right.getStepZ() * maxH);
            BlockPos backOrigin = origin.offset(
                    right.getStepX() * minH, minV, right.getStepZ() * minH);
            registerMonitorGroup(frontOrigin, group);

            boolean isDoubleSided = group.stream().anyMatch(
                    gp -> MonitorBlock.isDoubleSidedMonitor(level.getBlockState(gp)));

            RailwayManagementBlockEntity manager = getLinkedManager(level, group);
            int trackNumber = 0;
            int trackFontSize = 0;
            int trackPosition = 0;
            int backTrackNumber = 0;
            int backTrackFontSize = 0;
            int backTrackPosition = 0;
            int clockVisible = 1;
            int clockFontSize = 0;
            int backClockVisible = 1;
            int backClockFontSize = 0;
            BlockEntity firstBE = level.getBlockEntity(group.get(0));
            if (firstBE instanceof MonitorBlockEntity mbe) {
                trackNumber = mbe.getTrackNumber();
                trackFontSize = mbe.getTrackFontSize();
                trackPosition = mbe.getTrackPosition();
                backTrackNumber = mbe.getBackTrackNumber();
                backTrackFontSize = mbe.getBackTrackFontSize();
                backTrackPosition = mbe.getBackTrackPosition();
                clockVisible = mbe.getClockVisible();
                clockFontSize = mbe.getClockFontSize();
                backClockVisible = mbe.getBackClockVisible();
                backClockFontSize = mbe.getBackClockFontSize();
            }

            // ブロック厚み判定（薄型モニター対応）
            // zOffset: ブロック中央(0.5)から前面までの距離 + 微小オフセット
            float zOffset = 0.505f; // フルブロック前面
            float zOffsetBack = 0.505f;
            Block block = state.getBlock();
            if (block instanceof com.trainsystemutilities.block.ThinDoubleSidedMonitorBlock tdb) {
                float halfThickness = tdb.getThickness() / 32f; // 半分の厚み（ブロック単位）
                zOffset = halfThickness + 0.005f;
                zOffsetBack = halfThickness + 0.005f;
            } else if (block instanceof com.trainsystemutilities.block.ThinMonitorBlock tb) {
                // 片面薄型: 前面寄せ → フルブロックと同じ画面位置
                zOffset = 0.505f;
            }

            // 管理コンピューターにリンクされたモニター → レイアウトベース描画
            ManagementComputerBlockEntity computerBE = getLinkedComputer(level, group);
            // monitorEnabled確認（staticキャッシュ優先、なければBlockEntity）
            boolean computerMonitorEnabled = false;
            if (computerBE != null) {
                var enabledCache = com.trainsystemutilities.client.gui.MonitorClientCache.monitorEnabledCache;
                computerMonitorEnabled = enabledCache.containsKey(computerBE.getBlockPos())
                        ? enabledCache.get(computerBE.getBlockPos())
                        : computerBE.isMonitorEnabled();
            }
            // staticキャッシュまたはBlockEntityにレイアウトがあれば描画
            var cache = com.trainsystemutilities.client.gui.MonitorClientCache.layoutCache;
            boolean hasLayout = computerBE != null && computerMonitorEnabled && (
                    (cache.containsKey(computerBE.getBlockPos()) && !cache.get(computerBE.getBlockPos()).isEmpty())
                    || !computerBE.getMonitorLayout().isEmpty());
            if (hasLayout) {
                try {
                    renderComputerLayout(poseStack, bufferSource, cam, frontOrigin, facing,
                            groupWidth, groupHeight, computerBE, false, zOffset, level);
                    if (isDoubleSided) {
                        renderComputerLayout(poseStack, bufferSource, cam, backOrigin, facing,
                                groupWidth, groupHeight, computerBE, true, zOffsetBack, level);
                    }
                    didRender = true;
                } catch (Exception e) {
                    TrainSystemUtilities.LOGGER.error("Computer layout render error: {}", e.getMessage());
                }
                continue;
            }

            try {
                renderFace(poseStack, bufferSource, cam, frontOrigin, facing,
                        groupWidth, groupHeight, manager, false, trackNumber, trackFontSize, trackPosition,
                        clockVisible, clockFontSize, zOffset);
                if (isDoubleSided) {
                    renderFace(poseStack, bufferSource, cam, backOrigin, facing,
                            groupWidth, groupHeight, manager, true, backTrackNumber, backTrackFontSize, backTrackPosition,
                            backClockVisible, backClockFontSize, zOffsetBack);
                }
                didRender = true;
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.error("Monitor render error at {}: {}", frontOrigin, e.getMessage(), e);
            }
        }

        if (didRender) {
            bufferSource.endBatch();
        }

        // animStates/cssRenderersクリーンアップ: 描画範囲外の古いエントリを除去（最大64エントリ）
        if (animStates.size() > 64) {
            List<Long> removeKeys = new ArrayList<>();
            var iter = animStates.keySet().iterator();
            int toRemove = animStates.size() - 64;
            while (iter.hasNext() && toRemove > 0) {
                removeKeys.add(iter.next());
                toRemove--;
            }
            removeKeys.forEach(MonitorWorldRenderer::discardMonitorGroupCache);
        }
        if (cssRenderers.size() > 64) {
            List<Long> removeKeys = new ArrayList<>();
            var iter2 = cssRenderers.keySet().iterator();
            int toRemove2 = cssRenderers.size() - 64;
            while (iter2.hasNext() && toRemove2 > 0) {
                removeKeys.add(iter2.next());
                toRemove2--;
            }
            removeKeys.forEach(MonitorWorldRenderer::discardMonitorGroupCache);
        }
        // symRenderers も同様に上限制限 (路線記号アイコン用 CSSWorldRenderer のリーク防止)
        if (symRenderers.size() > 64) {
            var iter3 = symRenderers.entrySet().iterator();
            int toRemove3 = symRenderers.size() - 64;
            while (iter3.hasNext() && toRemove3 > 0) {
                iter3.next();
                iter3.remove();
                toRemove3--;
            }
        }
    }

    private static void renderFace(PoseStack poseStack, MultiBufferSource bufferSource,
                                    Vec3 cam, BlockPos pos, Direction facing,
                                    int monitorW, int monitorH,
                                    RailwayManagementBlockEntity manager, boolean isBack,
                                    int trackNumber, int trackFontSize, int trackPosition,
                                    int clockVisible, int clockFontSize, float zOffset) {
        if (monitorW <= 0 || monitorH <= 0) return;

        poseStack.pushPose();

        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);

        poseStack.translate(0.5, 0.5, 0.5);
        float rotation = switch (facing) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> -90f;
            case EAST -> 90f;
            default -> 0f;
        };
        if (isBack) rotation += 180f;
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        poseStack.translate(-0.5, -0.5, zOffset);

        int cssW = monitorW * 128;
        int cssH = monitorH * 128;
        float scale = 1.0f / 128.0f;

        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0, cssH, 0);
        poseStack.scale(1, -1, -1);

        long posKey = pos.asLong();
        MonitorAnimState animState = animStates.computeIfAbsent(posKey, k -> new MonitorAnimState());
        CSSWorldRenderer groupRenderer = cssRenderers.computeIfAbsent(posKey,
                k -> new CSSWorldRenderer(Minecraft.getInstance().font));

        updateAnimState(animState, manager);

        long currentTick = 0;
        long dayTime = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            currentTick = mc.level.getGameTime();
            dayTime = mc.level.getDayTime();
        }
        String clockText = formatDayTime(dayTime);

        // 構造的な要素数を計算 (= IR の slot 数)
        int arrCount = manager != null
                ? Math.min(manager.getArrivedTrains().size(), MonitorLayoutConstants.MAX_ARRIVED)
                : 0;
        int depArrCount = Math.min(animState.departedTrains.size(), MonitorLayoutConstants.MAX_DEP_ARRIVED);
        // next 列車数 (pagination 後の visible 数を計算)
        int nxtCount;
        int depNxtCount = Math.min(animState.departedNextTrains.size(), MonitorLayoutConstants.MAX_DEP_NEXT);
        if (manager != null) {
            var nextList = manager.getNextTrains();
            int blocks = Math.max(1, cssH / 128);
            int maxVisible = Math.max(1, 2 * Math.max(1, blocks - 1));
            boolean hasApp = false;
            for (var nt : nextList) { if (nt.isApproaching()) { hasApp = true; break; } }
            if (hasApp) {
                nxtCount = 1;
            } else {
                int totalPages = Math.max(1, (nextList.size() + maxVisible - 1) / maxVisible);
                int page = animState.nextTrainPage >= totalPages ? 0 : animState.nextTrainPage;
                int start = page * maxVisible;
                int end = Math.min(start + maxVisible, nextList.size());
                nxtCount = Math.max(0, end - start);
            }
            nxtCount = Math.min(nxtCount, MonitorLayoutConstants.MAX_NEXT);
        } else {
            nxtCount = 0;
        }

        long irKey = (((long) (isBack ? 1 : 0)) << 1) ^ posKey;
        boolean enabled = manager != null && manager.isMonitorEnabled();
        boolean hasSymbol = manager != null && manager.getAssignedLineSymbol() != null;
        IrNode sharedIr = getSharedMonitorIr();

        // Per-frame handler state 更新 (IR は static、 dynamic data は全て handler 経由)
        MonitorMainHandler handler = monitorHandlers.computeIfAbsent(irKey, k -> new MonitorMainHandler());
        handler.slideDistance = cssW;
        handler.clockText = clockText;
        handler.currentTick = currentTick;
        handler.cssW = cssW;
        handler.cssH = cssH;
        handler.trackNumber = trackNumber;
        handler.trackFontSize = trackFontSize;
        handler.trackPosition = trackPosition;
        handler.clockVisibleVal = clockVisible;
        handler.clockFontSize = clockFontSize;
        handler.hasSymbol = hasSymbol;
        handler.monitorEnabled = enabled;
        handler.isBack = isBack;
        handler.manager = manager;
        if (manager != null) {
            handler.arrived = manager.getArrivedTrains();
            handler.stationNameOrNull = manager.getLinkedStationName();
        } else {
            handler.arrived = java.util.Collections.emptyList();
            handler.stationNameOrNull = null;
        }
        handler.newTrainIds = new HashSet<>(animState.newTrains);
        handler.newNextNames = new HashSet<>(animState.newNextTrains);
        List<CachedTrain> depArrList = new ArrayList<>();
        for (UUID id : animState.departedTrains) {
            CachedTrain ct = animState.departedTrainCache.get(id);
            if (ct != null) depArrList.add(ct);
            if (depArrList.size() >= MonitorLayoutConstants.MAX_DEP_ARRIVED) break;
        }
        handler.departedArrived = depArrList;
        List<String> depNextList = new ArrayList<>(animState.departedNextTrains);
        if (depNextList.size() > MonitorLayoutConstants.MAX_DEP_NEXT) {
            depNextList = depNextList.subList(0, MonitorLayoutConstants.MAX_DEP_NEXT);
        }
        handler.departedNext = depNextList;
        handler.pageSlideOut = animState.pageSlideOut;

        // Next-train pagination — 元 MonitorLayoutConstants.buildNextSection 内ロジックを移植。
        if (manager != null) {
            var next = manager.getNextTrains();
            int blocks = Math.max(1, cssH / 128);
            int maxVisible = Math.max(1, 2 * Math.max(1, blocks - 1));
            boolean hasApproaching = false;
            int approachingIdx = -1;
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).isApproaching()) {
                    hasApproaching = true;
                    approachingIdx = i;
                    break;
                }
            }
            List<RailwayManagementBlockEntity.NextTrain> visible;
            if (hasApproaching) {
                animState.nextTrainPinned = true;
                visible = List.of(next.get(approachingIdx));
            } else {
                animState.nextTrainPinned = false;
                int totalPages = Math.max(1, (next.size() + maxVisible - 1) / maxVisible);
                if (animState.nextTrainPage >= totalPages) animState.nextTrainPage = 0;
                int start = animState.nextTrainPage * maxVisible;
                int end = Math.min(start + maxVisible, next.size());
                visible = start < end ? next.subList(start, end) : List.of();
            }
            if (visible.size() > MonitorLayoutConstants.MAX_NEXT) {
                visible = visible.subList(0, MonitorLayoutConstants.MAX_NEXT);
            }
            handler.nextVisible = visible;
        } else {
            handler.nextVisible = java.util.Collections.emptyList();
        }
        handler.arrEmpty = handler.arrived.isEmpty() && handler.departedArrived.isEmpty();
        handler.nextEmpty = handler.nextVisible.isEmpty() && handler.departedNext.isEmpty();

        groupRenderer.renderV3FromIr(sharedIr, handler, poseStack, bufferSource);

        // 路線記号パネル（CSSWorldRenderer 非発光モードで描画）
        if (manager != null && manager.getAssignedLineSymbol() != null) {
            renderLineSymbolPanel(posKey, manager, cssW, cssH, trackNumber, trackPosition, poseStack, bufferSource);
        }

        poseStack.popPose();
        poseStack.popPose();
    }


    // 路線記号用の非発光CSSWorldRendererキャッシュ
    private static final java.util.Map<Long, CSSWorldRenderer> symRenderers = new java.util.HashMap<>();

    /** 路線記号パネルをCSSWorldRenderer (非発光モード) で描画。
     *  GUIと同じ見た目: 白背景・色付きふち・テキスト・区切り線。発光なし。
     *  trackPosition (0=左, 1=右) に応じてアイコン位置を反転する。 */
    private static void renderLineSymbolPanel(long posKey, RailwayManagementBlockEntity manager,
                                               int cssW, int cssH, int trackNumber, int trackPosition,
                                               PoseStack poseStack, MultiBufferSource bufferSource) {
        var sym = manager.getAssignedLineSymbol();
        if (sym == null) return;

        // 非発光CSSWorldRenderer
        CSSWorldRenderer symRenderer = symRenderers.computeIfAbsent(posKey,
                k -> { var r = new CSSWorldRenderer(Minecraft.getInstance().font); r.setEmissive(false); return r; });

        int panelW = 128;
        int iconSize = 36;
        int borderW = 3;
        int fontSize = 9;
        int divW = 20;
        int divH = 2;
        int spacerH = 6; // テキスト間のレイアウト確保用 (区切り線+上下余白)

        float posX, posY;
        int renderW = iconSize + borderW * 2 + 4;
        int renderH = iconSize + borderW * 2 + 4;
        if (trackNumber > 0) {
            if (trackPosition == 1) {
                posX = cssW - 129;
            } else {
                posX = panelW - iconSize - borderW * 2 + 1;
            }
            posY = 40;
        } else {
            posX = (panelW - renderW) / 2f;
            posY = 40;
        }

        // border-radius は border-box (= iconSize + borderW*2) に適用される。
        int totalSize = iconSize + borderW * 2;
        int radiusPx = Math.round(sym.getBorderRadius() * totalSize / 40f);

        // Phase 5f++++ Step 12.F: 真の V3 IR-native — Element を経由せず直接 IrBuilder で構築。
        // flex centering を IR で表現する代わりに、 icon を中央位置に直接配置する。
        int iconLocalX = (renderW - totalSize) / 2;
        int iconLocalY = (renderH - totalSize) / 2;
        belugalab.mcss3.ir.IrNode panelIr = belugalab.mcss3.ir.IrBuilder.div()
                .addClass("sym-root")
                .rect(0, 0, renderW, renderH)
                .child(belugalab.mcss3.ir.IrBuilder.div()
                        .addClass("sym-icon")
                        .rect(iconLocalX, iconLocalY, totalSize, totalSize)
                        .bgColor(0xFFFFFFFF)
                        .borderRadius(radiusPx))
                .build();

        poseStack.pushPose();
        poseStack.translate(posX, posY, 0.04f);
        // Z軸を再反転して、CSSWorldRendererの内部z順序 (bg=0.01<border=0.02<text=0.03) が
        // translucentの深度ソート (奥→手前) で正しい順序になるようにする
        poseStack.scale(1, 1, -1);
        symRenderer.renderV3FromIr(panelIr, null, poseStack, bufferSource);

        // テキストと区切り線をGUI実装と同じ位置で手動描画 (z-fighting回避)
        // iconLocalX/Y は IR build 時に計算済 (line 546-547) を再利用
        // GUI: midY = y + size/2; letterY = midY - fontH; numY = midY + 2;
        float midY = iconLocalY + borderW + iconSize / 2f;
        float letterY = midY - fontSize;
        float numY = midY + 2;
        float iconCenterX = iconLocalX + borderW + iconSize / 2f;
        int textColor = 0xFF000000;
        int borderColorInt = parseHexColor(sym.getBorderColor(), 0xFF4fc3f7);
        float textZ = 0.05f;

        var font = Minecraft.getInstance().font;
        String letters = sym.getLetters();
        String numStr = sym.getNumberStr();
        int lettersW = font.width(letters);
        int numW = font.width(numStr);

        // 区切り線（midY ～ midY+divH）
        var vcDiv = belugalab.mcss3.draw.VectorRenderer.getWorldBufferText(bufferSource);
        float divLocalX = iconLocalX + borderW + (iconSize - divW) / 2f;
        belugalab.mcss3.draw.VectorRenderer.textFillRect(vcDiv, poseStack.last().pose(),
                divLocalX, midY, divW, divH, borderColorInt, textZ);

        // 縁色枠: V3 IR の border stroke は world で角丸コーナーが欠けるため、 他パネル枠 (isShowBorder)
        // と同じ VectorRenderer.strokeRoundedRect で描画する (非発光 text バッファ)。
        belugalab.mcss3.draw.VectorRenderer.strokeRoundedRect(vcDiv, poseStack.last().pose(),
                iconLocalX, iconLocalY, totalSize, totalSize, borderColorInt, (float) borderW, (float) radiusPx, textZ);

        // テキスト描画 (z=textZでprivate translate)
        poseStack.pushPose();
        poseStack.translate(0, 0, textZ);
        font.drawInBatch(letters, iconCenterX - lettersW / 2f, letterY, textColor, false,
                poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0xF000F0);
        font.drawInBatch(numStr, iconCenterX - numW / 2f, numY, textColor, false,
                poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0xF000F0);
        poseStack.popPose();

        poseStack.popPose();
    }


    /** 路線記号をベクターで描画 */
    private static void renderLineSymbol(PoseStack poseStack, MultiBufferSource bufferSource,
                                          RailwayManagementBlockEntity manager, int cssW, int cssH) {
        // assignedLineSymbol（管理コンピューターから割り当て）を優先
        var assigned = manager.getAssignedLineSymbol();
        String letters;
        int number;
        String colorHex;
        float size;
        int position;
        int borderRadius;
        if (assigned != null) {
            letters = assigned.getLetters();
            number = assigned.getNumber();
            colorHex = assigned.getBorderColor();
            size = manager.getLineSymbolSize();
            position = manager.getLineSymbolPosition();
            borderRadius = assigned.getBorderRadius();
        } else {
            letters = manager.getLineSymbolLetters();
            number = manager.getLineSymbolNumber();
            colorHex = manager.getLineSymbolBorderColor();
            size = manager.getLineSymbolSize();
            position = manager.getLineSymbolPosition();
            borderRadius = 12; // デフォルト
        }

        int borderColor = parseHexColor(colorHex, 0xFF4fc3f7);
        float halfSize = 7 * size; // コンパクトサイズ

        // 配置位置（ベゼル内側、コンテンツと被らない位置）
        float cx, cy;
        float marginX = halfSize + 12;
        float marginY = halfSize + 12;
        switch (position) {
            case 1 -> { cx = cssW - marginX; cy = marginY; }           // 右上
            case 2 -> { cx = marginX; cy = cssH - marginY; }           // 左下
            case 3 -> { cx = cssW - marginX; cy = cssH - marginY; }    // 右下
            default -> { cx = marginX; cy = marginY; }                  // 左上
        }

        var vc = VectorRenderer.getWorldBuffer(bufferSource); // 発光バッファ（モニターと同じ）
        var matrix = poseStack.last().pose();
        float z = 0.03f;

        // 背景色（発光を抑えるため半透明+暗めの白）
        int bgColor = 0xBBCCCCCC;
        boolean isCircle = borderRadius >= 20;
        if (isCircle) {
            VectorRenderer.fillCircle(vc, matrix, cx, cy, halfSize, borderColor, 16, z);
            VectorRenderer.fillCircle(vc, matrix, cx, cy, halfSize * 0.80f, bgColor, 16, z + 0.01f);
        } else {
            float x1 = cx - halfSize, y1 = cy - halfSize;
            float w = halfSize * 2, h = halfSize * 2;
            float bw = halfSize * 0.18f;
            float r = borderRadius * halfSize / 20f;
            VectorRenderer.fillRoundedRect(vc, matrix, x1, y1, w, h, r, borderColor, z);
            VectorRenderer.fillRoundedRect(vc, matrix, x1 + bw, y1 + bw, w - bw * 2, h - bw * 2, Math.max(0, r - bw), bgColor, z + 0.01f);
        }

        // 区切り線
        float lineW = Math.max(1f, halfSize * 0.06f);
        VectorRenderer.drawLine(vc, matrix, cx - halfSize * 0.55f, cy, cx + halfSize * 0.55f, cy, borderColor, lineW, z + 0.02f);

        // テキスト（font.drawInBatch）
        var font = Minecraft.getInstance().font;
        float textScale = halfSize / 12f;
        poseStack.pushPose();
        float letterY = cy - halfSize * 0.45f;
        float letterW = font.width(letters) * textScale;
        poseStack.translate(cx - letterW / 2f, letterY, z + 0.03f);
        poseStack.scale(textScale, textScale, 1);
        font.drawInBatch(letters, 0, 0, 0xFF000000, false, poseStack.last().pose(),
                bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0xF000F0);
        poseStack.popPose();

        String numStr = String.format("%02d", number);
        poseStack.pushPose();
        float numY = cy + halfSize * 0.05f;
        float numW = font.width(numStr) * textScale;
        poseStack.translate(cx - numW / 2f, numY, z + 0.03f);
        poseStack.scale(textScale, textScale, 1);
        font.drawInBatch(numStr, 0, 0, 0xFF000000, false, poseStack.last().pose(),
                bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0xF000F0);
        poseStack.popPose();
    }

    private static void updateAnimState(MonitorAnimState animState,
                                         RailwayManagementBlockEntity manager) {
        if (manager == null) return;

        var arrived = manager.getArrivedTrains();
        var next = manager.getNextTrains();

        Set<UUID> currentIds = new HashSet<>();
        for (var t : arrived) currentIds.add(t.id());
        Set<String> currentNextNames = new HashSet<>();
        for (var nt : next) currentNextNames.add(nt.name());

        if (!animState.initialized) {
            animState.initialized = true;
            animState.knownTrains.addAll(currentIds);
            animState.knownNextTrains.addAll(currentNextNames);
            for (var t : arrived) {
                animState.arrivedCache.put(t.id(), new CachedTrain(
                        t.name(), t.carriageCount(), t.destination(),
                        t.routeType(), t.arrivalDayTime(), t.scheduledStopSec()));
            }
            return;
        }

        // 出発した列車を検知（表示持続時間が経過したらクリア）
        long now2 = System.nanoTime();
        boolean hasNewDepartures = false;
        for (UUID id : animState.knownTrains) {
            if (!currentIds.contains(id)) {
                if (!animState.departedTrains.contains(id)) {
                    animState.departedTrains.add(id);
                    CachedTrain cached = animState.arrivedCache.get(id);
                    if (cached != null) animState.departedTrainCache.put(id, cached);
                    hasNewDepartures = true;
                }
            }
        }
        for (String name : animState.knownNextTrains) {
            if (!currentNextNames.contains(name) && !animState.departedNextTrains.contains(name)) {
                animState.departedNextTrains.add(name);
                hasNewDepartures = true;
            }
        }
        if (hasNewDepartures) {
            animState.departedTimestampNano = now2;
        }
        // 持続時間経過後にクリア
        if (!animState.departedTrains.isEmpty() && (now2 - animState.departedTimestampNano) > DEPARTED_DISPLAY_DURATION_NS) {
            animState.departedTrains.clear();
            animState.departedTrainCache.clear();
            animState.departedNextTrains.clear();
        }

        // 新着列車を検知。 V3 では AnimationNode.fill-mode forwards により
        // 完了後も transform が hold される + clock/ETA を hash から除外したため、
        // newTrains が永続化されていても animation は 1 回しか発火しない (= IR identity 安定)。
        for (UUID id : currentIds) {
            if (!animState.knownTrains.contains(id)) {
                animState.newTrains.add(id);
            }
        }
        animState.newTrains.retainAll(currentIds);

        for (String name : currentNextNames) {
            if (!animState.knownNextTrains.contains(name)) {
                animState.newNextTrains.add(name);
            }
        }
        animState.newNextTrains.retainAll(currentNextNames);

        // ページローテーション管理
        long now = System.nanoTime();
        animState.pageSlideOut = false;
        if (!next.isEmpty()) {
            int approxRowHeight = 32;
            int headerOverhead = 80;
            int maxVisible = Math.max(1, (128 - headerOverhead) / approxRowHeight);
            int totalPages = (next.size() + maxVisible - 1) / maxVisible;
            if (totalPages > 1 && !animState.nextTrainPinned) {
                long elapsed = now - animState.nextTrainPageChangeNano;
                if (elapsed >= PAGE_ROTATE_INTERVAL_NS) {
                    animState.nextTrainPage = (animState.nextTrainPage + 1) % totalPages;
                    animState.nextTrainPageChangeNano = now;
                    animState.pageSlideOut = true;
                }
            }
        }

        // known setsを更新
        animState.knownTrains.clear();
        animState.knownTrains.addAll(currentIds);
        animState.knownNextTrains.clear();
        animState.knownNextTrains.addAll(currentNextNames);

        // arrivedキャッシュ更新
        animState.arrivedCache.clear();
        for (var t : arrived) {
            animState.arrivedCache.put(t.id(), new CachedTrain(
                    t.name(), t.carriageCount(), t.destination(),
                    t.routeType(), t.arrivalDayTime(), t.scheduledStopSec()));
        }
    }


    private static List<BlockPos> findConnectedMonitors(Level level, BlockPos start, Direction facing) {
        List<BlockPos> connected = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            BlockState state = level.getBlockState(current);
            if (!MonitorBlock.isMonitorBlock(state)) continue;
            if (state.getValue(MonitorBlock.FACING) != facing) continue;

            connected.add(current);
            queue.add(current.above());
            queue.add(current.below());
            queue.add(current.relative(facing.getClockWise()));
            queue.add(current.relative(facing.getCounterClockWise()));
        }
        return connected;
    }

    private static RailwayManagementBlockEntity getLinkedManager(Level level, List<BlockPos> group) {
        for (BlockPos pos : group) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof MonitorBlockEntity monitor)) continue;

            BlockPos rmPos = monitor.getLinkedRailwayManagerPos();
            if (rmPos != null) {
                BlockEntity rmBE = level.getBlockEntity(rmPos);
                if (rmBE instanceof RailwayManagementBlockEntity rm) return rm;
            }
            if (monitor.getControllerPos() != null) {
                BlockEntity ctrlBE = level.getBlockEntity(monitor.getControllerPos());
                if (ctrlBE instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity computer) {
                    BlockPos managerPos = computer.getLinkedRailwayManagerPos();
                    if (managerPos != null) {
                        BlockEntity mBE = level.getBlockEntity(managerPos);
                        if (mBE instanceof RailwayManagementBlockEntity rm) return rm;
                    }
                }
            }
        }
        return null;
    }

    /** 管理コンピューターを探す */
    private static ManagementComputerBlockEntity getLinkedComputer(Level level, List<BlockPos> group) {
        for (BlockPos pos : group) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof MonitorBlockEntity monitor)) continue;
            if (monitor.getControllerPos() != null) {
                BlockEntity ctrlBE = level.getBlockEntity(monitor.getControllerPos());
                if (ctrlBE instanceof ManagementComputerBlockEntity computer) {
                    return computer;
                }
            }
        }
        return null;
    }

    /** 管理コンピューターレイアウトに基づくモニター描画 */
    private static final Map<String, CSSWorldRenderer> layoutRenderers = new HashMap<>();
    private static final Map<String, Integer> layoutLastPage = new HashMap<>();
    private static final ComputerLayoutHandler computerHandler = new ComputerLayoutHandler();
    /** Per-panel-type static IR template cache (JSON load 1 度のみ、 全 monitor で共有)。 */
    private static final Map<com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType, IrNode> computerIrCache =
            new java.util.EnumMap<>(com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.class);

    private static IrNode getComputerIr(com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType type) {
        if (type == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP) return null;
        IrNode cached = computerIrCache.get(type);
        if (cached != null) return cached;
        String path = switch (type) {
            case STATION_COUNT, TRAIN_COUNT -> "layouts/renderers/computer-panel-stat-count.json";
            case SIGNAL_COUNT -> "layouts/renderers/computer-panel-signal-count.json";
            case CLOCK -> "layouts/renderers/computer-panel-clock.json";
            case TRAIN_LIST -> "layouts/renderers/computer-panel-train-list.json";
            case SCHEDULE -> "layouts/renderers/computer-panel-schedule.json";
            default -> null;
        };
        if (path == null) return null;
        String json = com.trainsystemutilities.client.gui.TsuLayouts.load(path);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        IrNode ir = belugalab.mcss3.ir.compiler.JsonToIrCompiler.compile(root).root();
        computerIrCache.put(type, ir);
        return ir;
    }

    private static void renderComputerLayout(PoseStack poseStack, MultiBufferSource bufferSource,
                                              Vec3 cam, BlockPos pos, Direction facing,
                                              int monitorW, int monitorH,
                                              ManagementComputerBlockEntity computer,
                                              boolean isBack, float zOffset, Level level) {
        int cssW = monitorW * 128;
        int cssH = monitorH * 128;

        poseStack.pushPose();
        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        poseStack.translate(0.5, 0.5, 0.5);
        float rotation = switch (facing) {
            case NORTH -> 180f; case SOUTH -> 0f; case WEST -> -90f; case EAST -> 90f; default -> 0f;
        };
        if (isBack) rotation += 180f;
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotation));
        poseStack.translate(-0.5, -0.5, zOffset);

        float scale = 1.0f / 128.0f;
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0, cssH, 0);
        poseStack.scale(1, -1, -1);

        // ネットワークデータ取得: サーバー権威化 (MP 対応)。 screen (refreshNetworkData) と同じく、
        // server が updateNetworkCache() で毎秒 scan → sendBlockUpdated 同期した BE cache を読む。
        // dedicated server では client 側 scan (Create RAILWAYS/TrackGraph) が不完全で
        // map / 列車一覧 / 時刻表が空になるため。 cache が空のときのみ旧 client scan に fallback。
        com.trainsystemutilities.network.TrackNetworkScanner.NetworkData netData =
                netDataFromComputerCache(computer);
        if (netData == null) {
            BlockPos scanPos = computer.getLinkedTrackNetworkPos();
            if (scanPos == null) scanPos = computer.getLinkedRailwayManagerPos();
            netData = getNetworkDataCached(level, scanPos);
        }

        // staticキャッシュ優先（BlockEntity NBT同期の問題を回避）
        var screenCache = com.trainsystemutilities.client.gui.MonitorClientCache.layoutCache;
        var panels = screenCache.containsKey(computer.getBlockPos()) && !screenCache.get(computer.getBlockPos()).isEmpty()
                ? screenCache.get(computer.getBlockPos())
                : computer.getMonitorLayout();
        String cBorder = computer.getColorOrDefault("panelBorder", "#2A5570");

        // === 各パネルをposeStack.translateで独立配置（marginズレ完全回避） ===
        for (int i = 0; i < panels.size(); i++) {
            var panel = panels.get(i);
            int px = (int)(panel.getX() * cssW);
            int py = (int)(panel.getY() * cssH);
            int pw = (int)(panel.getWidth() * cssW);
            int ph = (int)(panel.getHeight() * cssH);
            if (pw <= 0 || ph <= 0) continue;

            String key = pos.asLong() + (isBack ? "b" : "f") + i;
            CSSWorldRenderer renderer = layoutRenderers.computeIfAbsent(key,
                    k -> new CSSWorldRenderer(Minecraft.getInstance().font));

            var ptype = panel.getType();
            IrNode panelIr = getComputerIr(ptype);
            if (panelIr == null) {
                // ROUTE_MAP は ベクター 描画のみ。下の overlay path に進む。
                if (panel.getType() != com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP) {
                    continue;
                }
            }

            // 計算: fs / gap / pad / clockFs (= panel ごとの動的 geometry)
            int estimatedLines = 1;
            if (netData != null) {
                switch (panel.getType()) {
                    case TRAIN_LIST -> estimatedLines = 2 + netData.trains().size() * 3;
                    case SCHEDULE -> {
                        estimatedLines = 1;
                        for (var t : netData.trains()) { if (t.id() != null) estimatedLines += 6; }
                    }
                    case SIGNAL_COUNT -> estimatedLines = 3;
                    case CLOCK -> estimatedLines = 1;
                    default -> estimatedLines = 2;
                }
            }
            int maxFsByHeight = estimatedLines > 0 ? (ph - 8) / estimatedLines : ph / 2;
            // 自動文字サイズ: パネル短辺基準 (128 css px = 1 block)。 glyph が実際に拡大される
            // ようになったため、 旧 auto ((ph-8)/行数 ∧ pw/10) は大型モニターで 50-60px になり
            // 不自然に巨大だった。 9 + 短辺/28 を 9..26 に clamp し、 行数 fit を上限とする。
            int autoBase = Math.max(9, Math.min(26, 9 + Math.min(pw, ph) / 28));
            int fs = panel.getFontSize() > 0 ? panel.getFontSize()
                    : Math.min(autoBase, Math.max(7, maxFsByHeight));
            if (panel.getFontSize() > 0) {
                // 明示 fontSize もパネル高に収まる範囲へ clamp
                // (= 大きすぎる指定でカード/行がパネル枠外へはみ出すのを防止)
                fs = Math.max(7, Math.min(fs, Math.max(7, maxFsByHeight)));
            }
            int gap = Math.max(1, fs / 4);
            int borderPad = panel.isShowBorder() ? (int) Math.max(3, Math.min(pw, ph) * 0.02f) + 1 : 0;
            int pad = Math.max(2, fs / 3) + borderPad;
            // 時計: 明示 fontSize はそのまま (旧: max(fs, auto) で明示値が負けて無効だった)。
            // auto は autoBase の 3 倍を上限に幅/高で clamp (旧 pw/4 直は大型で巨大化)。
            int clockFs = panel.getFontSize() > 0 ? fs
                    : Math.max(fs, Math.min(ph - 10, Math.min(pw / 4, autoBase * 3)));

            int trainListPage = 0;
            boolean trainListSlideIn = false;
            if (ptype == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST
                    && netData != null && !netData.trains().isEmpty()) {
                int cardH = fs * 3 + gap * 2 + pad * 2;
                int maxVisible = Math.max(1, (ph - fs * 2) / Math.max(1, cardH));
                int totalPages = Math.max(1, (netData.trains().size() + maxVisible - 1) / maxVisible);
                trainListPage = (int)((System.currentTimeMillis() / 5000) % totalPages);
                if (totalPages > 1) {
                    Integer prev = layoutLastPage.get(key);
                    if (prev != null && prev != trainListPage) trainListSlideIn = true;
                    layoutLastPage.put(key, trainListPage);
                }
            }

            int schedulePage = 0;
            boolean scheduleSlideIn = false;
            if (ptype == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SCHEDULE
                    && netData != null && !netData.trains().isEmpty()) {
                var validTrains = netData.trains().stream().filter(t -> t.id() != null).toList();
                if (!validTrains.isEmpty()) {
                    schedulePage = (int)((System.currentTimeMillis() / 10000) % validTrains.size());
                    if (validTrains.size() > 1) {
                        String schedKey = key + "_sc";
                        Integer prev = layoutLastPage.get(schedKey);
                        if (prev != null && prev != schedulePage) scheduleSlideIn = true;
                        layoutLastPage.put(schedKey, schedulePage);
                    }
                }
            }

            // 白枠はみ出し診断 (5s throttle、 原因確定後に撤去): TRAIN_LIST の geometry を丸ごと出す
            if (ptype == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST
                    && System.nanoTime() - lastTrainListDiagNano > 5_000_000_000L) {
                lastTrainListDiagNano = System.nanoTime();
                TrainSystemUtilities.LOGGER.info(
                        "[MonitorDiag] TRAIN_LIST px={} py={} pw={} ph={} fs={} gap={} pad={} cardH={} contentW={} trains={} page={} slideIn={} isBack={}",
                        px, py, pw, ph, fs, gap, pad,
                        fs * 3 + gap * 2 + pad * 2, Math.max(0, pw - pad * 2),
                        netData == null ? -1 : netData.trains().size(),
                        trainListPage, trainListSlideIn, isBack);
            }

            if (panelIr == null) {
                // ROUTE_MAP: ベクター 描画 only。 IR レンダリングは skip。
                poseStack.pushPose();
                poseStack.translate(px, py, 0);
            } else {
                computerHandler.updateForPanel(panel, computer, netData, level,
                        pw, ph, fs, gap, pad, clockFs,
                        trainListPage, trainListSlideIn, schedulePage, scheduleSlideIn);
                poseStack.pushPose();
                poseStack.translate(px, py, 0);
                renderer.renderV3FromIr(panelIr, computerHandler, poseStack, bufferSource);
            }

            // 枠線はVectorRendererでZ=0角丸太枠描画
            // text バッファ (RenderType.text) を使用 — emissive バッファは atlas 干渉で
            // 線が赤味がかる既知の問題があるため。
            if (panel.isShowBorder()) {
                var vc2 = VectorRenderer.getWorldBufferText(bufferSource);
                var m2 = poseStack.last().pose();
                float borderW = Math.max(3f, Math.min(pw, ph) * 0.02f); // パネルサイズに応じた太さ
                float borderR = Math.max(4f, Math.min(pw, ph) * 0.03f); // 角丸半径
                int borderColor = parseHexColor(cBorder, 0xFF2A5570);
                if (TSU_COLOR_DIAG) {
                    com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TSU-COLOR] panel border: cBorder='" + cBorder
                            + "' parsed=0x" + Integer.toHexString(borderColor)
                            + " pw=" + pw + " ph=" + ph);
                }
                VectorRenderer.strokeRoundedRect(vc2, m2, 0, 0, pw, ph, borderColor, borderW, borderR, 0);
            }

            // 路線マップはベクター描画（CSS描画後にオーバーレイ）
            if (panel.getType() == com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP && netData != null) {
                float mapX = 5;
                float mapY2 = 5;
                float mapW = pw - 10;
                float mapH = ph - 10;
                // 路線マップのサイズ（autoはパネルサイズに対して控えめ）
                int mapBase = Math.max(6, Math.min(ph / 15, pw / 20));
                int mapFontSize = panel.getMapTextSize() > 0 ? panel.getMapTextSize() : mapBase;
                int trainIcon = panel.getTrainIconSize() > 0 ? panel.getTrainIconSize() : Math.max(2, mapBase / 2);
                int stationIcon = panel.getStationIconSize() > 0 ? panel.getStationIconSize() : Math.max(2, mapBase / 2);
                int signalIcon = panel.getSignalIconSize() > 0 ? panel.getSignalIconSize() : Math.max(1, mapBase / 3);
                if (mapW > 0 && mapH > 0) {
                    int cMapLine = parseHexColor(computer.getColorOrDefault("mapLine", "#3A5068"), 0xFF3A5068);
                    int cMapStation = parseHexColor(computer.getColorOrDefault("mapStation", "#2A7A9C"), 0xFF2A7A9C);
                    int cMapTrain = parseHexColor(computer.getColorOrDefault("mapTrain", "#9A5C00"), 0xFF9A5C00);
                    renderRouteMapOnMonitor(poseStack, bufferSource, netData, mapX, mapY2, mapW, mapH, level,
                            mapFontSize, trainIcon, stationIcon, signalIcon, panel.isMapShowText(),
                            cMapLine, cMapStation, cMapTrain);
                }
            }
            poseStack.popPose();
        }

        poseStack.popPose();
        poseStack.popPose();

        if (layoutRenderers.size() > 64) {
            var it = layoutRenderers.entrySet().iterator();
            int rem = layoutRenderers.size() - 64;
            while (it.hasNext() && rem > 0) { it.next(); it.remove(); rem--; }
            layoutLastPage.keySet().retainAll(layoutRenderers.keySet());
        }
    }

    /** モニター上のベクター路線マップ描画（個別アイコンサイズ対応） */
    private static void renderRouteMapOnMonitor(PoseStack poseStack, MultiBufferSource bufferSource,
                                                 com.trainsystemutilities.network.TrackNetworkScanner.NetworkData data,
                                                 float mapX, float mapY, float mapW, float mapH,
                                                 Level level, int fontSize,
                                                 int trainIconSz, int stationIconSz, int signalIconSz,
                                                 boolean showText,
                                                 int lineColor, int stationColor, int trainColor) {
        var nodes = data.nodes();
        var edges = data.edges();
        if (nodes.isEmpty()) return;

        // ノード範囲→マップ座標変換
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (var n : nodes) {
            minX = Math.min(minX, n.x()); maxX = Math.max(maxX, n.x());
            minZ = Math.min(minZ, n.z()); maxZ = Math.max(maxZ, n.z());
        }
        double rangeX = maxX - minX + 20;
        double rangeZ = maxZ - minZ + 20;
        double cx = (minX + maxX) / 2.0;
        double cz = (minZ + maxZ) / 2.0;
        double zoom = Math.min(mapW / rangeX, mapH / rangeZ);
        float centerSX = mapX + mapW / 2f;
        float centerSY = mapY + mapH / 2f;

        var vc = VectorRenderer.getWorldBufferText(bufferSource); // 路線マップ: text バッファで色再現確保 (translucent emissive は atlas 干渉で赤味)
        var matrix = poseStack.last().pose();
        float lineW = Math.max(1.5f, fontSize / 4f);
        // クリッピング境界
        float clipX1 = mapX, clipY1 = mapY, clipX2 = mapX + mapW, clipY2 = mapY + mapH;

        // エッジ描画（ベクター線、クリッピング付き）
        for (var edge : edges) {
            var pts = edge.points();
            if (pts != null && pts.size() >= 2) {
                for (int j = 0; j < pts.size() - 1; j++) {
                    float x1 = (float)(centerSX + (pts.get(j)[0] - cx) * zoom);
                    float y1 = (float)(centerSY + (pts.get(j)[1] - cz) * zoom);
                    float x2 = (float)(centerSX + (pts.get(j + 1)[0] - cx) * zoom);
                    float y2 = (float)(centerSY + (pts.get(j + 1)[1] - cz) * zoom);
                    // 両端がクリップ外なら描画スキップ
                    if ((x1 < clipX1 && x2 < clipX1) || (x1 > clipX2 && x2 > clipX2)
                            || (y1 < clipY1 && y2 < clipY1) || (y1 > clipY2 && y2 > clipY2)) continue;
                    // 端をクランプ
                    x1 = Math.max(clipX1, Math.min(clipX2, x1));
                    y1 = Math.max(clipY1, Math.min(clipY2, y1));
                    x2 = Math.max(clipX1, Math.min(clipX2, x2));
                    y2 = Math.max(clipY1, Math.min(clipY2, y2));
                    VectorRenderer.drawLine(vc, matrix, x1, y1, x2, y2, lineColor, lineW);
                }
            }
        }

        // 駅アイコン + 駅名テキスト（個別サイズ）
        var font = Minecraft.getInstance().font;
        for (var station : data.stations()) {
            float sx = (float)(centerSX + (station.position().getX() - cx) * zoom);
            float sy = (float)(centerSY + (station.position().getZ() - cz) * zoom);
            if (sx < clipX1 || sx > clipX2 || sy < clipY1 || sy > clipY2) continue;
            VectorRenderer.fillRect(vc, matrix, sx - stationIconSz, sy - stationIconSz, stationIconSz * 2, stationIconSz * 2, stationColor);
            // 駅名テキスト
            if (showText) {
                float textScale = fontSize / 9f;
                int textW = (int)(font.width(station.name()) * textScale);
                boolean textLeft = (sx + stationIconSz + textW + 4) > clipX2;
                poseStack.pushPose();
                if (textLeft) {
                    poseStack.translate(sx - stationIconSz - textW - 2, sy - fontSize / 2f, 0.02f);
                } else {
                    poseStack.translate(sx + stationIconSz + 2, sy - fontSize / 2f, 0.02f);
                }
                poseStack.scale(textScale, textScale, 1);
                font.drawInBatch(station.name(), 0, 0, stationColor, true, poseStack.last().pose(),
                        bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0xF000F0);
                poseStack.popPose();
            }
        }

        // 信号アイコン（クリップ内のみ）
        for (var signal : data.signals()) {
            if (signal.position().equals(BlockPos.ZERO)) continue;
            float sx = (float)(centerSX + (signal.position().getX() - cx) * zoom);
            float sy = (float)(centerSY + (signal.position().getZ() - cz) * zoom);
            if (sx < clipX1 || sx > clipX2 || sy < clipY1 || sy > clipY2) continue;
            int color = switch (signal.state()) {
                case GREEN -> 0xFF2D6B30; case RED -> 0xFF9A2A22;
                case YELLOW -> 0xFF9A7505; case ORANGE -> 0xFF9A5C00;
            };
            VectorRenderer.fillRect(vc, matrix, sx - signalIconSz, sy - signalIconSz, signalIconSz * 2, signalIconSz * 2, color);
        }

        // 列車アイコン（クリップ内のみ、リアルタイム位置、車両数分の連結アイコン）
        for (var train : data.trains()) {
            // 先頭位置と進行方向を取得
            float headSx = 0, headSy = 0;
            float dirX = 0, dirY = 1; // 進行方向（マップ座標系）
            int carriageCount = train.carriageCount();
            boolean posFound = false;
            if (train.id() != null) {
                try {
                    var opt = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(train.id());
                    if (opt.isPresent() && !opt.get().carriages.isEmpty()) {
                        // 先頭車両位置
                        var leadBogey = opt.get().carriages.get(0).leadingBogey();
                        if (leadBogey != null && leadBogey.getAnchorPosition() != null) {
                            var lp = leadBogey.getAnchorPosition();
                            headSx = (float)(centerSX + (lp.x - cx) * zoom);
                            headSy = (float)(centerSY + (lp.z - cz) * zoom);
                            posFound = true;
                            // 末尾車両位置から進行方向を計算
                            if (opt.get().carriages.size() >= 2) {
                                var lastCarriage = opt.get().carriages.get(opt.get().carriages.size() - 1);
                                var tailBogey = lastCarriage.trailingBogey() != null ? lastCarriage.trailingBogey() : lastCarriage.leadingBogey();
                                if (tailBogey != null && tailBogey.getAnchorPosition() != null) {
                                    var tp = tailBogey.getAnchorPosition();
                                    float tailSx = (float)(centerSX + (tp.x - cx) * zoom);
                                    float tailSy = (float)(centerSY + (tp.z - cz) * zoom);
                                    float dx = tailSx - headSx, dy = tailSy - headSy;
                                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                                    if (len > 0.01f) { dirX = dx / len; dirY = dy / len; }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MonitorWorld] op failed", ignored); }
            }
            boolean fallbackMode = false;
            double fbHeadX = 0, fbHeadZ = 0, fbUx = 0, fbUz = 1;
            if (!posFound) {
                // MP fallback: live train が取れない (dedicated server の未ロード列車等)。
                // server broadcast (TrainPositionPayload 1Hz) を lerp 平滑化し、移動ベクトルから
                // heading を導出。 各車両は最寄り線路セグメントへ snap して曲線に沿わせる
                // (= 画面 MapRenderer の fallback と同方式)。
                double tx = train.worldX(), tz = train.worldZ();
                if (train.id() != null) {
                    var live = com.trainsystemutilities.client.transit.TransitTerminalClientCache
                            .trainPositions().get(train.id());
                    if (live != null) { tx = live.x(); tz = live.z(); }
                }
                if (tx == 0 && tz == 0) continue;
                fbHeadX = tx; fbHeadZ = tz;
                double dwx = 0, dwz = 1;
                if (train.id() != null) {
                    double[] sm = fallbackSmoothPos.get(train.id());
                    if (sm == null) sm = new double[]{tx, tz, 0, 1};
                    double nx = sm[0] + (tx - sm[0]) * 0.2;   // 毎フレーム 0.2 ずつ目標へ glide
                    double nz = sm[1] + (tz - sm[1]) * 0.2;
                    double mvx = nx - sm[0], mvz = nz - sm[1];
                    if (mvx * mvx + mvz * mvz > 1e-5) { sm[2] = mvx; sm[3] = mvz; } // 動いた時だけ heading 更新
                    sm[0] = nx; sm[1] = nz;
                    fallbackSmoothPos.put(train.id(), sm);
                    fbHeadX = nx; fbHeadZ = nz; dwx = sm[2]; dwz = sm[3];
                }
                double dl = Math.sqrt(dwx * dwx + dwz * dwz);
                fbUx = dl > 1e-6 ? dwx / dl : 0.0;
                fbUz = dl > 1e-6 ? dwz / dl : 1.0;
                headSx = (float)(centerSX + (fbHeadX - cx) * zoom);
                headSy = (float)(centerSY + (fbHeadZ - cz) * zoom);
                fallbackMode = true;
            }
            if (headSx < clipX1 - trainIconSz * carriageCount || headSx > clipX2 + trainIconSz * carriageCount
                    || headSy < clipY1 - trainIconSz * carriageCount || headSy > clipY2 + trainIconSz * carriageCount) continue;

            // 車両数分のアイコンを進行方向に沿って連ねて描画
            float iconStep = trainIconSz * 1.8f; // アイコン間隔（少し重なるように密着）
            if (fallbackMode) {
                // 各車両を head から heading 逆方向へ world 間隔でずらし、線路セグメントへ snap
                // (= curve でアイコンがパスからはみ出さない)。
                double worldStep = iconStep / Math.max(1e-6, zoom);
                for (int ci = 0; ci < carriageCount; ci++) {
                    double wx2 = fbHeadX - fbUx * worldStep * ci;
                    double wz2 = fbHeadZ - fbUz * worldStep * ci;
                    double[] snap = com.trainsystemutilities.client.gui.MapRenderer
                            .nearestTrackPoint(wx2, wz2, edges, nodes);
                    double px2 = snap != null ? snap[0] : wx2;
                    double pz2 = snap != null ? snap[1] : wz2;
                    float isx = (float)(centerSX + (px2 - cx) * zoom);
                    float isy = (float)(centerSY + (pz2 - cz) * zoom);
                    if (isx < clipX1 || isx > clipX2 || isy < clipY1 || isy > clipY2) continue;
                    VectorRenderer.fillRect(vc, matrix, isx - trainIconSz, isy - trainIconSz + 1, trainIconSz * 2, trainIconSz * 2 - 2, trainColor);
                }
            } else {
                for (int ci = 0; ci < carriageCount; ci++) {
                    float isx = headSx + dirX * iconStep * ci;
                    float isy = headSy + dirY * iconStep * ci;
                    if (isx < clipX1 || isx > clipX2 || isy < clipY1 || isy > clipY2) continue;
                    VectorRenderer.fillRect(vc, matrix, isx - trainIconSz, isy - trainIconSz + 1, trainIconSz * 2, trainIconSz * 2 - 2, trainColor);
                }
            }
            // 列車名テキスト（先頭車両位置に表示）
            if (showText) {
                float ts = fontSize / 9f;
                int tw = (int)(font.width(train.name()) * ts);
                boolean tLeft = (headSx + trainIconSz + tw + 4) > clipX2;
                poseStack.pushPose();
                if (tLeft) {
                    poseStack.translate(headSx - trainIconSz - tw - 2, headSy - fontSize / 2f, 0.02f);
                } else {
                    poseStack.translate(headSx + trainIconSz + 2, headSy - fontSize / 2f, 0.02f);
                }
                poseStack.scale(ts, ts, 1);
                font.drawInBatch(train.name(), 0, 0, trainColor, true, poseStack.last().pose(),
                        bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0xF000F0);
                poseStack.popPose();
            }
        }
    }

    // ベクター描画はMantaのVectorRendererを使用

    /** Helper: manager から色設定を取得 (null 安全)。 */
    private static String getColor(RailwayManagementBlockEntity manager, String key, String defaultHex) {
        if (manager == null) return defaultHex;
        return manager.getColorOrDefault(key, defaultHex);
    }

    /** #RRGGBB形式のHex文字列をARGB intに変換 */
    private static int parseHexColor(String hex, int defaultColor) {
        if (hex == null || hex.isEmpty()) return defaultColor;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) { return defaultColor; }
    }

    private static List<BlockEntity> getMonitorBlockEntities(Level level, BlockPos center, int range) {
        List<BlockEntity> result = new ArrayList<>();
        Set<Long> checked = new HashSet<>();
        for (int x = -range; x <= range; x += 16) {
            for (int z = -range; z <= range; z += 16) {
                BlockPos cp = center.offset(x, 0, z);
                long key = ((long)(cp.getX() >> 4) << 32) | ((long)(cp.getZ() >> 4) & 0xFFFFFFFFL);
                if (!checked.add(key)) continue;
                var chunk = level.getChunkAt(cp);
                if (chunk != null) {
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof MonitorBlockEntity && be.getBlockPos().distSqr(center) < range * range) {
                            result.add(be);
                        }
                    }
                }
            }
        }
        return result;
    }


    private static String formatDayTime(long dayTime) {
        long t = dayTime % 24000;
        int hours = (int)((t / 1000 + 6) % 24);
        int minutes = (int)((t % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }

    private static String getDepartureTime(long arrivalDayTime, int stopSec) {
        return formatDayTime(arrivalDayTime + (long)stopSec * 20);
    }
}
