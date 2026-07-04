package com.trainsystemutilities.contraption;
import belugalab.mcss3.anim.Animation;
import belugalab.mcss3.anim.Easing;
import belugalab.mcss3.ir.IrNode;
import belugalab.mcss3.screen.JsonLayoutHandler;

import belugalab.mcss3.world.CSSWorldRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.schedule.TrainScheduleReader;
import com.trainsystemutilities.block.MonitorBlock;
import com.trainsystemutilities.block.ThinDoubleSidedMonitorBlock;
import com.trainsystemutilities.network.MonitorDisplayInfoPayload;
import com.trainsystemutilities.registry.ModBlocks;
import com.trainsystemutilities.schedule.TrainCouplingManager;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MonitorMovementBehaviour implements MovementBehaviour {

    // アニメーション持続時間はCSS側で管理（@keyframes slide-in 0.4s）

    // === Tier 3a: 描画パイプライン最適化 ===
    /** 距離 culling 閾値: カメラから contraption までこの距離 (squared) を超えたら描画スキップ。 */
    private static final double RENDER_CULL_DISTANCE_SQ = 96 * 96; // 96 blocks
    /**
     * Per-monitor renderer。CSSWorldRenderer は前回の (root, dims, css, anim状態) を覚えて
     * 同じ入力なら resolveAll/layout を skip するキャッシュを持つ。modifier ごとに別インスタンスに
     * することで、front/back 両面が同じ renderer を共有 (= 後者は layout skip でき)、かつ
     * 別 monitor 同士の cache 干渉を防ぐ。
     * キー: "{contraptionEntityId}@{masterPos.asLong()}"
     */
    private static final ConcurrentHashMap<String, CSSWorldRenderer> rendererPerMonitor = new ConcurrentHashMap<>();
    /**
     * Per-monitor IR cache: 内容ハッシュが変わらなければ IrNode をリユース。
     * 同じ IrNode 参照を渡すことで CSSWorldRenderer の RenderGraph キャッシュが有効化される。
     */
    private static final ConcurrentHashMap<String, CachedIr> irCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MonitorMovementHandler> handlersPerMonitor = new ConcurrentHashMap<>();
    // SAME_GROUP_RENDER_GUARD removed — Create invokes renderInContraption multiple times per frame
    // for player-mounted carriages (different render passes). The 2ms guard blocked all but the
    // first call, causing monitors and doors to flicker on the carriage the player rides.
    private static long lastRenderCleanupNanos = 0;

    // === 診断ログ: プレイヤー搭乗時のフリッカー解析用 ===
    /** 直近 1 秒間のレンダー呼び出し数 (per-monitorKey)。 */
    private static final ConcurrentHashMap<String, int[]> renderCallCount = new ConcurrentHashMap<>();
    private static volatile long lastDiagLogNanos = 0L;
    private static final long DIAG_LOG_INTERVAL_NS = 1_000_000_000L; // 1 秒
    /** 1 秒間の描画パス別カウンタ (atomic): [0]=fbo, [1]=replay, [2]=fullRender, [3]=animSkip。 */
    private static final java.util.concurrent.atomic.AtomicIntegerArray pathCount =
            new java.util.concurrent.atomic.AtomicIntegerArray(4);
    private static final java.util.concurrent.atomic.AtomicInteger playerPathFbo = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger playerPathReplay = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger playerPathFull = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger playerPathSkip = new java.util.concurrent.atomic.AtomicInteger();
    /** プレイヤーが contraption の上に立っている (passenger ではない) 状態の描画パス内訳。 */
    private static final java.util.concurrent.atomic.AtomicInteger standPathFbo = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger standPathReplay = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger standPathFull = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger standPathSkip = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger standCullSkip = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger standTotalCalls = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long lastVehicleLogNanos = 0L;
    /** 立ち乗り中の renderInContraption 呼び出し時刻 (per-monitorKey)。フレーム間隔分布を測るため。 */
    private static final ConcurrentHashMap<String, long[]> standFrameTimings = new ConcurrentHashMap<>();
    /** 各 monitorKey の standFrameTimings バッファ書き込み位置。 */
    private static final ConcurrentHashMap<String, int[]> standFrameTimingsIdx = new ConcurrentHashMap<>();
    private static final int STAND_FRAME_BUF_SIZE = 240; // 直近 4 秒分 (60fps想定)
    private static final long RENDER_CLEANUP_INTERVAL_NANOS = 30_000_000_000L; // 30秒

    private static class CachedIr {
        IrNode root;
        int contentHash;
    }

    /** V3 Stable IR: JSON load + JsonToIrCompiler 1 回のみ、 全 monitor で共有。 */
    private static volatile IrNode sharedIr;

    private static IrNode getSharedIr() {
        IrNode ir = sharedIr;
        if (ir == null) {
            synchronized (MonitorMovementBehaviour.class) {
                ir = sharedIr;
                if (ir == null) {
                    String json = com.trainsystemutilities.client.gui.TsuLayouts.load(
                            "layouts/renderers/movement-monitor.json");
                    com.google.gson.JsonObject root =
                            com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    ir = belugalab.mcss3.ir.compiler.JsonToIrCompiler.compile(root).root();
                    sharedIr = ir;
                }
            }
        }
        return ir;
    }

    static final class MonitorMovementHandler implements JsonLayoutHandler {
        int slideDistance = 128;
        int cssW, cssH;
        volatile String clockText = "";
        TrainDisplayInfo info;
        ContraptionAnimState animState;

        void update(int cssW, int cssH, String clockText, TrainDisplayInfo info, ContraptionAnimState animState) {
            this.cssW = cssW;
            this.cssH = cssH;
            this.slideDistance = cssW;
            this.clockText = clockText;
            this.info = info;
            this.animState = animState;
        }

        @Override
        public Integer getDynamicNumber(String[] classes, String key, int def) {
            int contentW = Math.max(0, cssW - 36); // PAD * 2 = 36
            int innerH = Math.max(0, cssH - 36);
            int sectionH = Math.max(20, (innerH - 12 - 2 * 2) / 2); // HEADER_H=12, SECTION_GAP=2
            return switch (key) {
                case "cssW" -> cssW;
                case "cssH" -> cssH;
                case "contentW" -> contentW;
                case "sectionH" -> sectionH;
                default -> null;
            };
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
            if (c == null || info == null) return c == null ? null : ("mc-time".equals(c) ? clockText : null);
            switch (c) {
                case "mc-time": return clockText;
                case "trainType_local": return localizeTrainType("LOCAL");
                case "trainType_rapid": return localizeTrainType("RAPID");
                case "trainType_express": return localizeTrainType("EXPRESS");
                case "routeType": return localizeRouteType(info.routeType());
            }
            // current_* / next_* slot fields (= class name encodes slot + field)
            StopInfo stop = stopFor(c);
            if (stop == null) return null;
            String field = fieldOf(c);
            return switch (field) {
                case "name" -> stop.stationName();
                case "arr" -> stop.estArrivalDayTime() > 0
                        ? trf("tsu.monitor.eta_fmt", formatDayTime(stop.estArrivalDayTime())) : null;
                case "dep" -> stop.estDepartureDayTime() > 0
                        ? trf("tsu.monitor.dep_time_fmt", formatDayTime(stop.estDepartureDayTime())) : null;
                case "stopsec" -> stop.stopSec() > 0
                        ? trf("tsu.monitor.stop_seconds_fmt", stop.stopSec()) : null;
                case "partner" -> {
                    String partner = info.couplingPartner();
                    yield (partner != null && !partner.isEmpty()) ? "↔ " + partner : null;
                }
                default -> null;
            };
        }

        @Override
        public Boolean getDynamicBool(String[] classes, String key, boolean def) {
            if (info == null) return null;
            String tt = info.trainType();
            String rt = info.routeType();
            switch (key) {
                case "trainType_local_visible": return "LOCAL".equals(tt);
                case "trainType_rapid_visible": return "RAPID".equals(tt);
                case "trainType_express_visible": return "EXPRESS".equals(tt);
                case "routeType_visible": return rt != null && !rt.isEmpty();
            }
            StopInfo stop = stopFor(key);
            boolean hasStop = stop != null;
            // current/next 共通フィールド
            if (key.endsWith("_row_visible")) return hasStop;
            if (key.endsWith("_empty_visible")) return !hasStop;
            if (!hasStop) return false;
            String field = fieldOf(key);
            boolean hasCoupling = key.startsWith("current_")
                    && info.couplingStatus() != null && !info.couplingStatus().isEmpty()
                    && stop.atStation();
            boolean isCoupling = hasCoupling && info.couplingStatus().startsWith("COUPLE_");
            return switch (field) {
                case "arr_visible" -> !hasCoupling && !stop.atStation() && stop.estArrivalDayTime() > 0;
                case "dep_visible" -> !hasCoupling && stop.estDepartureDayTime() > 0;
                case "stopsec_visible" -> !hasCoupling && stop.stopSec() > 0;
                case "stopinfo_visible" -> !hasCoupling && stop.atStation();
                case "coupling_visible" -> hasCoupling && isCoupling;
                case "decoupling_visible" -> hasCoupling && !isCoupling;
                case "partner_visible" -> hasCoupling
                        && info.couplingPartner() != null && !info.couplingPartner().isEmpty();
                default -> null;
            };
        }

        @Override
        public Animation getDynamicAnimation(String[] classes, String key) {
            if (animState == null) return null;
            boolean slideIn = false;
            if ("current_anim".equals(key)) slideIn = animState.currentSlideIn;
            else if ("next_anim".equals(key)) slideIn = animState.nextSlideIn;
            else if (classes != null) {
                for (String c : classes) {
                    if ("anim-slide-in".equals(c)) {
                        // legacy: 旧 class-based dispatch
                        return Animation.of(400).easing(Easing.EASE_OUT)
                                .translateX(slideDistance, 0).build();
                    }
                }
            }
            if (!slideIn) return null;
            return Animation.of(400).easing(Easing.EASE_OUT)
                    .translateX(slideDistance, 0).build();
        }

        /** key 先頭 prefix ("current"/"next") から該当 StopInfo を返す。 */
        private StopInfo stopFor(String key) {
            if (info == null || key == null) return null;
            List<StopInfo> stops = info.stops();
            if (key.startsWith("current_")) {
                return stops.isEmpty() ? null : stops.get(0);
            }
            if (key.startsWith("next_")) {
                return stops.size() > 1 ? stops.get(1) : null;
            }
            return null;
        }

        /** key 末尾の field 名 ("current_arr_visible" → "arr_visible") を返す。 */
        private static String fieldOf(String key) {
            int us = key.indexOf('_');
            return us < 0 ? key : key.substring(us + 1);
        }

        private static String tr(String k) {
            return net.minecraft.network.chat.Component.translatable(k).getString();
        }
        private static String trf(String k, Object... args) {
            return net.minecraft.network.chat.Component.translatable(k, args).getString();
        }
        private static String localizeTrainType(String code) {
            return switch (code) {
                case "RAPID" -> tr("tsu.monitor.train_type_rapid");
                case "EXPRESS" -> tr("tsu.monitor.train_type_express");
                case "LOCAL" -> tr("tsu.monitor.train_type_local");
                default -> code == null ? "" : code;
            };
        }
        private static String localizeRouteType(String code) {
            if (code == null || code.isEmpty()) return "";
            return switch (code) {
                case "CIRCULAR" -> tr("tsu.monitor.route_type_circular");
                case "SHUTTLE" -> tr("tsu.monitor.route_type_shuttle");
                default -> code;
            };
        }
        private static String formatDayTime(long dayTime) {
            long t = dayTime % 24000;
            int hours = (int) ((t / 1000 + 6) % 24);
            int minutes = (int) ((t % 1000) * 60 / 1000);
            return String.format("%02d:%02d", hours, minutes);
        }
    }

    // ===== キャッシュ =====
    private static final ConcurrentHashMap<UUID, TrainDisplayInfo> displayCache = new ConcurrentHashMap<>();
    // P0-1 #8: WeakHashMap は thread-safe ではなく、 server tick + render thread からの並行
    // computeIfAbsent / get / put で CME / GC expungeStaleEntries 競合が発生していた。
    // Collections.synchronizedMap でラップしつつ WeakHashMap の auto-purge 特性は保持。
    // 内側の groupCache 値 Map<BlockPos, GroupInfo> も ConcurrentHashMap に変更 (= panel
    // 単位の並行 put が起こり得る)。
    private static final Map<Contraption, Map<BlockPos, GroupInfo>> groupCache =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Contraption, ContraptionAnimState> animStates =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Contraption, UUID> trainIdCache =
            Collections.synchronizedMap(new WeakHashMap<>());
    // 連結/切り離しでTrain IDが変わった場合のリダイレクト (旧ID → 新ID)
    private static final ConcurrentHashMap<UUID, UUID> trainIdRedirects = new ConcurrentHashMap<>();
    // ContraptionEntity ID → Train ID（サーバー/クライアント共有）
    private static final ConcurrentHashMap<Integer, UUID> entityIdToTrainId = new ConcurrentHashMap<>();
    // #7 MP fix: 同一 carriage entity に複数モニターがあっても 1 tick 1 回に集約するための last broadcast tick。
    private static final ConcurrentHashMap<Integer, Long> lastBroadcastTickByEntity = new ConcurrentHashMap<>();

    // ===== データ構造 =====
    private record GroupInfo(BlockPos master, BlockPos frontOrigin, BlockPos backOrigin,
                             int width, int height, boolean hasDoubleSided) {}

    record StopInfo(String stationName, int stopSec, long estArrivalDayTime, long estDepartureDayTime,
                    boolean atStation) {}

    /** 列車表示情報（路線種別・列車種別・連結ステータス含む） */
    record TrainDisplayInfo(List<StopInfo> stops, String routeType, String trainType,
                            String couplingStatus, String couplingPartner) {}

    static class ContraptionAnimState {
        String currentStation = "";
        String nextStation = "";
        // V3: slide-in 状態は AnimationNode fill-mode forwards + IR identity 安定 (clock/ETA を
        // hash から除外) によって、 駅変化があった次の build でだけ animation が発火する。
        boolean currentSlideIn = false;
        boolean nextSlideIn = false;
        boolean initialized = false;
    }

    @Override
    public boolean disableBlockEntityRendering() {
        return false;
    }

    // ===== キャッシュクリーンアップ =====
    private static long lastCleanupTick = 0;

    private static void cleanupStaleEntries(long gameTick) {
        if (gameTick - lastCleanupTick < 200) return; // 10秒ごと
        lastCleanupTick = gameTick;

        var trains = com.simibubi.create.Create.RAILWAYS.trains;
        displayCache.keySet().removeIf(id -> !trains.containsKey(id));
        trainIdRedirects.entrySet().removeIf(e ->
                !trains.containsKey(e.getKey()) && !trains.containsKey(e.getValue()));
        entityIdToTrainId.values().removeIf(id -> !trains.containsKey(id));
        lastBroadcastTickByEntity.keySet().removeIf(id -> !entityIdToTrainId.containsKey(id));
    }

    // ===== サーバー側: データ収集 =====

    @Override
    public void tick(MovementContext context) {
        // DEBUG: tick 処理 (displayCache 更新等) も完全停止して、TSU の処理量が原因か検証。
        if (DEBUG_DISABLE_MONITOR_RENDER) return;
        long profStart = com.trainsystemutilities.profiler.TsuProfiler.start();
        try {
            tickImpl(context);
        } finally {
            com.trainsystemutilities.profiler.TsuProfiler.end(
                    com.trainsystemutilities.profiler.TsuProfiler.Phase.TSU_MONITOR_TICK, profStart);
        }
    }

    private void tickImpl(MovementContext context) {
        if (context.world.isClientSide()) return;
        // Tier 1 改善: 全列車を同 tick で一斉処理せず、entity ID で 5tick に分散。
        // 8.5ms スパイク → 1.7ms × 5tick に平準化、サーバ tick の variance を下げる。
        int offset = context.contraption.entity != null
                ? Math.floorMod(context.contraption.entity.getId(), 5)
                : 0;
        if ((context.world.getGameTime() + offset) % 5 != 0) return;

        cleanupStaleEntries(context.world.getGameTime());

        try {
            Train train = getTrainFromContext(context);
            if (train == null) return;

            // Train IDが変わった場合（連結/切り離し）、リダイレクトを登録
            UUID prevId = trainIdCache.get(context.contraption);
            if (prevId != null && !prevId.equals(train.id)) {
                trainIdRedirects.put(prevId, train.id);
                // 注: displayCache.remove(prevId)は行わない
                // 切り離しでは1つのIDから複数の新IDが生まれるため、
                // 他のContraptionがまだ旧IDで参照している可能性がある
            }
            trainIdCache.put(context.contraption, train.id);
            if (context.contraption.entity != null) {
                entityIdToTrainId.put(context.contraption.entity.getId(), train.id);
            }

            boolean isAtStation = train.getCurrentStation() != null;

            // 連結/切り離しステータス
            String couplingStatus = "";
            String couplingPartner = "";
            String currentStationName = isAtStation && train.getCurrentStation() != null
                    ? train.getCurrentStation().name : null;
            // 駅にいなくても切り離し中ならステータス取得を試みる
            String decouplingStation = findDecouplingStation(train.id);
            if (currentStationName != null) {
                String[] cStatus = TrainCouplingManager.getMonitorCouplingStatus(train.id, currentStationName);
                if (cStatus != null) {
                    couplingStatus = cStatus[0];
                    couplingPartner = cStatus[1];
                }
            } else if (decouplingStation != null) {
                String[] cStatus = TrainCouplingManager.getMonitorCouplingStatus(train.id, decouplingStation);
                if (cStatus != null) {
                    couplingStatus = cStatus[0];
                    couplingPartner = cStatus[1];
                }
            }

            // スケジュールがない場合（切り離し直後の後尾列車など）
            if (train.runtime == null || train.runtime.getSchedule() == null
                    || train.runtime.getSchedule().entries.isEmpty()) {
                // 切り離し中なら駅名とステータスだけ表示
                List<StopInfo> stops = new ArrayList<>();
                String stationName = currentStationName != null ? currentStationName
                        : (decouplingStation != null ? decouplingStation : "");
                if (!stationName.isEmpty()) {
                    stops.add(new StopInfo(stationName, 0, 0, 0, true));
                }
                TrainDisplayInfo decoupInfo = new TrainDisplayInfo(stops, "", "",
                        couplingStatus, couplingPartner);
                displayCache.put(train.id, decoupInfo);
                broadcastDisplayInfo(context, train.id, decoupInfo);
                return;
            }

            ScheduleRuntime runtime = train.runtime;
            Schedule schedule = runtime.getSchedule();

            int currentIdx = runtime.currentEntry;
            int totalEntries = schedule.entries.size();
            if (currentIdx < 0 || currentIdx >= totalEntries) currentIdx = 0;

            long dayTime = context.world.getDayTime();
            long gameTick = context.world.getGameTime();

            // 次の2駅の停車情報を取得
            List<StopInfo> stops = new ArrayList<>(2);
            long accumulatedTime = dayTime;

            for (int count = 0; count < totalEntries && stops.size() < 2; count++) {
                int entryIdx = (currentIdx + count) % totalEntries;
                var entry = schedule.entries.get(entryIdx);

                String stationName = extractStationName(entry.instruction.getData());
                if (stationName.isEmpty()) continue;

                // 案L: 列車別実測 wait ticks を優先 (案I の条件型推定がフォールバック)
                int stopSec = (int) (RailwayManagementBlockEntity.computeStationWaitTicks(
                        train.id, stationName, entry) / 20);

                long estArrival;
                if (stops.isEmpty() && isAtStation) {
                    // 停車中: 実際の到着 gameTick を dayTime に変換して固定値として保持。
                    // 「停車中」表示自体は atStation() フラグで判定されるが、estArrival は
                    // 後続 stop の estDeparture チェーンに使われるため、ここで実値を設定して
                    // accumulatedTime 経由のドリフト (毎 tick currentDayTime に流される) を防ぐ。
                    Long arrivalTick = RailwayManagementBlockEntity.getPublicStationArrivalTick(train.id);
                    long arrivalGameTick = arrivalTick != null ? arrivalTick : gameTick;
                    estArrival = dayTime + (arrivalGameTick - gameTick);
                } else if (stops.isEmpty()) {
                    // 現区間: 案D アンカー経由で安定した到着 dayTime を取得。
                    // 全モニター (停留所モニター・からくりモニター) で同じアンカーを共有するため、
                    // 表示が一致し、毎 tick の揺れもなくなる。
                    String prevStation = findPrevStationName(schedule, entryIdx, totalEntries);
                    if (prevStation != null && !prevStation.isEmpty()) {
                        estArrival = RailwayManagementBlockEntity.getAnchoredArrivalDayTime(
                                context.world, train, train.id, prevStation, stationName);
                    } else {
                        long legTicks = RailwayManagementBlockEntity.getPublicLegTravelTicks(prevStation, stationName);
                        estArrival = dayTime + legTicks / 2;
                    }
                } else {
                    // 次以降の区間: 直前 stop の出発予定 + (列車別優先の) 平均 legTicks を加算。
                    // prev.estArrival は固定値なので estArrival もチェーンを通じて固定される。
                    StopInfo prev = stops.get(stops.size() - 1);
                    long legTicks = RailwayManagementBlockEntity.getPublicLegTravelTicksForTrain(
                            train.id, prev.stationName(), stationName);
                    long baseTime = prev.estDepartureDayTime() > 0 ? prev.estDepartureDayTime()
                            : (prev.estArrivalDayTime() > 0 ? prev.estArrivalDayTime() : accumulatedTime);
                    estArrival = baseTime + legTicks;
                }

                long estDeparture = estArrival > 0 && stopSec > 0 ? estArrival + (long) stopSec * 20 : 0;
                stops.add(new StopInfo(stationName, stopSec, estArrival, estDeparture, stops.isEmpty() && isAtStation));
                if (estDeparture > 0) accumulatedTime = estDeparture;
            }

            // 路線種別判定: hybrid (実挙動 tracker 優先 + 時刻表の確実判定で即時予測)。
            // 車載モニターは列車全体のバッジなので per-station gate は不要。
            String routeType = TrainScheduleReader.analyzeRouteType(train);

            // 列車種別判定
            String trainType = detectTrainType(train);

            TrainDisplayInfo info = new TrainDisplayInfo(stops, routeType, trainType,
                    couplingStatus, couplingPartner);
            displayCache.put(train.id, info);
            broadcastDisplayInfo(context, train.id, info);
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "MonitorMovementBehaviour: train display update failed", e);
        }
    }

    // ===== 路線種別判定 =====

    // 路線種別判定は com.trainsystemutilities.route.RouteClassifier に切出し (R2、 unit test 可能化)。

    /** Stable code → localized display string. Returns empty for empty/unknown codes. */
    private static String localizeRouteType(String code) {
        if (code == null || code.isEmpty()) return "";
        return switch (code) {
            case "CIRCULAR" -> net.minecraft.network.chat.Component.translatable("tsu.monitor.route_type_circular").getString();
            case "SHUTTLE" -> net.minecraft.network.chat.Component.translatable("tsu.monitor.route_type_shuttle").getString();
            default -> code;
        };
    }

    /** Stable code → localized display string. Returns empty for empty/unknown codes. */
    private static String localizeTrainType(String code) {
        if (code == null || code.isEmpty()) return "";
        return switch (code) {
            case "RAPID" -> net.minecraft.network.chat.Component.translatable("tsu.monitor.train_type_rapid").getString();
            case "EXPRESS" -> net.minecraft.network.chat.Component.translatable("tsu.monitor.train_type_express").getString();
            case "LOCAL" -> net.minecraft.network.chat.Component.translatable("tsu.monitor.train_type_local").getString();
            default -> code;
        };
    }

    // ===== 列車種別判定 =====

    /** TrainScheduleReader の共通メソッドを使用 */
    private static String detectTrainType(Train train) {
        return TrainScheduleReader.detectTrainType(train);
    }

    // ===== クライアント側: レンダリング =====

    @Override
    public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
                                     ContraptionMatrices matrices, MultiBufferSource buffer) {
        long profStart = com.trainsystemutilities.profiler.TsuProfiler.start();
        try {
            renderInContraptionImpl(context, renderWorld, matrices, buffer);
        } finally {
            com.trainsystemutilities.profiler.TsuProfiler.end(
                    com.trainsystemutilities.profiler.TsuProfiler.Phase.MONITOR_RENDER, profStart);
        }
    }

    /** デバッグフラグ: true にすると実描画 (renderFace) だけ skip し、診断ログ集計は継続。
     *  検証完了 (Create 純正のフリッカーと判明) → false に戻す。 */
    private static final boolean DEBUG_DISABLE_MONITOR_RENDER = false;

    private void renderInContraptionImpl(MovementContext context, VirtualRenderWorld renderWorld,
                                          ContraptionMatrices matrices, MultiBufferSource buffer) {
        if (!context.world.isClientSide()) return;

        // === Tier 3a: 距離 culling ===
        // contraption がカメラから 96 ブロック超 → 描画スキップ (見えないので無駄)
        Minecraft mc = Minecraft.getInstance();
        boolean playerOnThisCarriage = false;
        boolean playerStandingNearby = false;
        if (mc.gameRenderer != null && context.contraption != null
                && context.contraption.entity != null) {
            // 診断: プレイヤーがこの車両に搭乗 (passenger) しているか
            if (mc.player != null) {
                net.minecraft.world.entity.Entity root = mc.player.getRootVehicle();
                if (root != null && root != mc.player
                        && root.getId() == context.contraption.entity.getId()) {
                    playerOnThisCarriage = true;
                }
                // 診断: プレイヤーがこの contraption の "上に立っている" 可能性 (vehicle=null だが
                // 距離が近い)。実機では座らずに立つだけでも contraption の動きで揺れるため、
                // 距離ベースで「巻き込まれ判定」を疑似検出する。
                // 6 ブロック内 → 同じ車両に乗っている / 隣接している可能性が高い。
                if (mc.player.getVehicle() == null) {
                    double pdx = mc.player.getX() - context.contraption.entity.getX();
                    double pdy = mc.player.getY() - context.contraption.entity.getY();
                    double pdz = mc.player.getZ() - context.contraption.entity.getZ();
                    double pdistSq = pdx * pdx + pdy * pdy + pdz * pdz;
                    if (pdistSq < 36.0) playerStandingNearby = true; // 6 ブロック以内
                }
                // 診断: 1秒に1回だけ player vehicle 構造 + standing 状態をログ出し
                long n = System.nanoTime();
                if (n - lastVehicleLogNanos > 1_000_000_000L) {
                    lastVehicleLogNanos = n;
                    net.minecraft.world.entity.Entity v = mc.player.getVehicle();
                    String vCls = v != null ? v.getClass().getSimpleName() : "null";
                    int vId = v != null ? v.getId() : -1;
                    String rCls = root != null && root != mc.player
                            ? root.getClass().getSimpleName() : "null";
                    int rId = root != null && root != mc.player ? root.getId() : -1;
                    int contraptionEntId = context.contraption.entity.getId();
                    String ceCls = context.contraption.entity.getClass().getSimpleName();
                    com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                            "[TSU/PlayerVehicle] vehicle={}({}), root={}({}), contraptionEntity={}({}), standingNearby={}",
                            vCls, vId, rCls, rId, ceCls, contraptionEntId, playerStandingNearby);
                }
            }
            var cam = mc.gameRenderer.getMainCamera().getPosition();
            double dx = cam.x - context.contraption.entity.getX();
            double dy = cam.y - context.contraption.entity.getY();
            double dz = cam.z - context.contraption.entity.getZ();
            if (dx * dx + dy * dy + dz * dz > RENDER_CULL_DISTANCE_SQ) {
                return;
            }
        }

        GroupInfo group = getOrComputeGroupInfo(context);
        if (group == null) return;

        // === Tier 3a: per-monitor renderer (layout-skip cache が有効) ===
        int entityId = context.contraption.entity != null ? context.contraption.entity.getId() : 0;
        String monitorKey = entityId + "@" + group.master().asLong();

        // 診断: 1 秒ごとに呼び出し数 + プレイヤー搭乗状態を集計してログ。
        // 期待値: 通常車両 ~60 回/秒 (60fps × 1pass)、プレイヤー車両は + 倍数なら multi-pass の証拠。
        int[] counter = renderCallCount.computeIfAbsent(monitorKey, k -> new int[]{0, 0, 0});
        counter[0]++; // 総呼び出し
        if (playerOnThisCarriage) counter[1]++; // プレイヤー搭乗時の呼び出し
        long nowNs = System.nanoTime();
        if (nowNs - lastDiagLogNanos >= DIAG_LOG_INTERVAL_NS) {
            synchronized (MonitorMovementBehaviour.class) {
                if (nowNs - lastDiagLogNanos >= DIAG_LOG_INTERVAL_NS) {
                    lastDiagLogNanos = nowNs;
                    StringBuilder sb = new StringBuilder("[TSU/MonitorRender 1s] ");
                    int activeKeys = 0, cullSkipKeys = 0;
                    int totalCalls = 0, playerCalls = 0;
                    int cullSkipCalls = 0, cullSkipPlayer = 0;
                    String playerKeyPreview = null;
                    int playerCount = 0;
                    for (var e : renderCallCount.entrySet()) {
                        boolean isCull = e.getKey().endsWith("@cullSkip");
                        if (isCull) {
                            cullSkipKeys++;
                            cullSkipCalls += e.getValue()[0];
                            cullSkipPlayer += e.getValue()[1];
                        } else {
                            activeKeys++;
                            totalCalls += e.getValue()[0];
                            playerCalls += e.getValue()[1];
                            if (e.getValue()[1] > playerCount) {
                                playerCount = e.getValue()[1];
                                playerKeyPreview = e.getKey();
                            }
                        }
                    }
                    int pFbo = pathCount.getAndSet(0, 0);
                    int pReplay = pathCount.getAndSet(1, 0);
                    int pFull = pathCount.getAndSet(2, 0);
                    int pSkip = pathCount.getAndSet(3, 0);
                    int pPlayerFbo = playerPathFbo.getAndSet(0);
                    int pPlayerReplay = playerPathReplay.getAndSet(0);
                    int pPlayerFull = playerPathFull.getAndSet(0);
                    int pPlayerSkip = playerPathSkip.getAndSet(0);
                    int sTotal = standTotalCalls.getAndSet(0);
                    int sFbo = standPathFbo.getAndSet(0);
                    int sReplay = standPathReplay.getAndSet(0);
                    int sFull = standPathFull.getAndSet(0);
                    int sSkip = standPathSkip.getAndSet(0);
                    int sCull = standCullSkip.getAndSet(0);
                    sb.append("activeKeys=").append(activeKeys)
                      .append(" cullSkipKeys=").append(cullSkipKeys)
                      .append(" totalCalls=").append(totalCalls)
                      .append(" playerCalls=").append(playerCalls)
                      .append(" cullSkipCalls=").append(cullSkipCalls)
                      .append(" cullSkipPlayer=").append(cullSkipPlayer)
                      .append(" path[fbo=").append(pFbo)
                      .append(",replay=").append(pReplay)
                      .append(",full=").append(pFull)
                      .append(",animSkip=").append(pSkip).append("]")
                      .append(" playerPath[fbo=").append(pPlayerFbo)
                      .append(",replay=").append(pPlayerReplay)
                      .append(",full=").append(pPlayerFull)
                      .append(",animSkip=").append(pPlayerSkip).append("]")
                      .append(" stand[total=").append(sTotal)
                      .append(",fbo=").append(sFbo)
                      .append(",replay=").append(sReplay)
                      .append(",full=").append(sFull)
                      .append(",animSkip=").append(sSkip)
                      .append(",cullSkip=").append(sCull).append("]");
                    if (playerKeyPreview != null) {
                        sb.append(" topPlayerKey=").append(playerKeyPreview)
                          .append(" topPlayerCount=").append(playerCount);
                    }
                    com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(sb.toString());

                    // フレーム間隔分布: 立ち乗り中のサンプルから直近 1 秒の隣接呼び出し時刻差を計算。
                    // フリッカー (= 描画されないフレームがある) ならギャップが 33ms / 50ms 以上が増える。
                    long now = System.nanoTime();
                    long oneSecAgo = now - 1_000_000_000L;
                    for (var entry : standFrameTimings.entrySet()) {
                        long[] buf = entry.getValue();
                        // 1 秒以内の値だけソートして diff を集計
                        long[] recent = new long[buf.length];
                        int rn = 0;
                        for (long t : buf) {
                            if (t > oneSecAgo) recent[rn++] = t;
                        }
                        if (rn < 2) continue;
                        long[] sorted = java.util.Arrays.copyOf(recent, rn);
                        java.util.Arrays.sort(sorted);
                        // gap (ms) bucket: <20=normal, 20..40=1miss, 40..70=2miss, >70=3+miss
                        int normal = 0, miss1 = 0, miss2 = 0, miss3 = 0;
                        long minGap = Long.MAX_VALUE, maxGap = 0, totGap = 0;
                        for (int i = 1; i < rn; i++) {
                            long g = sorted[i] - sorted[i - 1];
                            totGap += g;
                            minGap = Math.min(minGap, g);
                            maxGap = Math.max(maxGap, g);
                            long gMs = g / 1_000_000L;
                            if (gMs < 20) normal++;
                            else if (gMs < 40) miss1++;
                            else if (gMs < 70) miss2++;
                            else miss3++;
                        }
                        long avg = totGap / Math.max(1, rn - 1);
                        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                                "[TSU/StandFrame] key={} samples={} gap(ms) avg={} min={} max={} hist[ok={},+1miss={},+2miss={},+3miss={}]",
                                entry.getKey(), rn, avg / 1_000_000L,
                                minGap / 1_000_000L, maxGap / 1_000_000L,
                                normal, miss1, miss2, miss3);
                    }
                    // フレームタイミングのバッファは保持 (リングバッファ) — 1 秒以上前のデータは
                    // 自動で oneSecAgo フィルタで除外される。
                    // リセット (各 entry の counter は配列を共有、in-place 0 リセット)
                    for (int[] c : renderCallCount.values()) {
                        c[0] = 0; c[1] = 0; c[2] = 0;
                    }
                }
            }
        }

        CSSWorldRenderer renderer = rendererPerMonitor.computeIfAbsent(monitorKey,
                k -> new CSSWorldRenderer(mc.font));

        Direction facing = getFacing(context);
        TrainDisplayInfo info = getCachedDisplayInfo(context);

        // === Tier 3a: animState 先行更新 (DOM cache hit でも anim フラグは更新が必要) ===
        ContraptionAnimState animState = updateAnimState(context.contraption, info);

        // === V3 Stable IR: parameterless build を 1 度だけ実行し全 monitor で共有 ===
        int cssW = group.width() * 128;
        int cssH = group.height() * 128;
        long dayTime = context.world.getDayTime();
        IrNode irRoot = getSharedIr();
        MonitorMovementHandler handler = handlersPerMonitor.computeIfAbsent(monitorKey, k -> new MonitorMovementHandler());
        handler.update(cssW, cssH, formatDayTime(dayTime), info, animState);

        // === 通常設置モニター (MonitorWorldRenderer) と同一挙動 ===
        // capture/replay キャッシュは走行中に clock / ETA を凍結させるため使わず、
        // 毎フレーム V3 IR から描画する。CSSWorldRenderer 内部の layout キャッシュにより
        // 毎フレーム描画でもコストは通常設置モニターと同等で、走行中もリアルタイム更新される。
        if (!DEBUG_DISABLE_MONITOR_RENDER) {
            renderFace(context, matrices, buffer, group.frontOrigin(), facing,
                    group.width(), group.height(), false, info, renderer, irRoot, handler);

            if (group.hasDoubleSided()) {
                renderFace(context, matrices, buffer, group.backOrigin(), facing,
                        group.width(), group.height(), true, info, renderer, irRoot, handler);
            }
        }

        // 定期クリーンアップ (broken contraptions の renderer/DOM entries を回収)
        long now = System.nanoTime();
        if (now - lastRenderCleanupNanos > RENDER_CLEANUP_INTERVAL_NANOS) {
            lastRenderCleanupNanos = now;
            cleanupRenderCaches();
        }
    }

    /** 古い per-monitor renderer/DOM エントリーを削除 (壊れた contraption の参照を解放)。 */
    private static void cleanupRenderCaches() {
        // simple cap-based: > 1000 → clear half
        if (rendererPerMonitor.size() > 1000) {
            int target = rendererPerMonitor.size() / 2;
            var it = rendererPerMonitor.keySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < target) {
                it.next(); it.remove(); removed++;
            }
        }
        if (irCache.size() > 1000) {
            int target = irCache.size() / 2;
            var it = irCache.keySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < target) {
                it.next(); it.remove(); removed++;
            }
        }
        if (handlersPerMonitor.size() > 1000) {
            int target = handlersPerMonitor.size() / 2;
            var it = handlersPerMonitor.keySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < target) {
                it.next(); it.remove(); removed++;
            }
        }
    }

    /**
     * #7 MP fix: server が計算した表示データを、 当該 carriage entity を tracking 中のクライアントへ配信。
     *
     * <p>SP は同一 JVM で {@link #displayCache} を共有するため不要だが、 専用サーバーではこれが無いと
     * クライアントの displayCache が空のまま → {@code info == null} → 全要素表示 (= 全種別バッジ表示) になる。
     */
    private static void broadcastDisplayInfo(MovementContext context, UUID trainId, TrainDisplayInfo info) {
        var entity = context.contraption.entity;
        if (entity == null) return;
        int entityId = entity.getId();
        long tick = context.world.getGameTime();
        // 同一 carriage の複数モニターが同 tick に重複送信しないよう 1 回に集約
        Long last = lastBroadcastTickByEntity.get(entityId);
        if (last != null && last == tick) return;
        lastBroadcastTickByEntity.put(entityId, tick);

        List<MonitorDisplayInfoPayload.Stop> wire = new ArrayList<>(info.stops().size());
        for (StopInfo s : info.stops()) {
            wire.add(new MonitorDisplayInfoPayload.Stop(
                    s.stationName(), s.stopSec(),
                    s.estArrivalDayTime(), s.estDepartureDayTime(), s.atStation()));
        }
        PacketDistributor.sendToPlayersTrackingEntity(entity, new MonitorDisplayInfoPayload(
                entityId, trainId, info.routeType(), info.trainType(),
                info.couplingStatus(), info.couplingPartner(), wire));
    }

    /** #7 MP fix: {@link MonitorDisplayInfoPayload} 受信時にクライアントの displayCache を埋める。 */
    public static void applyServerDisplayInfo(MonitorDisplayInfoPayload p) {
        List<StopInfo> stops = new ArrayList<>(p.stops().size());
        for (MonitorDisplayInfoPayload.Stop s : p.stops()) {
            stops.add(new StopInfo(s.stationName(), s.stopSec(),
                    s.estArrivalDayTime(), s.estDepartureDayTime(), s.atStation()));
        }
        TrainDisplayInfo info = new TrainDisplayInfo(stops, p.routeType(), p.trainType(),
                p.couplingStatus(), p.couplingPartner());
        entityIdToTrainId.put(p.entityId(), p.trainId());
        displayCache.put(p.trainId(), info);
    }

    private TrainDisplayInfo getCachedDisplayInfo(MovementContext context) {
        // 1. クライアント側でTrainを直接取得
        Train train = getTrainFromContext(context);
        UUID trainId = train != null ? train.id : null;

        // 2. entityIdToTrainIdからフォールバック（サーバーがtick()で書き込んだもの）
        if (trainId == null && context.contraption.entity != null) {
            trainId = entityIdToTrainId.get(context.contraption.entity.getId());
        }

        // 3. trainIdCacheからフォールバック
        if (trainId == null) {
            trainId = trainIdCache.get(context.contraption);
        }

        if (trainId == null) return null;

        // displayCacheから取得
        TrainDisplayInfo info = displayCache.get(trainId);
        if (info != null) return info;

        // リダイレクトを辿る
        UUID redirected = trainIdRedirects.get(trainId);
        if (redirected != null) {
            info = displayCache.get(redirected);
            if (info != null) return info;
        }

        return null;
    }

    // ===== レンダリング =====

    private void renderFace(MovementContext context, ContraptionMatrices matrices,
                            MultiBufferSource buffer, BlockPos faceOrigin, Direction facing,
                            int monitorW, int monitorH, boolean isBack, TrainDisplayInfo info,
                            CSSWorldRenderer renderer, IrNode cachedIr, JsonLayoutHandler handler) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(matrices.getViewProjection().last().pose());
        poseStack.last().normal().set(matrices.getViewProjection().last().normal());
        ContraptionMatrices.transform(poseStack, matrices.getModel());

        BlockPos offset = faceOrigin.subtract(context.localPos);
        poseStack.translate(offset.getX() + 0.5, offset.getY() + 0.5, offset.getZ() + 0.5);

        float rotation = switch (facing) {
            case SOUTH -> 0f; case NORTH -> 180f; case WEST -> -90f; case EAST -> 90f; default -> 0f;
        };
        if (isBack) rotation += 180f;
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotation));

        // 表示面の深さを faceOrigin のブロック厚から算出 (薄型・両面薄型対応)。
        // フルブロック / 薄型片面: 0.505 (front face = 立方体面 + epsilon)
        // 両面薄型 (t=8): 8/32 + 0.005 = 0.255
        // 両面極薄 (t=4): 4/32 + 0.005 = 0.130
        StructureBlockInfo blockInfo = context.contraption.getBlocks().get(faceOrigin);
        double depth = blockInfo != null ? computeSurfaceDepth(blockInfo.state()) : 0.505;
        poseStack.translate(-0.5, -0.5, depth);

        int cssW = monitorW * 128;
        int cssH = monitorH * 128;

        poseStack.pushPose();
        poseStack.scale(1f / 128f, 1f / 128f, 1f / 128f);
        poseStack.translate(0, cssH, 0);
        poseStack.scale(1, -1, -1);

        // V3 IR から毎フレーム描画 (通常設置モニターと同一経路)
        renderer.renderV3FromIr(cachedIr, handler, poseStack, buffer);
        poseStack.popPose();
    }

    // ===== アニメ状態更新 =====

    /**
     * IR cache: アニメ状態を更新する (IR が cache hit でも呼ばれる必要がある)。
     * 内容変化を検知して currentSlideIn/nextSlideIn フラグを立てる。
     * @return 更新後の animState
     */
    private static ContraptionAnimState updateAnimState(Contraption contraption, TrainDisplayInfo info) {
        ContraptionAnimState animState = animStates.computeIfAbsent(contraption, k -> new ContraptionAnimState());
        List<StopInfo> stops = info != null ? info.stops() : List.of();
        if (!animState.initialized) {
            animState.initialized = true;
            if (!stops.isEmpty()) animState.currentStation = stops.get(0).stationName();
            if (stops.size() > 1) animState.nextStation = stops.get(1).stationName();
        } else {
            String newCurrent = stops.isEmpty() ? "" : stops.get(0).stationName();
            String newNext = stops.size() > 1 ? stops.get(1).stationName() : "";
            if (!newCurrent.equals(animState.currentStation)) {
                animState.currentStation = newCurrent;
                animState.currentSlideIn = true;
            }
            if (!newNext.equals(animState.nextStation)) {
                animState.nextStation = newNext;
                animState.nextSlideIn = true;
            }
        }
        return animState;
    }

    // ===== ヘルパー =====

    /**
     * 表示面の Z オフセットを返す (pose-local 座標、ブロック中心からの距離 + Z-fighting epsilon)。
     *
     * <ul>
     *   <li>フルブロック (single/double): 立方体面まで 0.5 → 0.505</li>
     *   <li>薄型片面 (ThinMonitorBlock): FACING 側の立方体面に密着 → 0.505</li>
     *   <li>両面薄型 (ThinDoubleSidedMonitorBlock t=8): 中央配置で半厚 0.25 → 0.255</li>
     *   <li>両面極薄 (ThinDoubleSidedMonitorBlock t=4): 中央配置で半厚 0.125 → 0.130</li>
     * </ul>
     */
    private static double computeSurfaceDepth(BlockState state) {
        if (state.getBlock() instanceof ThinDoubleSidedMonitorBlock thinDS) {
            return thinDS.getThickness() / 32.0 + 0.005;
        }
        return 0.505;
    }

    private static Direction getFacing(MovementContext context) {
        if (context.state.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
            return context.state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
        }
        return Direction.NORTH;
    }

    private GroupInfo getOrComputeGroupInfo(MovementContext context) {
        Map<BlockPos, GroupInfo> cache = groupCache.computeIfAbsent(context.contraption, k -> new ConcurrentHashMap<>());
        if (cache.containsKey(context.localPos)) return cache.get(context.localPos);

        Direction facing = getFacing(context);
        List<BlockPos> group = findConnectedMonitors(context.contraption, context.localPos, facing);
        if (group.isEmpty()) return null;

        BlockPos master = group.get(0);
        for (BlockPos gp : group) {
            if (gp.getY() < master.getY() ||
                    (gp.getY() == master.getY() && gp.getX() < master.getX()) ||
                    (gp.getY() == master.getY() && gp.getX() == master.getX() && gp.getZ() < master.getZ())) {
                master = gp;
            }
        }

        Direction right = facing.getClockWise();
        int minH = 0, maxH = 0, minV = 0, maxV = 0;
        boolean hasDoubleSided = false;

        for (BlockPos gp : group) {
            BlockPos diff = gp.subtract(master);
            int h = diff.getX() * right.getStepX() + diff.getZ() * right.getStepZ();
            int v = diff.getY();
            minH = Math.min(minH, h); maxH = Math.max(maxH, h);
            minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            StructureBlockInfo info = context.contraption.getBlocks().get(gp);
            if (info != null && MonitorBlock.isDoubleSidedMonitor(info.state())) hasDoubleSided = true;
        }

        GroupInfo groupInfo = new GroupInfo(master,
                master.offset(right.getStepX() * maxH, minV, right.getStepZ() * maxH),
                master.offset(right.getStepX() * minH, minV, right.getStepZ() * minH),
                maxH - minH + 1, maxV - minV + 1, hasDoubleSided);

        for (BlockPos gp : group) cache.put(gp, groupInfo);
        return groupInfo;
    }

    /**
     * 列車IDに関連する切り離し駅名を検索する（DecouplingOrderのfrontTrainId/rearTrainIdから）。
     */
    private static String findDecouplingStation(UUID trainId) {
        // 直接のDecouplingOrder
        TrainCouplingManager.DecouplingOrder order = TrainCouplingManager.getDecouplingOrder(trainId);
        if (order != null && order.phase() != TrainCouplingManager.DecouplingPhase.COMPLETED) {
            return order.stationName();
        }
        // frontTrainId/rearTrainIdとして参照されている場合
        return TrainCouplingManager.findDecouplingStationForSplitTrain(trainId);
    }

    private static Train getTrainFromContext(MovementContext context) {
        try {
            if (context.contraption == null) return null;
            var entity = context.contraption.entity;
            if (!(entity instanceof CarriageContraptionEntity cce)) return null;
            var carriage = cce.getCarriage();
            if (carriage == null) return null;
            if (carriage.train != null
                    && com.simibubi.create.Create.RAILWAYS.trains.containsKey(carriage.train.id)) {
                return carriage.train;
            }
            // フォールバック: entityIdToTrainIdから取得（RAILWAYS全列車ループ回避）
            UUID cached = entityIdToTrainId.get(entity.getId());
            if (cached != null) {
                Train t = com.simibubi.create.Create.RAILWAYS.trains.get(cached);
                if (t != null) return t;
            }
            return null;
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Monitor] train resolve failed", e); return null; }
    }

    private static String extractStationName(CompoundTag instrData) {
        for (String key : instrData.getAllKeys()) {
            if (key.equals("Id")) continue;
            String val = instrData.getString(key);
            if (val != null && !val.isEmpty()) return val;
        }
        return "";
    }

    private static String findPrevStationName(Schedule schedule, int currentIdx, int totalEntries) {
        for (int i = 1; i < totalEntries; i++) {
            int prevIdx = (currentIdx - i + totalEntries) % totalEntries;
            String name = extractStationName(schedule.entries.get(prevIdx).instruction.getData());
            if (!name.isEmpty()) return name;
        }
        return null;
    }

    private static List<BlockPos> findConnectedMonitors(Contraption contraption, BlockPos start, Direction facing) {
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        List<BlockPos> connected = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            StructureBlockInfo info = blocks.get(current);
            if (info == null) continue;
            BlockState state = info.state();
            if (!MonitorBlock.isMonitorBlock(state)) continue;
            if (!state.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) continue;
            if (state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING) != facing) continue;
            connected.add(current);
            queue.add(current.above()); queue.add(current.below());
            queue.add(current.relative(facing.getClockWise())); queue.add(current.relative(facing.getCounterClockWise()));
        }
        return connected;
    }

    private static String formatDayTime(long dayTime) {
        long t = dayTime % 24000;
        int hours = (int) ((t / 1000 + 6) % 24);
        int minutes = (int) ((t % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }
}
