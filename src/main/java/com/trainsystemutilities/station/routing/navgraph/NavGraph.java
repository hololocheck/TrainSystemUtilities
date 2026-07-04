package com.trainsystemutilities.station.routing.navgraph;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 駅内ナビゲーション用の抽象グラフ。
 *
 * <p>Phase 2: ノード + エッジ + 区分情報を保持。
 *
 * <p>ノード種別:
 * <ul>
 *   <li>STAIR_BOTTOM / STAIR_TOP — 階段列の昇降両端</li>
 *   <li>LADDER_BOTTOM / LADDER_TOP — ラダー列の昇降両端</li>
 *   <li>DOOR — 通行可能開口</li>
 *   <li>PLATFORM — 番線のホーム位置</li>
 *   <li>JUNCTION — 任意の経路交差点</li>
 * </ul>
 *
 * <p>エッジ種別:
 * <ul>
 *   <li>STAIR_INTERNAL — 階段列内部 (bottom↔top、必ず経由)</li>
 *   <li>BRIDGE — 高架/橋上での歩行 (= 高い Y の stair_top 同士)</li>
 *   <li>PLATFORM_WALK — ホーム上の同一番線エリア内移動</li>
 *   <li>CROSS_PLATFORM — 番線間を地表で横断 (= 通常は迂回すべき直線パス)</li>
 *   <li>GROUND_CONNECTOR — 地表のニュートラル経路 (e.g. エントランス入口前)</li>
 *   <li>UNKNOWN — 分類不能</li>
 * </ul>
 */
public final class NavGraph {

    public enum NodeType { STAIR_BOTTOM, STAIR_TOP, LADDER_BOTTOM, LADDER_TOP, DOOR, PLATFORM, JUNCTION }

    public enum EdgeType { STAIR_INTERNAL, BRIDGE, PLATFORM_WALK, CROSS_PLATFORM, GROUND_CONNECTOR, UNKNOWN }

    /**
     * ノード。
     * @param entrance true = 範囲外から到達可能なエントランス階段 (= STAIR_BOTTOM のみ意味あり)
     * @param region   PLATFORM ノードのみ: 同フロア連続 standable 領域 (Set<Long> packed BlockPos)。それ以外は空。
     */
    public record Node(int id, NodeType type, BlockPos pos, int platform,
                        boolean entrance, Set<Long> region) {
        public Node {
            region = region == null ? Set.of() : Set.copyOf(region);
        }
        public static Node simple(int id, NodeType type, BlockPos pos, int platform) {
            return new Node(id, type, pos, platform, false, Set.of());
        }
    }

    /** エッジ: from→to の到達可能経路。blocks は実ブロック列。type は分類情報。 */
    public record Edge(int fromId, int toId, double cost, List<BlockPos> blocks, EdgeType type) {
        public Edge {
            type = type == null ? EdgeType.UNKNOWN : type;
        }
        public static Edge simple(int fromId, int toId, double cost, List<BlockPos> blocks) {
            return new Edge(fromId, toId, cost, blocks, EdgeType.UNKNOWN);
        }
    }

    private final UUID stationGroupId;
    private final List<Node> nodes;
    private final List<Edge> edges;

    public NavGraph(UUID stationGroupId, List<Node> nodes, List<Edge> edges) {
        this.stationGroupId = stationGroupId;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
    }

    public UUID stationGroupId() { return stationGroupId; }
    public List<Node> nodes() { return nodes; }
    public List<Edge> edges() { return edges; }
    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }

    public List<Node> nodesByType(NodeType type) {
        return nodes.stream().filter(n -> n.type() == type).toList();
    }

    public List<Node> platformNodes(int platform) {
        return nodes.stream()
                .filter(n -> n.type() == NodeType.PLATFORM && n.platform() == platform)
                .toList();
    }

    /** 全 PLATFORM ノードの region を統合した「全ホーム領域」を取得 (auto-clear 用)。 */
    public Set<Long> allPlatformRegion() {
        java.util.HashSet<Long> all = new java.util.HashSet<>();
        for (Node n : nodes) {
            if (n.type() == NodeType.PLATFORM) all.addAll(n.region());
        }
        return all;
    }
}
