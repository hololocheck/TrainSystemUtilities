package com.trainsystemutilities.client;

import com.manta.api.data.Mirror;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.electrification.ClientPantographContactState;
import com.trainsystemutilities.client.electrification.ClientTrainElectrificationCache;
import com.trainsystemutilities.contraption.MonitorMovementBehaviour;
import com.trainsystemutilities.electrification.contraption.TrainElectrificationView;
import com.trainsystemutilities.network.ElectrificationData;
import com.trainsystemutilities.network.PantographAction;
import com.trainsystemutilities.network.TrainData;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The client end of the trains (MANTA_7_CONCEPT C4, TSU slice T6), open while the client is in a world: the
 * server-wide electrification lists ({@link ElectrificationData}) and the player's train host ({@link TrainData}), polled
 * each tick into what the payloads' handlers wrote - the electrification caches and the pantographs' deployed state,
 * the electrification screen to open, the in-train monitors' display data. The monitors and the pantographs render in
 * the world with no screen open, so these mirrors cannot live on a page.
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class TrainClient {

    private static Mirror electrification;
    private static Mirror mine;
    private static int electrificationRevision = -1;
    private static int mineRevision = -1;
    /** The event counters last seen; -1 until the first snapshot, whose events are history, not news. */
    private static int seenOpen = -1;
    private static int seenMonitors = -1;

    private TrainClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        close();
        electrification = Mirror.open(ElectrificationData.channel(), ElectrificationData.schema());
        mine = Mirror.open(TrainData.channel(event.getPlayer().getUUID()), TrainData.schema());
        electrificationRevision = -1;
        mineRevision = -1;
        seenOpen = -1;
        seenMonitors = -1;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }

    private static void close() {
        if (electrification != null) {
            electrification.close();
            electrification = null;
        }
        if (mine != null) {
            mine.close();
            mine = null;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Mirror e = electrification;
        if (e != null) {
            e.poll();
            int revision = e.revision();
            if (revision >= 0 && revision != electrificationRevision) {
                electrificationRevision = revision;
                List<TrainElectrificationView> views = ElectrificationData.viewsOf((List<?>) e.get("elec-trains"),
                        (List<?>) e.get("elec-cars"), (List<?>) e.get("elec-pantos"));
                ClientTrainElectrificationCache.replaceAll(views);
                ClientPantographContactState.replaceAll(views);
                // Phase 24: 各 contraption の BE.deployed を同期の値で直接上書き
                //   → 視覚アニメ駆動が次フレームから新しい状態を反映
                applyDeployedToClientContraptionBEs(views);
            }
        }
        Mirror m = mine;
        if (m != null) {
            m.poll();
            int revision = m.revision();
            if (revision >= 0 && revision != mineRevision) {
                mineRevision = revision;
                int open = (Integer) m.get("open-elec-seq");
                if (seenOpen >= 0 && open != seenOpen) {
                    UUID train = parse((String) m.get("open-elec-train"));
                    if (train != null) {
                        com.trainsystemutilities.client.gui.ElectrificationDetailScreen.open(train);
                    }
                }
                seenOpen = open;
                int monitors = (Integer) m.get("mon-seq");
                if (seenMonitors >= 0 && monitors != seenMonitors) {
                    for (TrainData.MonitorRow row : TrainData.monitorsOf((List<?>) m.get("mon-rows"),
                            (List<?>) m.get("mon-stops"))) {
                        MonitorMovementBehaviour.applyServerDisplayInfo(row);
                    }
                }
                seenMonitors = monitors;
            }
        }
    }

    /** 同期の値に基づき、各 train の contraption BE (= サブ contraption ブロック) の
     *  PantographBlockEntity.deployed を直接書き換える。idlePredicate の差分判定で
     *  次フレームから新しいアニメ (fold/unfold) に遷移する。 */
    private static void applyDeployedToClientContraptionBEs(List<TrainElectrificationView> views) {
        if (views == null || views.isEmpty()) return;
        for (TrainElectrificationView v : views) {
            // The client's own copy: Create.RAILWAYS is the server's, empty on a dedicated server, and its carriages'
            // entities are the server's, whose contraptions are not the ones drawn.
            Train train = CreateClient.RAILWAYS.trains.get(v.trainId);
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
                java.util.Map<BlockPos, com.trainsystemutilities.electrification.blockentity.PantographBlockEntity> rendered =
                        new java.util.HashMap<>();
                for (var be : clientContraption.renderedBlockEntityView) {
                    if (be instanceof com.trainsystemutilities.electrification.blockentity.PantographBlockEntity pbe) {
                        rendered.put(pbe.getBlockPos(), pbe);
                    }
                }
                for (TrainElectrificationView.PantoEntry pe : carView.pantographs) {
                    if (pe == null || pe.pos == null) continue;
                    try {
                        var pbe = rendered.get(pe.pos);
                        if (pbe == null) {
                            continue;
                        }
                        if (pbe.isDeployed() != pe.deployed) {
                            pbe.clientSetDeployed(pe.deployed);
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

    private static Contraption contraptionOf(Carriage carriage) {
        if (carriage == null) return null;
        if (carriage.anyAvailableEntity() instanceof CarriageContraptionEntity cce) {
            return cce.getContraption();
        }
        return null;
    }

    /** A pantograph action (PantographTogglePayload until C4); the carriage index is clamped to the declaration. */
    public static void pantograph(UUID train, int action, int carriageIndex, @Nullable BlockPos pos) {
        Mirror m = mine;
        if (m == null || train == null) {
            return;
        }
        BlockPos p = pos == null ? BlockPos.ZERO : pos;
        m.send(TrainData.PANTOGRAPH, train.toString(),
                Math.max(PantographAction.ACTION_DEPLOY_ALL, Math.min(PantographAction.ACTION_TOGGLE_ONE, action)),
                Math.max(0, Math.min(TrainData.MAX_CAR, carriageIndex)), p.getX(), p.getY(), p.getZ());
    }

    @Nullable
    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
