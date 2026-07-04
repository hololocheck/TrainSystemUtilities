package com.trainsystemutilities.announce;

import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.compat.sas.SasIntegration;
import com.trainsystemutilities.network.AnnouncementPlaybackStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-BE シーケンシャル再生スケジューラ。
 *
 * <p>動作モデル:
 * <ol>
 *   <li>列車が検知点を通過 (onTrainPass) → BE のシーケンスを開始 (entry 0 から)。
 *       既にシーケンス活性中なら ignore。</li>
 *   <li>各 entry の condition を順番に評価:
 *     <ul>
 *       <li>NONE: skip → 次 entry へ</li>
 *       <li>ON_DETECTION_PASS: 条件即時達成 → delay 秒後に再生</li>
 *       <li>ON_DETECTION_STOPPED: 列車が検知点で停止するまで待機 → 停止後 delay 秒後に再生</li>
 *     </ul>
 *   </li>
 *   <li>再生完了 (PlaybackEndedEvent) → 次 entry へ</li>
 *   <li>全 entry 処理完了 → シーケンス終了</li>
 * </ol>
 *
 * <p>{@link AnnouncementConfig#isEnabled()} が false なら全てのシーケンス開始を抑止。
 */
public final class AnnouncementScheduler {

    private AnnouncementScheduler() {}

    private static final class SequenceState {
        final BlockPos bePos;
        final ResourceKey<Level> dim;
        java.util.UUID triggeringTrainId;  // sequence を起動した列車 (ON_STOP 判定用)
        int currentEntryIdx;       // -1 = idle, >= 0 = current entry
        int currentPlayIteration = 0; // 当該 entry を何回再生済みか。playCount に達したら次 entry へ。
        long fireAtTick = -1;      // tick で再生発火 (delay 経過後)。-1 = 未定
        boolean waitingForStop = false;
        boolean isPlaying = false;
        boolean manualTest = false;
        boolean sharedFire = false; // 共有先として fire 中なら true (master toggle 無視 + source の range board 使用)
        BlockPos sourceBePos = null; // 共有元 rmbe の位置 (sharedFire 時の範囲指定ボード参照用)

        SequenceState(BlockPos bePos, ResourceKey<Level> dim) {
            this.bePos = bePos;
            this.dim = dim;
            this.currentEntryIdx = -1;
        }
    }

    // TSU-20: key を (dimension, BlockPos) で一意化する。 BlockPos のみを key にすると別 dimension の
    // 同座標にある管理 block が同じ entry を共有し、一方の sequence が他方を suppress/overwrite してしまう。
    private static final Map<GlobalPos, SequenceState> sequences = new ConcurrentHashMap<>();

    private static GlobalPos keyOf(ResourceKey<Level> dim, BlockPos pos) {
        return GlobalPos.of(dim, pos.immutable());
    }

    private static void syncPlaybackState(ServerLevel sl, BlockPos bePos, int entryIdx, boolean playing) {
        int idx = playing ? entryIdx : -1;
        var payload = new AnnouncementPlaybackStatePayload(bePos, idx, idx >= 0);
        double px = bePos.getX() + 0.5;
        double py = bePos.getY() + 0.5;
        double pz = bePos.getZ() + 0.5;
        for (ServerPlayer player : sl.players()) {
            if (player.distanceToSqr(px, py, pz) <= 256.0D * 256.0D) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static void removeSequence(ServerLevel sl, SequenceState s) {
        sequences.remove(keyOf(s.dim, s.bePos));
        if (sl != null) syncPlaybackState(sl, s.bePos, -1, false);
    }

    public static void syncCurrentPlaybackTo(ServerPlayer player, BlockPos bePos) {
        if (player == null || bePos == null) return;
        int idx = -1;
        SequenceState s = sequences.get(keyOf(player.level().dimension(), bePos));
        if (s != null && s.isPlaying) {
            idx = s.currentEntryIdx;
        }
        PacketDistributor.sendToPlayer(player,
                new AnnouncementPlaybackStatePayload(bePos, idx, idx >= 0));
    }

    public static void clearPlaybackState(ServerLevel sl, BlockPos bePos) {
        if (sl == null || bePos == null) return;
        sequences.remove(keyOf(sl.dimension(), bePos));
        syncPlaybackState(sl, bePos, -1, false);
    }

    public static void init() {
        if (SasIntegration.isLoaded()) {
            try {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                        AnnouncementScheduler::onPlaybackEnded);
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn(
                        "[AnnouncementScheduler] failed to subscribe PlaybackEndedEvent: {}", t.toString());
            }
        }
    }

    /** 列車が検知点を通過した時。共有先 rmbe にも propagate してから自身を処理。 */
    public static void onTrainPass(Train train, GlobalPos detectionPos,
                                    ResourceKey<Level> beDim, BlockPos bePos) {
        MinecraftServer server = ServerHolder.get();
        if (server == null) return;
        ServerLevel sl = server.getLevel(beDim);
        if (sl == null) return;
        var be = sl.getBlockEntity(bePos);
        if (!(be instanceof RailwayManagementBlockEntity rmbe)) return;

        // 検知カード共有先 rmbe にも propagate
        // 共有先は target 側の destination filter は適用 (= 列車が向かう駅でのみ fire)、
        // 但し master toggle は bypass する (受け入れ専用 rmbe の用途のため)。
        // findOrScanManagerForStation で cache miss でも周辺 scan で検出。
        AnnouncementConfig sourceCfg = rmbe.getAnnouncementConfig();
        if (!sourceCfg.sharedDetectionToStations().isEmpty()) {
            BlockPos cmpPos = rmbe.getLinkedComputerPos();
            if (cmpPos != null) {
                var cmpBe = sl.getBlockEntity(cmpPos);
                if (cmpBe instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity mcbe) {
                    for (String stationName : sourceCfg.sharedDetectionToStations()) {
                        if (stationName == null || stationName.isEmpty()) continue;
                        // cache hit を試した後、見つからなければ scan で再取得 (より堅牢)
                        var targetRmbe = mcbe.findOrScanManagerForStation(stationName, null);
                        if (targetRmbe == null) continue;
                        BlockPos targetPos = targetRmbe.getBlockPos();
                        if (targetPos.equals(bePos)) continue;
                        startSequenceFor(server, train, beDim, targetPos, /*sharedFire*/ true, bePos);
                    }
                }
            }
        }

        // Self (destination filter + master toggle 適用)
        startSequenceFor(server, train, beDim, bePos, /*sharedFire*/ false, null);
    }

    /** 単一 rmbe に対する sequence 起動。 sharedFire=true なら master toggle のみ bypass、
     *  destination filter は (source/target ともに) その rmbe 自身の linkedStation で常に適用する。
     *  これで「本線に登録した検知カードを複数駅に共有しても、列車が向かう駅でのみ fire」が成立する。 */
    private static void startSequenceFor(MinecraftServer server, Train train,
                                          ResourceKey<Level> beDim, BlockPos bePos,
                                          boolean sharedFire, BlockPos sourceBePos) {
        ServerLevel sl = server.getLevel(beDim);
        if (sl == null) return;
        var be = sl.getBlockEntity(bePos);
        if (!(be instanceof RailwayManagementBlockEntity rmbe)) return;
        AnnouncementConfig cfg = rmbe.getAnnouncementConfig();
        // 共有 fire は target の master toggle 状態に関わらず発火する (= sharedTo に登録した時点で "受け入れる")。
        // 自前 fire は cfg.isEnabled() を尊重 (master toggle off なら何もしない)。
        if (cfg.size() == 0) return;
        if (!sharedFire && !cfg.isEnabled()) return;

        // Filter: 列車の destination が rmbe の linked station と一致する場合のみ sequence を開始。
        // 共有 fire でも target 側の linkedStation で評価するため、無関係な駅は fire しない。
        String linkedStation = rmbe.getLinkedStationName();
        if (linkedStation != null && !linkedStation.isEmpty()) {
            String destName = null;
            try {
                if (train.navigation != null && train.navigation.destination != null) {
                    destName = train.navigation.destination.name;
                }
            } catch (Throwable ignored) { TrainSystemUtilities.LOGGER.debug("[Announce] read train destination failed", ignored); }
            if (destName == null || !linkedStation.equals(destName)) {
                return; // この列車はこの駅に向かっていない
            }
        }

        SequenceState s = sequences.get(keyOf(beDim, bePos));
        if (s != null && s.currentEntryIdx >= 0) {
            // 既に走っている → ignore (列車が連続通過しても 1 シーケンスのみ)
            return;
        }
        s = new SequenceState(bePos, beDim);
        s.currentEntryIdx = 0;
        s.triggeringTrainId = train.id;
        s.sharedFire = sharedFire;
        s.sourceBePos = sourceBePos;
        sequences.put(keyOf(beDim, bePos), s);
        advance(server, s, /*fromStopEvent*/ false);
    }

    /** 列車が検知点で停止した時。waitingForStop なら advance。 */
    public static void onTrainStopped(Train train, GlobalPos detectionPos,
                                       ResourceKey<Level> beDim, BlockPos bePos) {
        SequenceState s = sequences.get(keyOf(beDim, bePos));
        if (s == null) return;
        if (!s.waitingForStop) return;
        MinecraftServer server = ServerHolder.get();
        if (server == null) return;
        s.waitingForStop = false;
        advance(server, s, /*fromStopEvent*/ true);
    }

    /**
     * 現在の entry を評価して次の状態に進める。
     * NONE はループで skip。再生待ち / 停止待ちなら state を残してリターン。
     */
    private static void advance(MinecraftServer server, SequenceState s, boolean fromStopEvent) {
        ServerLevel sl = server.getLevel(s.dim);
        if (sl == null) { sequences.remove(keyOf(s.dim, s.bePos)); return; }

        while (true) {
            var be = sl.getBlockEntity(s.bePos);
            if (!(be instanceof RailwayManagementBlockEntity rmbe)) {
                removeSequence(sl, s);
                return;
            }
            AnnouncementConfig cfg = rmbe.getAnnouncementConfig();
            // 共有 fire は target の master toggle を無視
            if ((!s.sharedFire && !cfg.isEnabled()) || s.currentEntryIdx >= cfg.size()) {
                removeSequence(sl, s);
                return;
            }
            AnnouncementEntry entry = cfg.get(s.currentEntryIdx);
            if (entry == null) {
                removeSequence(sl, s);
                return;
            }
            AnnouncementCondition cond = entry.condition();

            switch (cond.type) {
                case NONE -> {
                    // skip
                    s.currentEntryIdx++;
                    s.currentPlayIteration = 0;
                    fromStopEvent = false;
                    continue; // loop
                }
                case ON_DETECTION_PASS -> {
                    long now = sl.getGameTime();
                    s.fireAtTick = now + Math.max(0, cond.delaySeconds) * 20L;
                    return; // serverTick が拾う
                }
                case ON_DETECTION_STOPPED -> {
                    if (fromStopEvent) {
                        long now = sl.getGameTime();
                        s.fireAtTick = now + Math.max(0, cond.delaySeconds) * 20L;
                        return;
                    } else {
                        s.waitingForStop = true;
                        return;
                    }
                }
            }
        }
    }

    /** Server tick: waitingForStop の sequence は triggering train が停止したか check。
     *  fireAtTick が来た sequence は playAudio を発火。 */
    public static void serverTick(MinecraftServer server) {
        if (sequences.isEmpty()) return;
        for (SequenceState s : sequences.values()) {
            // waitingForStop: triggering train が停止したら advance
            if (s.waitingForStop && s.triggeringTrainId != null) {
                Train t = com.simibubi.create.Create.RAILWAYS.trains.get(s.triggeringTrainId);
                if (t != null && Math.abs(t.speed) < 0.001) {
                    s.waitingForStop = false;
                    advance(server, s, /*fromStopEvent*/ true);
                }
            }
            if (s.isPlaying) continue;
            if (s.waitingForStop) continue;
            if (s.fireAtTick < 0) continue;
            ServerLevel sl = server.getLevel(s.dim);
            if (sl == null) continue;
            long now = sl.getGameTime();
            if (now < s.fireAtTick) continue;
            s.fireAtTick = -1;
            firePlayback(server, s);
        }
    }

    private static void firePlayback(MinecraftServer server, SequenceState s) {
        ServerLevel sl = server.getLevel(s.dim);
        if (sl == null) return;
        var be = sl.getBlockEntity(s.bePos);
        if (!(be instanceof RailwayManagementBlockEntity rmbe)) {
            removeSequence(sl, s);
            return;
        }
        AnnouncementConfig cfg = rmbe.getAnnouncementConfig();
        if (s.currentEntryIdx >= cfg.size()) {
            removeSequence(sl, s);
            return;
        }
        ItemStack media = rmbe.getAnnouncementMedia(s.currentEntryIdx);
        if (media.isEmpty() || !SasIntegration.hasAudio(media)) {
            // No audio for this entry → skip
            s.currentEntryIdx++;
            s.currentPlayIteration = 0;
            advance(server, s, false);
            return;
        }
        // 範囲指定ボードの解決:
        // 1) 共有 fire 中なら、source rmbe が range も共有しているか確認 → 共有していれば source の range board を使う
        // 2) それ以外は target 自身を範囲共有先として登録している rmbe があれば、その range board を使う (range のみ共有のケース)
        // 3) いずれもなければ target 自身の range board を使用
        ItemStack rangeBoard = rmbe.getRangeBoard();
        boolean rangeResolved = false;
        if (s.sharedFire && s.sourceBePos != null) {
            var srcBe = sl.getBlockEntity(s.sourceBePos);
            if (srcBe instanceof RailwayManagementBlockEntity srcRmbe
                    && srcRmbe.getAnnouncementConfig().isRangeSharedTo(rmbe.getLinkedStationName())) {
                ItemStack srcRange = srcRmbe.getRangeBoard();
                if (!srcRange.isEmpty()) { rangeBoard = srcRange; rangeResolved = true; }
            }
        }
        if (!rangeResolved) {
            var rangeSrc = rmbe.findRangeShareSource();
            if (rangeSrc != null) {
                ItemStack srcRange = rangeSrc.getRangeBoard();
                if (!srcRange.isEmpty()) rangeBoard = srcRange;
            }
        }
        boolean ok = SasIntegration.playAudio(sl, s.bePos, media, rangeBoard, cfg.isAttenuationMode());
        if (ok) {
            s.isPlaying = true;
            syncPlaybackState(sl, s.bePos, s.currentEntryIdx, true);
        } else {
            s.currentEntryIdx++;
            s.currentPlayIteration = 0;
            advance(server, s, false);
        }
    }

    /** SAS PlaybackEndedEvent: 1 回分の再生完了 → playCount に達していなければ同じ entry を再再生、
     *  達していれば次 entry へ進む。 */
    public static void onPlaybackEnded(belugalab.sas.api.PlaybackEndedEvent event) {
        BlockPos pos = event.getPos();
        if (pos == null) return;
        ServerLevel evLevel = event.getLevel();
        if (evLevel == null) return;
        GlobalPos gk = keyOf(evLevel.dimension(), pos);
        SequenceState s = sequences.get(gk);
        if (s == null) return;
        // 複数 client 同時発火対策: isPlaying が true → false に CAS で切替。先に切替できた呼び出しのみ進める。
        synchronized (s) {
            if (!s.isPlaying) return;
            s.isPlaying = false;
        }
        MinecraftServer server = ServerHolder.get();
        if (server == null) { sequences.remove(gk); return; }
        ServerLevel sl = server.getLevel(s.dim);
        if (sl == null) { sequences.remove(gk); return; }
        if (s.manualTest) {
            removeSequence(sl, s);
            return;
        }
        syncPlaybackState(sl, s.bePos, -1, false);
        var be = sl.getBlockEntity(s.bePos);
        if (!(be instanceof RailwayManagementBlockEntity rmbe)) { removeSequence(sl, s); return; }
        AnnouncementConfig cfg = rmbe.getAnnouncementConfig();
        AnnouncementEntry entry = (s.currentEntryIdx >= 0 && s.currentEntryIdx < cfg.size())
                ? cfg.get(s.currentEntryIdx) : null;
        s.currentPlayIteration++;
        int targetCount = entry != null ? entry.playCount() : 1;
        if (entry != null && s.currentPlayIteration < targetCount) {
            // まだ繰り返し回数に達していない → 同じ entry をすぐに再再生 (delay は最初の発火時のみ)
            firePlayback(server, s);
            return;
        }
        // 規定回数完了 → 次の entry へ
        s.currentEntryIdx++;
        s.currentPlayIteration = 0;
        advance(server, s, false);
    }

    /** Test 再生 entry 単発 (シーケンスを介さず即時)。 */
    public static boolean testPlay(ServerLevel level, BlockPos bePos, int entryIdx) {
        var be = level.getBlockEntity(bePos);
        if (!(be instanceof RailwayManagementBlockEntity rmbe)) return false;
        ItemStack media = rmbe.getAnnouncementMedia(entryIdx);
        if (media.isEmpty() || !SasIntegration.hasAudio(media)) return false;
        boolean ok = SasIntegration.playAudio(level, bePos, media, rmbe.getRangeBoard(),
                rmbe.getAnnouncementConfig().isAttenuationMode());
        if (ok) {
            SequenceState s = new SequenceState(bePos, level.dimension());
            s.currentEntryIdx = entryIdx;
            s.isPlaying = true;
            s.manualTest = true;
            sequences.put(keyOf(level.dimension(), bePos), s);
            syncPlaybackState(level, bePos, entryIdx, true);
        }
        return ok;
    }

    @EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
    public static final class ServerHolder {
        private static volatile MinecraftServer current;
        public static MinecraftServer get() { return current; }
        @SubscribeEvent
        public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
            current = e.getServer();
        }
        @SubscribeEvent
        public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent e) {
            current = null;
            sequences.clear();
            com.trainsystemutilities.detection.TrainDetectionManager.clearAll();
        }
        @SubscribeEvent
        public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
            MinecraftServer s = current;
            if (s != null) AnnouncementScheduler.serverTick(s);
        }
    }
}
