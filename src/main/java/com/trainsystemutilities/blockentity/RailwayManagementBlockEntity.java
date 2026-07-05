package com.trainsystemutilities.blockentity;

import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.eta.TrainArrivalCalculator;
import com.trainsystemutilities.schedule.TrainCouplingManager;
import com.trainsystemutilities.schedule.TrainScheduleReader;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * 鉄道管理ブロック：駅にリンクして使用。
 * リンクした駅の名前、到着列車、停車時間を管理する。
 */
public class RailwayManagementBlockEntity extends BlockEntity implements Container {

    private String linkedStationName = null;
    private BlockPos linkedStationPos = null;
    private int scanCooldown = 0;
    private static final int SCAN_INTERVAL = 20; // 1 second
    private ItemStack monitorLinkCard = ItemStack.EMPTY;
    private int linkedMonitorGroupCount = 0;

    // === Phase 18: SAS 統合 (アナウンス機能) ===
    /** 検知カード (slot 1)。 */
    private ItemStack detectionCard = ItemStack.EMPTY;
    /** SAS 範囲指定ボード (slot 2)。 */
    private ItemStack rangeBoard = ItemStack.EMPTY;
    /** アナウンスエントリ媒体 (slot 3..3+MAX_ENTRIES-1)。 */
    private final java.util.List<ItemStack> announcementMedia = new java.util.ArrayList<>();
    // god-class 分割 (v2): アナウンス (config + detection listener + 共有元探索) を
    // AnnouncementManager へ切出し。 検知 card / 範囲ボード / 媒体 slot 自体は inventory slot に在置。
    private final AnnouncementManager announcement = new AnnouncementManager();
    // god-class 分割 (v2): ホームドア (帯色 + 発火条件 + detection listener) を ScreenDoorController へ切出し。
    // メモリーカード自体は下の inventory slot (screenDoorCard) に在置。
    private final ScreenDoorController screenDoor = new ScreenDoorController();

    // === Phase 21: ホームドア群管理 ===
    /** メモリーカード (= screen_door_group)。 group の members から ホームドア BE 群を制御。 */
    private ItemStack screenDoorCard = ItemStack.EMPTY;
    // 帯色 / 発火条件 / 上限は ScreenDoorController (上の screenDoor) へ移設。

    /** Slot allocation:
     *   0      = monitor link card (existing)
     *   1      = detection card
     *   2      = range board
     *   3..18  = per-entry recording media (MAX_ENTRIES = 16)
     *   19     = screen door memory card
     */
    private static final int SLOT_MONITOR_CARD = 0;
    private static final int SLOT_DETECTION_CARD = 1;
    private static final int SLOT_RANGE_BOARD = 2;
    private static final int SLOT_MEDIA_BASE = 3;
    private static final int SLOT_SCREEN_DOOR_CARD = SLOT_MEDIA_BASE
            + com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES;
    private static final int TOTAL_SLOTS = SLOT_SCREEN_DOOR_CARD + 1;

    {
        // Initialize media slot list to MAX_ENTRIES empty stacks
        for (int i = 0; i < com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES; i++) {
            announcementMedia.add(ItemStack.EMPTY);
        }
    }
    /** Positions of monitors we last linked. Persisted so that, when the link
     *  card is removed, we can find the previously-linked monitors and clear
     *  their {@code linkedRailwayManagerPos}. Otherwise their displays would
     *  keep rendering stale data. */
    private final java.util.Set<BlockPos> linkedMonitorPositions = new java.util.HashSet<>();
    private boolean monitorEnabled = true;

    // ドア開く方向 (Create DoorControl と同じ方角ベース)
    public enum DoorSide {
        AUTO,   // ALL: Create デフォルト (両側)
        NORTH,  // 北側
        SOUTH,  // 南側
        EAST,   // 東側
        WEST,   // 西側
        NONE    // 自動開閉なし
    }
    private DoorSide doorOpenSide = DoorSide.AUTO;

    public DoorSide getDoorOpenSide() { return doorOpenSide; }
    public void setDoorOpenSide(DoorSide side) {
        this.doorOpenSide = side != null ? side : DoorSide.AUTO;
        setChanged();
        if (level != null && !level.isClientSide()) {
            applyDoorOpenSideToStation();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** リンク済 Create 駅 BE の doorControls に現在の doorOpenSide を反映。
     *  Create reflection / getName().endsWith 判定は StationDoorControl に隔離 (B: god-class 縮小)。 */
    private void applyDoorOpenSideToStation() {
        if (level == null || level.isClientSide() || linkedStationPos == null) return;
        StationDoorControl.applyDoorControl(level, linkedStationPos, mapToCreateDoorControl());
    }

    /** 現在の doorOpenSide を Create DoorControl enum 名に変換。 */
    private String mapToCreateDoorControl() {
        return switch (doorOpenSide) {
            case NONE -> "NONE";
            case NORTH -> "NORTH";
            case SOUTH -> "SOUTH";
            case EAST -> "EAST";
            case WEST -> "WEST";
            case AUTO -> "ALL";
        };
    }

    // 路線記号(管理コンピューターから割り当て)
    private LineSymbol assignedLineSymbol = null;
    // LineSymbolStore を自 NBT で一度 seed したか (transient, migration/computer 先行ロード対策)
    private boolean lineSymbolSeeded = false;
    // 管理コンピューターへの逆リンク
    private BlockPos linkedComputerPos = null;

    // 管理者モード
    private UUID ownerUUID = null;
    private String ownerName = "";
    private boolean privateMode = false;
    private boolean batchApply = true;
    // god-class 分割 (v2): monitor 表示設定 (global track/clock + 路線記号 + 行色 35 件) を切出し。
    private final MonitorDisplaySettings display = new MonitorDisplaySettings();

    // Monitor group info for client display
    public record MonitorGroupInfo(BlockPos masterPos, int width, int height, boolean doubleSided,
                                      int trackNumber, int trackFontSize, int trackPosition,
                                      int backTrackNumber, int backTrackFontSize, int backTrackPosition,
                                      int clockVisible, int clockFontSize, int backClockVisible, int backClockFontSize) {}
    private final List<MonitorGroupInfo> monitorGroups = new ArrayList<>();

    // Arrived trains info
    private final List<ArrivedTrain> arrivedTrains = new ArrayList<>();
    // Next expected trains
    private final List<NextTrain> nextTrains = new ArrayList<>();
    // Recently departed (for animation)
    private final List<UUID> recentlyDeparted = new ArrayList<>();

    public record ArrivedTrain(UUID id, String name, long arrivalTick, long arrivalDayTime,
                                int carriageCount, String destination, int scheduledStopSec,
                                String routeType, String trainType,
                                String couplingStatus, String couplingPartner) {}
    public record NextTrain(String name, int carriageCount, String fromStation, String routeType,
                             int scheduledStopSec, long estimatedArrivalDayTime, String currentStopStation,
                             boolean isApproaching, String trainType) {}

    public RailwayManagementBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RAILWAY_MANAGEMENT.get(), pos, state);
    }

    // --- Station link ---

    public void linkToStation(String stationName, BlockPos stationPos) {
        this.linkedStationName = stationName;
        this.linkedStationPos = stationPos;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public String getLinkedStationName() { return linkedStationName; }
    public BlockPos getLinkedStationPos() { return linkedStationPos; }
    public LineSymbol getAssignedLineSymbol() { return assignedLineSymbol; }
    public BlockPos getLinkedComputerPos() { return linkedComputerPos; }
    public void setLinkedComputerPos(BlockPos pos) { this.linkedComputerPos = pos; setChanged(); }
    public void setAssignedLineSymbol(LineSymbol symbol) {
        this.assignedLineSymbol = symbol;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public List<ArrivedTrain> getArrivedTrains() { return arrivedTrains; }
    public List<NextTrain> getNextTrains() { return nextTrains; }
    public List<UUID> getRecentlyDeparted() { return recentlyDeparted; }

    /**
     * 割り当て路線記号を {@link com.trainsystemutilities.station.LineSymbolStore} から解決する
     * (= chunk load 非依存)。 管理用コンピューターは割り当て時に store へ書き込むだけでよく、
     * 遠隔駅の本 BE でも (load 済みなら) ここで自駅キーを引いて反映する。 変化時のみ block update。
     */
    private void resolveLineSymbol() {
        if (level == null || level.isClientSide()) return;
        if (linkedStationName == null || linkedStationName.isEmpty()) return;
        var server = level.getServer();
        if (server == null) return;
        var store = com.trainsystemutilities.station.LineSymbolStore.get(server);
        String key = ManagementComputerBlockEntity.stationKey(linkedStationName, linkedStationPos);
        LineSymbol resolved = store.getSymbol(key);
        // migration / computer 先行ロード対策: store が自駅ぶん未 populate かつ自身は割り当て済なら、
        // 自 NBT のシンボルで store を一度だけ seed して現状維持する (= 既存割り当てを消さない)。
        // 以後は store が権威 (computer の sync が seed を上書き / 削除できる)。
        if (resolved == null && !lineSymbolSeeded && assignedLineSymbol != null) {
            store.setSymbol(key, assignedLineSymbol);
            lineSymbolSeeded = true;
            return;
        }
        lineSymbolSeeded = true;
        if (resolved == assignedLineSymbol) return;          // 定常状態: 同一 snapshot / 両 null
        if (symbolsEqual(resolved, assignedLineSymbol)) {    // 値同一 (= reload 直後): block update 無しで採用
            assignedLineSymbol = resolved;
            return;
        }
        setAssignedLineSymbol(resolved);                     // 実変化: setChanged + sendBlockUpdated
    }

    private static boolean symbolsEqual(LineSymbol a, LineSymbol b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.save().equals(b.save());
    }

    // --- Tick: scan for trains at linked station ---

    public static void tick(Level level, BlockPos pos, BlockState state,
                            RailwayManagementBlockEntity be) {
        if (level.isClientSide()) return;

        be.resolveLineSymbol();

        be.scanCooldown--;
        if (be.scanCooldown <= 0) {
            be.scanCooldown = SCAN_INTERVAL;
            be.updateTrainInfo();
            be.updateMonitorLinks();
        }
    }

    private void updateTrainInfo() {
        if (level == null || linkedStationName == null || linkedStationName.isEmpty()) return;

        // 実測データ収集（全列車の駅間移動を監視）
        measureTrainTravelTimes();

        List<ArrivedTrain> newArrivals = new ArrayList<>();
        long currentTick = level.getGameTime();

        try {
            com.simibubi.create.Create.RAILWAYS.trains.forEach((id, train) -> {
                if (train.getCurrentStation() != null
                        && linkedStationName.equals(train.getCurrentStation().name)) {
                    // Train is at our station
                    // Check if it was already tracked
                    ArrivedTrain existing = null;
                    for (ArrivedTrain at : arrivedTrains) {
                        if (at.id().equals(id)) { existing = at; break; }
                    }

                    // Calculate scheduled stop from train schedule
                    int carriages = train.carriages.size();
                    String destination = TrainScheduleReader.getNextDestination(train);
                    int stopSec = TrainScheduleReader.getScheduledStopSeconds(train);

                    // 連結/切り離しステータスを取得
                    String couplingStatus = "";
                    String couplingPartner = "";
                    String[] cStatus = TrainCouplingManager.getMonitorCouplingStatus(id, linkedStationName);
                    if (cStatus != null) {
                        couplingStatus = cStatus[0];
                        couplingPartner = cStatus[1];
                    }

                    if (existing != null) {
                        // ステータスが変わった場合は更新
                        if (!couplingStatus.equals(existing.couplingStatus())
                                || !couplingPartner.equals(existing.couplingPartner())) {
                            newArrivals.add(new ArrivedTrain(
                                    existing.id(), existing.name(), existing.arrivalTick(), existing.arrivalDayTime(),
                                    carriages, destination, stopSec, existing.routeType(), existing.trainType(),
                                    couplingStatus, couplingPartner));
                        } else {
                            newArrivals.add(existing);
                        }
                    } else {
                        String routeType = TrainScheduleReader.routeTypeForStation(train, linkedStationName);
                        String trainType = TrainScheduleReader.detectTrainType(train);
                        long dayTime = level.getDayTime();
                        newArrivals.add(new ArrivedTrain(
                                id, train.name.getString(), currentTick, dayTime, carriages, destination, stopSec,
                                routeType, trainType, couplingStatus, couplingPartner));
                        fireScreenDoorConditionEvent(
                                com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP);

                        // 到着ログ: 予測との比較
                        String cachePrefix = id.toString() + ":";
                        for (var entry : arrivalEstimateCache.entrySet()) {
                            if (entry.getKey().startsWith(cachePrefix)) {
                                long estimatedDayTime = entry.getValue();
                                long diff = dayTime - estimatedDayTime;
                                com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrainArrival] train=" + train.name.getString()
                                        + " station=" + linkedStationName
                                        + " actualDayTime=" + dayTime
                                        + " estimatedDayTime=" + estimatedDayTime
                                        + " diff=" + diff + "t"
                                        + " diffMinMC=" + String.format("%.1f", diff * 60.0 / 1000)
                                        + " gameTick=" + currentTick);
                                break;
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            // Create API errors は無視して継続するが、観測性のため debug ログに残す
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "updateTrainInfo: Create train scan failed for station {}", linkedStationName, e);
        }

        // 切り離し中の列車を追加検出（getCurrentStation()がnullでもDecouplingOrderから検出）
        if (TrainCouplingManager.isStationDecoupling(linkedStationName)) {
            try {
                Set<UUID> alreadyTracked = new HashSet<>();
                for (ArrivedTrain at : newArrivals) alreadyTracked.add(at.id());

                com.simibubi.create.Create.RAILWAYS.trains.forEach((id, train) -> {
                    if (alreadyTracked.contains(id)) return;
                    // この列車が切り離しでこの駅に関与しているか
                    String decouplingStation = TrainCouplingManager.findDecouplingStationForSplitTrain(id);
                    if (linkedStationName.equals(decouplingStation)) {
                        String couplingStatus = "";
                        String couplingPartner = "";
                        String[] cStatus = TrainCouplingManager.getMonitorCouplingStatus(id, linkedStationName);
                        if (cStatus != null) {
                            couplingStatus = cStatus[0];
                            couplingPartner = cStatus[1];
                        }
                        ArrivedTrain existing = null;
                        for (ArrivedTrain at : arrivedTrains) {
                            if (at.id().equals(id)) { existing = at; break; }
                        }
                        if (existing != null) {
                            newArrivals.add(new ArrivedTrain(
                                    existing.id(), existing.name(), existing.arrivalTick(), existing.arrivalDayTime(),
                                    train.carriages.size(), "", 0, existing.routeType(), existing.trainType(),
                                    couplingStatus, couplingPartner));
                        } else {
                            newArrivals.add(new ArrivedTrain(
                                    id, train.name.getString(), level.getGameTime(), level.getDayTime(),
                                    train.carriages.size(), "", 0, "", "",
                                    couplingStatus, couplingPartner));
                            fireScreenDoorConditionEvent(
                                    com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP);
                        }
                    }
                });
            } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] arrival scan failed for {}", linkedStationName, e); /* ignore */ }
        }

        // Detect departures
        recentlyDeparted.clear();
        for (ArrivedTrain old : arrivedTrains) {
            boolean stillHere = false;
            for (ArrivedTrain na : newArrivals) {
                if (na.id().equals(old.id())) { stillHere = true; break; }
            }
            if (!stillHere) {
                recentlyDeparted.add(old.id());
                fireScreenDoorConditionEvent(
                        com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_DEPART);
            }
        }

        // Find next arriving trains (trains heading to our station but not yet here)
        nextTrains.clear();
        try {
            com.simibubi.create.Create.RAILWAYS.trains.forEach((id, train) -> {
                if (train.getCurrentStation() == null || !linkedStationName.equals(train.getCurrentStation().name)) {
                    // Not at our station - check if heading here
                    if (TrainScheduleReader.isHeadingToStation(train, linkedStationName)) {
                        String rt = TrainScheduleReader.routeTypeForStation(train, linkedStationName);
                        String tt = TrainScheduleReader.detectTrainType(train);
                        String currentStop = train.getCurrentStation() != null ? train.getCurrentStation().name : "";
                        String from = currentStop;
                        int stopSec = TrainScheduleReader.getStopSecondsAtStation(train, linkedStationName);
                        long estArrival = estimateArrivalDayTime(train);
                        boolean approaching = TrainScheduleReader.isTrainApproaching(train, linkedStationName);
                        nextTrains.add(new NextTrain(train.name.getString(), train.carriages.size(), from, rt, stopSec, estArrival, currentStop, approaching, tt));
                    }
                }
            });
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Railway] next-train scan failed for {}", linkedStationName, e); /* ignore */ }

        boolean changed = !arrivedTrains.equals(newArrivals) || !recentlyDeparted.isEmpty();
        arrivedTrains.clear();
        arrivedTrains.addAll(newArrivals);

        if (changed) {
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ===== 実測ベースの到着時刻予測システム =====
    // god-class 分割: ETA 計算 subsystem を com.trainsystemutilities.eta.TrainArrivalCalculator へ
    // verbatim 移設。 static map / 定数 / nested record / lifecycle 登録は移設先に在置。
    // BE は this.level / this.linkedStationName / this.arrivalEstimateCache を引数化して委譲する。

    // 到着予定キャッシュ (BE 残留: estimateArrivalDayTime に引数で渡す)
    private final java.util.Map<String, Long> arrivalEstimateCache = new java.util.HashMap<>();

    // 駅位置キャッシュは com.trainsystemutilities.blockentity.StationPosCache に分離 (B: god-class 縮小)。

    /** {@link TrainArrivalCalculator#getPublicLegTravelTicks} への thin delegator (外部呼出し元互換)。 */
    public static long getPublicLegTravelTicks(String fromStation, String toStation) {
        return TrainArrivalCalculator.getPublicLegTravelTicks(fromStation, toStation);
    }

    /** {@link TrainArrivalCalculator#getPublicLegTravelTicksForTrain} への thin delegator (外部呼出し元互換)。 */
    public static long getPublicLegTravelTicksForTrain(UUID trainId, String fromStation, String toStation) {
        return TrainArrivalCalculator.getPublicLegTravelTicksForTrain(trainId, fromStation, toStation);
    }

    /** {@link TrainArrivalCalculator#computeWaitTicksForEntry} への thin delegator (外部呼出し元互換)。 */
    public static long computeWaitTicksForEntry(
            com.simibubi.create.content.trains.schedule.ScheduleEntry entry) {
        return TrainArrivalCalculator.computeWaitTicksForEntry(entry);
    }

    /** {@link TrainArrivalCalculator#computeStationWaitTicks} への thin delegator (外部呼出し元互換)。 */
    public static long computeStationWaitTicks(UUID trainId, String stationName,
            com.simibubi.create.content.trains.schedule.ScheduleEntry entry) {
        return TrainArrivalCalculator.computeStationWaitTicks(trainId, stationName, entry);
    }

    /** {@link TrainArrivalCalculator#getPublicTrainDepartureTick} への thin delegator (外部呼出し元互換)。 */
    public static Long getPublicTrainDepartureTick(UUID trainId) {
        return TrainArrivalCalculator.getPublicTrainDepartureTick(trainId);
    }

    /** {@link TrainArrivalCalculator#getPublicTrainDepartureStation} への thin delegator (外部呼出し元互換)。 */
    public static String getPublicTrainDepartureStation(UUID trainId) {
        return TrainArrivalCalculator.getPublicTrainDepartureStation(trainId);
    }

    /** {@link TrainArrivalCalculator#getPublicStationArrivalTick} への thin delegator (外部呼出し元互換)。 */
    public static Long getPublicStationArrivalTick(UUID trainId) {
        return TrainArrivalCalculator.getPublicStationArrivalTick(trainId);
    }

    /** {@link TrainArrivalCalculator#getAnchoredArrivalGameTick} への thin delegator (外部呼出し元互換)。 */
    public static long getAnchoredArrivalGameTick(
            Level level,
            com.simibubi.create.content.trains.entity.Train train,
            UUID trainId,
            String fromStation,
            String toStation) {
        return TrainArrivalCalculator.getAnchoredArrivalGameTick(level, train, trainId, fromStation, toStation);
    }

    /** {@link TrainArrivalCalculator#getAnchoredArrivalDayTime} への thin delegator (外部呼出し元互換)。 */
    public static long getAnchoredArrivalDayTime(
            Level level,
            com.simibubi.create.content.trains.entity.Train train,
            UUID trainId,
            String fromStation,
            String toStation) {
        return TrainArrivalCalculator.getAnchoredArrivalDayTime(level, train, trainId, fromStation, toStation);
    }

    /** {@link TrainArrivalCalculator#measureTrainTravelTimes} への thin delegator (this.level を引数化)。 */
    private void measureTrainTravelTimes() {
        TrainArrivalCalculator.measureTrainTravelTimes(level);
    }

    /** {@link TrainArrivalCalculator#estimateArrivalDayTime} への thin delegator
     *  (this.level / this.linkedStationName / this.arrivalEstimateCache を引数化)。 */
    private long estimateArrivalDayTime(com.simibubi.create.content.trains.entity.Train train) {
        return TrainArrivalCalculator.estimateArrivalDayTime(level, linkedStationName, arrivalEstimateCache, train);
    }

    // --- Container implementation ---
    // Slot allocation: 0 = monitor link card, 1 = detection card, 2 = range board,
    //                  3..18 = per-entry recording media (MAX_ENTRIES = 16)

    @Override public int getContainerSize() { return TOTAL_SLOTS; }

    @Override
    public boolean isEmpty() {
        if (!monitorLinkCard.isEmpty()) return false;
        if (!detectionCard.isEmpty()) return false;
        if (!rangeBoard.isEmpty()) return false;
        for (ItemStack s : announcementMedia) if (!s.isEmpty()) return false;
        if (!screenDoorCard.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot == SLOT_MONITOR_CARD) return monitorLinkCard;
        if (slot == SLOT_DETECTION_CARD) return detectionCard;
        if (slot == SLOT_RANGE_BOARD) return rangeBoard;
        if (slot == SLOT_SCREEN_DOOR_CARD) return screenDoorCard;
        int mediaIdx = slot - SLOT_MEDIA_BASE;
        if (mediaIdx >= 0 && mediaIdx < announcementMedia.size()) {
            return announcementMedia.get(mediaIdx);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = getItem(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stack.split(amount);
        setChanged();
        if (slot == SLOT_MONITOR_CARD) updateMonitorLinks();
        if (slot == SLOT_DETECTION_CARD) refreshDetectionListener();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result;
        if (slot == SLOT_MONITOR_CARD) { result = monitorLinkCard; monitorLinkCard = ItemStack.EMPTY; updateMonitorLinks(); }
        else if (slot == SLOT_DETECTION_CARD) { result = detectionCard; detectionCard = ItemStack.EMPTY; refreshDetectionListener(); }
        else if (slot == SLOT_RANGE_BOARD) { result = rangeBoard; rangeBoard = ItemStack.EMPTY; }
        else if (slot == SLOT_SCREEN_DOOR_CARD) {
            result = screenDoorCard; screenDoorCard = ItemStack.EMPTY;
            refreshScreenDoorDetectionListener();
            syncScreenDoorCardToClient();
        }
        else {
            int mediaIdx = slot - SLOT_MEDIA_BASE;
            if (mediaIdx >= 0 && mediaIdx < announcementMedia.size()) {
                result = announcementMedia.get(mediaIdx);
                announcementMedia.set(mediaIdx, ItemStack.EMPTY);
            } else result = ItemStack.EMPTY;
        }
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == SLOT_MONITOR_CARD) { monitorLinkCard = stack; updateMonitorLinks(); }
        else if (slot == SLOT_DETECTION_CARD) { detectionCard = stack; refreshDetectionListener(); }
        else if (slot == SLOT_RANGE_BOARD) { rangeBoard = stack; }
        else if (slot == SLOT_SCREEN_DOOR_CARD) {
            screenDoorCard = stack;
            refreshScreenDoorDetectionListener();
            syncScreenDoorCardToClient();
        }
        else {
            int mediaIdx = slot - SLOT_MEDIA_BASE;
            if (mediaIdx >= 0 && mediaIdx < announcementMedia.size()) {
                announcementMedia.set(mediaIdx, stack);
            }
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_MONITOR_CARD) {
            return stack.getItem() instanceof com.trainsystemutilities.item.MonitorLinkCardItem;
        }
        if (slot == SLOT_DETECTION_CARD) {
            // 他の rmbe から「検知カード共有先」として指定されている場合は自前のカードを置けない
            if (isSharedDetectionTarget()) return false;
            return stack.getItem() instanceof com.trainsystemutilities.item.TrainDetectionCardItem;
        }
        if (slot == SLOT_RANGE_BOARD) {
            // 「範囲指定ボード共有先」として指定されている場合は置けない
            if (isSharedRangeTarget()) return false;
            // SAS 必須: SasApi.isRangeBoard でチェック (SAS 未導入時は常に false)
            return com.trainsystemutilities.compat.sas.SasIntegration.isRangeBoard(stack);
        }
        if (slot == SLOT_SCREEN_DOOR_CARD) {
            // メモリーカード (= screen_door_group 登録済) のみ受付
            if (!(stack.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem)) return false;
            if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return false;
            var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            return com.trainsystemutilities.item.MemoryCardItem.TYPE_SCREEN_DOOR_GROUP
                    .equals(tag.getString("Type"));
        }
        int mediaIdx = slot - SLOT_MEDIA_BASE;
        if (mediaIdx >= 0 && mediaIdx < announcementMedia.size()) {
            return com.trainsystemutilities.compat.sas.SasIntegration.isRecordingMedium(stack);
        }
        return false;
    }

    @Override
    public void clearContent() {
        monitorLinkCard = ItemStack.EMPTY;
        detectionCard = ItemStack.EMPTY;
        rangeBoard = ItemStack.EMPTY;
        for (int i = 0; i < announcementMedia.size(); i++) announcementMedia.set(i, ItemStack.EMPTY);
        screenDoorCard = ItemStack.EMPTY;
        refreshDetectionListener();
        refreshScreenDoorDetectionListener();
    }

    // === Phase 21: ホームドア群 getters/setters ===
    public ItemStack getScreenDoorCard() { return screenDoorCard; }
    public int getScreenDoorBandColorARGB() { return screenDoor.getBandColorARGB(); }
    public void setScreenDoorBandColorARGB(int argb) {
        screenDoor.setBandColorARGB(argb);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public java.util.List<com.trainsystemutilities.screendoor.ScreenDoorCondition> getScreenDoorConditions() {
        return screenDoor.getConditions();
    }
    public void addScreenDoorCondition(com.trainsystemutilities.screendoor.ScreenDoorCondition c) {
        if (screenDoor.addCondition(c)) screenDoorChanged();
    }
    public void removeScreenDoorCondition(int idx) {
        if (screenDoor.removeCondition(idx)) screenDoorChanged();
    }
    public void updateScreenDoorCondition(int idx, com.trainsystemutilities.screendoor.ScreenDoorCondition c) {
        if (screenDoor.updateCondition(idx, c)) screenDoorChanged();
    }
    private void screenDoorChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    private void fireScreenDoorConditionEvent(int eventType) {
        com.trainsystemutilities.screendoor.ScreenDoorConditionEvaluator.fire(this, eventType);
    }

    // === Phase 18: Announcement helpers ===

    public ItemStack getDetectionCard() { return detectionCard; }
    public ItemStack getRangeBoard() { return rangeBoard; }
    public ItemStack getAnnouncementMedia(int entryIndex) {
        return (entryIndex >= 0 && entryIndex < announcementMedia.size())
                ? announcementMedia.get(entryIndex) : ItemStack.EMPTY;
    }
    public com.trainsystemutilities.announce.AnnouncementConfig getAnnouncementConfig() {
        return announcement.getConfig();
    }
    public void setAnnouncementConfig(com.trainsystemutilities.announce.AnnouncementConfig cfg) {
        announcement.setConfig(cfg);
        setChanged();
    }

    /**
     * Entry の reorder 時に媒体 slot も「from→to」へ移動 (List.remove(from) + add(to,..) と同じ semantics)。
     * これで entry metadata と媒体 ItemStack の対応が崩れない。
     */
    public void swapAnnouncementMedia(int from, int to) {
        if (from < 0 || from >= announcementMedia.size()) return;
        if (to < 0 || to >= announcementMedia.size()) return;
        if (from == to) return;
        ItemStack moved = announcementMedia.remove(from);
        announcementMedia.add(to, moved);
        setChanged();
    }

    /** Entry 削除時: entryIndex の媒体を取り出して返し、 後続の媒体を 1 つ前へシフト
     *  (entry.remove と同じ semantics で entry↔媒体 の整列を保つ)。 リスト長 = MAX_ENTRIES は維持。
     *  取り出した媒体は caller がプレイヤーへ返却する。 server-side のみ呼ぶ。 */
    public ItemStack removeAnnouncementMedia(int entryIndex) {
        if (entryIndex < 0 || entryIndex >= announcementMedia.size()) return ItemStack.EMPTY;
        ItemStack removed = announcementMedia.remove(entryIndex);
        announcementMedia.add(ItemStack.EMPTY);
        setChanged();
        // MP: overlay slot の menu container 同期は不安定なことがあるため、 BE NBT 同期も発火 (= screen-door card と同方針)
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return removed;
    }

    /** 検知カードを共有されている (= 検知スロットがロックされる)。 */
    public boolean isSharedDetectionTarget() {
        return announcement.isSharedDetectionTarget(this);
    }

    /** 範囲指定ボードを共有されている (= 範囲指定スロットがロックされる)。 */
    public boolean isSharedRangeTarget() {
        return announcement.isSharedRangeTarget(this);
    }

    /** 共有元の情報 1 件: source station 名 + どの種別を共有しているか。 */
    public static final class IncomingShareInfo {
        public final String sourceStationName;
        public final boolean detection;
        public final boolean range;
        public IncomingShareInfo(String sourceStationName, boolean detection, boolean range) {
            this.sourceStationName = sourceStationName;
            this.detection = detection;
            this.range = range;
        }
    }

    /**
     * この rmbe を共有先に指定している source 一覧 (検知 / 範囲指定 のどちらか以上を共有している駅)。
     * アナウンス GUI の「○○から〜を共有」表示と、スロットロックの両方で使う。client/server 両対応。
     */
    public java.util.List<IncomingShareInfo> getIncomingShareSources() {
        return announcement.getIncomingShareSources(this);
    }

    /**
     * 範囲指定ボード共有元の rmbe を返す (sharedRangeTo にこの rmbe の station が登録されている source)。
     * 複数あれば最初の一つ。なければ null。スケジューラの音声再生で source の range board を使うために使う。
     */
    public RailwayManagementBlockEntity findRangeShareSource() {
        return announcement.findRangeShareSource(this);
    }

    /** Detection card の bind 状態に応じて listener を更新 (AnnouncementManager へ委譲)。 */
    public void refreshDetectionListener() {
        announcement.refreshListener(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // BE removal 時に detection listener を解除 (chunk unload 含む)
        announcement.unregisterListener();
        unregisterScreenDoorDetectionListeners();
    }

    /** Phase 21: ホームドア group の detection listener を再登録 (ScreenDoorController へ委譲)。 */
    public void refreshScreenDoorDetectionListener() {
        screenDoor.refreshListener(this);
    }

    /** MP desync 対策: screen-door card slot は menu container 同期が落ちて client slot が空になる
     *  ことがある (= 「カードを入れると消える」)。 BE NBT 同期 (getUpdateTag に ScreenDoorCard を含む)
     *  を明示発火し、 独立チャネルで確実に client BE → menu slot へカードを届ける。 server-side のみ。 */
    private void syncScreenDoorCardToClient() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void unregisterScreenDoorDetectionListeners() {
        screenDoor.unregisterListeners();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // NBT 読み込み後の初期化: detection listener 登録 (server-side のみ)
        if (level != null && !level.isClientSide()) {
            refreshDetectionListener();
            refreshScreenDoorDetectionListener();
        }
    }

    public int getLinkedMonitorGroupCount() { return linkedMonitorGroupCount; }
    public ItemStack getMonitorLinkCard() { return monitorLinkCard; }
    public boolean isMonitorEnabled() { return monitorEnabled; }

    // 管理者モード
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getOwnerName() { return ownerName; }
    public boolean isPrivateMode() { return privateMode; }
    public void setOwner(UUID uuid, String name) {
        this.ownerUUID = uuid; this.ownerName = name; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void togglePrivateMode() {
        privateMode = !privateMode; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public boolean canAccess(net.minecraft.world.entity.player.Player player) {
        if (!privateMode) return true;
        if (ownerUUID == null) return true;
        return ownerUUID.equals(player.getUUID());
    }
    public void toggleMonitorEnabled() {
        monitorEnabled = !monitorEnabled;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public boolean isBatchApply() { return batchApply; }
    public void toggleBatchApply() {
        batchApply = !batchApply;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public int getGlobalTrackNumber() { return display.getGlobalTrackNumber(); }
    public void setGlobalTrackNumber(int num) {
        display.setGlobalTrackNumber(num);
        if (batchApply) applyTrackNumberToAll(num);
        updateMonitorLinks();
    }
    public List<MonitorGroupInfo> getMonitorGroups() { return monitorGroups; }

    public int getGlobalTrackFontSize() { return display.getGlobalTrackFontSize(); }
    public void setGlobalTrackFontSize(int size) {
        display.setGlobalTrackFontSize(size);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setTrackFontSizeForGroup(size));
        updateMonitorLinks();
    }

    public int getGlobalTrackPosition() { return display.getGlobalTrackPosition(); }
    public void setGlobalTrackPosition(int pos) {
        display.setGlobalTrackPosition(pos);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setTrackPositionForGroup(pos));
        updateMonitorLinks();
    }

    public void setTrackPositionForGroup(int groupIndex, int pos) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setTrackPositionForGroup(pos);
        updateMonitorLinks();
    }

    public int getGlobalBackTrackNumber() { return display.getGlobalBackTrackNumber(); }
    public void setGlobalBackTrackNumber(int num) {
        display.setGlobalBackTrackNumber(num);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setBackTrackNumberForGroup(num));
        updateMonitorLinks();
    }

    public int getGlobalBackTrackFontSize() { return display.getGlobalBackTrackFontSize(); }
    public void setGlobalBackTrackFontSize(int size) {
        display.setGlobalBackTrackFontSize(size);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setBackTrackFontSizeForGroup(size));
        updateMonitorLinks();
    }

    public int getGlobalBackTrackPosition() { return display.getGlobalBackTrackPosition(); }
    public void setGlobalBackTrackPosition(int pos) {
        display.setGlobalBackTrackPosition(pos);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setBackTrackPositionForGroup(pos));
        updateMonitorLinks();
    }

    public void setBackTrackNumberForGroup(int groupIndex, int num) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setBackTrackNumberForGroup(num);
        updateMonitorLinks();
    }

    public void setBackTrackFontSizeForGroup(int groupIndex, int size) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setBackTrackFontSizeForGroup(size);
        updateMonitorLinks();
    }

    public void setBackTrackPositionForGroup(int groupIndex, int pos) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setBackTrackPositionForGroup(pos);
        updateMonitorLinks();
    }

    public int getGlobalClockVisible() { return display.getGlobalClockVisible(); }
    public void setGlobalClockVisible(int val) {
        display.setGlobalClockVisible(val);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setClockVisibleForGroup(val));
        updateMonitorLinks();
    }

    public int getGlobalClockFontSize() { return display.getGlobalClockFontSize(); }
    public void setGlobalClockFontSize(int size) {
        display.setGlobalClockFontSize(size);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setClockFontSizeForGroup(size));
        updateMonitorLinks();
    }

    public int getGlobalBackClockVisible() { return display.getGlobalBackClockVisible(); }
    public void setGlobalBackClockVisible(int val) {
        display.setGlobalBackClockVisible(val);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setBackClockVisibleForGroup(val));
        updateMonitorLinks();
    }

    public int getGlobalBackClockFontSize() { return display.getGlobalBackClockFontSize(); }
    public void setGlobalBackClockFontSize(int size) {
        display.setGlobalBackClockFontSize(size);
        if (batchApply) forEachLinkedMonitorGroup(m -> m.setBackClockFontSizeForGroup(size));
        updateMonitorLinks();
    }

    // --- 路線記号設定 ---
    public String getLineSymbolLetters() { return display.getLineSymbolLetters(); }
    public int getLineSymbolNumber() { return display.getLineSymbolNumber(); }
    public String getLineSymbolBorderColor() { return display.getLineSymbolBorderColor(); }
    public float getLineSymbolSize() { return display.getLineSymbolSize(); }
    public int getLineSymbolPosition() { return display.getLineSymbolPosition(); }
    public boolean hasLineSymbol() { return assignedLineSymbol != null || display.hasLineSymbolLetters(); }

    public void setLineSymbol(String letters, int number, String borderColor) {
        display.setLineSymbol(letters, number, borderColor);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setLineSymbolSize(float size) {
        display.setLineSymbolSize(size);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setLineSymbolPosition(int pos) {
        display.setLineSymbolPosition(pos);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void clearLineSymbol() { setLineSymbol("", 0, ""); }

    // --- Color settings ---
    public String getColor(String key) { return display.getColor(key); }
    public void setColor(String key, String value) {
        display.setColor(key, value);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    /** Get color or default if empty */
    public String getColorOrDefault(String key, String defaultColor) {
        return display.getColorOrDefault(key, defaultColor);
    }

    public void setClockVisibleForGroup(int groupIndex, int val) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setClockVisibleForGroup(val);
        updateMonitorLinks();
    }

    public void setClockFontSizeForGroup(int groupIndex, int size) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setClockFontSizeForGroup(size);
        updateMonitorLinks();
    }

    public void setBackClockVisibleForGroup(int groupIndex, int val) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setBackClockVisibleForGroup(val);
        updateMonitorLinks();
    }

    public void setBackClockFontSizeForGroup(int groupIndex, int size) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setBackClockFontSizeForGroup(size);
        updateMonitorLinks();
    }

    public void setTrackFontSizeForGroup(int groupIndex, int size) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(groupIndex).masterPos());
        if (be instanceof MonitorBlockEntity m) m.setTrackFontSizeForGroup(size);
        updateMonitorLinks();
    }

    public void setTrackNumberForGroup(int groupIndex, int trackNum) {
        if (level == null || level.isClientSide()) return;
        if (groupIndex < 0 || groupIndex >= monitorGroups.size()) return;
        MonitorGroupInfo group = monitorGroups.get(groupIndex);
        BlockEntity be = level.getBlockEntity(group.masterPos());
        if (be instanceof MonitorBlockEntity monitor) {
            monitor.setTrackNumberForGroup(trackNum);
        }
        updateMonitorLinks();
    }

    private void applyTrackNumberToAll(int trackNum) {
        forEachLinkedMonitorGroup(m -> m.setTrackNumberForGroup(trackNum));
    }

    /**
     * 一括設定の汎用ヘルパー: MonitorLinkCard に登録された各モニター位置を反復し、
     * 各 group につき 1 度だけ {@code action} を呼ぶ。
     *
     * <p>{@code monitorGroups} の {@code masterPos} は {@code MonitorBlockEntity#updateMultiBlockStructure()}
     * の更新タイミングに依存しており、チャンクロード直後や group 拡張直後に古い値を指していることがある
     * (= その位置に MonitorBlockEntity が無い → batch apply で「一部反映されない」症状)。
     * このヘルパーは「実際に登録されているモニター位置」から開始して group key (master or 自分) で
     * 重複を防ぐため、master pos の取得失敗に左右されない。
     */
    private void forEachLinkedMonitorGroup(java.util.function.Consumer<MonitorBlockEntity> action) {
        if (level == null || level.isClientSide()) return;
        if (monitorLinkCard.isEmpty()) return;
        var positions = com.trainsystemutilities.item.MonitorLinkCardItem
                .getRegisteredPositions(monitorLinkCard);
        java.util.Set<BlockPos> visitedGroups = new java.util.HashSet<>();
        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof MonitorBlockEntity monitor)) continue;
            BlockPos groupKey = monitor.getMasterPos() != null ? monitor.getMasterPos() : pos;
            if (!visitedGroups.add(groupKey)) continue;
            action.accept(monitor);
        }
    }

    /** Update monitor link count, collect group info, and notify monitors */
    private void updateMonitorLinks() {
        if (level == null || level.isClientSide()) return;
        monitorGroups.clear();

        java.util.Set<BlockPos> newPositions = new java.util.HashSet<>();
        if (!monitorLinkCard.isEmpty()) {
            var positions = com.trainsystemutilities.item.MonitorLinkCardItem.getRegisteredPositions(monitorLinkCard);
            newPositions.addAll(positions);
            linkedMonitorGroupCount = com.trainsystemutilities.item.MonitorLinkCardItem.countMonitorGroups(level, positions);

            // Collect group info and set data source
            java.util.Set<BlockPos> visited = new java.util.HashSet<>();
            for (BlockPos mPos : positions) {
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(mPos);
                if (be instanceof MonitorBlockEntity monitor) {
                    monitor.setLinkedRailwayManager(worldPosition);
                    BlockPos master = monitor.getMasterPos() != null ? monitor.getMasterPos() : mPos;
                    if (visited.add(master)) {
                        boolean doubleSided = false;
                        for (BlockPos connPos : monitor.findConnectedMonitors()) {
                            var bs = level.getBlockState(connPos);
                            if (bs.is(com.trainsystemutilities.registry.ModBlocks.DOUBLE_MONITOR.get())
                                    || bs.is(com.trainsystemutilities.registry.ModBlocks.DOUBLE_MONITOR_HALF.get())
                                    || bs.is(com.trainsystemutilities.registry.ModBlocks.DOUBLE_MONITOR_SLIM.get())) {
                                doubleSided = true;
                                break;
                            }
                        }
                        monitorGroups.add(new MonitorGroupInfo(master,
                                monitor.getMultiBlockWidth(), monitor.getMultiBlockHeight(),
                                doubleSided, monitor.getTrackNumber(), monitor.getTrackFontSize(), monitor.getTrackPosition(),
                                monitor.getBackTrackNumber(), monitor.getBackTrackFontSize(), monitor.getBackTrackPosition(),
                                monitor.getClockVisible(), monitor.getClockFontSize(),
                                monitor.getBackClockVisible(), monitor.getBackClockFontSize()));
                    }
                }
            }
        } else {
            linkedMonitorGroupCount = 0;
        }

        // Unlink any monitor that was previously ours but is no longer in the
        // card. Without this, removing the card leaves monitors rendering
        // stale data because their linkedRailwayManagerPos still points here.
        for (BlockPos oldPos : linkedMonitorPositions) {
            if (newPositions.contains(oldPos)) continue;
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(oldPos);
            if (be instanceof MonitorBlockEntity monitor
                    && worldPosition.equals(monitor.getLinkedRailwayManagerPos())) {
                monitor.setLinkedRailwayManager(null);
            }
        }
        linkedMonitorPositions.clear();
        linkedMonitorPositions.addAll(newPositions);

        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // --- Client sync ---

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // --- NBT ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (linkedStationName != null) {
            tag.putString("LinkedStation", linkedStationName);
        }
        if (linkedStationPos != null) {
            tag.putLong("LinkedStationPos", linkedStationPos.asLong());
        }
        if (assignedLineSymbol != null) {
            tag.put("AssignedSymbol", assignedLineSymbol.save());
        }
        if (linkedComputerPos != null) {
            tag.putLong("LinkedComputerPos", linkedComputerPos.asLong());
        }
        tag.putString("DoorOpenSide", doorOpenSide.name());
        if (!monitorLinkCard.isEmpty()) {
            tag.put("MonitorCard", monitorLinkCard.save(registries));
        }
        // Phase 18: アナウンス用 slot + config
        if (!detectionCard.isEmpty()) {
            tag.put("DetectionCard", detectionCard.save(registries));
        }
        if (!rangeBoard.isEmpty()) {
            tag.put("RangeBoard", rangeBoard.save(registries));
        }
        net.minecraft.nbt.ListTag mediaTag = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < announcementMedia.size(); i++) {
            ItemStack s = announcementMedia.get(i);
            if (s.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("idx", i);
            entry.put("item", s.save(registries));
            mediaTag.add(entry);
        }
        if (!mediaTag.isEmpty()) tag.put("AnnouncementMedia", mediaTag);
        announcement.writeNbt(tag);

        // Phase 21: ホームドア群
        if (!screenDoorCard.isEmpty()) {
            tag.put("ScreenDoorCard", screenDoorCard.save(registries));
        }
        screenDoor.writeNbt(tag);

        tag.putInt("MonitorGroups", linkedMonitorGroupCount);
        // Persist the set of currently-linked monitor positions so the
        // "card removed → unlink stale displays" logic survives reloads.
        if (!linkedMonitorPositions.isEmpty()) {
            net.minecraft.nbt.ListTag linksTag = new net.minecraft.nbt.ListTag();
            for (BlockPos lp : linkedMonitorPositions) {
                linksTag.add(net.minecraft.nbt.LongTag.valueOf(lp.asLong()));
            }
            tag.put("LinkedMonitors", linksTag);
        }
        tag.putBoolean("MonitorEnabled", monitorEnabled);
        tag.putBoolean("BatchApply", batchApply);
        // Owner mode
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
            tag.putString("OwnerName", ownerName);
        }
        tag.putBoolean("PrivateMode", privateMode);
        display.writeNbt(tag);

        ListTag groupList = new ListTag();
        for (MonitorGroupInfo g : monitorGroups) {
            CompoundTag gt = new CompoundTag();
            gt.putLong("MasterPos", g.masterPos().asLong());
            gt.putInt("W", g.width());
            gt.putInt("H", g.height());
            gt.putBoolean("Double", g.doubleSided());
            gt.putInt("Track", g.trackNumber());
            gt.putInt("FontSize", g.trackFontSize());
            gt.putInt("TrackPos", g.trackPosition());
            gt.putInt("BackTrack", g.backTrackNumber());
            gt.putInt("BackFontSize", g.backTrackFontSize());
            gt.putInt("BackTrackPos", g.backTrackPosition());
            gt.putInt("ClockVis", g.clockVisible());
            gt.putInt("ClockFS", g.clockFontSize());
            gt.putInt("BackClockVis", g.backClockVisible());
            gt.putInt("BackClockFS", g.backClockFontSize());
            groupList.add(gt);
        }
        tag.put("MonitorGroupList", groupList);

        ListTag trainList = new ListTag();
        for (ArrivedTrain at : arrivedTrains) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", at.id());
            t.putString("Name", at.name());
            t.putLong("ArrivalTick", at.arrivalTick());
            t.putLong("ArrivalDayTime", at.arrivalDayTime());
            t.putInt("Carriages", at.carriageCount());
            t.putString("Destination", at.destination());
            t.putInt("StopSec", at.scheduledStopSec());
            t.putString("RouteType", at.routeType());
            t.putString("TrainType", at.trainType() != null ? at.trainType() : "");
            t.putString("CouplingStatus", at.couplingStatus() != null ? at.couplingStatus() : "");
            t.putString("CouplingPartner", at.couplingPartner() != null ? at.couplingPartner() : "");
            trainList.add(t);
        }
        tag.put("ArrivedTrains", trainList);

        // Save next trains for client sync
        ListTag nextList = new ListTag();
        for (NextTrain nt : nextTrains) {
            CompoundTag t = new CompoundTag();
            t.putString("Name", nt.name());
            t.putInt("Carriages", nt.carriageCount());
            t.putString("From", nt.fromStation());
            t.putString("RouteType", nt.routeType());
            t.putInt("StopSec", nt.scheduledStopSec());
            t.putLong("EstArrival", nt.estimatedArrivalDayTime());
            t.putString("CurrentStop", nt.currentStopStation() != null ? nt.currentStopStation() : "");
            t.putBoolean("Approaching", nt.isApproaching());
            t.putString("TrainType", nt.trainType() != null ? nt.trainType() : "");
            nextList.add(t);
        }
        tag.put("NextTrains", nextList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        linkedStationName = tag.contains("LinkedStation") ? tag.getString("LinkedStation") : null;
        linkedStationPos = tag.contains("LinkedStationPos") ? BlockPos.of(tag.getLong("LinkedStationPos")) : null;
        assignedLineSymbol = tag.contains("AssignedSymbol") ? LineSymbol.load(tag.getCompound("AssignedSymbol")) : null;
        linkedComputerPos = tag.contains("LinkedComputerPos") ? BlockPos.of(tag.getLong("LinkedComputerPos")) : null;
        if (tag.contains("DoorOpenSide")) {
            try { doorOpenSide = DoorSide.valueOf(tag.getString("DoorOpenSide")); }
            catch (IllegalArgumentException e) { doorOpenSide = DoorSide.AUTO; }
        } else {
            doorOpenSide = DoorSide.AUTO;
        }
        if (tag.contains("MonitorCard")) {
            monitorLinkCard = ItemStack.parse(registries, tag.getCompound("MonitorCard")).orElse(ItemStack.EMPTY);
        }
        // Phase 18: アナウンス slot + config
        detectionCard = tag.contains("DetectionCard")
                ? ItemStack.parse(registries, tag.getCompound("DetectionCard")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        rangeBoard = tag.contains("RangeBoard")
                ? ItemStack.parse(registries, tag.getCompound("RangeBoard")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        for (int i = 0; i < announcementMedia.size(); i++) announcementMedia.set(i, ItemStack.EMPTY);
        if (tag.contains("AnnouncementMedia", net.minecraft.nbt.Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag mediaTag = tag.getList("AnnouncementMedia", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < mediaTag.size(); i++) {
                CompoundTag e = mediaTag.getCompound(i);
                int idx = e.getInt("idx");
                if (idx >= 0 && idx < announcementMedia.size()) {
                    announcementMedia.set(idx,
                            ItemStack.parse(registries, e.getCompound("item")).orElse(ItemStack.EMPTY));
                }
            }
        }
        announcement.readNbt(tag);
        // Phase 21: ホームドア群
        screenDoorCard = tag.contains("ScreenDoorCard")
                ? ItemStack.parse(registries, tag.getCompound("ScreenDoorCard")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        screenDoor.readNbt(tag);
        linkedMonitorGroupCount = tag.getInt("MonitorGroups");
        linkedMonitorPositions.clear();
        if (tag.contains("LinkedMonitors", net.minecraft.nbt.Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag linksTag = tag.getList("LinkedMonitors", net.minecraft.nbt.Tag.TAG_LONG);
            for (int i = 0; i < linksTag.size(); i++) {
                linkedMonitorPositions.add(BlockPos.of(((net.minecraft.nbt.LongTag) linksTag.get(i)).getAsLong()));
            }
        }
        monitorEnabled = !tag.contains("MonitorEnabled") || tag.getBoolean("MonitorEnabled");
        batchApply = !tag.contains("BatchApply") || tag.getBoolean("BatchApply");
        // Owner mode
        if (tag.hasUUID("OwnerUUID")) {
            ownerUUID = tag.getUUID("OwnerUUID");
            ownerName = tag.getString("OwnerName");
        } else {
            ownerUUID = null;
            ownerName = "";
        }
        privateMode = tag.getBoolean("PrivateMode");
        display.readNbt(tag);

        monitorGroups.clear();
        ListTag groupList = tag.getList("MonitorGroupList", Tag.TAG_COMPOUND);
        for (int i = 0; i < groupList.size(); i++) {
            CompoundTag gt = groupList.getCompound(i);
            monitorGroups.add(new MonitorGroupInfo(
                    BlockPos.of(gt.getLong("MasterPos")),
                    gt.getInt("W"), gt.getInt("H"),
                    gt.getBoolean("Double"), gt.getInt("Track"), gt.getInt("FontSize"), gt.getInt("TrackPos"),
                    gt.getInt("BackTrack"), gt.getInt("BackFontSize"), gt.getInt("BackTrackPos"),
                    gt.contains("ClockVis") ? gt.getInt("ClockVis") : 1, gt.getInt("ClockFS"),
                    gt.contains("BackClockVis") ? gt.getInt("BackClockVis") : 1, gt.getInt("BackClockFS")));
        }

        arrivedTrains.clear();
        ListTag trainList = tag.getList("ArrivedTrains", Tag.TAG_COMPOUND);
        for (int i = 0; i < trainList.size(); i++) {
            CompoundTag t = trainList.getCompound(i);
            arrivedTrains.add(new ArrivedTrain(
                    t.getUUID("Id"), t.getString("Name"),
                    t.getLong("ArrivalTick"), t.getLong("ArrivalDayTime"),
                    t.getInt("Carriages"), t.getString("Destination"),
                    t.getInt("StopSec"), t.getString("RouteType"),
                    t.getString("TrainType"),
                    t.getString("CouplingStatus"), t.getString("CouplingPartner")));
        }

        nextTrains.clear();
        ListTag nextList = tag.getList("NextTrains", Tag.TAG_COMPOUND);
        for (int i = 0; i < nextList.size(); i++) {
            CompoundTag t = nextList.getCompound(i);
            nextTrains.add(new NextTrain(
                    t.getString("Name"), t.getInt("Carriages"),
                    t.getString("From"), t.getString("RouteType"),
                    t.getInt("StopSec"), t.getLong("EstArrival"),
                    t.getString("CurrentStop"), t.getBoolean("Approaching"),
                    t.getString("TrainType")));
        }
    }
}
