package com.trainsystemutilities.electrification.contraption;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.ElectrificationConstants;
import com.trainsystemutilities.electrification.wire.EnergizedWireState;
import com.trainsystemutilities.electrification.wire.WireConnection;
import com.trainsystemutilities.electrification.wire.WireNetworkSavedData;
import com.trainsystemutilities.electrification.wire.WireType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバ tick で全電化列車のパンタグラフ集電 + 接触判定を駆動する。
 *
 * <p>{@link com.simibubi.create.api.behaviour.movement.MovementBehaviour#tick} は
 * Carriage contraption が「走行中」(= 動いている / actor がアクティブ) のときのみ呼ばれるため、
 * 停車中の列車は FE が貯まらない。これを補うため、ServerTickEvent.Post から
 * {@code Create.RAILWAYS.trains} を全件走査して各 panto の接触判定・FE 受け取りを行う。
 *
 * <p>{@link com.trainsystemutilities.electrification.contraption.PantographMovementBehaviour}
 * 側のロジックと等価。両方が呼ばれた場合は同じ tick 内で 2 回受け取ろうとするが、
 * Info.receive() は CAPACITY で上限管理されるので一時的な過剰でも害なし。
 */
public final class ContraptionElectrificationTicker {

    /** 集電バー基準位置の局所オフセット (= block 中心から上に 0.5 = ブロック上面)。 */
    private static final double PICKUP_BASE_Y = 0.5;
    /** XZ 平面上の許容ずれ (= 真上ではないが近い架線も拾う)。 */
    private static final double XZ_TOLERANCE = 1.0;
    /** バーが上方へ伸びる最大距離 (= 架線高さ - pantograph 上端 がこれ以下なら集電可)。 */
    private static final double VERTICAL_REACH = 4.0;
    /** 1 tick の集電量 (= 2000 FE)。FE_KEEP_ALIVE_PER_TICK (1500) を上回るので架線下では満充電方向。 */
    private static final int FE_PICKUP_PER_TICK = 2000;
    /** 走行維持コスト (FE/tick)。**列車 1 本あたりの合計** (= インバータ車両数に依らない)。
     *  先頭の非空インバータ車から順に消費する (= 1 両ずつ枯渇)。 従来は FEInverterMovementBehaviour が
     *  車両ごとに引いていた (= 車両数 × 1500) ため、 複数インバータ + パンタ 1 本で集電 (2000) が
     *  消費 (N×1500) に負けて FE が減り続けた。 列車合計 1500 に統一し、 パンタ 1 本で走れるようにする。 */
    public static final int FE_KEEP_ALIVE_PER_TICK = 1500;

    /** WireType ごとの「吊架線 (碍子接続点) → トロリ線 (接触線)」までのオフセット (= 下方向距離)。
     *  {@code CatenaryRenderer} の TROLLEY_OFFSET_Y / TROLLEY_OFFSET_Y_HIGH と完全に同期させること。
     *  SIMPLE は装飾線なので接触なし扱いとして 0 (= attach 点で接触判定)。 */
    private static double trolleyOffset(WireType t) {
        if (t == null) return 0;
        return switch (t) {
            case SIMPLE -> 0;
            case TWO_TIER -> 0.5;
            case TWIN_2ROW -> 0.5;
            case HIGH_OFFSET -> 0.9;
            case CUSTOM -> 0; // 接続ごとに異なるため、呼び出し側で trolleyOffset(WireConnection) を使う
        };
    }

    /** CUSTOM 線も含めて接続ごとの正確な trolley オフセットを返す。 */
    private static double trolleyOffset(WireConnection wc) {
        if (wc == null) return 0;
        if (wc.type() == WireType.CUSTOM) {
            return Math.max(0, Math.min(2.0, wc.customTrolleyOffset()));
        }
        return trolleyOffset(wc.type());
    }

    private static int tickLogCounter = 0;
    private static final int TICK_LOG_EVERY = 40;

    private ContraptionElectrificationTicker() {}

    /** 全電化列車を走査して集電 tick を回す。
     *  さらに「電化列車かつパンタ全折畳」の状態なら速度を 0 に強制 → Create 側で動かない。 */
    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        boolean shouldLog = (tickLogCounter++ % TICK_LOG_EVERY) == 0;
        int trainsProcessed = 0;
        int contraptionsProcessed = 0;
        int chargedPantos = 0;
        int carriagesSkippedNoEntity = 0;

        // P0-1 #9: snapshot iteration で Create 側の spawn/despawn 並行変更による CME を排除
        for (Train train : com.trainsystemutilities.compat.CreateBridge.snapshotTrains()) {
            if (train == null || train.carriages == null) continue;
            trainsProcessed++;

            // 列車全体でパンタ車・インバータ車・展開パンタの有無を事前集計。
            // (= 旧コードは「車両単独に panto+inverter 両方ある」しか電化扱いしなかったが、
            //   1 車両にパンタ・別車両にインバータを置く構成にも対応するため、判定を列車単位に変更)
            // trainElectrified = FE 集電 / 強制停止が動く本格電化 (機能版 inverter 必須)
            // trainPantographable = 接触判定だけ動く装飾モード対応 (機能版 or 装飾版どちらでも)
            boolean trainHasPanto = false, trainHasInverter = false,
                    trainHasAnyInverterType = false;
            for (Carriage carriage : train.carriages) {
                Contraption c = contraptionOf(carriage);
                if (c == null) continue;
                ContraptionElectrificationState.Info info =
                        ContraptionElectrificationState.getInfo(c);
                if (info == null) continue;
                if (!info.pantographs.isEmpty()) trainHasPanto = true;
                if (!info.inverters.isEmpty()) trainHasInverter = true;
                if (info.hasAnyInverterType()) trainHasAnyInverterType = true;
            }
            boolean trainElectrified = trainHasPanto && trainHasInverter;
            boolean trainPantographable = trainHasPanto && trainHasAnyInverterType;

            if (trainPantographable) {
                int carriageIdx = -1;
                for (Carriage carriage : train.carriages) {
                    carriageIdx++;
                    Contraption c = contraptionOf(carriage);
                    if (c == null || c.entity == null) {
                        carriagesSkippedNoEntity++;
                        if (shouldLog) TrainSystemUtilities.LOGGER.info(
                                "[ElectTick] train={} car={} SKIP (entity unloaded; c={} entity={})",
                                train.name.getString(), carriageIdx,
                                c == null ? "null" : "ok",
                                c == null ? "n/a" : (c.entity == null ? "null" : "ok"));
                        continue;
                    }
                    if (!(c.entity.level() instanceof ServerLevel level)) continue;
                    ContraptionElectrificationState.Info info =
                            ContraptionElectrificationState.getInfo(c);
                    if (info == null) continue;
                    // パンタを持つ車両のみ contact 検出を実行 (インバータのみの車両は何もしない)
                    if (info.pantographs.isEmpty()) continue;
                    contraptionsProcessed++;
                    if (trainElectrified) {
                        // 本格電化: 接触判定 + FE pickup
                        int charged = tickContraption(level, c, info, shouldLog, train, carriageIdx);
                        chargedPantos += charged;
                    } else {
                        // 装飾モード列車 (機能版 inverter なし): 接触判定だけ走らせて
                        // パンタの架線への "当たり" 見た目を成立させる (FE 受け取りはしない)
                        recomputeContactsInternal(level, c, info);
                    }
                }
            }
            // 走行維持コスト: 電化列車が走行中なら、 列車合計 FE_KEEP_ALIVE_PER_TICK を先頭の非空
            // インバータ車から順に消費する (= 1 両ずつ枯渇)。 パンタ折畳でも FE がある限り走行し、
            // FE=0 で停止する (= ContraptionElectrificationLock / ElectrificationTrainGate)。
            // 従来の「全パンタ折畳 → 即強制停止」は撤廃し、 停止条件を FE=0 に一本化した (E2/E3)。
            if (trainElectrified && Math.abs(train.speed) > 1.0e-4) {
                consumeKeepAliveSequential(train, FE_KEEP_ALIVE_PER_TICK);
            }
        }
        if (shouldLog) {

            // 解体された列車の PERSISTENT_ENERGY エントリを GC
            // (= ログ出力間隔 = 10 秒に 1 回でも十分。リーク量は微小)
            java.util.Set<Long> activeKeys = new java.util.HashSet<>();
            for (Train tr : Create.RAILWAYS.trains.values()) {
                if (tr == null || tr.carriages == null) continue;
                for (int i = 0; i < tr.carriages.size(); i++) {
                    activeKeys.add(ContraptionElectrificationState.carriageKey(tr.id, i));
                }
            }
            ContraptionElectrificationState.pruneToActiveKeys(activeKeys);
        }
    }

    /** 接触判定のみ即座に再計算 (FE 受け取りはスキップ) する公開ヘルパ。
     *  GUI からの deploy toggle 直後に「inContact / barOffsetY を最新化してから sync 配信」
     *  するため呼ばれる。これがないと、トグル直後の sync は inContact=false (= 旧値) を
     *  クライアントに送ってしまい、パンタが一度全開して直後に最終姿勢へ調整するチラつきが出る。 */
    public static void recomputeContactsImmediate(Contraption c) {
        if (c == null || c.entity == null) return;
        if (!(c.entity.level() instanceof ServerLevel level)) return;
        ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
        if (info == null) return;
        // パンタを持たない車両は contact 検出する対象が無いので skip
        if (info.pantographs.isEmpty()) return;
        // 列車単位の "パンタ操作可能" 判定 (装飾モードも含む)
        Train train = getTrainOf(c);
        if (train == null || !isTrainPantographable(train)) return;
        // tickContraption と等価の接触判定パスだが、FE pickup と stoppage チェックは行わない
        recomputeContactsInternal(level, c, info);
    }

    /** tickContraption の接触判定部分のみを抽出した内部実装。 */
    private static void recomputeContactsInternal(ServerLevel level, Contraption c,
                                                   ContraptionElectrificationState.Info info) {
        WireNetworkSavedData data = WireNetworkSavedData.get(level);
        EnergizedWireState energized = EnergizedWireState.get(level);
        if (data.size() == 0 || energized.size() == 0) {
            for (BlockPos pp : info.pantographs) info.updatePantographContact(pp, false, 0f);
            info.inContact = false;
            return;
        }
        boolean anyContact = false;
        for (BlockPos lp : info.pantographs) {
            if (!info.isPantographDeployed(lp)) {
                info.updatePantographContact(lp, false, 0f);
                continue;
            }
            Vec3 localPickup = new Vec3(lp.getX() + 0.5, lp.getY() + PICKUP_BASE_Y + 0.5, lp.getZ() + 0.5);
            Vec3 worldPickup = c.entity.toGlobalVector(localPickup, 1.0F);
            List<WireConnection> nearby = data.spatialIndex().queryRangeXZ(worldPickup, XZ_TOLERANCE + 0.5);
            boolean contact = false;
            double bestWireY = 0;
            double bestVerticalGap = Double.MAX_VALUE;
            for (WireConnection wc : nearby) {
                if (!energized.isEnergized(wc)) continue;
                double trolleyOff = trolleyOffset(wc);
                Vec3 attachA = wc.attachA();
                Vec3 attachB = wc.attachB();
                Vec3 trolleyA = new Vec3(attachA.x, attachA.y - trolleyOff, attachA.z);
                Vec3 trolleyB = new Vec3(attachB.x, attachB.y - trolleyOff, attachB.z);
                double xzDist = pointSegDistXZ(worldPickup, trolleyA, trolleyB);
                if (xzDist > XZ_TOLERANCE) continue;
                double wireY = wireYAtClosestPointXZ(worldPickup, trolleyA, trolleyB);
                double vertical = wireY - worldPickup.y;
                if (vertical < 0) continue;
                if (vertical > VERTICAL_REACH) continue;
                if (vertical < bestVerticalGap) {
                    contact = true;
                    bestVerticalGap = vertical;
                    bestWireY = wireY;
                }
            }
            if (contact) {
                float barOffsetY = (float) (bestWireY - worldPickup.y);
                info.updatePantographContact(lp, true, barOffsetY);
                anyContact = true;
            } else {
                info.updatePantographContact(lp, false, 0f);
            }
        }
        info.inContact = anyContact;
    }

    /** 1 つの contraption の全パンタを順に処理。戻り値は集電に成功したパンタ数。 */
    private static int tickContraption(ServerLevel level, Contraption c,
                                        ContraptionElectrificationState.Info info,
                                        boolean shouldLogDetail,
                                        Train trainCtx, int carriageIdxCtx) {
        WireNetworkSavedData data = WireNetworkSavedData.get(level);
        EnergizedWireState energized = EnergizedWireState.get(level);
        if (data.size() == 0 || energized.size() == 0) {
            for (BlockPos pp : info.pantographs) info.updatePantographContact(pp, false, 0f);
            info.inContact = false;
            if (shouldLogDetail && !info.pantographs.isEmpty()) {
            }
            return 0;
        }

        int charged = 0;
        boolean anyContact = false;

        for (BlockPos lp : info.pantographs) {
            if (!info.isPantographDeployed(lp)) {
                info.updatePantographContact(lp, false, 0f);
                continue;
            }
            // パンタ block の上面 (= world-space)
            Vec3 localPickup = new Vec3(lp.getX() + 0.5, lp.getY() + PICKUP_BASE_Y + 0.5, lp.getZ() + 0.5);
            Vec3 worldPickup = c.entity.toGlobalVector(localPickup, 1.0F);
            // 「真上 + 上方の到達範囲」を考えると、XZ 検索半径は XZ_TOLERANCE で十分。
            List<WireConnection> nearby = data.spatialIndex().queryRangeXZ(worldPickup, XZ_TOLERANCE + 0.5);
            int energizedNearby = 0;
            for (WireConnection wc : nearby) if (energized.isEnergized(wc)) energizedNearby++;

            boolean contact = false;
            double bestWireY = 0;
            double bestVerticalGap = Double.MAX_VALUE;
            for (WireConnection wc : nearby) {
                if (!energized.isEnergized(wc)) continue;
                // 接触するのは吊架線 (attachA/B) ではなく下に下がっているトロリ線。
                // WireType 別に対応するオフセット下方向にずらして接触判定する。
                double trolleyOff = trolleyOffset(wc);
                Vec3 attachA = wc.attachA();
                Vec3 attachB = wc.attachB();
                Vec3 trolleyA = new Vec3(attachA.x, attachA.y - trolleyOff, attachA.z);
                Vec3 trolleyB = new Vec3(attachB.x, attachB.y - trolleyOff, attachB.z);
                // 1) XZ 平面で線分との水平距離
                double xzDist = pointSegDistXZ(worldPickup, trolleyA, trolleyB);
                if (xzDist > XZ_TOLERANCE) continue;
                // 2) 線分の Y は XZ 最近点での Y を採る (架線がたわむ場合の高さ)
                double wireY = wireYAtClosestPointXZ(worldPickup, trolleyA, trolleyB);
                double vertical = wireY - worldPickup.y;
                if (vertical < 0) continue;                  // 架線が下にある = 物理的にあり得ない
                if (vertical > VERTICAL_REACH) continue;    // 届かない
                if (vertical < bestVerticalGap) {
                    contact = true;
                    bestVerticalGap = vertical;
                    bestWireY = wireY;
                }
            }

            if (shouldLogDetail) TrainSystemUtilities.LOGGER.info(
                    "[ElectTick]   train={} car={} panto@{} pickup=({},{},{}) nearbyWires={} energized={} contact={} fe={}",
                    trainCtx.name.getString(), carriageIdxCtx, lp,
                    String.format("%.1f", worldPickup.x),
                    String.format("%.1f", worldPickup.y),
                    String.format("%.1f", worldPickup.z),
                    nearby.size(), energizedNearby, contact, info.getStoredEnergy());

            if (contact) {
                float barOffsetY = (float) (bestWireY - worldPickup.y);
                info.updatePantographContact(lp, true, barOffsetY);
                // 集電した FE は列車内の全インバータ車両に均等分配する。
                // パンタ車両自身にインバータが無くても、他の車両に蓄電される。
                int received = distributeFEAcrossTrain(trainCtx, FE_PICKUP_PER_TICK);
                if (received > 0) charged++;
                anyContact = true;
            } else {
                info.updatePantographContact(lp, false, 0f);
            }
        }
        info.inContact = anyContact;
        return charged;
    }

    /** XZ 平面上での 線分 ab と点 p の最短距離。Y は無視。 */
    private static double pointSegDistXZ(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, abz = b.z - a.z;
        double apx = p.x - a.x, apz = p.z - a.z;
        double abLen2 = abx * abx + abz * abz;
        double t = abLen2 < 1e-9 ? 0
                : Math.max(0, Math.min(1, (apx * abx + apz * abz) / abLen2));
        double cx = a.x + abx * t, cz = a.z + abz * t;
        double dx = p.x - cx, dz = p.z - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** XZ 最近点での Y を返す。 */
    private static double wireYAtClosestPointXZ(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, abz = b.z - a.z;
        double apx = p.x - a.x, apz = p.z - a.z;
        double abLen2 = abx * abx + abz * abz;
        double t = abLen2 < 1e-9 ? 0
                : Math.max(0, Math.min(1, (apx * abx + apz * abz) / abLen2));
        return a.y + (b.y - a.y) * t;
    }

    private static Contraption contraptionOf(Carriage carriage) {
        if (carriage == null) return null;
        if (carriage.anyAvailableEntity() instanceof CarriageContraptionEntity cce) {
            return cce.getContraption();
        }
        return null;
    }

    // ===== Train 単位の電化判定 / FE 分配ヘルパ =====
    //
    // 1 列車内で「パンタ車両」と「インバータ車両」が別になっていてもよい設計。
    // 例: 先頭車にパンタ、客車に FE インバータ複数 → 列車全体で 1 つの電化システム
    // と見なす。集電された FE はインバータ車両に均等分配される。

    /** 指定列車が「電化列車」か (= パンタを 1 つ以上持つ車両 AND **機能版** インバータを 1 つ以上持つ車両が両方存在)。
     *  FE 集電 / 走行停止判定の gate に使う (装飾版は除外)。 */
    public static boolean isTrainElectrified(Train train) {
        if (train == null || train.carriages == null) return false;
        boolean hasPanto = false, hasInv = false;
        for (Carriage carriage : train.carriages) {
            Contraption c = contraptionOf(carriage);
            if (c == null) continue;
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info == null) continue;
            if (!info.pantographs.isEmpty()) hasPanto = true;
            if (!info.inverters.isEmpty()) hasInv = true;
            if (hasPanto && hasInv) return true;
        }
        return false;
    }

    /** 列車がパンタ操作可能 (= 接触判定 + UI からの展開/折畳 の対象) か。
     *  装飾版インバータでも OK (UI 視覚動作のみ、FE は流れない)。 */
    public static boolean isTrainPantographable(Train train) {
        if (train == null || train.carriages == null) return false;
        boolean hasPanto = false, hasAnyInv = false;
        for (Carriage carriage : train.carriages) {
            Contraption c = contraptionOf(carriage);
            if (c == null) continue;
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info == null) continue;
            if (!info.pantographs.isEmpty()) hasPanto = true;
            if (info.hasAnyInverterType()) hasAnyInv = true;
            if (hasPanto && hasAnyInv) return true;
        }
        return false;
    }

    /** Contraption から所属 Train を取り出す。entity 未ロード時は null。 */
    public static Train getTrainOf(Contraption c) {
        if (c == null || c.entity == null) return null;
        if (!(c.entity instanceof CarriageContraptionEntity cce)) return null;
        Carriage carriage = cce.getCarriage();
        return carriage == null ? null : carriage.train;
    }

    /** Contraption が電化列車に所属するか。FEInverterMovementBehaviour 等の per-tick チェックに使用。 */
    public static boolean isPartOfElectrifiedTrain(Contraption c) {
        Train train = getTrainOf(c);
        return train != null && isTrainElectrified(train);
    }

    /** 列車内の全インバータ車両の FE 合計。ElectrificationTrainGate の駆動可否判定に使用。 */
    public static int trainTotalStoredEnergy(Train train) {
        if (train == null || train.carriages == null) return 0;
        int total = 0;
        for (Carriage carriage : train.carriages) {
            Contraption c = contraptionOf(carriage);
            if (c == null) continue;
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info != null && !info.inverters.isEmpty()) {
                total += info.getStoredEnergy();
            }
        }
        return total;
    }

    /** 電化判定 (= panto + inverter 両方あり) と全インバータ FE 合計。 */
    public record TrainElectricStatus(boolean electrified, int totalStoredEnergy) {}

    /**
     * {@link #isTrainElectrified} + {@link #trainTotalStoredEnergy} を 1 回の carriage 走査で
     * まとめて求める (= ElectrificationTrainGate が 2 回走査していたのを 1 回に統合)。
     * 返り値は両メソッドを個別呼びした場合と同一。
     */
    public static TrainElectricStatus trainElectricStatus(Train train) {
        if (train == null || train.carriages == null) return new TrainElectricStatus(false, 0);
        boolean hasPanto = false, hasInv = false;
        int total = 0;
        for (Carriage carriage : train.carriages) {
            Contraption c = contraptionOf(carriage);
            if (c == null) continue;
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info == null) continue;
            if (!info.pantographs.isEmpty()) hasPanto = true;
            if (!info.inverters.isEmpty()) {
                hasInv = true;
                total += info.getStoredEnergy();
            }
        }
        return new TrainElectricStatus(hasPanto && hasInv, total);
    }

    /** 列車内の全インバータ車両に FE を均等分配して充電する。
     *  パンタ車両自身にインバータが無くても、他車両にあれば充電が機能する。
     *  戻り値 = 実際に受け取られた FE 合計 (= CAPACITY で上限切られた分は除外)。 */
    public static int distributeFEAcrossTrain(Train train, int amount) {
        if (train == null || train.carriages == null || amount <= 0) return 0;
        List<ContraptionElectrificationState.Info> targetInfos = new ArrayList<>();
        List<Contraption> targetContraptions = new ArrayList<>();
        for (Carriage carriage : train.carriages) {
            Contraption c = contraptionOf(carriage);
            if (c == null) continue;
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info == null || info.inverters.isEmpty()) continue;
            targetInfos.add(info);
            targetContraptions.add(c);
        }
        if (targetInfos.isEmpty()) return 0;

        int perCar = amount / targetInfos.size();
        int remainder = amount - perCar * targetInfos.size();
        int totalAccepted = 0;
        for (int i = 0; i < targetInfos.size(); i++) {
            int give = perCar + (i < remainder ? 1 : 0);
            int accepted = targetInfos.get(i).receive(give);
            totalAccepted += accepted;
            if (accepted > 0) {
                ContraptionElectrificationState.persistEnergyToInverterNbt(
                        targetContraptions.get(i), targetInfos.get(i).getStoredEnergy());
            }
        }
        return totalAccepted;
    }

    /** 列車の走行維持コストを、 先頭 (carriage 順) の非空インバータ車から順に amount 分 drain する。
     *  1 両が空になったら次の車へ繰り越す (= 1 両ずつ枯渇 = E2)。 実際に drain した合計を返す。
     *  従来 (FEInverterMovementBehaviour で車両ごとに引く) と違い、 列車合計が amount で頭打ちになるため、
     *  複数インバータでもパンタ 1 本の集電で走れる (E1)。 */
    public static int consumeKeepAliveSequential(Train train, int amount) {
        if (train == null || train.carriages == null || amount <= 0) return 0;
        int remaining = amount;
        int totalDrained = 0;
        for (Carriage carriage : train.carriages) {
            if (remaining <= 0) break;
            Contraption c = contraptionOf(carriage);
            if (c == null) continue;
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
            if (info == null || info.inverters.isEmpty() || info.getStoredEnergy() <= 0) continue;
            int taken = info.drain(remaining);
            if (taken > 0) {
                remaining -= taken;
                totalDrained += taken;
                ContraptionElectrificationState.persistEnergyToInverterNbt(c, info.getStoredEnergy());
            }
        }
        return totalDrained;
    }

    private static double pointSegDistSq(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double apx = p.x - a.x, apy = p.y - a.y, apz = p.z - a.z;
        double abLen2 = abx * abx + aby * aby + abz * abz;
        double t = abLen2 < 1e-9 ? 0
                : Math.max(0, Math.min(1, (apx * abx + apy * aby + apz * abz) / abLen2));
        double cx = a.x + abx * t, cy = a.y + aby * t, cz = a.z + abz * t;
        double dx = p.x - cx, dy = p.y - cy, dz = p.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double wireYAtClosestPoint(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double apx = p.x - a.x, apy = p.y - a.y, apz = p.z - a.z;
        double abLen2 = abx * abx + aby * aby + abz * abz;
        double t = abLen2 < 1e-9 ? 0
                : Math.max(0, Math.min(1, (apx * abx + apy * aby + apz * abz) / abLen2));
        return a.y + aby * t;
    }
}
