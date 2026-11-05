package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState;
import com.trainsystemutilities.electrification.contraption.TrainElectrificationView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The electrified trains on manta:data (MANTA_7_CONCEPT C4, TSU slice T6): what TrainElectrificationSyncPayload sent to
 * every player - every second (ElectrificationTickHandler) and after a pantograph action - is one server-wide host's
 * three lists, declared by the state-only document {@link #DOCUMENT}; every client subscribes at login
 * (client.TrainClient), as the broadcast reached everyone. The payload nested trains / cars / pantographs; the lists
 * are flat, a car naming its train's row and a pantograph its car's. The snapshot is the payload's: live data where a
 * carriage is loaded, the last known view where it is not. Whole trains are left out once the lists are full - never a
 * part of one (the payload's writer threw on a train name past 64 chars, which disconnected every player).
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class ElectrificationData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/electrification-state.json";
    static final String CHANNEL = TrainSystemUtilities.MOD_ID + ":electrification@server";

    /** electrification-state.json's list sizes and the maxLen of a train name (UTF-8 bytes). */
    static final int TRAIN_ROWS = 64;
    static final int CAR_ROWS = 512;
    static final int PANTO_ROWS = 1024;
    static final int NAME_BYTES = 128;
    /** A carriage index's declared max (the payload's decoder took 0..1024). */
    static final int MAX_CAR_INDEX = 1024;
    /** A pantograph bar offset rides as thousandths (the document has no float). */
    static final int BAR = 1000;

    private static Schema schema;
    /** The open host; server thread only (start, stop and every publisher run there). */
    @Nullable
    private static Host host;

    /** 列車 UUID → 直近 broadcast 時の電化スナップショット (in-memory)。
     *
     *  <p>列車がチャンクロード外に行くと {@link Carriage#anyAvailableEntity()} が null を返し、
     *  ContraptionElectrificationState から live data を取れなくなる。GUI 側が「列車が
     *  電化されていない」と誤判定するのを防ぐため、最後に live data を取れた状態を
     *  per-train UUID で保持し、live data 欠落時にフォールバックする。
     *
     *  <p>Train.id は {@code Create.RAILWAYS.trains} と同期する (= 解体された列車は次回
     *  snapshot 時に retainAll で除去される)。 */
    private static final Map<UUID, TrainElectrificationView> lastKnownViews = new ConcurrentHashMap<>();

    private ElectrificationData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel() {
        return CHANNEL;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        host = MantaData.host(event.getServer(), CHANNEL, schema(), player -> true);
    }

    /** Manta closes every host with the server; this one is forgotten so the next server opens its own. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        host = null;
    }

    /** The electrified trains as they are now (TrainElectrificationSyncPayload.broadcast until C4). */
    public static void publish(MinecraftServer server) {
        Host h = host;
        if (server == null || h == null) {
            return;
        }
        List<TrainElectrificationView> views = fitting(snapshot());
        h.setList("elec-trains", trainRows(views));
        h.setList("elec-cars", carRows(views));
        h.setList("elec-pantos", pantoRows(views));
    }

    /** サーバ側で全電化列車のスナップショットを作成。チャンクロード外の列車も
     *  {@link #lastKnownViews} キャッシュから前回値を使って続けて見せる。 */
    static List<TrainElectrificationView> snapshot() {
        List<TrainElectrificationView> views = new ArrayList<>();
        Set<UUID> seenTrainIds = new HashSet<>();

        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train == null) continue;
            seenTrainIds.add(train.id);

            TrainElectrificationView cached = lastKnownViews.get(train.id);
            TrainElectrificationView fresh = buildView(train, cached);
            if (fresh != null) {
                // 列車単位の電化判定: panto と inverter の両方が存在
                boolean freshIsElectrified = fresh.isElectrifiedTrain();
                if (freshIsElectrified) {
                    lastKnownViews.put(train.id, fresh);
                    views.add(fresh);
                } else if (cached != null && cached.isElectrifiedTrain()) {
                    // 全車両 unloaded で live data 不足 → 旧キャッシュをそのまま使用
                    views.add(cached);
                }
                // else: 電化されていない列車 → views に追加しない
            }
        }

        // 解体されて Create.RAILWAYS.trains から消えた列車のキャッシュを除去
        if (lastKnownViews.size() > seenTrainIds.size()) {
            lastKnownViews.keySet().retainAll(seenTrainIds);
        }
        return views;
    }

    /** 1 列車分のビューを構築。{@code cached} は前回 broadcast 時のキャッシュ (null 可)。
     *  各車両ごとに live data を試み、取得失敗時は cached の同 carriageIndex を使う。
     *  両方失敗時は {@link TrainElectrificationView.Car#empty} で穴埋め。 */
    private static TrainElectrificationView buildView(Train train, TrainElectrificationView cached) {
        if (train.carriages == null) return null;
        List<TrainElectrificationView.Car> cars = new ArrayList<>(train.carriages.size());
        int idx = 0;
        for (Carriage carriage : train.carriages) {
            TrainElectrificationView.Car liveCar = tryBuildLiveCar(carriage, idx);
            if (liveCar != null) {
                cars.add(liveCar);
            } else {
                TrainElectrificationView.Car cachedCar = findCachedCar(cached, idx);
                cars.add(cachedCar != null ? cachedCar : TrainElectrificationView.Car.empty(idx));
            }
            idx++;
        }
        return new TrainElectrificationView(train.id, train.name.getString(), cars);
    }

    /** {@code carriage} の contraption が loaded で、電化系コンポーネント (panto or inverter) を
     *  1 つでも持つなら Car を返す。完全に何も持たない車両 (= 通常車両) は null を返す。
     *  ※ 旧仕様は「self-electrified (panto+inverter 両方ある)」のみ Car 化していたが、
     *  パンタ車両/インバータ車両を分離する構成にも対応するため判定を緩和。
     *  「列車として電化されているか」の最終判定は呼び出し側 (buildView/snapshot) で行う。 */
    private static TrainElectrificationView.Car tryBuildLiveCar(Carriage carriage, int idx) {
        Contraption c = contraptionOf(carriage);
        if (c == null) return null;
        ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c);
        if (info == null) return null;
        if (info.inverters.isEmpty() && info.dummyInverters.isEmpty() && info.pantographs.isEmpty()) return null;
        List<TrainElectrificationView.PantoEntry> pantos = new ArrayList<>(info.pantographs.size());
        for (BlockPos pp : info.pantographs) {
            pantos.add(new TrainElectrificationView.PantoEntry(
                    pp,
                    info.deployedPantographs.contains(pp),
                    info.isPantographInContact(pp),
                    info.getPantographBarOffsetY(pp)));
        }
        return new TrainElectrificationView.Car(idx, !info.inverters.isEmpty(),
                info.inverters.size(),
                info.dummyInverters.size(),
                info.getStoredEnergy(), ContraptionElectrificationState.Info.CAPACITY,
                info.inContact, pantos);
    }

    private static TrainElectrificationView.Car findCachedCar(TrainElectrificationView cached, int idx) {
        if (cached == null || cached.cars == null) return null;
        for (TrainElectrificationView.Car cc : cached.cars) {
            if (cc.carriageIndex == idx) return cc;
        }
        return null;
    }

    private static Contraption contraptionOf(Carriage carriage) {
        if (carriage == null) return null;
        if (carriage.anyAvailableEntity() instanceof CarriageContraptionEntity cce) {
            return cce.getContraption();
        }
        return null;
    }

    // ===== rows: every value within its declaration =====

    /** How many trains the last publish left out; the warning is logged when it changes, not every second. */
    private static int lastLeftOut;

    /** The views to send: in order, whole trains while trains, cars and pantographs all fit their lists. */
    static List<TrainElectrificationView> fitting(List<TrainElectrificationView> views) {
        List<TrainElectrificationView> kept = new ArrayList<>(Math.min(views.size(), TRAIN_ROWS));
        int cars = 0;
        int pantos = 0;
        for (TrainElectrificationView v : views) {
            int c = v.cars.size();
            int p = 0;
            for (TrainElectrificationView.Car car : v.cars) p += car.pantographs.size();
            if (kept.size() == TRAIN_ROWS || cars + c > CAR_ROWS || pantos + p > PANTO_ROWS) {
                break;
            }
            kept.add(v);
            cars += c;
            pantos += p;
        }
        int leftOut = views.size() - kept.size();
        if (leftOut != lastLeftOut) {
            lastLeftOut = leftOut;
            if (leftOut > 0) {
                TrainSystemUtilities.LOGGER.warn("[Electrification] {} electrified trains; the lists carry {} of them",
                        views.size(), kept.size());
            }
        }
        return kept;
    }

    static List<List<Object>> trainRows(List<TrainElectrificationView> views) {
        List<List<Object>> rows = new ArrayList<>(views.size());
        for (TrainElectrificationView v : views) {
            rows.add(List.<Object>of(v.trainId.toString(), TransitData.text(v.trainName, NAME_BYTES)));
        }
        return rows;
    }

    static List<List<Object>> carRows(List<TrainElectrificationView> views) {
        List<List<Object>> rows = new ArrayList<>();
        for (int t = 0; t < views.size(); t++) {
            for (TrainElectrificationView.Car car : views.get(t).cars) {
                rows.add(List.<Object>of(t, TransitData.clamp(car.carriageIndex, 0, MAX_CAR_INDEX), car.hasInverter,
                        car.inverterCount, car.dummyInverterCount, car.storedEnergy, car.capacity, car.inContact));
            }
        }
        return rows;
    }

    static List<List<Object>> pantoRows(List<TrainElectrificationView> views) {
        List<List<Object>> rows = new ArrayList<>();
        int carRow = 0;
        for (TrainElectrificationView v : views) {
            for (TrainElectrificationView.Car car : v.cars) {
                for (TrainElectrificationView.PantoEntry pe : car.pantographs) {
                    rows.add(List.<Object>of(carRow, pe.pos.getX(), pe.pos.getY(), pe.pos.getZ(), pe.deployed,
                            pe.inContact, TrainPositionsData.toInt(Math.round((double) pe.barOffsetY * BAR))));
                }
                carRow++;
            }
        }
        return rows;
    }

    /** The three lists read back as the views the payload carried; a train whose id does not parse is left out. */
    public static List<TrainElectrificationView> viewsOf(List<?> trainRows, List<?> carRows, List<?> pantoRows) {
        List<List<TrainElectrificationView.PantoEntry>> pantosByCar = new ArrayList<>();
        for (int i = 0; i < carRows.size(); i++) pantosByCar.add(new ArrayList<>());
        for (Object o : pantoRows) {
            List<?> r = (List<?>) o;
            int car = (Integer) r.get(0);
            if (car < pantosByCar.size()) {
                pantosByCar.get(car).add(new TrainElectrificationView.PantoEntry(
                        new BlockPos((Integer) r.get(1), (Integer) r.get(2), (Integer) r.get(3)),
                        (Boolean) r.get(4), (Boolean) r.get(5), (Integer) r.get(6) / (float) BAR));
            }
        }
        List<List<TrainElectrificationView.Car>> carsByTrain = new ArrayList<>();
        for (int i = 0; i < trainRows.size(); i++) carsByTrain.add(new ArrayList<>());
        for (int i = 0; i < carRows.size(); i++) {
            List<?> r = (List<?>) carRows.get(i);
            int train = (Integer) r.get(0);
            if (train < carsByTrain.size()) {
                carsByTrain.get(train).add(new TrainElectrificationView.Car((Integer) r.get(1), (Boolean) r.get(2),
                        (Integer) r.get(3), (Integer) r.get(4), (Integer) r.get(5), (Integer) r.get(6),
                        (Boolean) r.get(7), pantosByCar.get(i)));
            }
        }
        List<TrainElectrificationView> views = new ArrayList<>(trainRows.size());
        for (int i = 0; i < trainRows.size(); i++) {
            List<?> r = (List<?>) trainRows.get(i);
            UUID id = ManagementComputerData.parseUuid((String) r.get(0));
            if (id != null) {
                views.add(new TrainElectrificationView(id, (String) r.get(1), carsByTrain.get(i)));
            }
        }
        return views;
    }
}
