package com.trainsystemutilities.schedule;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.entity.AddTrainPacket;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.trainsystemutilities.TrainSystemUtilities;
import net.createmod.catnip.platform.CatnipServices;
import com.trainsystemutilities.network.TrackNetworkScanner;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.*;

/**
 * 列車の連結・切り離し動作を管理するメインマネージャー。
 */
public class TrainCouplingManager {

    // 連結待機中の列車 (列車ID → 待機駅名)
    private static final Map<UUID, String> couplingWaitingTrains = new java.util.concurrent.ConcurrentHashMap<>();

    // 連結フェーズ (列車ID → フェーズ)
    private static final Map<UUID, CouplingPhase> couplingPhases = new java.util.concurrent.ConcurrentHashMap<>();

    // 切り離し指示
    private static final Map<UUID, DecouplingOrder> decouplingOrders = new java.util.concurrent.ConcurrentHashMap<>();

    // 連結待機中の駅 (駅名 → 待機列車ID)
    private static final Map<String, UUID> waitingAtStation = new java.util.concurrent.ConcurrentHashMap<>();

    // 切り離し後に駅ブロック条件を無視できる列車
    private static final Set<UUID> bypassStationCheck = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 連結後の待機中列車 (列車ID → 残りtick数)
    private static final Map<UUID, Integer> mergeWaitCountdown = new java.util.concurrent.ConcurrentHashMap<>();

    // 連結実行キュー（ConcurrentModificationException回避のため次tickで実行）
    private static final List<PendingMerge> pendingMerges = java.util.Collections.synchronizedList(new ArrayList<>());

    // 切り離し実行キュー（ConcurrentModificationException回避）
    private static final List<PendingSplit> pendingSplits = java.util.Collections.synchronizedList(new ArrayList<>());

    private record PendingSplit(UUID mergedTrainId, String stationName, int waitTimeTicks) {}

    // クライアント同期キュー（スレッド安全: サーバースレッドで書き込み、クライアントスレッドで読み取り）
    private static final java.util.concurrent.ConcurrentLinkedQueue<ClientSyncData> pendingClientSync = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public record ClientSyncData(UUID oldId1, UUID oldId2, Train mergedTrain, UUID stationId) {}

    // 完全停車確認カウンター（列車ID → 連続停車tick数）
    private static final Map<UUID, Integer> stoppedTickCounter = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int REQUIRED_STOPPED_TICKS = 20; // 1秒間完全停車を確認

    private record PendingMerge(UUID waitingTrainId, UUID incomingTrainId) {}

    // 連結前のスケジュールを保存 (元の列車ID → スケジュール, 元のcurrentEntry)
    private static final Map<UUID, SavedSchedule> savedSchedules = new java.util.concurrent.ConcurrentHashMap<>();

    public record SavedSchedule(UUID originalTrainId, com.simibubi.create.content.trains.schedule.Schedule schedule,
                                 int currentEntry, int carriageCount, boolean isFront,
                                 Component trainName) {}

    public record DecouplingOrder(UUID trainId, String stationName, DecouplingPhase phase,
                                   UUID frontTrainId, UUID rearTrainId,
                                   int waitTimeTicks, int ticksWaited) {}

    public enum CouplingPhase {
        WAITING_AT_STATION,
        PARTNER_APPROACHING,
        COUPLING_EXECUTING,
        COMPLETED
    }

    public enum DecouplingPhase {
        ARRIVING, DECOUPLING, FRONT_DEPARTING, WAITING_SIGNAL, REAR_STARTING, COMPLETED
    }

    /**
     * 連結待機を登録する。駅に停車して相手を待つ。
     */
    public static void registerCouplingWait(UUID trainId, String stationName) {
        couplingWaitingTrains.put(trainId, stationName);
        couplingPhases.put(trainId, CouplingPhase.WAITING_AT_STATION);
        waitingAtStation.put(stationName, trainId);
    }

    /**
     * 指定駅に連結待機中の列車があるか。
     */
    public static boolean isStationWaitingForCoupling(String stationName) {
        return waitingAtStation.containsKey(stationName);
    }

    /**
     * 連結待機中の駅名一覧を取得。
     */
    public static Set<String> getWaitingStationNames() {
        return Collections.unmodifiableSet(waitingAtStation.keySet());
    }

    /**
     * 列車が連結のために信号を無視できるかどうか。
     * 条件: この列車自身が連結モード（スケジュールに連結条件あり）かつ
     *       目的地の駅に連結待ちの列車がいる。
     */
    public static boolean canBypassSignal(UUID trainId) {
        // 自分が既に待機中なら不要
        if (couplingWaitingTrains.containsKey(trainId)) return false;

        // この列車が連結モードか確認
        if (!hasTrainCouplingCondition(trainId)) return false;

        // この列車の目的地駅に連結待ちの列車がいるか
        try {
            var trainOpt = TrackNetworkScanner.getTrainById(trainId);
            if (trainOpt.isEmpty()) return false;
            Train train = trainOpt.get();
            if (train.navigation == null || train.navigation.destination == null) return false;

            String destStation = train.navigation.destination.name;
            return waitingAtStation.containsKey(destStation);
        } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Coupling] dest station read failed", e); return false; }
    }

    /**
     * 列車のスケジュールに連結条件があるか確認。
     */
    public static boolean hasTrainCouplingCondition(UUID trainId) {
        // 連結待ち登録済みなら連結モード
        if (couplingWaitingTrains.containsKey(trainId)) return true;

        try {
            var trainOpt = TrackNetworkScanner.getTrainById(trainId);
            if (trainOpt.isEmpty()) return false;
            var train = trainOpt.get();
            if (train.runtime == null || train.runtime.getSchedule() == null) return false;

            var schedule = train.runtime.getSchedule();
            int currentEntry = train.runtime.currentEntry;
            if (currentEntry < 0 || currentEntry >= schedule.entries.size()) return false;

            var entry = schedule.entries.get(currentEntry);
            for (var condList : entry.conditions) {
                for (var cond : condList) {
                    if (cond instanceof CouplingCondition cc && cc.isCouple()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Coupling] schedule condition read failed", e); /* ignore */ }
        return false;
    }

    /**
     * 連結フェーズを取得。
     */
    public static CouplingPhase getCouplingPhase(UUID trainId) {
        return couplingPhases.get(trainId);
    }

    /**
     * モニター表示用: 列車の連結/切り離しステータスとパートナー名を取得。
     * @return [status, partnerName] or null if not coupling/decoupling
     */
    public static String[] getMonitorCouplingStatus(UUID trainId, String stationName) {
        // 連結待機中チェック
        CouplingPhase cPhase = couplingPhases.get(trainId);
        if (cPhase != null && cPhase != CouplingPhase.COMPLETED) {
            String partnerName = findCouplingPartnerName(trainId, stationName);
            String status = switch (cPhase) {
                case WAITING_AT_STATION -> "COUPLE_WAIT";
                case PARTNER_APPROACHING -> "COUPLE_WAIT";
                case COUPLING_EXECUTING -> "COUPLE_DOING";
                default -> "";
            };
            if (!status.isEmpty()) return new String[]{status, partnerName};
        }

        // 切り離しチェック
        DecouplingOrder dOrder = decouplingOrders.get(trainId);
        if (dOrder != null && dOrder.phase() != DecouplingPhase.COMPLETED
                && dOrder.stationName().equals(stationName)) {
            String status = switch (dOrder.phase()) {
                case ARRIVING -> "DECOUPLE_PREP";
                case DECOUPLING -> "DECOUPLE_DOING";
                case FRONT_DEPARTING, WAITING_SIGNAL, REAR_STARTING -> "DECOUPLE_DOING";
                default -> "";
            };
            String partnerName = findDecouplingPartnerNames(dOrder);
            if (!status.isEmpty()) return new String[]{status, partnerName};
        }

        // 切り離し後の新しい列車（frontTrainId/rearTrainId）もチェック
        for (DecouplingOrder order : decouplingOrders.values()) {
            if (order.phase() == DecouplingPhase.COMPLETED) continue;
            if (!order.stationName().equals(stationName)) continue;
            if (trainId.equals(order.frontTrainId()) || trainId.equals(order.rearTrainId())) {
                String status = switch (order.phase()) {
                    case FRONT_DEPARTING, WAITING_SIGNAL, REAR_STARTING -> "DECOUPLE_DOING";
                    default -> "";
                };
                if (!status.isEmpty()) {
                    // パートナーは切り離された相手側
                    UUID partnerId = trainId.equals(order.frontTrainId())
                            ? order.rearTrainId() : order.frontTrainId();
                    String partnerName = getTrainNameById(partnerId);
                    return new String[]{status, partnerName != null ? partnerName : ""};
                }
            }
        }

        return null;
    }

    private static String findCouplingPartnerName(UUID waitingTrainId, String stationName) {
        // 同じ駅に向かっている連結相手を探す
        try {
            for (var entry : Create.RAILWAYS.trains.entrySet()) {
                UUID id = entry.getKey();
                if (id.equals(waitingTrainId)) continue;
                Train train = entry.getValue();
                // 連結モードで同じ駅に向かっている列車
                if (hasTrainCouplingCondition(id) && !couplingWaitingTrains.containsKey(id)) {
                    if (train.navigation != null && train.navigation.destination != null
                            && train.navigation.destination.name.equals(stationName)) {
                        return train.name.getString();
                    }
                }
            }
        } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Coupling] find partner name failed", e); /* ignore */ }
        return "";
    }

    private static String findDecouplingPartnerNames(DecouplingOrder order) {
        // 切り離し後の列車名を取得
        StringBuilder sb = new StringBuilder();
        if (order.frontTrainId() != null) {
            String name = getTrainNameById(order.frontTrainId());
            if (name != null) sb.append(name);
        }
        if (order.rearTrainId() != null) {
            String name = getTrainNameById(order.rearTrainId());
            if (name != null) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(name);
            }
        }
        // 分割前なら保存スケジュールから列車名を取得
        if (sb.length() == 0) {
            for (SavedSchedule ss : savedSchedules.values()) {
                if (ss.trainName() != null) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(ss.trainName().getString());
                }
            }
        }
        return sb.toString();
    }

    private static String getTrainNameById(UUID trainId) {
        if (trainId == null) return null;
        try {
            var opt = TrackNetworkScanner.getTrainById(trainId);
            if (opt.isPresent()) return opt.get().name.getString();
        } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Coupling] train name lookup failed", e); /* ignore */ }
        return null;
    }

    /**
     * 連結待機中の駅にパートナーが到着したか確認し、到着していれば連結を実行する。
     * CouplingCondition.tickCompletion() から呼ばれる。
     *
     * 侵入列車はNavigationMixinで駅手前に停車するため、
     * getCurrentStation()はnullになる。代わりに:
     * - canBypassSignal()==true（連結モードで接近中）
     * - speed==0（停車済み）
     * - 目的地がこの駅
     * で判定する。
     */
    public static boolean checkPartnerArrived(UUID waitingTrainId, String stationName) {
        CouplingPhase phase = couplingPhases.get(waitingTrainId);
        if (phase == CouplingPhase.COMPLETED) return true;

        try {
            for (var entry : Create.RAILWAYS.trains.entrySet()) {
                UUID id = entry.getKey();
                Train incomingTrain = entry.getValue();
                if (id.equals(waitingTrainId)) continue;

                // 連結モードか確認
                if (!hasTrainCouplingCondition(id)) continue;

                // 侵入列車の目的地がこの駅か確認
                boolean destMatch = false;
                if (incomingTrain.navigation != null && incomingTrain.navigation.destination != null) {
                    destMatch = incomingTrain.navigation.destination.name.equals(stationName);
                }
                // または既に駅に到着済み
                if (incomingTrain.getCurrentStation() != null) {
                    destMatch = destMatch || incomingTrain.getCurrentStation().name.equals(stationName);
                }

                if (!destMatch) continue;

                // CRAWLINGフェーズで完全停車した状態でのみ連結を実行
                CouplingApproachTracker.TrackState trackState = CouplingApproachTracker.getOrCreate(id);
                if (trackState.phase != CouplingApproachTracker.Phase.CRAWLING) {
                    stoppedTickCounter.remove(id);
                    continue;
                }

                // 停車済みか確認（速度がほぼ0）
                if (Math.abs(incomingTrain.speed) > 0.005) {
                    stoppedTickCounter.remove(id);
                    continue;
                }

                // 完全停車を一定tick数確認（瞬間的な速度0を拾わない）
                int stoppedTicks = stoppedTickCounter.getOrDefault(id, 0) + 1;
                stoppedTickCounter.put(id, stoppedTicks);
                if (stoppedTicks < REQUIRED_STOPPED_TICKS) continue;

                // 待機列車と侵入列車の物理的距離を確認
                var waitingOpt2 = TrackNetworkScanner.getTrainById(waitingTrainId);
                if (waitingOpt2.isEmpty()) continue;
                Train waitCheck = waitingOpt2.get();

                if (waitCheck.carriages.isEmpty() || incomingTrain.carriages.isEmpty()) continue;
                CarriageContraptionEntity waitEntity = waitCheck.carriages.get(waitCheck.carriages.size() - 1).anyAvailableEntity();
                CarriageContraptionEntity incEntity = incomingTrain.carriages.get(0).anyAvailableEntity();
                if (waitEntity == null || incEntity == null) continue;

                double physicalDist = waitEntity.position().distanceTo(incEntity.position());
                if (physicalDist > 15) continue;


                // 次tickで実行するようキューに入れる（trains反復中のConcurrentModificationException回避）
                pendingMerges.add(new PendingMerge(waitingTrainId, id));
                couplingPhases.put(waitingTrainId, CouplingPhase.COUPLING_EXECUTING);
                return false;
            }
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Coupling] Error checking partner arrival", e);
        }

        return false;
    }

    /**
     * 2つの列車を1つに合体する。
     * waitingTrain（前方）の後ろに incomingTrain（後方）のキャリッジを追加する。
     *
     * @param waitingTrain 駅で待機中の列車（前方）
     * @param incomingTrain 侵入してきた列車（後方）
     * @return 成功したらtrue
     */
    private static boolean executeMerge(Train waitingTrain, Train incomingTrain) {
        try {

            // 両方のスケジュールを保存（切り離し時に復元するため）。
            // TSU-22: merged train ID で front/rear を scope して保存する。 旧実装は merge ごとに global
            // clear() してから 1 組保存し split は first-match で引いていたため、 2 組目を merge すると 1 組目の
            // schedule が消え、 1 組目の split が誤った carriage 数/schedule を使っていた。 scoped getter が
            // 他 merge の entry を無視するため global clear は不要 (= per-split cleanup で entry は除去される)。
            UUID mergedId = UUID.randomUUID();
            saveSchedule(waitingTrain, true, mergedId);   // 先頭（待機列車）
            saveSchedule(incomingTrain, false, mergedId); // 後尾（進入列車）

            // キャリッジリストを結合
            List<Carriage> mergedCarriages = new ArrayList<>(waitingTrain.carriages);
            mergedCarriages.addAll(incomingTrain.carriages);

            // スペーシングを結合（間のスペーシングを計算して追加）
            List<Integer> mergedSpacing = new ArrayList<>(waitingTrain.carriageSpacing);

            // 待機列車の最後尾ボギーと侵入列車の先頭ボギーの間のスペーシング
            int gapSpacing = calculateGapSpacing(waitingTrain, incomingTrain);
            mergedSpacing.add(gapSpacing);

            mergedSpacing.addAll(incomingTrain.carriageSpacing);

            // 新しい合体列車を作成
            boolean doubleEnded = waitingTrain.doubleEnded || incomingTrain.doubleEnded;
            Train mergedTrain = new Train(
                    mergedId,
                    waitingTrain.owner,
                    waitingTrain.graph,
                    mergedCarriages,
                    mergedSpacing,
                    doubleEnded,
                    Component.literal(waitingTrain.name.getString() + " + " + incomingTrain.name.getString()),
                    waitingTrain.icon,
                    waitingTrain.mapColorIndex
            );

            // 信号ブロックの再計算（旧列車の信号情報を引き継ぐ）
            try {
                mergedTrain.collectInitiallyOccupiedSignalBlocks();
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.warn("[Coupling] Signal block recalculation failed, continuing", e);
            }

            // 待機列車のスケジュールを合体列車に引き継ぐ
            // 連結条件の次のエントリから再開する
            transferScheduleToMergedTrain(waitingTrain, mergedTrain);

            // 旧列車の駅予約をクリア（新列車が正しく駅を予約できるように）
            try {
                if (waitingTrain.getCurrentStation() != null) {
                    waitingTrain.getCurrentStation().cancelReservation(waitingTrain);
                }
                if (incomingTrain.getCurrentStation() != null) {
                    incomingTrain.getCurrentStation().cancelReservation(incomingTrain);
                }
                // ナビゲーション先の駅予約もクリア
                if (waitingTrain.navigation.destination != null) {
                    waitingTrain.navigation.destination.cancelReservation(waitingTrain);
                }
                if (incomingTrain.navigation.destination != null) {
                    incomingTrain.navigation.destination.cancelReservation(incomingTrain);
                }
            } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Coupling] cancel reservation on merge failed", e); /* ignore */ }

            // 連結駅の参照を保持（後で合体列車に設定するため）
            com.simibubi.create.content.trains.station.GlobalStation couplingStation = waitingTrain.getCurrentStation();

            // 旧列車を削除、新列車を登録
            Create.RAILWAYS.removeTrain(waitingTrain.id);
            Create.RAILWAYS.removeTrain(incomingTrain.id);
            Create.RAILWAYS.addTrain(mergedTrain);

            // クライアントに新列車を通知（駅表示・列車認識に必要）
            // RemoveTrainPacketは送らない（エンティティが消えるため）
            CatnipServices.NETWORK.sendToAllClients(new AddTrainPacket(mergedTrain));

            // 合体列車を連結駅に紐付ける（駅ブロックが列車を認識するため）
            if (couplingStation != null) {
                mergedTrain.currentStation = couplingStation.id;
                couplingStation.reserveFor(mergedTrain);

                // 検証ログ
                Train check = couplingStation.getImminentTrain();
            }

            // クライアント同期用にキューに追加（クライアントtickで処理）
            pendingClientSync.add(new ClientSyncData(waitingTrain.id, incomingTrain.id, mergedTrain,
                    couplingStation != null ? couplingStation.id : null));

            // 連結後の待機時間: 両列車のmax
            CouplingPersistentData pd = CouplingPersistentData.get();
            var frontData = pd.getFrontSchedule(mergedTrain.id);
            var rearData = pd.getRearSchedule(mergedTrain.id);
            int frontWait = frontData != null ? frontData.waitTimeSeconds() : 5;
            int rearWait = rearData != null ? rearData.waitTimeSeconds() : 5;
            int mergeWait = Math.max(frontWait, rearWait) * 20; // ticks
            mergeWaitCountdown.put(mergedTrain.id, mergeWait);
            // 待機中はスケジュールを一時停止
            mergedTrain.runtime.paused = true;

            // 全ての連結状態をクリーンアップ
            cleanupAfterMerge(waitingTrain.id, incomingTrain.id);


            return true;
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Coupling] Merge failed!", e);
            return false;
        }
    }

    /**
     * 2つの列車の間のスペーシング（ボギー間距離）を計算する。
     *
     * Createの carriageSpacing は隣接キャリッジのボギー間のトラック距離。
     * 通常の2ボギーキャリッジ同士なら bogeySpacing と同程度の値。
     *
     * 停車時の物理的な隙間は1ブロック。ボギーの実際の位置関係から計算する。
     */
    /**
     * 連結時のスペーシングを計算する。
     * エンティティの実際の位置から距離を取得し、bogeySpacingの半分を差し引いて
     * ボギー間のトラック距離を算出する。
     */
    private static int calculateGapSpacing(Train waitingTrain, Train incomingTrain) {
        try {
            if (waitingTrain.carriages.isEmpty() || incomingTrain.carriages.isEmpty()) return 2;
            Carriage lastWaiting = waitingTrain.carriages.get(waitingTrain.carriages.size() - 1);
            Carriage firstIncoming = incomingTrain.carriages.get(0);

            CarriageContraptionEntity lastEntity = lastWaiting.anyAvailableEntity();
            CarriageContraptionEntity firstEntity = firstIncoming.anyAvailableEntity();

            if (lastEntity != null && firstEntity != null) {
                // エンティティ位置はキャリッジの中心（ボギー間の中点）
                double entityDist = lastEntity.position().distanceTo(firstEntity.position());
                return gapSpacing(entityDist, lastWaiting.bogeySpacing, firstIncoming.bogeySpacing);
            }
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Coupling] Failed to calculate gap spacing", e);
        }
        return 2;
    }

    /**
     * ボギー間トラック距離の純式 (Minecraft 非依存)。エンティティ間距離から各キャリッジの
     * ボギー→端の半分 (bogeySpacing/2) を引き、最小 1 にクランプする。
     */
    static int gapSpacing(double entityDist, double lastBogeySpacing, double firstBogeySpacing) {
        double adjustment = lastBogeySpacing / 2.0 + firstBogeySpacing / 2.0;
        return Math.max((int) Math.round(entityDist - adjustment), 1);
    }

    /**
     * 連結完了後に全ての状態をクリーンアップする。
     */
    private static void cleanupAfterMerge(UUID waitingId, UUID incomingId) {
        // 待機状態のクリア
        String station = couplingWaitingTrains.remove(waitingId);
        couplingWaitingTrains.remove(incomingId);
        couplingPhases.remove(waitingId);
        couplingPhases.remove(incomingId);

        if (station != null) {
            waitingAtStation.remove(station);
        }
        // 全てのwaitingAtStationから旧IDを削除
        waitingAtStation.values().removeIf(id -> id.equals(waitingId) || id.equals(incomingId));

        // 信号点滅を停止
        if (station != null) {
            CouplingSignalController.clearStation(station);
        }

        // アプローチトラッカーとカウンターをクリア
        CouplingApproachTracker.remove(waitingId);
        CouplingApproachTracker.remove(incomingId);
        stoppedTickCounter.remove(waitingId);
        stoppedTickCounter.remove(incomingId);

    }

    /**
     * 待機列車のスケジュールを合体列車に引き継ぐ。
     * 連結条件があるエントリの次から再開する。
     */
    private static void transferScheduleToMergedTrain(Train sourceTrain, Train mergedTrain) {
        try {
            if (sourceTrain.runtime == null || sourceTrain.runtime.getSchedule() == null) {
                return;
            }

            var schedule = sourceTrain.runtime.getSchedule();
            int currentEntry = sourceTrain.runtime.currentEntry;

            if (schedule.entries.isEmpty()) {
                return;
            }

            // 連結条件の次のエントリに進める
            int nextEntry = currentEntry + 1;
            if (nextEntry >= schedule.entries.size()) {
                nextEntry = 0; // ループ
            }

            // savedProgressを設定してsetScheduleで正しいエントリから再開
            schedule.savedProgress = nextEntry;
            mergedTrain.runtime.setSchedule(schedule, false);

        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Coupling] Failed to transfer schedule", e);
        }
    }

    /**
     * 列車のスケジュールを永続保存する（切り離し時に復元するため）。
     * CouplingPersistentDataを使ってワールドに保存。
     * @param isFront true=先頭（待機列車）, false=後尾（進入列車）
     */
    private static void saveSchedule(Train train, boolean isFront, UUID mergedTrainId) {
        if (train.runtime != null && train.runtime.getSchedule() != null) {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                var registries = server.registryAccess();
                // 切り離し条件の待機時間を優先して保存（切り離し後に使用するため）
                int waitSeconds = extractWaitTimeFromSchedule(train.runtime.getSchedule(), true);
                if (waitSeconds <= 0) {
                    waitSeconds = extractWaitTimeFromSchedule(train.runtime.getSchedule(), false);
                }
                CouplingPersistentData.get().saveSchedule(
                        train.id, train.id, mergedTrainId, train.runtime.getSchedule(),
                        train.runtime.currentEntry, train.carriages.size(),
                        isFront, train.name, waitSeconds, registries);
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.error("[Coupling] Failed to save schedule", e);
            }
        }
    }

    /**
     * スケジュールからCouplingConditionの待機時間を抽出する。
     * @param decouple true=切り離し条件の待機時間, false=連結条件の待機時間
     */
    private static int extractWaitTimeFromSchedule(Schedule schedule, boolean decouple) {
        for (int i = 0; i < schedule.entries.size(); i++) {
            var entry = schedule.entries.get(i);
            for (var condList : entry.conditions) {
                for (var cond : condList) {
                    if (cond instanceof CouplingCondition cc) {
                        if (decouple && cc.isDecouple()) return cc.getWaitTimeSeconds();
                        if (!decouple && cc.isCouple()) return cc.getWaitTimeSeconds();
                    }
                }
            }
        }
        TrainSystemUtilities.LOGGER.warn("[WaitTime] No matching condition found (decouple={}), returning default 5", decouple);
        return 5;
    }

    /**
     * 連結完了処理。
     */
    public static void completeCoupling(UUID trainId) {
        String stationName = couplingWaitingTrains.remove(trainId);
        couplingPhases.remove(trainId);
        if (stationName != null) {
            waitingAtStation.remove(stationName);
            CouplingSignalController.clearStation(stationName);
        }
    }

    /**
     * クライアント同期処理。
     * 旧列車をクライアントのmovingTrains/waitingTrainsから除外し、
     * クライアント側のtickによるカクカクを防ぐ。
     * trainsマップからは削除しない（エンティティ消失防止）。
     */
    public static void processClientSync() {
        ClientSyncData sync;
        while ((sync = pendingClientSync.poll()) != null) {
            try {
                removeFromClientTickLists(sync.oldId1());
                removeFromClientTickLists(sync.oldId2());
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * クライアントのmovingTrains/waitingTrainsから旧列車を除外する。
     * trainsマップには残す（エンティティがdiscardされないため）。
     */
    private static void removeFromClientTickLists(UUID trainId) {
        if (trainId == null) return;
        try {
            var clientRailways = com.simibubi.create.CreateClient.RAILWAYS;

            // movingTrainsから削除（privateフィールド）
            java.lang.reflect.Field movingField = clientRailways.getClass().getDeclaredField("movingTrains");
            movingField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<Train> movingTrains = (java.util.List<Train>) movingField.get(clientRailways);
            movingTrains.removeIf(t -> t.id.equals(trainId));

            // waitingTrainsから削除
            java.lang.reflect.Field waitingField = clientRailways.getClass().getDeclaredField("waitingTrains");
            waitingField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<Train> waitingTrains = (java.util.List<Train>) waitingField.get(clientRailways);
            waitingTrains.removeIf(t -> t.id.equals(trainId));

        } catch (Exception e) {
            // ignore on dedicated server or reflection failure
        }
    }

    /**
     * 連結後の待機処理。ServerTickEvent.Postから呼ぶこと。
     */
    public static void processMergeWait() {
        if (mergeWaitCountdown.isEmpty()) return;

        var iter = mergeWaitCountdown.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            UUID trainId = entry.getKey();
            int remaining = entry.getValue() - 1;

            var trainOpt = TrackNetworkScanner.getTrainById(trainId);
            if (trainOpt.isEmpty()) {
                iter.remove();
                continue;
            }

            Train train = trainOpt.get();
            if (remaining <= 0) {
                // 待機完了 → スケジュール再開
                train.runtime.paused = false;
                iter.remove();
            } else {
                // 待機中 → 停止を維持
                train.speed = 0;
                train.targetSpeed = 0;
                entry.setValue(remaining);
            }
        }
    }

    /**
     * 保留中の連結処理を実行する。trains反復外から呼ぶこと。
     */
    public static void processPendingMerges() {
        if (pendingMerges.isEmpty()) return;

        List<PendingMerge> toProcess = new ArrayList<>(pendingMerges);
        pendingMerges.clear();

        for (PendingMerge pm : toProcess) {
            try {
                var waitingOpt = TrackNetworkScanner.getTrainById(pm.waitingTrainId());
                var incomingOpt = TrackNetworkScanner.getTrainById(pm.incomingTrainId());

                if (waitingOpt.isEmpty() || incomingOpt.isEmpty()) {
                    TrainSystemUtilities.LOGGER.warn("[Coupling] Pending merge: train not found");
                    continue;
                }

                boolean success = executeMerge(waitingOpt.get(), incomingOpt.get());
                if (success) {
                    couplingPhases.put(pm.waitingTrainId(), CouplingPhase.COMPLETED);
                }
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.error("[Coupling] Pending merge failed", e);
            }
        }
    }

    private static int cleanupCounter = 0;

    /**
     * 定期クリーンアップ: RAILWAYSに存在しない列車のエントリを除去。
     */
    public static void cleanupStaleEntries() {
        if (++cleanupCounter < 200) return; // 10秒ごと
        cleanupCounter = 0;
        var trains = Create.RAILWAYS.trains;
        stoppedTickCounter.keySet().removeIf(id -> !trains.containsKey(id));
        mergeWaitCountdown.keySet().removeIf(id -> !trains.containsKey(id));
        couplingPhases.entrySet().removeIf(e ->
                e.getValue() == CouplingPhase.COMPLETED && !trains.containsKey(e.getKey()));
    }

    /**
     * ScheduleRuntime.tick() から呼ばれる。
     */
    public static void processScheduleTick(Train train) {
        // 連結待機のクリーンアップ: 登録列車が駅にいなくなったら解除
        String waitingStation = couplingWaitingTrains.get(train.id);
        if (waitingStation != null) {
            GlobalStation current = train.getCurrentStation();
            if (current == null || !current.name.equals(waitingStation)) {
                // 駅を離れた → 連結待機解除
                completeCoupling(train.id);
            }
        }

        // 切り離し指示の処理
        DecouplingOrder dOrder = decouplingOrders.get(train.id);
        if (dOrder != null) {
            processDecouplingTick(train, dOrder);
        }
    }

    /**
     * 駅到着時に呼ばれる (ScheduleRuntimeMixin から)。
     */
    public static void onStationReached(Train train) {
        GlobalStation station = train.getCurrentStation();
        if (station == null) return;

    }

    /**
     * Train.tick() から呼ばれる。
     */
    public static void onTrainTick(Train train) {
        if (bypassStationCheck.contains(train.id)) {
            if (train.speed != 0) {
                bypassStationCheck.remove(train.id);
            }
        }
    }

    // ===== 切り離し（後回し） =====

    public static void registerDecouplingOrder(UUID trainId, String stationName, int waitSeconds) {
        decouplingOrders.put(trainId, new DecouplingOrder(
                trainId, stationName, DecouplingPhase.ARRIVING, null, null,
                waitSeconds * 20, 0));
    }

    public static DecouplingOrder getDecouplingOrder(UUID trainId) {
        return decouplingOrders.get(trainId);
    }

    public static boolean hasDecouplingOrder(UUID trainId) {
        return decouplingOrders.containsKey(trainId);
    }

    /**
     * 分割後の列車（frontTrainId/rearTrainId）として切り離しに関与している場合、駅名を返す。
     */
    public static String findDecouplingStationForSplitTrain(UUID trainId) {
        for (DecouplingOrder order : decouplingOrders.values()) {
            if (order.phase() == DecouplingPhase.COMPLETED) continue;
            if (trainId.equals(order.frontTrainId()) || trainId.equals(order.rearTrainId())) {
                return order.stationName();
            }
        }
        return null;
    }

    /**
     * 指定駅で切り離しが進行中かどうか。
     */
    public static boolean isStationDecoupling(String stationName) {
        return decouplingOrders.values().stream()
                .anyMatch(o -> o.stationName().equals(stationName)
                        && o.phase() != DecouplingPhase.COMPLETED);
    }

    /**
     * 切り離しフェーズを毎tick処理する。
     *
     * ARRIVING: 駅到着確認 → 分割キューに追加
     * DECOUPLING: executeSplitが完了するまで待機
     * FRONT_DEPARTING: 両列車が待機時間完了後、先頭が発車するのを待つ
     * WAITING_SIGNAL: 先頭が去った後、後尾が駅ブロックへ移動
     * REAR_STARTING: 後尾が駅に到達して待機時間完了
     * COMPLETED: 完了
     */
    private static void processDecouplingTick(Train train, DecouplingOrder order) {
        switch (order.phase()) {
            case ARRIVING -> {
                // 駅に停車したら分割キューに追加
                if (train.getCurrentStation() != null
                        && train.getCurrentStation().name.equals(order.stationName())) {

                    // 信号点滅開始
                    CouplingSignalController.registerDecoupling(
                            order.stationName(), null, null);

                    // 次tickで分割実行（trains反復中のConcurrentModificationException回避）
                    pendingSplits.add(new PendingSplit(train.id, order.stationName(), order.waitTimeTicks()));

                    decouplingOrders.put(train.id, new DecouplingOrder(
                            train.id, order.stationName(), DecouplingPhase.DECOUPLING,
                            order.frontTrainId(), order.rearTrainId(),
                            order.waitTimeTicks(), 0));

                }
            }
            case DECOUPLING -> {
                // executeSplitがprocessPendingSplitsで処理されるまで待機
            }
            case FRONT_DEPARTING -> {
                // 両列車に待機時間を適用
                int waited = order.ticksWaited() + 1;
                if (waited < order.waitTimeTicks()) {
                    // まだ待機中
                    decouplingOrders.put(train.id, new DecouplingOrder(
                            train.id, order.stationName(), DecouplingPhase.FRONT_DEPARTING,
                            order.frontTrainId(), order.rearTrainId(),
                            order.waitTimeTicks(), waited));

                    // 両列車を停止状態に保つ
                    holdTrainStopped(order.frontTrainId());
                    holdTrainStopped(order.rearTrainId());
                } else {
                    // 待機完了 → 先頭列車は自動的にスケジュールで発車
                    // 後尾は先頭が去るのを待つ
                    decouplingOrders.put(train.id, new DecouplingOrder(
                            train.id, order.stationName(), DecouplingPhase.WAITING_SIGNAL,
                            order.frontTrainId(), order.rearTrainId(),
                            order.waitTimeTicks(), waited));

                }
            }
            case WAITING_SIGNAL -> {
                // 先頭が去ったか確認
                var frontOpt = TrackNetworkScanner.getTrainById(order.frontTrainId());
                boolean frontGone = frontOpt.isEmpty()
                        || frontOpt.get().getCurrentStation() == null
                        || !frontOpt.get().getCurrentStation().name.equals(order.stationName());

                if (frontGone) {
                    // 後尾を駅ブロックへ移動させるためbypass設定
                    bypassStationCheck.add(order.rearTrainId());

                    decouplingOrders.put(train.id, new DecouplingOrder(
                            train.id, order.stationName(), DecouplingPhase.REAR_STARTING,
                            order.frontTrainId(), order.rearTrainId(),
                            order.waitTimeTicks(), 0));

                } else {
                    // 先頭がまだいる → 後尾を停止に保つ
                    holdTrainStopped(order.rearTrainId());
                }
            }
            case REAR_STARTING -> {
                // 後尾が駅に到達したかチェック
                var rearOpt = TrackNetworkScanner.getTrainById(order.rearTrainId());
                if (rearOpt.isPresent()) {
                    Train rearTrain = rearOpt.get();
                    if (rearTrain.getCurrentStation() != null
                            && rearTrain.getCurrentStation().name.equals(order.stationName())) {
                        // 駅到達 → 完了
                        cleanupDecoupling(order);
                    }
                }
            }
            case COMPLETED -> {}
        }
    }

    /**
     * 列車を指定の駅へナビゲーションさせる。
     */
    private static void navigateTrainToStation(Train train, String stationName) {
        try {
            if (train.graph == null) return;
            for (var station : train.graph.getPoints(com.simibubi.create.content.trains.graph.EdgePointType.STATION)) {
                if (station.name.equals(stationName)) {
                    var path = train.navigation.findPathTo(station, Double.MAX_VALUE);
                    if (path != null) {
                        train.navigation.startNavigation(path);
                    } else {
                        TrainSystemUtilities.LOGGER.warn("[Decoupling] No path found for rear to '{}'", stationName);
                    }
                    return;
                }
            }
            TrainSystemUtilities.LOGGER.warn("[Decoupling] Station '{}' not found in graph", stationName);
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Decoupling] Failed to navigate rear train", e);
        }
    }

    private static void holdTrainStopped(UUID trainId) {
        if (trainId == null) return;
        var opt = TrackNetworkScanner.getTrainById(trainId);
        if (opt.isPresent()) {
            opt.get().speed = 0;
            opt.get().targetSpeed = 0;
        }
    }

    private static void holdTrainStoppedWithPause(UUID trainId) {
        if (trainId == null) return;
        var opt = TrackNetworkScanner.getTrainById(trainId);
        if (opt.isPresent()) {
            opt.get().speed = 0;
            opt.get().targetSpeed = 0;
            if (opt.get().runtime != null) {
                opt.get().runtime.paused = true;
            }
        }
    }

    private static void cleanupDecoupling(DecouplingOrder order) {
        decouplingOrders.put(order.trainId(), new DecouplingOrder(
                order.trainId(), order.stationName(), DecouplingPhase.COMPLETED,
                order.frontTrainId(), order.rearTrainId(),
                order.waitTimeTicks(), order.ticksWaited()));

        bypassStationCheck.remove(order.rearTrainId());
        CouplingSignalController.clearStation(order.stationName());
    }

    /**
     * 保留中の分割処理を実行し、進行中の切り離しフェーズをtickする。
     * ServerTickEvent.Postから呼ぶこと。
     */
    public static void processPendingSplits() {
        // 保留中の分割を実行
        if (!pendingSplits.isEmpty()) {
            List<PendingSplit> toProcess = new ArrayList<>(pendingSplits);
            pendingSplits.clear();

            for (PendingSplit ps : toProcess) {
                try {
                    var trainOpt = TrackNetworkScanner.getTrainById(ps.mergedTrainId());
                    if (trainOpt.isEmpty()) {
                        TrainSystemUtilities.LOGGER.warn("[Decoupling] Split: merged train not found");
                        continue;
                    }
                    executeSplit(trainOpt.get(), ps.stationName(), ps.waitTimeTicks());
                } catch (Exception e) {
                    TrainSystemUtilities.LOGGER.error("[Decoupling] Split failed", e);
                }
            }
        }

        // 進行中の切り離しフェーズをtick（合体列車が消えた後も処理を続けるため）
        if (!decouplingOrders.isEmpty()) {
            // ConcurrentModification回避のためコピーして反復
            for (var entry : new ArrayList<>(decouplingOrders.entrySet())) {
                DecouplingOrder order = entry.getValue();
                if (order.phase() == DecouplingPhase.COMPLETED) continue;
                if (order.phase() == DecouplingPhase.ARRIVING || order.phase() == DecouplingPhase.DECOUPLING) continue;
                // FRONT_DEPARTING以降は合体列車がないので独立してtick
                processDecouplingTickIndependent(order);
            }

            // COMPLETED状態のオーダーを削除
            decouplingOrders.entrySet().removeIf(e ->
                    e.getValue().phase() == DecouplingPhase.COMPLETED);
        }
    }

    /**
     * 合体列車が消えた後のフェーズ処理（FRONT_DEPARTING以降）。
     */
    private static void processDecouplingTickIndependent(DecouplingOrder order) {
        switch (order.phase()) {
            case FRONT_DEPARTING -> {
                // 先頭の待機時間をPersistentDataから取得
                CouplingPersistentData pd = CouplingPersistentData.get();
                var frontData = pd.getFrontSchedule(order.trainId());
                int frontWaitTicks = frontData != null ? frontData.waitTimeSeconds() * 20 : order.waitTimeTicks();

                int waited = order.ticksWaited() + 1;
                if (waited < frontWaitTicks) {
                    decouplingOrders.put(order.trainId(), new DecouplingOrder(
                            order.trainId(), order.stationName(), DecouplingPhase.FRONT_DEPARTING,
                            order.frontTrainId(), order.rearTrainId(),
                            order.waitTimeTicks(), waited));
                    // 両列車を完全停止（スケジュールも一時停止）
                    holdTrainStoppedWithPause(order.frontTrainId());
                    holdTrainStoppedWithPause(order.rearTrainId());
                } else {
                    // 先頭の待機完了 → 先頭のスケジュール再開
                    var frontOpt = TrackNetworkScanner.getTrainById(order.frontTrainId());
                    if (frontOpt.isPresent() && frontOpt.get().runtime != null) {
                        frontOpt.get().runtime.paused = false;
                    }
                    decouplingOrders.put(order.trainId(), new DecouplingOrder(
                            order.trainId(), order.stationName(), DecouplingPhase.WAITING_SIGNAL,
                            order.frontTrainId(), order.rearTrainId(),
                            order.waitTimeTicks(), waited));
                }
            }
            case WAITING_SIGNAL -> {
                // 後尾を完全停止（スケジュールも一時停止）
                var rearOpt = TrackNetworkScanner.getTrainById(order.rearTrainId());
                if (rearOpt.isPresent()) {
                    rearOpt.get().speed = 0;
                    rearOpt.get().targetSpeed = 0;
                    if (rearOpt.get().runtime != null) {
                        rearOpt.get().runtime.paused = true;
                    }
                }

                // 先頭が駅ブロックマーカーから離れたか確認
                var frontOpt = TrackNetworkScanner.getTrainById(order.frontTrainId());
                boolean frontGone = frontOpt.isEmpty()
                        || frontOpt.get().getCurrentStation() == null
                        || !frontOpt.get().getCurrentStation().name.equals(order.stationName());

                if (frontGone) {
                    // 後尾を駅へ移動させる
                    if (rearOpt.isPresent()) {
                        Train rearTrain = rearOpt.get();
                        if (rearTrain.runtime != null) {
                            rearTrain.runtime.paused = false;
                        }
                        // 駅へのナビゲーションを開始
                        navigateTrainToStation(rearTrain, order.stationName());
                    }
                    bypassStationCheck.add(order.rearTrainId());
                    decouplingOrders.put(order.trainId(), new DecouplingOrder(
                            order.trainId(), order.stationName(), DecouplingPhase.REAR_STARTING,
                            order.frontTrainId(), order.rearTrainId(),
                            order.waitTimeTicks(), 0));
                }
            }
            case REAR_STARTING -> {
                var rearOpt = TrackNetworkScanner.getTrainById(order.rearTrainId());
                if (rearOpt.isPresent()) {
                    Train rearTrain = rearOpt.get();
                    if (rearTrain.getCurrentStation() != null
                            && rearTrain.getCurrentStation().name.equals(order.stationName())) {

                        // 後尾の待機時間をPersistentDataから取得
                        CouplingPersistentData pd = CouplingPersistentData.get();
                        CouplingPersistentData.SavedScheduleData rearData = pd.getByTrainId(order.rearTrainId());
                        int rearWaitTicks = rearData != null ? rearData.waitTimeSeconds() * 20 : order.waitTimeTicks();

                        int waited = order.ticksWaited() + 1;
                        if (waited < rearWaitTicks) {
                            // 後尾待機中
                            rearTrain.speed = 0;
                            rearTrain.targetSpeed = 0;
                            decouplingOrders.put(order.trainId(), new DecouplingOrder(
                                    order.trainId(), order.stationName(), DecouplingPhase.REAR_STARTING,
                                    order.frontTrainId(), order.rearTrainId(),
                                    order.waitTimeTicks(), waited));
                        } else {
                            // 後尾待機完了 → スケジュール復元・発車
                            if (rearData != null) {
                                MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
                                restoreScheduleFromPersistent(rearTrain, rearData, srv.registryAccess());
                                pd.remove(order.rearTrainId());
                            }
                            cleanupDecoupling(order);
                        }
                    }
                } else {
                    // 後尾列車が見つからない → クリーンアップ
                    CouplingPersistentData.get().remove(order.rearTrainId());
                    cleanupDecoupling(order);
                    TrainSystemUtilities.LOGGER.warn("[Decoupling] Rear train lost, forcing cleanup");
                }
            }
            default -> {}
        }
    }

    /**
     * 合体列車を2つに分割する。executeMergeの逆操作。
     */
    private static boolean executeSplit(Train mergedTrain, String stationName, int waitTimeTicks) {
        // CouplingPersistentDataからisFrontフラグで先頭/後尾を正しく特定
        CouplingPersistentData persistentData = CouplingPersistentData.get();
        CouplingPersistentData.SavedScheduleData frontData = persistentData.getFrontSchedule(mergedTrain.id);
        CouplingPersistentData.SavedScheduleData rearData = persistentData.getRearSchedule(mergedTrain.id);
        if (frontData == null || rearData == null) {
            TrainSystemUtilities.LOGGER.warn("[Decoupling] Could not find front/rear saved schedules (front={}, rear={})",
                    frontData != null, rearData != null);
            return false;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        var registries = server.registryAccess();

        int frontCount = frontData.carriageCount();
        int totalCount = mergedTrain.carriages.size();

        if (frontCount >= totalCount || frontCount <= 0) {
            TrainSystemUtilities.LOGGER.error("[Decoupling] Invalid split point: front={} total={}", frontCount, totalCount);
            return false;
        }


        // キャリッジを分割
        List<Carriage> frontCarriages = new ArrayList<>(mergedTrain.carriages.subList(0, frontCount));
        List<Carriage> rearCarriages = new ArrayList<>(mergedTrain.carriages.subList(frontCount, totalCount));

        // スペーシングを分割（接合部のスペーシングは除去）
        List<Integer> frontSpacing = new ArrayList<>(mergedTrain.carriageSpacing.subList(0, frontCount - 1));
        List<Integer> rearSpacing = new ArrayList<>(mergedTrain.carriageSpacing.subList(frontCount, totalCount - 1));

        // 新しい列車を作成（保存した元の名前を使用）
        Train frontTrain = new Train(
                UUID.randomUUID(), mergedTrain.owner, mergedTrain.graph,
                frontCarriages, frontSpacing, false,
                Component.literal(frontData.trainName()),
                mergedTrain.icon, mergedTrain.mapColorIndex);

        Train rearTrain = new Train(
                UUID.randomUUID(), mergedTrain.owner, mergedTrain.graph,
                rearCarriages, rearSpacing, false,
                Component.literal(rearData.trainName()),
                mergedTrain.icon, mergedTrain.mapColorIndex);

        // 信号ブロック再計算
        try { frontTrain.collectInitiallyOccupiedSignalBlocks(); } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Decoupling] front signal-block collect failed", e); /* ignore */ }
        try { rearTrain.collectInitiallyOccupiedSignalBlocks(); } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Decoupling] rear signal-block collect failed", e); /* ignore */ }

        // スケジュール復元: 先頭のみ。後尾は駅到達後に復元する
        restoreScheduleFromPersistent(frontTrain, frontData, registries);

        // 駅の列車参照: 合体列車を解除し、frontTrainに引き継ぐ
        com.simibubi.create.content.trains.station.GlobalStation splitStation = mergedTrain.getCurrentStation();
        try {
            if (splitStation != null) {
                splitStation.cancelReservation(mergedTrain);
            }
            mergedTrain.currentStation = null;
        } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Decoupling] merged reservation clear failed", e); /* ignore */ }

        // 旧列車削除、新列車登録
        Create.RAILWAYS.removeTrain(mergedTrain.id);
        Create.RAILWAYS.addTrain(frontTrain);
        Create.RAILWAYS.addTrain(rearTrain);

        // frontTrainに切り離し駅を設定（モニター検出用）
        if (splitStation != null) {
            try {
                frontTrain.currentStation = splitStation.id;
                splitStation.reserveFor(frontTrain);
            } catch (Exception e) { TrainSystemUtilities.LOGGER.debug("[Decoupling] front station reserve failed", e); /* ignore */ }
        }

        // クライアントに新列車を通知（RemoveTrainPacketは送らない）
        CatnipServices.NETWORK.sendToAllClients(new AddTrainPacket(frontTrain));
        CatnipServices.NETWORK.sendToAllClients(new AddTrainPacket(rearTrain));

        // DecouplingOrderを更新
        decouplingOrders.put(mergedTrain.id, new DecouplingOrder(
                mergedTrain.id, stationName, DecouplingPhase.FRONT_DEPARTING,
                frontTrain.id, rearTrain.id,
                waitTimeTicks, 0));

        // 先頭のデータをクリア、後尾は新しいIDで再登録（駅到達後に復元するため）
        persistentData.remove(frontData.originalTrainId());
        persistentData.put(rearTrain.id, rearData);


        return true;
    }

    /**
     * 保存されたスケジュールを列車に復元する。
     * 切り離し条件のエントリを探し、その次のエントリから再開。
     */
    private static void restoreSchedule(Train train, SavedSchedule saved) {
        try {
            if (saved.schedule() == null || saved.schedule().entries.isEmpty()) return;

            // ディープコピーしてから復元（複数列車で共有しないため）
            Schedule schedule;
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                var registries = server.registryAccess();
                schedule = Schedule.fromTag(registries, saved.schedule().write(registries));
            } catch (Exception e) {
                schedule = saved.schedule();
            }

            // 切り離し条件のエントリを探す
            int decoupleEntry = -1;
            for (int i = 0; i < schedule.entries.size(); i++) {
                var entry = schedule.entries.get(i);
                for (var condList : entry.conditions) {
                    for (var cond : condList) {
                        if (cond instanceof CouplingCondition cc && cc.isDecouple()) {
                            decoupleEntry = i;
                            break;
                        }
                    }
                    if (decoupleEntry >= 0) break;
                }
                if (decoupleEntry >= 0) break;
            }

            int startEntry;
            if (decoupleEntry >= 0) {
                startEntry = decoupleEntry + 1;
            } else {
                startEntry = saved.currentEntry() + 1;
            }
            if (startEntry >= schedule.entries.size()) {
                startEntry = 0;
            }

            // 行先指示（DestinationInstruction）までスキップ
            // 名前変更などの非行先エントリから開始すると経路が見つからない
            int resumeEntry = startEntry;
            for (int attempt = 0; attempt < schedule.entries.size(); attempt++) {
                var entry = schedule.entries.get(resumeEntry);
                if (entry.instruction instanceof DestinationInstruction) {
                    break;
                }
                resumeEntry = (resumeEntry + 1) % schedule.entries.size();
            }

            // 復元先のエントリの行先を確認
            String destName = "unknown";
            if (resumeEntry < schedule.entries.size()) {
                var resumeInstructions = schedule.entries.get(resumeEntry).instruction;
                if (resumeInstructions != null) {
                    destName = resumeInstructions.getData().getString("Text");
                }
            }

            schedule.savedProgress = resumeEntry;
            train.runtime.setSchedule(schedule, false);

        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Decoupling] Failed to restore schedule", e);
        }
    }

    /**
     * CouplingPersistentDataからスケジュールを復元する。
     */
    private static void restoreScheduleFromPersistent(Train train, CouplingPersistentData.SavedScheduleData data,
                                                       net.minecraft.core.HolderLookup.Provider registries) {
        try {
            Schedule schedule = Schedule.fromTag(registries, data.scheduleNbt());
            if (schedule.entries.isEmpty()) return;

            // 切り離し条件のエントリを探す
            int decoupleEntry = -1;
            for (int i = 0; i < schedule.entries.size(); i++) {
                var entry = schedule.entries.get(i);
                for (var condList : entry.conditions) {
                    for (var cond : condList) {
                        if (cond instanceof CouplingCondition cc && cc.isDecouple()) {
                            decoupleEntry = i;
                            break;
                        }
                    }
                    if (decoupleEntry >= 0) break;
                }
                if (decoupleEntry >= 0) break;
            }

            int startEntry = decoupleEntry >= 0 ? decoupleEntry + 1 : data.currentEntry() + 1;
            if (startEntry >= schedule.entries.size()) startEntry = 0;

            // DestinationInstructionまでスキップ
            int resumeEntry = startEntry;
            for (int attempt = 0; attempt < schedule.entries.size(); attempt++) {
                if (schedule.entries.get(resumeEntry).instruction instanceof DestinationInstruction) break;
                resumeEntry = (resumeEntry + 1) % schedule.entries.size();
            }

            String destName = "unknown";
            if (resumeEntry < schedule.entries.size() && schedule.entries.get(resumeEntry).instruction != null) {
                destName = schedule.entries.get(resumeEntry).instruction.getData().getString("Text");
            }

            schedule.savedProgress = resumeEntry;
            train.runtime.setSchedule(schedule, false);

        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.error("[Decoupling] Failed to restore schedule from persistent data", e);
        }
    }

    public static boolean canBypassStationCheck(UUID trainId) {
        return bypassStationCheck.contains(trainId);
    }

    // measureMaxOverhang は com.trainsystemutilities.schedule.CouplingGeometry に分離 (B: god-class 縮小)。

    /**
     * 停車距離を動的に計算する。
     *
     * 方向判定を完全に廃止。各キャリッジの両端のオーバーハングの
     * 大きい方を使用する。どちら側にブロックを追加しても検出できる。
     *
     * 停車距離 = ボギー間トラック距離
     *          + 待機列車最後尾の最大オーバーハング
     *          + 隙間(1ブロック)
     *          + 侵入列車先頭の最大オーバーハング
     */
    public static double calculateStopDistance(String stationName, Train incomingTrain) {
        UUID waitingId = waitingAtStation.get(stationName);
        if (waitingId == null) return 5;
        var waitingOpt = TrackNetworkScanner.getTrainById(waitingId);
        if (waitingOpt.isEmpty()) return 5;
        // geometry は CouplingGeometry に分離 (B: god-class 縮小)。
        return CouplingGeometry.calculateStopDistance(waitingOpt.get(), incomingTrain);
    }
}
