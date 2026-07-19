package com.trainsystemutilities.blockentity;

import com.trainsystemutilities.block.ManagementComputerBlock;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.registry.ModBlocks;
import com.trainsystemutilities.schedule.CreateScheduleIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.trainsystemutilities.gui.ManagementComputerMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 管理用コンピューターのBlockEntity。
 * モニターとリンクし、路線図の投影制御、列車管理機能を提供する。
 */
public class ManagementComputerBlockEntity extends BlockEntity implements Container {

    private BlockPos linkedMonitorPos = null;
    private BlockPos linkedRailwayManagerPos = null;
    private BlockPos linkedTrackNetworkPos = null; // 線路ネットワークの参照位置
    private List<UUID> selectedTrains = new ArrayList<>();

    // メモリーカードスロット
    private ItemStack memoryCard = ItemStack.EMPTY;
    // モニター連携カードスロット (slot 1) — MonitorLinkCardItem を入れるとカードの登録モニターと linkedMonitorPos が連携
    private ItemStack monitorLinkCard = ItemStack.EMPTY;

    // 時刻表書き出し用 (slot 2 = 入力=空Createスケジュール, slot 3 = 出力=書込済み)
    private ItemStack exportInputStack = ItemStack.EMPTY;
    private ItemStack exportOutputStack = ItemStack.EMPTY;
    /** 書き出し対象列車 + 進捗 (server tick で進行、 getUpdateTag で同期)。 */
    private UUID exportTrainId = null;
    private int exportProgress = 0;
    private boolean exportAll = false;     // すべて書き出し (= 入力スタック分まとめて書き出し)
    public static final int EXPORT_TICKS = 40; // ~2秒

    // 列車操作キュー
    private UUID pendingStopTrainId = null;
    private UUID pendingResumeTrainId = null;

    // 全列車段階的停止システム
    private final List<UUID> stopQueue = new ArrayList<>();
    private boolean allStopActive = false;

    // 路線記号リスト
    private final List<LineSymbol> lineSymbols = new ArrayList<>();
    // 駅→路線記号割り当て（駅名 → LineSymbol UUID）
    private final java.util.Map<String, java.util.UUID> stationSymbolMap = new java.util.concurrent.ConcurrentHashMap<>();
    // 駅→RailwayManagementBE位置キャッシュ（駅名 → BlockPos）
    // server tick 内の scan/clear と client→server execute 内の lookup が並行する可能性があるため Concurrent。
    private final java.util.Map<String, BlockPos> stationManagerPosMap = new java.util.concurrent.ConcurrentHashMap<>();
    // 路線記号 -> RailwayManagementBE への伝播が必要かを示す dirty フラグ。
    // assign/save/remove 系の API が呼ばれたタイミングだけ true にし、
    // 200tick タイマで一括反映する。これにより stationSymbolMap が空でなくても無条件に
    // propagateSymbolToLinkedManagers() を回さずに済む。
    private boolean symbolPropagationDirty = false;

    // モニターレイアウト
    private final List<MonitorLayoutPanel> monitorLayout = new ArrayList<>();
    private int monitorW = 0, monitorH = 0;
    private boolean monitorEnabled = true;

    // モニター色設定（12項目）
    private String colorPanelTitle = "";    // パネルタイトル
    private String colorPanelBorder = "";   // パネル枠線
    private String colorTrainName = "";     // 列車名
    private String colorTrainStatus = "";   // 列車速度/状態
    private String colorTrainDest = "";     // 列車目的地
    private String colorClock = "";         // 時計
    private String colorStatValue = "";     // 統計値
    private String colorSignalGreen = "";   // 信号(緑)
    private String colorSignalRed = "";     // 信号(赤)
    private String colorMapLine = "";       // 路線マップ線
    private String colorMapStation = "";    // 路線マップ駅
    private String colorMapTrain = "";      // 路線マップ列車

    // 時刻表編集用一時データ（サーバー側、NBT非永続化）
    private UUID pendingScheduleTrainId = null;
    private net.minecraft.nbt.CompoundTag pendingScheduleNbt = null;

    /** 電子式時刻表として管理用コンピューターが管理する列車 (= 物理アイテムで直接渡された「通常」時刻表と区別)。 */
    private final java.util.Set<UUID> electronicTimetableTrains = new java.util.HashSet<>();

    /** 電子式時刻表 (= この管理用コンピューターで設定された) 列車か。 */
    public boolean isElectronicTimetable(UUID trainId) {
        return trainId != null && electronicTimetableTrains.contains(trainId);
    }

    /** 列車を電子式時刻表としてマークし、 server-global 登録簿にも反映する (= 運転士右クリック横取り用)。 */
    private void markTrainElectronic(UUID id) {
        if (id == null) return;
        electronicTimetableTrains.add(id);
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            com.trainsystemutilities.schedule.ElectronicTimetableState.get(sl.getServer()).add(id);
        }
        setChanged();
    }

    /** 時刻表共有: follower 列車 → source 列車。 1列車は1ソースのみ追従 (= put が自動 re-parent)。 */
    private final java.util.Map<UUID, UUID> timetableFollowerToSource = new java.util.HashMap<>();

    /** この列車が他列車の時刻表を共有 (追従) しているか。 */
    public boolean isTimetableFollower(UUID trainId) {
        return trainId != null && timetableFollowerToSource.containsKey(trainId);
    }
    /** follower が追従している source 列車の UUID (なければ null)。 */
    public UUID getTimetableShareSource(UUID follower) {
        return follower == null ? null : timetableFollowerToSource.get(follower);
    }
    /** この列車が共有元 (= 1つ以上の follower を持つ) か。 */
    public boolean isTimetableShareSource(UUID trainId) {
        return trainId != null && timetableFollowerToSource.containsValue(trainId);
    }

    /** source の時刻表を follower へ共有 ON/OFF (反転)。 server 専用 (payload 経由)。 */
    public void toggleTimetableShare(UUID source, UUID follower) {
        if (source == null || follower == null || source.equals(follower)) return;
        if (level == null || level.isClientSide()) return;
        if (source.equals(timetableFollowerToSource.get(follower))) {
            // 解除: follower は最後の時刻表を保持し編集可へ戻る
            timetableFollowerToSource.remove(follower);
        } else {
            // 追従開始 (別ソースからの場合は put が自動 re-parent)
            timetableFollowerToSource.put(follower, source);
            copyScheduleToFollower(source, follower);
        }
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** source の現時刻表を follower 列車へコピー (Create Schedule 経由)。 server only。 */
    private void copyScheduleToFollower(UUID source, UUID follower) {
        try {
            var srcOpt = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(source);
            var fOpt = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(follower);
            if (srcOpt.isEmpty() || fOpt.isEmpty()) return;
            var srcTrain = srcOpt.get();
            var fTrain = fOpt.get();
            if (srcTrain.runtime == null || fTrain.runtime == null) return;
            var srcSched = srcTrain.runtime.getSchedule();
            if (srcSched == null || srcSched.entries.isEmpty()) return;
            var registries = level.registryAccess();
            var copy = com.simibubi.create.content.trains.schedule.Schedule
                    .fromTag(registries, srcSched.write(registries));
            fTrain.runtime.setSchedule(copy, false);
            fTrain.runtime.paused = true;
            markTrainElectronic(follower);
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.error("[ScheduleShare] copy failed", e);
        }
    }

    /** source 編集後に全 follower へ伝播。 applyPendingSchedule から呼ぶ。 server only。 */
    private void propagateScheduleToFollowers(UUID source) {
        if (source == null) return;
        for (var e : timetableFollowerToSource.entrySet()) {
            if (source.equals(e.getValue())) copyScheduleToFollower(source, e.getKey());
        }
    }

    /** 列車に運転士 (モブ / ブレイズバーナー) が乗っているか。 編集ゲートに使う。 */
    public static boolean hasConductor(com.simibubi.create.content.trains.entity.Train train) {
        return train != null && (train.hasForwardConductor() || train.hasBackwardConductor());
    }

    /** 同期用: 列車UUID → bit0=運転士あり, bit1=Create schedule あり。 server tick で計算し getUpdateTag でクライアントへ同期 (= MP 対応)。 */
    private final java.util.Map<UUID, Byte> trainTimetableFlags = new java.util.HashMap<>();
    /** 同期済みの「運転士あり」フラグ (client でも参照可)。 */
    public boolean hasSyncedConductor(UUID trainId) {
        Byte f = trainTimetableFlags.get(trainId); return f != null && (f & 1) != 0;
    }
    /** 同期済みの「Create schedule あり」フラグ。 */
    public boolean hasSyncedSchedule(UUID trainId) {
        Byte f = trainTimetableFlags.get(trainId); return f != null && (f & 2) != 0;
    }
    /** 同期済みの「runtime.paused」フラグ (bit2 = 値4)。 停止ボタン活性判定の MP 対応。 */
    public boolean hasSyncedPaused(UUID trainId) {
        Byte f = trainTimetableFlags.get(trainId); return f != null && (f & 4) != 0;
    }

    /** 同期用: 列車UUID → 種別コード。 source of truth は server-global な
     *  {@link com.trainsystemutilities.schedule.TrainTypeState}。 server tick でそこから読み取り
     *  getUpdateTag で client へ同期する (= client が SavedData を直読みしないため。 §5.1)。 */
    private final java.util.Map<UUID, String> trainTypes = new java.util.HashMap<>();

    /** 同期済みの列車種別コード。 未設定は空文字。 client から参照してよい唯一の経路。 */
    public String getSyncedTrainType(UUID trainId) {
        String t = trainId == null ? null : trainTypes.get(trainId);
        return t == null ? com.trainsystemutilities.schedule.TrainTypes.NONE : t;
    }

    /** 種別を設定する (server 専用)。 SavedData を更新し、 同期マップと client 配信も行う。 */
    public void setTrainType(UUID trainId, String code) {
        if (trainId == null || level == null || level.isClientSide()) return;
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            com.trainsystemutilities.schedule.TrainTypeState.get(sl.getServer()).set(trainId, code);
        }
        if (com.trainsystemutilities.schedule.TrainTypes.isSet(code)) trainTypes.put(trainId, code);
        else trainTypes.remove(trainId);
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** 同期用: 列車の schedule 表示明細 (entries テキスト / current / cyclic)。 server tick で計算し getUpdateTag で同期。 */
    public record SchedView(java.util.List<String> entries, int current, boolean cyclic) {}
    private final java.util.Map<UUID, SchedView> trainSchedViews = new java.util.HashMap<>();
    /** 同期済みの schedule 表示明細。 client で getTrainById の代わりに使う (MP 対応)。 null 可。 */
    public SchedView getSyncedSchedView(UUID trainId) { return trainSchedViews.get(trainId); }

    /** 時刻表編集を適用する */
    public void applyPendingSchedule() {
        if (pendingScheduleTrainId == null || pendingScheduleNbt == null) return;
        try {
            var opt = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(pendingScheduleTrainId);
            if (opt.isPresent() && opt.get().runtime != null && opt.get().runtime.paused) {
                var train = opt.get();
                var oldSchedule = train.runtime.getSchedule();
                // 新しいScheduleを構築（既存Scheduleをベースに変更を適用）
                var newSchedule = new com.simibubi.create.content.trains.schedule.Schedule();
                newSchedule.cyclic = pendingScheduleNbt.getBoolean("Cyclic");
                var entriesTag = pendingScheduleNbt.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (int i = 0; i < entriesTag.size(); i++) {
                    var entryTag = entriesTag.getCompound(i);
                    var instrTag = entryTag.getCompound("Instruction");
                    String instrId = instrTag.getString("Id");
                    // ScheduleEntry構築
                    var entry = new com.simibubi.create.content.trains.schedule.ScheduleEntry();
                    // Instruction設定
                    switch (instrId) {
                        case CreateScheduleIds.DESTINATION -> {
                            var instr = new com.simibubi.create.content.trains.schedule.destination.DestinationInstruction();
                            instr.getData().putString("Text", instrTag.getString("Text"));
                            entry.instruction = instr;
                        }
                        case CreateScheduleIds.PACKAGE_DELIVERY -> {
                            var instr = new com.simibubi.create.content.trains.schedule.destination.DeliverPackagesInstruction();
                            instr.getData().putString("Text", instrTag.getString("Text"));
                            entry.instruction = instr;
                        }
                        case CreateScheduleIds.PACKAGE_RETRIEVAL -> {
                            var instr = new com.simibubi.create.content.trains.schedule.destination.FetchPackagesInstruction();
                            instr.getData().putString("Text", instrTag.getString("Text"));
                            entry.instruction = instr;
                        }
                        case CreateScheduleIds.RENAME -> {
                            var instr = new com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction();
                            instr.getData().putString("Text", instrTag.getString("Text"));
                            entry.instruction = instr;
                        }
                        case CreateScheduleIds.THROTTLE -> {
                            var instr = new com.simibubi.create.content.trains.schedule.destination.ChangeThrottleInstruction();
                            instr.getData().putInt("Value", instrTag.getInt("Value"));
                            entry.instruction = instr;
                        }
                        default -> { continue; }
                    }
                    // Conditions設定
                    var condListTag = entryTag.getList("Conditions", net.minecraft.nbt.Tag.TAG_LIST);
                    for (int g = 0; g < condListTag.size(); g++) {
                        var condGroupTag = condListTag.getList(g);
                        var condGroup = new java.util.ArrayList<com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition>();
                        for (int c = 0; c < condGroupTag.size(); c++) {
                            var condTag = condGroupTag.getCompound(c);
                            String condId = condTag.getString("Id");
                            switch (condId) {
                                case CreateScheduleIds.DELAY -> {
                                    var cond = new com.simibubi.create.content.trains.schedule.condition.ScheduledDelay();
                                    cond.getData().putInt("Value", condTag.getInt("Value"));
                                    cond.getData().putInt("TimeUnit", condTag.getInt("TimeUnit"));
                                    condGroup.add(cond);
                                }
                                case CreateScheduleIds.TIME_OF_DAY -> {
                                    var cond = new com.simibubi.create.content.trains.schedule.condition.TimeOfDayCondition();
                                    cond.getData().putInt("Hour", condTag.getInt("Hour"));
                                    cond.getData().putInt("Minute", condTag.getInt("Minute"));
                                    cond.getData().putInt("Rotation", condTag.getInt("Rotation"));
                                    condGroup.add(cond);
                                }
                                case CreateScheduleIds.ITEM_THRESHOLD -> {
                                    var cond = new com.simibubi.create.content.trains.schedule.condition.ItemThresholdCondition();
                                    cond.getData().putInt("Threshold", condTag.getInt("Threshold"));
                                    cond.getData().putInt("Operator", condTag.getInt("Operator"));
                                    cond.getData().putInt("Measure", condTag.getInt("Measure"));
                                    condGroup.add(cond);
                                }
                                case CreateScheduleIds.FLUID_THRESHOLD -> {
                                    var cond = new com.simibubi.create.content.trains.schedule.condition.FluidThresholdCondition();
                                    cond.getData().putInt("Threshold", condTag.getInt("Threshold"));
                                    cond.getData().putInt("Operator", condTag.getInt("Operator"));
                                    condGroup.add(cond);
                                }
                                case CreateScheduleIds.REDSTONE_LINK -> {
                                    var cond = new com.simibubi.create.content.trains.schedule.condition.RedstoneLinkCondition();
                                    cond.getData().putInt("Inverted", condTag.getInt("Inverted"));
                                    condGroup.add(cond);
                                }
                                case CreateScheduleIds.PLAYER_COUNT -> {
                                    var cond = new com.simibubi.create.content.trains.schedule.condition.PlayerPassengerCondition();
                                    cond.getData().putInt("Count", condTag.getInt("Count"));
                                    cond.getData().putInt("Exact", condTag.getInt("Exact"));
                                    condGroup.add(cond);
                                }
                                case CreateScheduleIds.IDLE -> {
                                    var cond = new com.simibubi.create.content.trains.schedule.condition.IdleCargoCondition();
                                    cond.getData().putInt("Value", condTag.getInt("Value"));
                                    cond.getData().putInt("TimeUnit", condTag.getInt("TimeUnit"));
                                    condGroup.add(cond);
                                }
                                case CreateScheduleIds.POWERED -> condGroup.add(new com.simibubi.create.content.trains.schedule.condition.StationPoweredCondition());
                                case CreateScheduleIds.UNLOADED -> condGroup.add(new com.simibubi.create.content.trains.schedule.condition.StationUnloadedCondition());
                                case "trainsystemutilities:coupling" -> {
                                    var cond = new com.trainsystemutilities.schedule.CouplingCondition();
                                    cond.getData().putInt("Mode", condTag.getInt("Mode"));
                                    cond.getData().putInt("WaitTime", condTag.getInt("WaitTime"));
                                    condGroup.add(cond);
                                }
                            }
                        }
                        if (!condGroup.isEmpty()) entry.conditions.add(condGroup);
                    }
                    newSchedule.entries.add(entry);
                }
                train.runtime.setSchedule(newSchedule, false);
                // 適用後も停止状態を維持（プレイヤーが手動で再開するまで発車しない）
                train.runtime.paused = true;
                // 電子式時刻表としてマーク (= この管理用コンピューターで管理)
                if (!newSchedule.entries.isEmpty()) {
                    markTrainElectronic(pendingScheduleTrainId);
                }
                // 共有元なら全 follower へ伝播
                propagateScheduleToFollowers(pendingScheduleTrainId);
            }
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.error("[ScheduleEdit] Failed to apply schedule", e);
        }
        pendingScheduleTrainId = null;
        pendingScheduleNbt = null;
    }

    public void setPendingSchedule(UUID trainId, net.minecraft.nbt.CompoundTag nbt) {
        this.pendingScheduleTrainId = trainId;
        this.pendingScheduleNbt = nbt;
    }

    // 管理者モード
    private UUID ownerUUID = null;
    private String ownerName = "";
    private boolean privateMode = false;

    // ネットワークデータキャッシュ（サーバー側でtickごとに更新）
    private int cachedSignalCount = 0;
    private int cachedStationCount = 0;
    private int cachedTrainCount = 0;

    // マップデータ（クライアント同期用）
    private List<com.trainsystemutilities.network.TrackNetworkScanner.NodeInfo> cachedNodes = new ArrayList<>();
    private List<com.trainsystemutilities.network.TrackNetworkScanner.EdgeInfo> cachedEdges = new ArrayList<>();
    private List<com.trainsystemutilities.network.TrackNetworkScanner.StationInfo> cachedStations = new ArrayList<>();
    private List<com.trainsystemutilities.network.TrackNetworkScanner.SignalInfo> cachedSignals = new ArrayList<>();
    private List<com.trainsystemutilities.network.TrackNetworkScanner.TrainInfo> cachedTrains = new ArrayList<>();

    public ManagementComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MANAGEMENT_COMPUTER.get(), pos, state);
    }

    /** サーバー側 instance registry — 全 MCBE を一覧する用。線路記号解決等で使う。 */
    private static final java.util.Set<ManagementComputerBlockEntity> SERVER_INSTANCES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static java.util.Set<ManagementComputerBlockEntity> serverInstances() {
        return SERVER_INSTANCES;
    }

    /** いずれかのロード済みコンピューターが trainId を電子式時刻表として管理しているか (= live 参照)。 server only。
     *  運転士モブ右クリックの横取り ({@link com.trainsystemutilities.mixin.ScheduleItemEntityInteractionMixin}) で使う。 */
    public static boolean isTrainElectronicAnywhere(UUID trainId) {
        if (trainId == null) return false;
        for (ManagementComputerBlockEntity be : SERVER_INSTANCES) {
            if (be.electronicTimetableTrains.contains(trainId)) return true;
        }
        return false;
    }

    /** wiki 自動キャプチャ用: サンプル列車の電子式 / 同期フラグを seed (client dummy BE 専用)。
     *  meta = id -> {schedule エントリ数, 電子式(1/0)}。 */
    public void wikiSeed(java.util.Map<UUID, int[]> meta) {
        for (var e : meta.entrySet()) {
            int entries = e.getValue()[0];
            boolean elec = e.getValue()[1] == 1;
            byte flags = 0;
            if (entries > 0) flags = (byte) (1 | 2); // bit0=運転士, bit1=schedule
            trainTimetableFlags.put(e.getKey(), flags);
            if (elec) electronicTimetableTrains.add(e.getKey());
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) SERVER_INSTANCES.add(this);
    }

    @Override
    public void setRemoved() {
        SERVER_INSTANCES.remove(this);
        super.setRemoved();
    }

    public static String stationKey(String stationName, BlockPos stationPos) {
        String safeName = stationName != null ? stationName : "";
        return stationPos != null ? safeName + "|" + stationPos.asLong() : safeName;
    }

    private static boolean stationMatches(RailwayManagementBlockEntity manager, String stationName, BlockPos stationPos) {
        if (manager == null || !java.util.Objects.equals(stationName, manager.getLinkedStationName())) return false;
        BlockPos linkedPos = manager.getLinkedStationPos();
        return stationPos == null || linkedPos == null || stationPos.equals(linkedPos);
    }

    private static String legacyStationKey(String key) {
        int split = key.lastIndexOf('|');
        return split >= 0 ? key.substring(0, split) : key;
    }

    private UUID getSymbolIdForStation(String stationName, BlockPos stationPos) {
        if (stationPos != null) {
            UUID exact = stationSymbolMap.get(stationKey(stationName, stationPos));
            if (exact != null) return exact;
        }
        return stationSymbolMap.get(stationName);
    }

    private LineSymbol getSymbolById(UUID id) {
        if (id == null) return null;
        for (LineSymbol s : lineSymbols) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }

    private LineSymbol getSymbolForStationKey(String key) {
        UUID id = stationSymbolMap.get(key);
        if (id == null) {
            id = stationSymbolMap.get(legacyStationKey(key));
        }
        return getSymbolById(id);
    }

    public static void tick(Level level, BlockPos pos, BlockState state,
                            ManagementComputerBlockEntity blockEntity) {
        if (level.isClientSide()) return;

        if (blockEntity.isLinkedToMonitor() && blockEntity.linkedRailwayManagerPos != null) {
            blockEntity.pushDataToMonitor();
        }

        // モニターサイズ自動検出（未計算の場合）
        if (blockEntity.isLinkedToMonitor() && blockEntity.monitorW == 0 && level.getGameTime() % 40 == 0) {
            blockEntity.detectMonitorSize();
        }

        // メモリーカードスロットから自動リンク
        if (level.getGameTime() % 20 == 0) {
            blockEntity.checkMemoryCardSlot();
        }

        // 全列車段階的停止処理
        if (blockEntity.allStopActive && !blockEntity.stopQueue.isEmpty()) {
            blockEntity.processStopQueue();
        }

        // 時刻表書き出し進行
        blockEntity.processExport();

        // ネットワークデータキャッシュ更新（毎秒）
        if (level.getGameTime() % 20 == 0 &&
                (blockEntity.linkedRailwayManagerPos != null || blockEntity.linkedTrackNetworkPos != null)) {
            blockEntity.updateNetworkCache();
            // 路線記号をリンク先RailwayManagementBEに伝播（dirty 時のみ・最大10秒以内）
            if (blockEntity.symbolPropagationDirty
                    || (level.getGameTime() % 200 == 0 && !blockEntity.stationSymbolMap.isEmpty())) {
                blockEntity.propagateSymbolToLinkedManagers();
                blockEntity.symbolPropagationDirty = false;
            }
        }
    }

    private void checkMemoryCardSlot() {
        if (memoryCard.isEmpty()) return;
        if (!memoryCard.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return;
        var tag = memoryCard.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        String type = tag.getString("Type");
        if ("track_network".equals(type)) {
            BlockPos trackPos = BlockPos.of(tag.getLong("Pos"));
            if (!trackPos.equals(linkedTrackNetworkPos)) {
                linkedTrackNetworkPos = trackPos;
                mapDataDirty = true;
                setChanged();
            }
        } else if ("railway_manager".equals(type)) {
            BlockPos managerPos = BlockPos.of(tag.getLong("Pos"));
            if (!managerPos.equals(linkedRailwayManagerPos)) {
                linkedRailwayManagerPos = managerPos;
                mapDataDirty = true;
                setChanged();
            }
        }
    }

    private boolean mapDataDirty = true;

    private void updateNetworkCache() {
        if (level == null) return;
        // 線路ネットワーク直接リンク or 鉄道管理ブロック経由
        BlockPos scanPos = linkedTrackNetworkPos != null ? linkedTrackNetworkPos : linkedRailwayManagerPos;
        if (scanPos == null) return;
        var data = com.trainsystemutilities.network.TrackNetworkScanner
                .scanFromPosition(level, scanPos);
        cachedSignalCount = data.signals().size();
        cachedStationCount = data.stations().size();
        cachedTrainCount = data.trains().size();
        cachedNodes = data.nodes();
        cachedEdges = data.edges();
        cachedStations = data.stations();
        cachedSignals = data.signals();
        cachedTrains = data.trains();
        // 電子式時刻表ゲート用フラグ (運転士 / schedule) を server で計算 → getUpdateTag で MP 同期
        trainTimetableFlags.clear();
        trainSchedViews.clear();
        trainTypes.clear();
        var typeState = level instanceof net.minecraft.server.level.ServerLevel sl2
                ? com.trainsystemutilities.schedule.TrainTypeState.get(sl2.getServer()) : null;
        for (var ti : data.trains()) {
            if (ti.id() == null) continue;
            // 種別は Create の Train が解決できなくても同期する (= 手動設定値であって列車状態ではない)
            if (typeState != null) {
                String type = typeState.get(ti.id());
                if (com.trainsystemutilities.schedule.TrainTypes.isSet(type)) trainTypes.put(ti.id(), type);
            }
            var opt = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(ti.id());
            if (opt.isEmpty()) continue;
            var tr = opt.get();
            var sched = tr.runtime != null ? tr.runtime.getSchedule() : null;
            byte flags = 0;
            if (hasConductor(tr)) flags |= 1;
            if (sched != null && !sched.entries.isEmpty()) flags |= 2;
            if (tr.runtime != null && tr.runtime.paused) flags |= 4;
            trainTimetableFlags.put(ti.id(), flags);
            // schedule 表示明細を server で抽出 → client が getTrainById 無しで詳細/時刻表を出せる (MP 対応)
            if (sched != null) {
                java.util.List<String> sEntries = new java.util.ArrayList<>();
                for (int i = 0; i < sched.entries.size() && i < 64; i++) {
                    sEntries.add(scheduleEntryText(sched.entries.get(i)));
                }
                trainSchedViews.put(ti.id(), new SchedView(sEntries, tr.runtime.currentEntry, sched.cyclic));
            }
        }
        // 電子式時刻表を server-global 登録簿へ push (= world load 後 / モブ右クリック横取りで大域参照するため)
        if (level instanceof net.minecraft.server.level.ServerLevel sl && !electronicTimetableTrains.isEmpty()) {
            var ets = com.trainsystemutilities.schedule.ElectronicTimetableState.get(sl.getServer());
            for (UUID id : electronicTimetableTrains) ets.add(id);
        }
        // 各駅周辺のRailwayManagementBEの位置をキャッシュ
        scanStationManagers();
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** schedule entry の表示テキストを抽出 (= 旧 refreshSelectedTrainSnapshot の client 側ロジックを server へ移設)。 */
    private static String scheduleEntryText(com.simibubi.create.content.trains.schedule.ScheduleEntry entry) {
        try {
            if (entry.instruction instanceof com.simibubi.create.content.trains.schedule.destination.DestinationInstruction dest) {
                return dest.getFilter();
            }
            var summary = entry.instruction.getSummary();
            return summary != null ? summary.getSecond().getString() : "";
        } catch (Exception ignored) { return "?"; }
    }

    // --- Container implementation (slot 0 = memoryCard, slot 1 = monitorLinkCard,
    //     slot 2 = 時刻表書き出し 入力, slot 3 = 書き出し 出力) ---
    @Override public int getContainerSize() { return 4; }
    @Override public boolean isEmpty() {
        return memoryCard.isEmpty() && monitorLinkCard.isEmpty()
                && exportInputStack.isEmpty() && exportOutputStack.isEmpty();
    }
    @Override
    public ItemStack getItem(int slot) {
        return switch (slot) {
            case 0 -> memoryCard;
            case 1 -> monitorLinkCard;
            case 2 -> exportInputStack;
            case 3 -> exportOutputStack;
            default -> ItemStack.EMPTY;
        };
    }
    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack src = getItem(slot);
        if (src.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = src.split(amount);
        if (slot == 0 && src.isEmpty()) memoryCard = ItemStack.EMPTY;
        if (slot == 1 && src.isEmpty()) monitorLinkCard = ItemStack.EMPTY;
        if (slot == 2 && src.isEmpty()) exportInputStack = ItemStack.EMPTY;
        if (slot == 3 && src.isEmpty()) exportOutputStack = ItemStack.EMPTY;
        setChanged();
        if (slot == 1) syncMonitorLinkFromCard();
        return result;
    }
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack old;
        switch (slot) {
            case 0 -> { old = memoryCard; memoryCard = ItemStack.EMPTY; }
            case 1 -> { old = monitorLinkCard; monitorLinkCard = ItemStack.EMPTY; syncMonitorLinkFromCard(); }
            case 2 -> { old = exportInputStack; exportInputStack = ItemStack.EMPTY; }
            case 3 -> { old = exportOutputStack; exportOutputStack = ItemStack.EMPTY; }
            default -> { return ItemStack.EMPTY; }
        }
        return old;
    }
    @Override
    public void setItem(int slot, ItemStack stack) {
        switch (slot) {
            case 0 -> { memoryCard = stack; setChanged(); }
            case 1 -> { monitorLinkCard = stack; setChanged(); syncMonitorLinkFromCard(); }
            case 2 -> { exportInputStack = stack; setChanged(); }
            case 3 -> { exportOutputStack = stack; setChanged(); }
            default -> {}
        }
    }
    @Override public boolean stillValid(Player player) {
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64;
    }
    @Override public void clearContent() {
        memoryCard = ItemStack.EMPTY;
        monitorLinkCard = ItemStack.EMPTY;
        exportInputStack = ItemStack.EMPTY;
        exportOutputStack = ItemStack.EMPTY;
        setChanged();
        syncMonitorLinkFromCard();
    }

    // --- 時刻表書き出し (server 権威) ---
    public ItemStack getExportInputStack() { return exportInputStack; }
    public ItemStack getExportOutputStack() { return exportOutputStack; }
    public int getExportProgress() { return exportProgress; }
    public boolean isExportAll() { return exportAll; }
    public void toggleExportAll() {
        exportAll = !exportAll; setChanged();
        if (level != null && !level.isClientSide())
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Create スケジュールアイテムか (= 中身は書き出し時に train の schedule で上書きする)。 */
    private static boolean isBlankSchedule(ItemStack s) {
        return !s.isEmpty()
                && s.getItem() instanceof com.simibubi.create.content.trains.schedule.ScheduleItem;
    }

    /** 書き出し開始 (server)。 入力=空スケジュール + 出力空 + train に schedule があれば進捗開始。 */
    public void startExport(UUID trainId) {
        if (exportProgress > 0 || trainId == null) return;
        if (!isBlankSchedule(exportInputStack) || !exportOutputStack.isEmpty()) return;
        var opt = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(trainId);
        if (opt.isEmpty() || opt.get().runtime == null || opt.get().runtime.getSchedule() == null
                || opt.get().runtime.getSchedule().entries.isEmpty()) return;
        exportTrainId = trainId;
        exportProgress = 1;
        setChanged();
    }

    /** 毎tick: 書き出し進行。 完了時に train の schedule を create:schedule アイテムへ書き込む。 */
    private void processExport() {
        if (exportProgress <= 0 || level == null || level.isClientSide()) return;
        var opt = exportTrainId != null
                ? com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(exportTrainId)
                : java.util.Optional.<com.simibubi.create.content.trains.entity.Train>empty();
        // 中断条件: 入力が空スケジュールでない / 出力が埋まる / train や schedule が無い
        if (!isBlankSchedule(exportInputStack) || !exportOutputStack.isEmpty()
                || opt.isEmpty() || opt.get().runtime == null || opt.get().runtime.getSchedule() == null) {
            exportProgress = 0; exportTrainId = null; setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return;
        }
        exportProgress++;
        if (exportProgress >= EXPORT_TICKS) {
            var schedule = opt.get().runtime.getSchedule();
            CompoundTag schedTag = schedule.write(level.registryAccess());
            net.minecraft.world.item.Item scheduleItem = com.simibubi.create.AllItems.SCHEDULE.get();
            int count = Math.min(exportAll ? exportInputStack.getCount() : 1, 64);
            ItemStack out = new ItemStack(scheduleItem, count);
            out.set(com.simibubi.create.AllDataComponents.TRAIN_SCHEDULE, schedTag);
            exportInputStack.shrink(count);
            exportOutputStack = out;
            exportProgress = 0; exportTrainId = null;
        }
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Monitor link card に登録された position から linkedMonitorPos を設定。
     *  card 空なら link 解除。空でなければ最初の登録位置を採用 (= 既存 single-link UI 維持)。
     *  Note: card に複数 monitor 登録されている場合、connected グループの代表点 (= first) を使用。 */
    private void syncMonitorLinkFromCard() {
        // Server side でのみ link を更新 (client side のレベル操作を避ける)
        if (level == null || level.isClientSide()) return;
        if (monitorLinkCard.isEmpty()) {
            linkedMonitorPos = null;
            monitorW = 0;
            monitorH = 0;
            if (level != null && getBlockState().hasProperty(ManagementComputerBlock.LINKED)) {
                level.setBlock(worldPosition, getBlockState().setValue(ManagementComputerBlock.LINKED, false), 3);
            }
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return;
        }
        var positions = com.trainsystemutilities.item.MonitorLinkCardItem
                .getRegisteredPositions(monitorLinkCard);
        if (positions.isEmpty()) {
            linkedMonitorPos = null;
            monitorW = 0;
            monitorH = 0;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return;
        }
        // 最初の登録位置を採用 + 既存 link logic を再利用して dimensions を計算
        BlockPos firstPos = positions.get(0);
        linkToMonitor(firstPos);
    }

    // --- 管理者モード ---
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getOwnerName() { return ownerName; }
    public boolean isPrivateMode() { return privateMode; }
    public void setOwner(UUID uuid, String name) {
        this.ownerUUID = uuid; this.ownerName = name; setChanged();
    }
    public void togglePrivateMode() {
        privateMode = !privateMode; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public boolean canAccess(Player player) {
        if (!privateMode) return true;
        if (ownerUUID == null) return true;
        return ownerUUID.equals(player.getUUID());
    }

    // --- Getters ---
    public int getCachedSignalCount() { return cachedSignalCount; }
    public int getCachedStationCount() { return cachedStationCount; }
    public int getCachedTrainCount() { return cachedTrainCount; }
    public List<com.trainsystemutilities.network.TrackNetworkScanner.NodeInfo> getCachedNodes() { return cachedNodes; }
    public List<com.trainsystemutilities.network.TrackNetworkScanner.EdgeInfo> getCachedEdges() { return cachedEdges; }
    public List<com.trainsystemutilities.network.TrackNetworkScanner.StationInfo> getCachedStations() { return cachedStations; }
    public List<com.trainsystemutilities.network.TrackNetworkScanner.SignalInfo> getCachedSignals() { return cachedSignals; }
    public List<com.trainsystemutilities.network.TrackNetworkScanner.TrainInfo> getCachedTrains() { return cachedTrains; }

    public boolean linkToMonitor(BlockPos monitorPos) {
        if (level == null) return false;
        BlockState monitorState = level.getBlockState(monitorPos);
        // Phase 5d: 全モニター variant (single/double + half/slim) を受け付ける
        if (!com.trainsystemutilities.block.MonitorBlock.isMonitorBlock(monitorState)) return false;
        this.linkedMonitorPos = monitorPos;
        BlockEntity be = level.getBlockEntity(monitorPos);
        if (be instanceof MonitorBlockEntity monitorBE) {
            List<BlockPos> connected = monitorBE.findConnectedMonitors();
            for (BlockPos connectedPos : connected) {
                BlockEntity connBE = level.getBlockEntity(connectedPos);
                if (connBE instanceof MonitorBlockEntity connMonitor) {
                    connMonitor.setControllerPos(worldPosition);
                }
            }
            // モニターグループのサイズを計算
            net.minecraft.core.Direction facing = level.getBlockState(monitorPos).getValue(
                    com.trainsystemutilities.block.MonitorBlock.FACING);
            net.minecraft.core.Direction right = facing.getClockWise();
            int minH = 0, maxH = 0, minV = 0, maxV = 0;
            for (BlockPos cp : connected) {
                BlockPos diff = cp.subtract(monitorPos);
                int h = diff.getX() * right.getStepX() + diff.getZ() * right.getStepZ();
                int v = diff.getY();
                minH = Math.min(minH, h); maxH = Math.max(maxH, h);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            monitorW = maxH - minH + 1;
            monitorH = maxV - minV + 1;
        }
        level.setBlock(worldPosition, getBlockState().setValue(ManagementComputerBlock.LINKED, true), 3);
        setChanged();
        // Phase 5d: setBlock は state が同じだと no-op になるため、BE update を明示的に broadcast。
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    public boolean linkToRailwayManager(BlockPos managerPos) {
        if (level == null) return false;
        BlockState managerState = level.getBlockState(managerPos);
        if (!managerState.is(ModBlocks.RAILWAY_MANAGEMENT_BLOCK.get())) return false;
        this.linkedRailwayManagerPos = managerPos;
        // 逆リンク設定
        var rmbe = level.getBlockEntity(managerPos);
        if (rmbe instanceof RailwayManagementBlockEntity rm) {
            rm.setLinkedComputerPos(worldPosition);
        }
        setChanged();
        return true;
    }

    public void pushDataToMonitor() {
        if (level == null || linkedMonitorPos == null || linkedRailwayManagerPos == null) return;
        BlockEntity managerBE = level.getBlockEntity(linkedRailwayManagerPos);
        if (!(managerBE instanceof RailwayManagementBlockEntity)) return;
    }

    public boolean isLinkedToMonitor() { return linkedMonitorPos != null; }
    public BlockPos getLinkedMonitorPos() { return linkedMonitorPos; }
    public BlockPos getLinkedRailwayManagerPos() { return linkedRailwayManagerPos; }
    public void setLinkedRailwayManagerPos(BlockPos pos) {
        this.linkedRailwayManagerPos = pos; setChanged();
    }
    private void detectMonitorSize() {
        if (level == null || linkedMonitorPos == null) return;
        BlockEntity be = level.getBlockEntity(linkedMonitorPos);
        if (!(be instanceof MonitorBlockEntity monitorBE)) return;
        try {
            net.minecraft.core.Direction facing = level.getBlockState(linkedMonitorPos).getValue(
                    com.trainsystemutilities.block.MonitorBlock.FACING);
            net.minecraft.core.Direction right = facing.getClockWise();
            List<BlockPos> connected = monitorBE.findConnectedMonitors();
            int minH = 0, maxH = 0, minV = 0, maxV = 0;
            for (BlockPos cp : connected) {
                BlockPos diff = cp.subtract(linkedMonitorPos);
                int h = diff.getX() * right.getStepX() + diff.getZ() * right.getStepZ();
                int v = diff.getY();
                minH = Math.min(minH, h); maxH = Math.max(maxH, h);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            monitorW = maxH - minH + 1;
            monitorH = maxV - minV + 1;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtComputer] monitor size recompute failed", ignored); }
    }

    public List<MonitorLayoutPanel> getMonitorLayout() { return monitorLayout; }
    public int getMonitorW() { return monitorW; }
    public int getMonitorH() { return monitorH; }
    public void setMonitorSize(int w, int h) { monitorW = w; monitorH = h; setChanged(); }
    public boolean isMonitorEnabled() { return monitorEnabled; }
    public void setMonitorEnabled(boolean enabled) { monitorEnabled = enabled; setChanged(); }
    public void toggleMonitorEnabled() { monitorEnabled = !monitorEnabled; setChanged(); }

    public BlockPos getLinkedTrackNetworkPos() { return linkedTrackNetworkPos; }
    public void setLinkedTrackNetworkPos(BlockPos pos) {
        this.linkedTrackNetworkPos = pos; setChanged();
    }

    public void openManagementScreen(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.translatable("gui.trainsystemutilities.management_computer.title");
                }
                @Override
                public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                    return new ManagementComputerMenu(containerId, playerInventory, ManagementComputerBlockEntity.this);
                }
            }, worldPosition);
        }
    }

    /**
     * 全列車停止（環状線対応・1台ずつ順番停止方式）。
     * 1台ずつpausedにし、駅に停車したら次の列車をpausedにする。
     * 既に駅にいる列車は即停止。
     */
    public void startAllTrainsStop() {
        if (allStopActive) return;
        if (level == null) return;

        BlockPos scanPos = linkedTrackNetworkPos != null ? linkedTrackNetworkPos : linkedRailwayManagerPos;
        if (scanPos == null) return;

        var data = com.trainsystemutilities.network.TrackNetworkScanner.scanFromPosition(level, scanPos);
        if (data.trains().isEmpty()) return;

        stopQueue.clear();

        for (var trainInfo : data.trains()) {
            if (trainInfo.id() == null) continue;
            var optTrain = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(trainInfo.id());
            if (optTrain.isEmpty()) continue;
            var train = optTrain.get();
            if (train.runtime == null) continue;

            if (train.getCurrentStation() != null) {
                // 既に駅にいる → その場で pin
                train.runtime.paused = true;
            } else {
                // 走行中 → 駅到着まで走らせ続ける対象に登録 (= まだ paused にしない)。
                // 全列車を並列に対象化し、各々が次の駅に着いた瞬間に pin することで、
                // 走行中の その場停止による経路の詰まり / serial queue の停滞を防ぐ。
                if (!stopQueue.contains(trainInfo.id())) stopQueue.add(trainInfo.id());
            }
        }

        allStopActive = true;
    }

    /**
     * 毎tick: 駅到着 pin・並列方式。 停止対象の各列車は走行を続け (= paused にしない)、
     * Create の駅到着 (getCurrentStation != null) を検知した瞬間にその駅で pin (paused=true) する。
     * 走行中の列車を その場で止めないため、 先頭が詰まって後続が停滞することがない。
     * 行先重複や駅数不足で駅に入れない列車は信号で自然に待機し、 入れ次第 pin される。
     */
    private void processStopQueue() {
        if (stopQueue.isEmpty()) {
            allStopActive = false;
            return;
        }
        java.util.Iterator<UUID> it = stopQueue.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            var optTrain = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(id);
            if (optTrain.isEmpty()) { it.remove(); continue; }   // 列車消滅
            var train = optTrain.get();
            if (train.runtime == null) { it.remove(); continue; }
            if (train.getCurrentStation() != null) {
                // 駅に到着 → その駅で pin して対象から外す
                train.runtime.paused = true;
                it.remove();
            }
            // まだ走行中 → そのまま走らせ続ける (次tickで再判定)
        }
        if (stopQueue.isEmpty()) {
            allStopActive = false;
        }
    }

    /** 全列車停止中かどうか */
    public boolean isAllStopActive() { return allStopActive; }
    /** 停止キュー残数 */
    public int getStopQueueSize() { return stopQueue.size(); }

    /** 全列車の運行を再開 */
    public void resumeAllTrains() {
        allStopActive = false;
        stopQueue.clear();

        if (level == null) return;
        BlockPos scanPos = linkedTrackNetworkPos != null ? linkedTrackNetworkPos : linkedRailwayManagerPos;
        if (scanPos == null) return;

        var data = com.trainsystemutilities.network.TrackNetworkScanner.scanFromPosition(level, scanPos);
        for (var trainInfo : data.trains()) {
            if (trainInfo.id() != null) {
                resumeTrain(trainInfo.id());
            }
        }
    }

    public void requestStop(UUID trainId) {
        pendingStopTrainId = trainId;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void requestResume(UUID trainId) {
        pendingResumeTrainId = trainId;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** 列車の運行停止（次の駅で停車して待機） */
    public void emergencyStop(UUID trainId) {
        var optTrain = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(trainId);
        if (optTrain.isPresent()) {
            var train = optTrain.get();
            if (train.runtime != null) {
                train.runtime.paused = true;
            }
        }
    }

    /** 列車の運行再開 */
    public void resumeTrain(UUID trainId) {
        var optTrain = com.trainsystemutilities.network.TrackNetworkScanner.getTrainById(trainId);
        if (optTrain.isPresent()) {
            var train = optTrain.get();
            if (train.runtime != null) {
                train.runtime.paused = false;
            }
        }
    }

    /**
     * SECURITY (TSU-NET-002): {@code trainId} がこの管理コンピュータの linked track network に
     * 属する列車かを検証する。single-train の stop/resume/schedule/export/share/ticket 経路は
     * global {@code Create.RAILWAYS.trains} を直接引くため、mutation/query 前にこの membership を
     * 確認して「reach 可能な computer 経由で管轄外の任意列車を操作する」ことを防ぐ。
     *
     * <p>authoritative な linked-network scan を都度行う (all-train 経路と同じソース)。
     * 呼び出しは discrete なユーザー操作時のみで per-tick ではない (periodic scan の性能は TSU-PERF-001 で別途)。
     */
    public boolean containsLinkedTrain(UUID trainId) {
        if (trainId == null || level == null) return false;
        BlockPos scanPos = linkedTrackNetworkPos != null ? linkedTrackNetworkPos : linkedRailwayManagerPos;
        if (scanPos == null) return false;
        var data = com.trainsystemutilities.network.TrackNetworkScanner.scanFromPosition(level, scanPos);
        for (var t : data.trains()) {
            if (trainId.equals(t.id())) return true;
        }
        return false;
    }

    public void selectTrain(UUID trainId) {
        if (!selectedTrains.contains(trainId)) { selectedTrains.add(trainId); setChanged(); }
    }
    public void deselectTrain(UUID trainId) { selectedTrains.remove(trainId); setChanged(); }
    public List<UUID> getSelectedTrains() { return selectedTrains; }

    // --- 色設定 API ---
    private static final String[] COLOR_KEYS = {
            "panelTitle", "panelBorder", "trainName", "trainStatus", "trainDest",
            "clock", "statValue", "signalGreen", "signalRed", "mapLine", "mapStation", "mapTrain"
    };
    public static String[] getColorKeys() { return COLOR_KEYS; }

    public String getColor(String key) {
        return switch (key) {
            case "panelTitle" -> colorPanelTitle; case "panelBorder" -> colorPanelBorder;
            case "trainName" -> colorTrainName; case "trainStatus" -> colorTrainStatus;
            case "trainDest" -> colorTrainDest; case "clock" -> colorClock;
            case "statValue" -> colorStatValue; case "signalGreen" -> colorSignalGreen;
            case "signalRed" -> colorSignalRed; case "mapLine" -> colorMapLine;
            case "mapStation" -> colorMapStation; case "mapTrain" -> colorMapTrain;
            default -> "";
        };
    }
    public void setColor(String key, String value) {
        switch (key) {
            case "panelTitle" -> colorPanelTitle = value; case "panelBorder" -> colorPanelBorder = value;
            case "trainName" -> colorTrainName = value; case "trainStatus" -> colorTrainStatus = value;
            case "trainDest" -> colorTrainDest = value; case "clock" -> colorClock = value;
            case "statValue" -> colorStatValue = value; case "signalGreen" -> colorSignalGreen = value;
            case "signalRed" -> colorSignalRed = value; case "mapLine" -> colorMapLine = value;
            case "mapStation" -> colorMapStation = value; case "mapTrain" -> colorMapTrain = value;
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public String getColorOrDefault(String key, String defaultColor) {
        String c = getColor(key);
        return (c != null && !c.isEmpty()) ? c : defaultColor;
    }

    // --- 路線記号 API ---
    public List<LineSymbol> getLineSymbols() { return lineSymbols; }
    private void clearRemovedSymbolAssignments() {
        if (stationSymbolMap.isEmpty()) return;
        java.util.Set<java.util.UUID> validIds = new java.util.HashSet<>();
        for (LineSymbol symbol : lineSymbols) {
            if (symbol != null && symbol.getId() != null) validIds.add(symbol.getId());
        }
        stationSymbolMap.entrySet().removeIf(entry -> !validIds.contains(entry.getValue()));
    }
    private void onLineSymbolsChanged() {
        clearRemovedSymbolAssignments();
        symbolPropagationDirty = true;
        setChanged();
        scanStationManagers();
        propagateSymbolToLinkedManagers();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void addLineSymbol(LineSymbol symbol) {
        if (symbol == null) return;
        lineSymbols.add(symbol);
        onLineSymbolsChanged();
    }
    public void saveLineSymbol(int index, LineSymbol symbol) {
        if (symbol == null) return;
        if (index >= 0 && index < lineSymbols.size()) {
            LineSymbol existing = lineSymbols.get(index);
            existing.setLetters(symbol.getLetters());
            existing.setNumber(symbol.getNumber());
            existing.setBorderColor(symbol.getBorderColor());
            existing.setName(symbol.getName());
            existing.setBorderRadius(symbol.getBorderRadius());
        } else {
            lineSymbols.add(symbol);
        }
        onLineSymbolsChanged();
    }
    public void removeLineSymbol(int index) {
        if (index >= 0 && index < lineSymbols.size()) {
            lineSymbols.remove(index);
            onLineSymbolsChanged();
        }
    }

    /** 全 ManagementComputer が前回 full scan を行った gameTick (per BE)。 */
    private long lastFullScanGameTime = -10000;
    /** 全 station について full scan を行う最小間隔 (= 30秒)。 */
    private static final long FULL_SCAN_INTERVAL_TICKS = 600;
    /** Full scan の半径 (旧 40→16: 27倍削減)。 */
    private static final int FULL_SCAN_RADIUS_H = 16;
    private static final int FULL_SCAN_RADIUS_V = 8;

    /** 各駅周辺の RailwayManagementBE を検索してキャッシュ。
     *
     * <p>旧実装は毎秒 stationManagerPosMap.clear() + N × 81×17×81 = N×111,537 回の
     * {@code getBlockEntity} 呼び出しで server tick の 40%超 を消費していた。
     *
     * <p>修正:
     * <ul>
     *   <li>キャッシュ済みエントリは {@code getBlockEntity} 1回で validate するだけ</li>
     *   <li>キャッシュミス時のみ近傍走査、半径 16×8×16 (=4,913 positions) に削減</li>
     *   <li>full scan は 30 秒に 1 回のみ (新規 BE 配置検知用)</li>
     * </ul>
     * 通常時のコスト: N × 1 = 5 calls/sec (= 旧の 100,000分の1)
     */
    private void scanStationManagers() {
        if (level == null || cachedStations == null) return;
        long now = level.getGameTime();
        boolean doFullScan = (now - lastFullScanGameTime) >= FULL_SCAN_INTERVAL_TICKS;

        for (var station : cachedStations) {
            BlockPos sp = station.position();
            String key = stationKey(station.name(), sp);
            BlockPos cached = stationManagerPosMap.get(key);

            // 1. キャッシュ済みなら getBlockEntity 1回で validate
            if (cached != null) {
                var be = level.getBlockEntity(cached);
                if (be instanceof RailwayManagementBlockEntity rmbe
                        && stationMatches(rmbe, station.name(), sp)) {
                    rmbe.setLinkedComputerPos(worldPosition);
                    LineSymbol sym = getSymbolForStation(station.name(), sp);
                    rmbe.setAssignedLineSymbol(sym);
                    continue; // 有効、次の駅へ
                }
                // 無効 → エントリ削除して下の full scan に進む
                stationManagerPosMap.remove(key);
                stationManagerPosMap.remove(station.name());
            }

            // 2. キャッシュミス: full scan 期間でなければ skip
            if (!doFullScan) continue;

            // 3. 近傍 loaded chunk の BlockEntity 直接走査 (§9: getStationManager と同型に統一。
            //    per-position probe → chunk BE 走査で N 駅 × 大量 probe を解消)。
            boolean found = false;
            for (int ccx = (sp.getX() - FULL_SCAN_RADIUS_H) >> 4; ccx <= (sp.getX() + FULL_SCAN_RADIUS_H) >> 4 && !found; ccx++) {
                for (int ccz = (sp.getZ() - FULL_SCAN_RADIUS_H) >> 4; ccz <= (sp.getZ() + FULL_SCAN_RADIUS_H) >> 4 && !found; ccz++) {
                    if (!(level.getChunk(ccx, ccz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false)
                            instanceof net.minecraft.world.level.chunk.LevelChunk lc)) continue;
                    for (var ent : lc.getBlockEntities().entrySet()) {
                        net.minecraft.core.BlockPos bp = ent.getKey();
                        if (Math.abs(bp.getX() - sp.getX()) > FULL_SCAN_RADIUS_H
                                || Math.abs(bp.getY() - sp.getY()) > FULL_SCAN_RADIUS_V
                                || Math.abs(bp.getZ() - sp.getZ()) > FULL_SCAN_RADIUS_H) continue;
                        if (ent.getValue() instanceof RailwayManagementBlockEntity rmbe
                                && stationMatches(rmbe, station.name(), sp)) {
                            stationManagerPosMap.put(key, bp);
                            stationManagerPosMap.put(station.name(), bp);
                            rmbe.setLinkedComputerPos(worldPosition);
                            rmbe.setAssignedLineSymbol(getSymbolForStation(station.name(), sp));
                            found = true;
                            break;
                        }
                    }
                }
            }
        }

        if (doFullScan) lastFullScanGameTime = now;
    }

    /** 駅名からRailwayManagementBEの位置を取得（キャッシュ済み） */
    public BlockPos getManagerPosForStation(String stationName) {
        return stationManagerPosMap.get(stationName);
    }

    public BlockPos getManagerPosForStation(String stationName, BlockPos stationPos) {
        BlockPos exact = stationManagerPosMap.get(stationKey(stationName, stationPos));
        return exact != null ? exact : stationManagerPosMap.get(stationName);
    }

    /** 駅にRailwayManagementBEがリンクされているか */
    public boolean hasManagerForStation(String stationName) {
        return stationManagerPosMap.containsKey(stationName);
    }

    public boolean hasManagerForStation(String stationName, BlockPos stationPos) {
        // キャッシュ参照のみ (描画パスから毎フレーム呼ばれるため重スキャン禁止)。
        // 新しく置かれた RMBE は scanStationManagers (1秒周期) または明示的な
        // findOrScanManagerForStation 呼び出しでキャッシュ登録される。
        return getManagerPosForStation(stationName, stationPos) != null;
    }

    // --- 駅→路線記号割り当て API ---
    public java.util.Map<String, java.util.UUID> getStationSymbolMap() { return stationSymbolMap; }
    public void assignSymbolToStation(String stationName, java.util.UUID symbolId) {
        if (symbolId != null) stationSymbolMap.put(stationName, symbolId);
        else stationSymbolMap.remove(stationName);
        symbolPropagationDirty = true;
        setChanged();
        // リンクされたRailwayManagementBEにシンボルを伝播
        propagateSymbolToLinkedManagers();
    }
    /** 全駅のシンボル割り当てをキャッシュ済みRailwayManagementBEに伝播 */
    void propagateSymbolToLinkedManagers() {
        if (level == null) return;
        // stationManagerPosMap には同一 managerPos に対して複数キー
        // (full "name|x,y,z" + 後方互換の bare "name") が登録される。
        // bare key は stationSymbolMap で見つからず getSymbolForStationKey が null を返すため、
        // そのまま setAssignedLineSymbol(null) を呼ぶと直後に full key 側で
        // 非 null が再設定される 2 連射パターンが発生し、200 tick おきに
        // クライアントへ "null → 実シンボル" の連続更新が飛ぶ。
        // managerPos ごとに集約し、非 null を優先して 1 回だけ反映する。
        java.util.Map<BlockPos, LineSymbol> resolved = new java.util.HashMap<>();
        java.util.Set<BlockPos> hasNonNull = new java.util.HashSet<>();
        for (var entry : stationManagerPosMap.entrySet()) {
            BlockPos managerPos = entry.getValue();
            if (managerPos == null) continue;
            LineSymbol sym = getSymbolForStationKey(entry.getKey());
            if (sym != null) {
                resolved.put(managerPos, sym);
                hasNonNull.add(managerPos);
            } else if (!hasNonNull.contains(managerPos) && !resolved.containsKey(managerPos)) {
                resolved.put(managerPos, null);
            }
        }
        for (var entry : resolved.entrySet()) {
            var be = level.getBlockEntity(entry.getKey());
            if (be instanceof RailwayManagementBlockEntity rmbe) {
                rmbe.setAssignedLineSymbol(entry.getValue());
            }
        }
        // chunk load 非依存の権威 store へ全駅ぶんを同期 (= 遠隔/未ロード駅も RMBE 側 tick で解決可能)。
        syncSymbolsToStore();
    }

    /**
     * 全ネットワーク駅の「解決済み路線記号」を {@link com.trainsystemutilities.station.LineSymbolStore}
     * (chunk load 非依存の権威 store) へ書き込む。 各 {@code RailwayManagementBlockEntity} は自駅キーで
     * これを引いて反映するため、 遠隔駅の chunk が unload されていても割り当てが行き渡る
     * (= 従来の block entity 間 push が unload 駅に届かなかった問題の根治)。
     */
    private void syncSymbolsToStore() {
        if (level == null || level.isClientSide() || cachedStations == null) return;
        var server = level.getServer();
        if (server == null) return;
        var store = com.trainsystemutilities.station.LineSymbolStore.get(server);
        for (var st : cachedStations) {
            String k = stationKey(st.name(), st.position());
            LineSymbol sym = getSymbolForStation(st.name(), st.position());
            store.setSymbol(k, sym);
        }
    }

    /**
     * 指定駅にリンクされた RailwayManagementBlockEntity を検索 (キャッシュ + 周辺スキャン)。
     * キャッシュ未登録でも世界をスキャンして見つけ、見つかればキャッシュに登録する。
     */
    public RailwayManagementBlockEntity findOrScanManagerForStation(String stationName, BlockPos stationPos) {
        if (level == null) return null;
        boolean cacheChanged = false;
        // 1. キャッシュ参照
        BlockPos cached = getManagerPosForStation(stationName, stationPos);
        if (cached != null) {
            var be = level.getBlockEntity(cached);
            if (be instanceof RailwayManagementBlockEntity rm
                    && stationMatches(rm, stationName, stationPos)) {
                return rm;
            }
            // chunk が unload 済み (be==null) のときはキャッシュを消さない (= 位置は依然有効)。
            // 消していたため、 遠隔駅への割り当て時に有効な managerPos キャッシュを失っていた (二次バグ)。
            if (level.isLoaded(cached)) {
                stationManagerPosMap.remove(stationKey(stationName, stationPos));
                stationManagerPosMap.remove(stationName);
                cacheChanged = true;
            }
        }
        // 2. 周辺スキャン (scanStationManagers と同じ範囲: ±40 水平, ±8 垂直)
        if (stationPos == null) {
            if (cacheChanged) {
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
            return null;
        }
        // O(n²) 解消: ±40×±8×±40 = 111,537 回の getBlockEntity probe を、 範囲に重なる loaded chunk の
        // BlockEntity 直接走査 (空間 index 相当) に置換。 同一フィルタ (RailwayManagement + 距離 +
        // stationMatches) で behavior-identical、 unloaded chunk は元実装同様 skip。
        int minCx = (stationPos.getX() - 40) >> 4, maxCx = (stationPos.getX() + 40) >> 4;
        int minCz = (stationPos.getZ() - 40) >> 4, maxCz = (stationPos.getZ() + 40) >> 4;
        for (int ccx = minCx; ccx <= maxCx; ccx++) {
            for (int ccz = minCz; ccz <= maxCz; ccz++) {
                if (!(level.getChunk(ccx, ccz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false)
                        instanceof net.minecraft.world.level.chunk.LevelChunk lc)) continue;
                for (var e : lc.getBlockEntities().entrySet()) {
                    net.minecraft.core.BlockPos bp = e.getKey();
                    if (Math.abs(bp.getX() - stationPos.getX()) > 40
                            || Math.abs(bp.getY() - stationPos.getY()) > 8
                            || Math.abs(bp.getZ() - stationPos.getZ()) > 40) continue;
                    if (e.getValue() instanceof RailwayManagementBlockEntity rmbe
                            && stationMatches(rmbe, stationName, stationPos)) {
                        stationManagerPosMap.put(stationKey(stationName, stationPos), bp);
                        stationManagerPosMap.put(stationName, bp);
                        cacheChanged = true;
                        rmbe.setLinkedComputerPos(worldPosition);
                        if (cacheChanged) {
                            setChanged();
                            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                        }
                        return rmbe;
                    }
                }
            }
        }
        if (cacheChanged) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return null;
    }
    /** 駅名からLineSymbolを取得（未割り当てならnull） */
    public LineSymbol getSymbolForStation(String stationName) {
        java.util.UUID id = stationSymbolMap.get(stationName);
        if (id == null) return null;
        for (LineSymbol s : lineSymbols) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }

    public void assignSymbolToStation(String stationName, BlockPos stationPos, java.util.UUID symbolId) {
        String key = stationKey(stationName, stationPos);
        if (symbolId != null) stationSymbolMap.put(key, symbolId);
        else stationSymbolMap.remove(key);
        if (stationPos != null) {
            stationSymbolMap.remove(stationName);
        }
        symbolPropagationDirty = true;
        setChanged();
        // キャッシュ済みマネージャに伝播
        propagateSymbolToLinkedManagers();
        // この駅に対するマネージャを直接検索 (キャッシュ未登録対策)
        RailwayManagementBlockEntity rmbe = findOrScanManagerForStation(stationName, stationPos);
        if (rmbe != null) {
            rmbe.setAssignedLineSymbol(getSymbolForStation(stationName, stationPos));
        }
    }

    public LineSymbol getSymbolForStation(String stationName, BlockPos stationPos) {
        UUID id = getSymbolIdForStation(stationName, stationPos);
        return getSymbolById(id);
    }

    // --- NBT ---
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (linkedMonitorPos != null) {
            tag.putInt("MonitorX", linkedMonitorPos.getX());
            tag.putInt("MonitorY", linkedMonitorPos.getY());
            tag.putInt("MonitorZ", linkedMonitorPos.getZ());
        }
        if (linkedRailwayManagerPos != null) {
            tag.putInt("ManagerX", linkedRailwayManagerPos.getX());
            tag.putInt("ManagerY", linkedRailwayManagerPos.getY());
            tag.putInt("ManagerZ", linkedRailwayManagerPos.getZ());
        }
        if (linkedTrackNetworkPos != null) {
            tag.putLong("TrackNetPos", linkedTrackNetworkPos.asLong());
        }
        if (!memoryCard.isEmpty()) {
            tag.put("MemoryCard", memoryCard.save(registries));
        }
        if (!monitorLinkCard.isEmpty()) {
            tag.put("MonitorLinkCard", monitorLinkCard.save(registries));
        }
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
            tag.putString("OwnerName", ownerName);
        }
        tag.putBoolean("PrivateMode", privateMode);
        // モニターレイアウト
        tag.put("MonitorLayout", MonitorLayoutPanel.saveList(monitorLayout));
        tag.putInt("MonW", monitorW);
        tag.putInt("MonH", monitorH);
        tag.putBoolean("MonEnabled", monitorEnabled);
        if (pendingStopTrainId != null) tag.putUUID("PendingStop", pendingStopTrainId);
        if (pendingResumeTrainId != null) tag.putUUID("PendingResume", pendingResumeTrainId);
        tag.putInt("CachedSignals", cachedSignalCount);
        tag.putInt("CachedStations", cachedStationCount);
        tag.putInt("CachedTrains", cachedTrainCount);
        if (!electronicTimetableTrains.isEmpty()) {
            net.minecraft.nbt.ListTag etl = new net.minecraft.nbt.ListTag();
            for (UUID id : electronicTimetableTrains) {
                CompoundTag c = new CompoundTag(); c.putUUID("Id", id); etl.add(c);
            }
            tag.put("ElectronicTimetables", etl);
        }
        if (!trainTimetableFlags.isEmpty()) {
            net.minecraft.nbt.ListTag ttf = new net.minecraft.nbt.ListTag();
            for (var e : trainTimetableFlags.entrySet()) {
                CompoundTag c = new CompoundTag(); c.putUUID("U", e.getKey()); c.putByte("F", e.getValue()); ttf.add(c);
            }
            tag.put("TrainTimetableFlags", ttf);
        }
        if (!trainTypes.isEmpty()) {
            net.minecraft.nbt.ListTag tt = new net.minecraft.nbt.ListTag();
            for (var e : trainTypes.entrySet()) {
                CompoundTag c = new CompoundTag(); c.putUUID("U", e.getKey()); c.putString("T", e.getValue()); tt.add(c);
            }
            tag.put("TrainTypes", tt);
        }
        if (!exportInputStack.isEmpty()) tag.put("ExportIn", exportInputStack.save(registries));
        if (!exportOutputStack.isEmpty()) tag.put("ExportOut", exportOutputStack.save(registries));
        if (exportTrainId != null) tag.putUUID("ExportTrain", exportTrainId);
        tag.putInt("ExportProgress", exportProgress);
        tag.putBoolean("ExportAll", exportAll);
        if (!timetableFollowerToSource.isEmpty()) {
            net.minecraft.nbt.ListTag tsl = new net.minecraft.nbt.ListTag();
            for (var e : timetableFollowerToSource.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putUUID("F", e.getKey()); c.putUUID("S", e.getValue()); tsl.add(c);
            }
            tag.put("TimetableShares", tsl);
        }

        // 色設定
        for (String k : COLOR_KEYS) {
            String v = getColor(k);
            if (v != null && !v.isEmpty()) tag.putString("Color_" + k, v);
        }
        // 路線記号（空でも常に保存 — sendBlockUpdatedのloadAdditional経由での復元に必要）
        tag.put("LineSymbols", LineSymbol.saveList(lineSymbols));
        // 駅→路線記号割り当て（常に保存）
        CompoundTag symMap = new CompoundTag();
        for (var entry : stationSymbolMap.entrySet()) {
            symMap.putUUID(entry.getKey(), entry.getValue());
        }
        tag.put("StationSymbolMap", symMap);
        CompoundTag managerMap = new CompoundTag();
        for (var entry : stationManagerPosMap.entrySet()) {
            if (entry.getValue() != null) {
                managerMap.putLong(entry.getKey(), entry.getValue().asLong());
            }
        }
        tag.put("StationManagerPosMap", managerMap);

        // マップデータ
        // ノード
        var nodeList = new net.minecraft.nbt.ListTag();
        for (var n : cachedNodes) {
            CompoundTag nt = new CompoundTag();
            nt.putInt("I", n.id()); nt.putDouble("X", n.x()); nt.putDouble("Z", n.z());
            nodeList.add(nt);
        }
        tag.put("MapNodes", nodeList);
        // エッジ (曲線ジオメトリ込み同期 = MP では client 再スキャン不可のため points も送る)
        var edgeList = new net.minecraft.nbt.ListTag();
        for (var e : cachedEdges) {
            CompoundTag et = new CompoundTag();
            et.putInt("F", e.fromId()); et.putInt("T", e.toId());
            if (e.points() != null && !e.points().isEmpty()) {
                net.minecraft.nbt.ListTag pts = new net.minecraft.nbt.ListTag();
                for (double[] pt : e.points()) {
                    CompoundTag p = new CompoundTag();
                    p.putDouble("X", pt[0]); p.putDouble("Z", pt[1]);
                    pts.add(p);
                }
                et.put("P", pts);
            }
            edgeList.add(et);
        }
        tag.put("MapEdges", edgeList);
        // 駅
        var stationList = new net.minecraft.nbt.ListTag();
        for (var s : cachedStations) {
            CompoundTag st = new CompoundTag();
            st.putString("N", s.name()); st.putLong("P", s.position().asLong());
            stationList.add(st);
        }
        tag.put("MapStations", stationList);
        // 列車（位置 + UUID 付き）。UUID を保存しないと client 側で id=null になり、
        // 列車詳細 popup や全列車停止などの id 引きが失敗する。
        var trainList = new net.minecraft.nbt.ListTag();
        for (var t : cachedTrains) {
            CompoundTag tt = new CompoundTag();
            tt.putString("N", t.name()); tt.putDouble("X", t.worldX()); tt.putDouble("Z", t.worldZ());
            tt.putInt("C", t.carriageCount());
            tt.putDouble("SP", t.speed()); tt.putBoolean("AS", t.isStoppedAtStation());
            tt.putString("ST", t.currentStationName() == null ? "" : t.currentStationName());
            if (t.id() != null) tt.putUUID("U", t.id());
            trainList.add(tt);
        }
        tag.put("MapTrains", trainList);
        // 信号（位置 + 状態）。MapRenderer の信号ドット用 (= MP では client 再scan できないため同期)。
        var signalList = new net.minecraft.nbt.ListTag();
        for (var s : cachedSignals) {
            CompoundTag st = new CompoundTag();
            st.putLong("P", s.position().asLong());
            st.putInt("S", s.state().ordinal());
            signalList.add(st);
        }
        tag.put("MapSignals", signalList);
        // 列車 schedule 表示明細 (entries テキスト / current / cyclic) を同期 (詳細popup・電子式時刻表の MP 対応)。
        var schedViewList = new net.minecraft.nbt.ListTag();
        for (var e : trainSchedViews.entrySet()) {
            CompoundTag sv = new CompoundTag();
            sv.putUUID("U", e.getKey());
            sv.putInt("Cur", e.getValue().current());
            sv.putBoolean("Cyc", e.getValue().cyclic());
            net.minecraft.nbt.ListTag el = new net.minecraft.nbt.ListTag();
            for (String s : e.getValue().entries()) el.add(net.minecraft.nbt.StringTag.valueOf(s == null ? "" : s));
            sv.put("E", el);
            schedViewList.add(sv);
        }
        tag.put("TrainSchedViews", schedViewList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        linkedMonitorPos = tag.contains("MonitorX") ? new BlockPos(
                tag.getInt("MonitorX"), tag.getInt("MonitorY"), tag.getInt("MonitorZ")) : null;
        linkedRailwayManagerPos = tag.contains("ManagerX") ? new BlockPos(
                tag.getInt("ManagerX"), tag.getInt("ManagerY"), tag.getInt("ManagerZ")) : null;
        linkedTrackNetworkPos = tag.contains("TrackNetPos") ? BlockPos.of(tag.getLong("TrackNetPos")) : null;
        memoryCard = tag.contains("MemoryCard")
                ? ItemStack.parse(registries, tag.getCompound("MemoryCard")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        monitorLinkCard = tag.contains("MonitorLinkCard")
                ? ItemStack.parse(registries, tag.getCompound("MonitorLinkCard")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        if (tag.hasUUID("OwnerUUID")) {
            ownerUUID = tag.getUUID("OwnerUUID");
            ownerName = tag.getString("OwnerName");
        } else { ownerUUID = null; ownerName = ""; }
        privateMode = tag.getBoolean("PrivateMode");
        // モニターレイアウト
        monitorLayout.clear();
        if (tag.contains("MonitorLayout")) {
            monitorLayout.addAll(MonitorLayoutPanel.loadList(
                    tag.getList("MonitorLayout", net.minecraft.nbt.Tag.TAG_COMPOUND)));
        }
        monitorW = tag.getInt("MonW");
        monitorH = tag.getInt("MonH");
        monitorEnabled = !tag.contains("MonEnabled") || tag.getBoolean("MonEnabled");
        pendingStopTrainId = tag.hasUUID("PendingStop") ? tag.getUUID("PendingStop") : null;
        pendingResumeTrainId = tag.hasUUID("PendingResume") ? tag.getUUID("PendingResume") : null;
        cachedSignalCount = tag.getInt("CachedSignals");
        cachedStationCount = tag.getInt("CachedStations");
        cachedTrainCount = tag.getInt("CachedTrains");
        electronicTimetableTrains.clear();
        if (tag.contains("ElectronicTimetables")) {
            var etl = tag.getList("ElectronicTimetables", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < etl.size(); i++) {
                CompoundTag c = etl.getCompound(i);
                if (c.hasUUID("Id")) electronicTimetableTrains.add(c.getUUID("Id"));
            }
        }
        trainTimetableFlags.clear();
        if (tag.contains("TrainTimetableFlags")) {
            var ttf = tag.getList("TrainTimetableFlags", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < ttf.size(); i++) {
                CompoundTag c = ttf.getCompound(i);
                if (c.hasUUID("U")) trainTimetableFlags.put(c.getUUID("U"), c.getByte("F"));
            }
        }
        trainTypes.clear();
        if (tag.contains("TrainTypes")) {
            var tt = tag.getList("TrainTypes", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < tt.size(); i++) {
                CompoundTag c = tt.getCompound(i);
                if (c.hasUUID("U")) trainTypes.put(c.getUUID("U"), c.getString("T"));
            }
        }
        exportInputStack = tag.contains("ExportIn")
                ? ItemStack.parse(registries, tag.getCompound("ExportIn")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        exportOutputStack = tag.contains("ExportOut")
                ? ItemStack.parse(registries, tag.getCompound("ExportOut")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        exportTrainId = tag.hasUUID("ExportTrain") ? tag.getUUID("ExportTrain") : null;
        exportProgress = tag.getInt("ExportProgress");
        exportAll = tag.getBoolean("ExportAll");
        timetableFollowerToSource.clear();
        if (tag.contains("TimetableShares")) {
            var tsl = tag.getList("TimetableShares", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < tsl.size(); i++) {
                CompoundTag c = tsl.getCompound(i);
                if (c.hasUUID("F") && c.hasUUID("S")) {
                    timetableFollowerToSource.put(c.getUUID("F"), c.getUUID("S"));
                }
            }
        }

        // 色設定読み込み
        for (String k : COLOR_KEYS) {
            if (tag.contains("Color_" + k)) setColor(k, tag.getString("Color_" + k));
        }
        // 路線記号読み込み（ワールドロード時のみ。sendBlockUpdated経由のリロードではスキップ）
        lineSymbols.clear();
        if (tag.contains("LineSymbols")) {
            lineSymbols.addAll(LineSymbol.loadList(tag.getList("LineSymbols", net.minecraft.nbt.Tag.TAG_COMPOUND)));
        }
        // 駅→路線記号割り当て読み込み（空でなければ読み込む。空のNBTではメモリ上のデータを保持）
        stationSymbolMap.clear();
        if (tag.contains("StationSymbolMap")) {
            CompoundTag symMap2 = tag.getCompound("StationSymbolMap");
            for (String key : symMap2.getAllKeys()) {
                if (symMap2.hasUUID(key)) stationSymbolMap.put(key, symMap2.getUUID(key));
            }
        }
        stationManagerPosMap.clear();
        if (tag.contains("StationManagerPosMap")) {
            CompoundTag managerMap = tag.getCompound("StationManagerPosMap");
            for (String key : managerMap.getAllKeys()) {
                stationManagerPosMap.put(key, BlockPos.of(managerMap.getLong(key)));
            }
        }

        // マップデータ読み込み
        cachedNodes = new ArrayList<>();
        var nodeList = tag.getList("MapNodes", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < nodeList.size(); i++) {
            CompoundTag nt = nodeList.getCompound(i);
            cachedNodes.add(new com.trainsystemutilities.network.TrackNetworkScanner.NodeInfo(
                    nt.getInt("I"), nt.getDouble("X"), nt.getDouble("Z")));
        }
        cachedEdges = new ArrayList<>();
        var edgeList = tag.getList("MapEdges", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < edgeList.size(); i++) {
            CompoundTag et = edgeList.getCompound(i);
            List<double[]> pts = new ArrayList<>();
            if (et.contains("P")) {
                var ptl = et.getList("P", net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (int j = 0; j < ptl.size(); j++) {
                    var p = ptl.getCompound(j);
                    pts.add(new double[]{p.getDouble("X"), p.getDouble("Z")});
                }
            }
            cachedEdges.add(new com.trainsystemutilities.network.TrackNetworkScanner.EdgeInfo(
                    et.getInt("F"), et.getInt("T"), pts));
        }
        cachedStations = new ArrayList<>();
        var stationList = tag.getList("MapStations", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < stationList.size(); i++) {
            CompoundTag st = stationList.getCompound(i);
            cachedStations.add(new com.trainsystemutilities.network.TrackNetworkScanner.StationInfo(
                    st.getString("N"), BlockPos.of(st.getLong("P"))));
        }
        cachedTrains = new ArrayList<>();
        var trainList = tag.getList("MapTrains", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < trainList.size(); i++) {
            CompoundTag tt = trainList.getCompound(i);
            java.util.UUID uuid = tt.contains("U") ? tt.getUUID("U") : null;
            cachedTrains.add(new com.trainsystemutilities.network.TrackNetworkScanner.TrainInfo(
                    uuid, tt.getString("N"), tt.getInt("C"),
                    tt.getDouble("SP"), tt.getBoolean("AS"), tt.getString("ST"),
                    tt.getDouble("X"), tt.getDouble("Z")));
        }
        cachedSignals = new ArrayList<>();
        var signalList = tag.getList("MapSignals", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var signalStates = com.trainsystemutilities.network.TrackNetworkScanner.SignalState.values();
        for (int i = 0; i < signalList.size(); i++) {
            CompoundTag st = signalList.getCompound(i);
            int ord = st.getInt("S");
            cachedSignals.add(new com.trainsystemutilities.network.TrackNetworkScanner.SignalInfo(
                    BlockPos.of(st.getLong("P")),
                    signalStates[ord >= 0 && ord < signalStates.length ? ord : 0]));
        }
        trainSchedViews.clear();
        var schedViewList = tag.getList("TrainSchedViews", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < schedViewList.size(); i++) {
            CompoundTag sv = schedViewList.getCompound(i);
            if (!sv.hasUUID("U")) continue;
            java.util.List<String> sEntries = new java.util.ArrayList<>();
            var el = sv.getList("E", net.minecraft.nbt.Tag.TAG_STRING);
            for (int j = 0; j < el.size(); j++) sEntries.add(el.getString(j));
            trainSchedViews.put(sv.getUUID("U"), new SchedView(sEntries, sv.getInt("Cur"), sv.getBoolean("Cyc")));
        }
    }

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
}
