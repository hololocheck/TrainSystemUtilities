package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.electrification.ClientPantographContactState;
import com.trainsystemutilities.client.electrification.ClientTrainElectrificationCache;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState;
import com.trainsystemutilities.electrification.contraption.TrainElectrificationView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server → Client: 電化列車スナップショット配信。
 *
 * <p>全列車の電化情報 (= FE インバータ / パンタ位置 / 展開状態 / FE 残量) をまとめて送信。
 * 管理コンピューター列車タブが開いているクライアントに 1 秒 / 通電状態変化時に配信。
 *
 * <p>1 列車あたりのサイズ: 16 (UUID) + name(<64) + 1 (carCount)
 *   + per car: 1 + 1 + 4 + 4 + 1 + per panto (8 + 1)
 *  10 編成 4 両 2 panto/車両 = 約 480 bytes。実用範囲。
 */
public record TrainElectrificationSyncPayload(List<TrainElectrificationView> views) implements CustomPacketPayload {

    public static final Type<TrainElectrificationSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "train_electrification_sync"));

    public static final StreamCodec<FriendlyByteBuf, TrainElectrificationSyncPayload> STREAM_CODEC =
            StreamCodec.of(TrainElectrificationSyncPayload::write, TrainElectrificationSyncPayload::read);

    private static void write(FriendlyByteBuf buf, TrainElectrificationSyncPayload p) {
        buf.writeVarInt(p.views.size());
        for (TrainElectrificationView v : p.views) {
            buf.writeUUID(v.trainId);
            buf.writeUtf(v.trainName == null ? "" : v.trainName, 64);
            buf.writeVarInt(v.cars.size());
            for (TrainElectrificationView.Car car : v.cars) {
                buf.writeVarInt(car.carriageIndex);
                buf.writeBoolean(car.hasInverter);
                buf.writeVarInt(car.inverterCount);
                buf.writeVarInt(car.dummyInverterCount);
                buf.writeVarInt(car.storedEnergy);
                buf.writeVarInt(car.capacity);
                buf.writeBoolean(car.inContact);
                buf.writeVarInt(car.pantographs.size());
                for (TrainElectrificationView.PantoEntry pe : car.pantographs) {
                    buf.writeLong(pe.pos.asLong());
                    buf.writeBoolean(pe.deployed);
                    buf.writeBoolean(pe.inContact);
                    buf.writeFloat(pe.barOffsetY);
                }
            }
        }
    }

    private static TrainElectrificationSyncPayload read(FriendlyByteBuf buf) {
        // P0-4 #7: 同時運行する電化列車の最大想定数
        int n = BoundedStreamCodec.readBoundedListLength(buf, 1024);
        List<TrainElectrificationView> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            // P0-4 #7: 列車名は generic label → 128 bytes
            String name = BoundedStreamCodec.readBoundedUtf(buf, 128);
            // P0-4 #7 hotfix: 1 列車内の車両数 (Create 大型列車対応で 1024 まで許容)
            int cc = BoundedStreamCodec.readBoundedListLength(buf, 1024);
            List<TrainElectrificationView.Car> cars = new ArrayList<>(cc);
            for (int j = 0; j < cc; j++) {
                // P0-4 #7 hotfix: carriageIndex は 1 列車内の車両 index (1024 まで許容)
                int idx = BoundedStreamCodec.readBoundedVarInt(buf, 1024);
                boolean hasInv = buf.readBoolean();
                // P0-4 #7: inverter count は 1 車両 max 数十 (writeVarInt 経由)
                int invCount = BoundedStreamCodec.readBoundedVarInt(buf, 4096);
                int dummyInvCount = BoundedStreamCodec.readBoundedVarInt(buf, 4096);
                // P0-4 #7: stored energy / capacity は FE 値 (int 範囲、 writeVarInt 経由)
                int se = BoundedStreamCodec.readBoundedVarInt(buf, Integer.MAX_VALUE);
                int cap = BoundedStreamCodec.readBoundedVarInt(buf, Integer.MAX_VALUE);
                boolean contact = buf.readBoolean();
                // P0-4 #7: 1 車両のパンタグラフ数
                int pc = BoundedStreamCodec.readBoundedListLength(buf, 256);
                List<TrainElectrificationView.PantoEntry> pantos = new ArrayList<>(pc);
                for (int k = 0; k < pc; k++) {
                    BlockPos pos = BlockPos.of(buf.readLong());
                    boolean dep = buf.readBoolean();
                    boolean inC = buf.readBoolean();
                    float off = buf.readFloat();
                    pantos.add(new TrainElectrificationView.PantoEntry(pos, dep, inC, off));
                }
                cars.add(new TrainElectrificationView.Car(idx, hasInv, invCount,
                        dummyInvCount, se, cap, contact, pantos));
            }
            list.add(new TrainElectrificationView(id, name, cars));
        }
        return new TrainElectrificationSyncPayload(list);
    }

    public static void handle(TrainElectrificationSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            for (TrainElectrificationView v : payload.views) {
                // P0-4 #7: trainName はユーザー由来 → SafeLog.sanitize で CR/LF/ESC 除去
            }
            ClientTrainElectrificationCache.replaceAll(payload.views);
            ClientPantographContactState.replaceAll(payload.views);
            // Phase 24: 各 contraption の BE.deployed を sync payload の値で直接上書き
            //   → 視覚アニメ駆動が次フレームから新しい状態を反映
            applyDeployedToClientContraptionBEs(payload.views);
        });
    }

    /** sync payload に基づき、各 train の contraption BE (= サブ contraption ブロック) の
     *  PantographBlockEntity.deployed を直接書き換える。idlePredicate の差分判定で
     *  次フレームから新しいアニメ (fold/unfold) に遷移する。 */
    private static void applyDeployedToClientContraptionBEs(List<TrainElectrificationView> views) {
        if (views == null || views.isEmpty()) return;
        int updated = 0;
        int attempts = 0;
        for (TrainElectrificationView v : views) {
            Train train = Create.RAILWAYS.trains.get(v.trainId);
            if (train == null) {
                continue;
            }
            if (train.carriages == null) continue;
            for (TrainElectrificationView.Car carView : v.cars) {
                if (carView.pantographs == null || carView.pantographs.isEmpty()) continue;
                if (carView.carriageIndex < 0 || carView.carriageIndex >= train.carriages.size()) {
                    continue;
                }
                Carriage carriage = train.carriages.get(carView.carriageIndex);
                Contraption c = contraptionOf(carriage);
                if (c == null) {
                    continue;
                }
                // ClientContraption は遅延生成 (= 描画されるまで null)。
                // ここで強制初期化して renderLevel に BE 群を立ち上げる。
                var clientContraption = c.getOrCreateClientContraptionLazy();
                if (clientContraption == null) {
                    continue;
                }
                // 実際に描画される BE は renderedBlockEntityView。getBlockEntity(pos) と
                // 別のインスタンスを返すことがあるので、ここでは list を走査して pos マッチを探す。
                java.util.Map<net.minecraft.core.BlockPos, com.trainsystemutilities.electrification.blockentity.PantographBlockEntity> rendered =
                        new java.util.HashMap<>();
                for (var be : clientContraption.renderedBlockEntityView) {
                    if (be instanceof com.trainsystemutilities.electrification.blockentity.PantographBlockEntity pbe) {
                        rendered.put(pbe.getBlockPos(), pbe);
                    }
                }
                for (TrainElectrificationView.PantoEntry pe : carView.pantographs) {
                    if (pe == null || pe.pos == null) continue;
                    attempts++;
                    try {
                        var pbe = rendered.get(pe.pos);
                        if (pbe == null) {
                            continue;
                        }
                        if (pbe.isDeployed() != pe.deployed) {
                            pbe.clientSetDeployed(pe.deployed);
                            updated++;
                        }
                    } catch (Throwable ex) {
                        TrainSystemUtilities.LOGGER.warn(
                                "[ApplyBE-DEBUG] car {} pos {} exception: {}",
                                carView.carriageIndex, pe.pos, ex.toString());
                    }
                }
            }
        }
    }

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

    /** サーバ側で全電化列車のスナップショットを作成。チャンクロード外の列車も
     *  {@link #lastKnownViews} キャッシュから前回値を使って続けて見せる。 */
    public static TrainElectrificationSyncPayload snapshot() {
        List<TrainElectrificationView> views = new ArrayList<>();
        Set<UUID> seenTrainIds = new HashSet<>();
        int totalTrains = 0;
        int trainsFromCache = 0;

        for (Train train : Create.RAILWAYS.trains.values()) {
            totalTrains++;
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
                    trainsFromCache++;
                }
                // else: 電化されていない列車 → views に追加しない
            }
        }

        // 解体されて Create.RAILWAYS.trains から消えた列車のキャッシュを除去
        if (lastKnownViews.size() > seenTrainIds.size()) {
            lastKnownViews.keySet().retainAll(seenTrainIds);
        }

        if (!views.isEmpty() || trainsFromCache > 0) {
        }
        return new TrainElectrificationSyncPayload(views);
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

    /** broadcast 呼び出し回数 (= log を 100 回ごとにサンプリングする用)。 */
    private static int broadcastCount = 0;

    /** 全プレイヤーへ配信。 */
    public static void broadcast(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        TrainElectrificationSyncPayload payload = snapshot();
        int playerCount = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                PacketDistributor.sendToPlayer(p, payload);
                playerCount++;
            }
        }
        // 10 秒ごと (= 10 broadcasts) にハートビートログ
        if (++broadcastCount % 10 == 0) {
        }
    }

    private static Contraption contraptionOf(Carriage carriage) {
        if (carriage == null) return null;
        if (carriage.anyAvailableEntity() instanceof CarriageContraptionEntity cce) {
            return cce.getContraption();
        }
        return null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
