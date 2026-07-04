package com.trainsystemutilities.network;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Create のトラックネットワークをスキャンし、
 * クリックした線路が属するネットワーク上の駅、信号、列車の情報を収集する。
 */
public class TrackNetworkScanner {

    public record NetworkData(
            List<BlockPos> stationPositions,
            List<BlockPos> signalPositions,
            List<UUID> trainIds,
            List<StationInfo> stations,
            List<SignalInfo> signals,
            List<TrainInfo> trains,
            List<NodeInfo> nodes,
            List<EdgeInfo> edges
    ) {}

    public record StationInfo(String name, BlockPos position) {}
    public record SignalInfo(BlockPos position, SignalState state) {}

    public record TrainInfo(
            UUID id, String name, int carriageCount, double speed,
            boolean isStoppedAtStation, String currentStationName,
            double worldX, double worldZ
    ) {}

    /** トラックノード（2Dマップ用） */
    public record NodeInfo(int id, double x, double z) {}

    /** トラックエッジ（ポリライン：中間点含む滑らかなパス） */
    public record EdgeInfo(int fromId, int toId, List<double[]> points) {}

    public enum SignalState { GREEN, YELLOW, RED, ORANGE }

    /**
     * 指定位置のトラックネットワークのみをスキャンする。
     */
    public static NetworkData scanFromPosition(Level level, BlockPos pos) {
        List<BlockPos> stationPositions = new ArrayList<>();
        List<BlockPos> signalPositions = new ArrayList<>();
        List<UUID> trainIds = new ArrayList<>();
        List<StationInfo> stations = new ArrayList<>();
        List<SignalInfo> signals = new ArrayList<>();
        List<TrainInfo> trains = new ArrayList<>();
        List<NodeInfo> nodes = new ArrayList<>();
        List<EdgeInfo> edges = new ArrayList<>();

        try {
            TrackGraph targetGraph = findGraphForPosition(level, pos);

            if (targetGraph == null) {
                return new NetworkData(stationPositions, signalPositions, trainIds,
                        stations, signals, trains, nodes, edges);
            }

            // ノード収集（2Dマップ用）
            // graph.getNodes() returns TrackNode objects with getLocation()
            Map<Object, Integer> nodeIndexMap = new HashMap<>();
            int nodeIdx = 0;
            for (var node : targetGraph.getNodes()) {
                var vec = node.getLocation();
                nodes.add(new NodeInfo(nodeIdx, vec.x, vec.z));
                nodeIndexMap.put(node, nodeIdx);
                nodeIdx++;
            }

            // エッジ収集（TrackNodeLocation → TrackNode → getConnectionsFrom）
            // エッジ収集（TrackEdgeから中間点をサンプリングして滑らかなポリライン生成）
            try {
                for (var nodeLoc : targetGraph.getNodes()) {
                    TrackNode trackNode = targetGraph.locateNode(nodeLoc);
                    if (trackNode == null) continue;
                    int fromIdx = nodeIndexMap.getOrDefault(nodeLoc, -1);
                    if (fromIdx < 0) continue;
                    var connectedEdges = targetGraph.getConnectionsFrom(trackNode);
                    if (connectedEdges != null) {
                        for (var entry : connectedEdges.entrySet()) {
                            TrackNode toTrackNode = entry.getKey();
                            var toLoc = toTrackNode.getLocation();
                            int toIdx = nodeIndexMap.getOrDefault(toLoc, -1);
                            if (toIdx >= 0 && fromIdx < toIdx) {
                                // TrackEdgeから中間点を取得
                                List<double[]> points = new ArrayList<>();
                                var trackEdge = entry.getValue();
                                try {
                                    // TrackEdge.getPosition(graph, t) でカーブ上の点を高密度サンプリング
                                    int segments = 24;
                                    for (int s = 0; s <= segments; s++) {
                                        double t = (double) s / segments;
                                        var pos3d = trackEdge.getPosition(targetGraph, t);
                                        if (pos3d != null) {
                                            points.add(new double[]{pos3d.x, pos3d.z});
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // getPositionが使えない場合はノード直線
                                }
                                if (points.size() < 2) {
                                    // フォールバック: ノード座標を直線
                                    var fromVec = nodeLoc.getLocation();
                                    var toVec = toLoc.getLocation();
                                    points.clear();
                                    points.add(new double[]{fromVec.x, fromVec.z});
                                    points.add(new double[]{toVec.x, toVec.z});
                                }
                                edges.add(new EdgeInfo(fromIdx, toIdx, points));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.debug("Edge scan skipped: {}", e.getMessage());
            }

            // 駅スキャン
            for (GlobalStation station : targetGraph.getPoints(
                    com.simibubi.create.content.trains.graph.EdgePointType.STATION)) {
                BlockPos stationPos = station.getBlockEntityPos();
                if (stationPos != null) {
                    stationPositions.add(stationPos);
                    stations.add(new StationInfo(station.name, stationPos));
                }
            }

            // 信号スキャン（実際のBlockPos + リアルタイム状態取得）
            try {
                for (SignalBoundary signal : targetGraph.getPoints(
                        com.simibubi.create.content.trains.graph.EdgePointType.SIGNAL)) {
                    for (boolean side : new boolean[]{true, false}) {
                        var sideEntities = signal.blockEntities.get(side);
                        if (sideEntities != null && !sideEntities.isEmpty()) {
                            BlockPos sigPos = sideEntities.keySet().iterator().next();
                            signalPositions.add(sigPos);
                            // Create APIから信号状態を取得
                            SignalState state = SignalState.GREEN;
                            try {
                                var createState = signal.cachedStates.get(side);
                                if (createState != null) {
                                    state = switch (createState) {
                                        case RED -> SignalState.RED;
                                        case YELLOW -> SignalState.YELLOW;
                                        default -> SignalState.GREEN;
                                    };
                                }
                            } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackScan] signal/network read failed", ignored); }
                            signals.add(new SignalInfo(sigPos, state));
                        }
                    }
                }
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.warn("Failed to scan signals: {}", e.getMessage());
            }

            // 列車スキャン（ワールド座標付き）
            int totalRailwaysTrains = com.simibubi.create.Create.RAILWAYS.trains.size();
            int[] matchedCount = new int[]{0};
            int[] mismatchedCount = new int[]{0};
            int[] nullGraphCount = new int[]{0};
            com.simibubi.create.Create.RAILWAYS.trains.forEach((id, train) -> {
                if (train.graph == null) { nullGraphCount[0]++; return; }
                if (train.graph != targetGraph) { mismatchedCount[0]++; return; }
                matchedCount[0]++;
                if (train.graph == targetGraph) {
                    trainIds.add(id);

                    String stationName = "";
                    boolean atStation = false;
                    if (train.getCurrentStation() != null) {
                        stationName = train.getCurrentStation().name;
                        atStation = true;
                    }

                    // 列車のワールド座標をボギー位置から取得（トラック上の正確な位置）
                    double wx = 0, wz = 0;
                    try {
                        if (!train.carriages.isEmpty()) {
                            var carriage = train.carriages.get(0);
                            var bogey = carriage.leadingBogey();
                            if (bogey != null) {
                                Vec3 bogeyPos = bogey.getAnchorPosition();
                                if (bogeyPos != null) {
                                    wx = bogeyPos.x;
                                    wz = bogeyPos.z;
                                }
                            }
                        }
                        // フォールバック: 駅座標
                        if (wx == 0 && wz == 0 && atStation && train.getCurrentStation() != null) {
                            BlockPos sp = train.getCurrentStation().getBlockEntityPos();
                            if (sp != null) { wx = sp.getX(); wz = sp.getZ(); }
                        }
                    } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackScan] signal/network read failed", ignored); }

                    trains.add(new TrainInfo(
                            id, train.name.getString(), train.carriages.size(),
                            train.speed, atStation, stationName, wx, wz
                    ));
                }
            });
            // Debug log only — INFO レベルの同期 I/O はサーバスレッドを詰まらせるため
            if (TrainSystemUtilities.LOGGER.isDebugEnabled()) {
                String side = level.isClientSide() ? "CLIENT" : "SERVER";
                TrainSystemUtilities.LOGGER.debug(
                        "[TSU-SCAN-{}] RAILWAYS.trains.size={} matched={} mismatched={} nullGraph={} "
                      + "→ result.trains={} stations={} pos={}",
                        side, totalRailwaysTrains, matchedCount[0], mismatchedCount[0], nullGraphCount[0],
                        trains.size(), stations.size(), pos);
            }

        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.warn("Failed to scan track network: {}", e.getMessage());
        }

        return new NetworkData(stationPositions, signalPositions, trainIds,
                stations, signals, trains, nodes, edges);
    }

    /**
     * {@code seedPos} の物理線路網 (Create {@link TrackGraph}) 上に存在する全 Create 駅の BE 位置を返す。
     * 列車スケジュールに依存せず「線路で繋がっているか」だけで判定するので、 列車未運行でも機能する
     * (= 券売機 / 管理用コンピューターの「同一ネットワーク」判定に使う)。 グラフが見つからなければ空。
     */
    public static List<BlockPos> networkStationPositions(Level level, BlockPos seedPos) {
        List<BlockPos> result = new ArrayList<>();
        try {
            TrackGraph graph = findGraphForPosition(level, seedPos);
            if (graph == null) return result;
            for (GlobalStation station : graph.getPoints(
                    com.simibubi.create.content.trains.graph.EdgePointType.STATION)) {
                BlockPos sp = station.getBlockEntityPos();
                if (sp != null) result.add(sp);
            }
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.debug("networkStationPositions failed: {}", t.getMessage());
        }
        return result;
    }

    public static TrackGraph findGraphForPosition(BlockPos pos) {
        return findGraphForPosition(null, pos);
    }

    public static TrackGraph findGraphForPosition(Level level, BlockPos pos) {
        if (com.simibubi.create.Create.RAILWAYS == null
                || com.simibubi.create.Create.RAILWAYS.trackNetworks == null) return null;

        TrackGraph closest = null;
        double closestDist = 128 * 128;

        try {
            for (TrackGraph graph : com.simibubi.create.Create.RAILWAYS.trackNetworks.values()) {
                for (var nodeLoc : graph.getNodes()) {
                    if (level != null && !Objects.equals(level.dimension(), nodeLoc.getDimension())) continue;
                    var vec = nodeLoc.getLocation();
                    BlockPos nodePos = BlockPos.containing(vec);
                    double dist = pos.distSqr(nodePos);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = graph;
                    }
                }
            }
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.debug("findGraphForPosition failed: {}", e.getMessage());
        }

        return closest;
    }

    public static Optional<Train> getTrainById(UUID trainId) {
        return Optional.ofNullable(com.simibubi.create.Create.RAILWAYS.trains.get(trainId));
    }

    public static List<String> getAllTrainNames() {
        List<String> names = new ArrayList<>();
        com.simibubi.create.Create.RAILWAYS.trains.forEach((id, train) -> names.add(train.name.getString()));
        return names;
    }

    public static Optional<Train> getTrainByName(String name) {
        return com.simibubi.create.Create.RAILWAYS.trains.values().stream()
                .filter(train -> train.name.getString().equals(name))
                .findFirst();
    }
}
