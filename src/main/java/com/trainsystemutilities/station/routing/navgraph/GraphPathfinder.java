package com.trainsystemutilities.station.routing.navgraph;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.routing.WalkingPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * {@link NavGraph} 上の A* + 実 BlockPos 列への再構築を行う。
 *
 * <p>1. プレイヤー位置から最寄りグラフノードまで短距離 voxel A*
 * <p>2. グラフ A* (ノード数 ~10-50 なので μs オーダー)
 * <p>3. 各エッジの blockSequence を結合して最終 path 生成
 */
public final class GraphPathfinder {

    /** プレイヤー位置からグラフノードへの接続: 試行する K 個 (近い順)。 */
    private static final int CONNECT_TRY_COUNT = 4;
    /** connect 時の partial path 許容: voxel A* がノードからこの distSq 以内に到達できれば OK。
     *  20 ブロック (distSq=400) は player→ノード距離が完全到達に至らずとも、近傍まで歩いた
     *  あと数歩でノードに到達できる距離。これを超える隔たりは「実質到達不可」として reject。 */
    private static final int CONNECT_PARTIAL_MAX_DIST_SQ = 400;

    private GraphPathfinder() {}

    public record Result(boolean reachable, List<BlockPos> path, double cost, NavGraph.Node startNode, NavGraph.Node goalNode) {
        public static Result unreachable() {
            return new Result(false, List.of(), 0, null, null);
        }
    }

    /**
     * @param start          プレイヤーの足元 BlockPos
     * @param targetPlatform 1-based 番線番号
     */
    public static Result findPath(ServerLevel level, NavGraph graph, BlockPos start, int targetPlatform) {
        var log = TrainSystemUtilities.LOGGER;
        if (graph == null || graph.nodeCount() == 0) {
            log.info("[GraphPath] empty graph — fallback");
            return Result.unreachable();
        }

        // ゴールノード: 指定番線の PLATFORM ノード (通常 1 個)
        List<NavGraph.Node> goalNodes = graph.platformNodes(targetPlatform);
        if (goalNodes.isEmpty()) {
            log.info("[GraphPath] no platform node for platform={}", targetPlatform);
            return Result.unreachable();
        }
        log.info("[GraphPath] BEGIN start={} targetPlatform={} graph(nodes={}, edges={}) goalNodes={}",
                start, targetPlatform, graph.nodeCount(), graph.edgeCount(), goalNodes.size());

        // プレイヤー → 最寄りノードの接続 (K 個を距離順に試行)
        // ※ STAIR_BOTTOM / LADDER_BOTTOM / DOOR (= 駅へのエントランス系) を最優先。
        //   PLATFORM ノードは「ホーム上」= 既に駅構造内にいるケース、最後に試行。
        //   これにより player から voxel A* がホーム壁を直接登るのを避け、正規の入口経由になる。
        List<NavGraph.Node> sortedByDistance = new ArrayList<>(graph.nodes());
        sortedByDistance.sort((a, b) -> {
            int ca = entrancePriority(a);
            int cb = entrancePriority(b);
            if (ca != cb) return Integer.compare(ca, cb);
            return Double.compare(a.pos().distSqr(start), b.pos().distSqr(start));
        });

        WalkingPathfinder.Result connectResult = null;
        NavGraph.Node startNode = null;
        for (int i = 0; i < Math.min(CONNECT_TRY_COUNT, sortedByDistance.size()); i++) {
            NavGraph.Node candidate = sortedByDistance.get(i);
            int distSq = (int) candidate.pos().distSqr(start);
            log.info("[GraphPath]   connect try[{}/{}]={} type={} entrance={} distSq={}",
                    i + 1, CONNECT_TRY_COUNT, candidate.pos(), candidate.type(),
                    candidate.entrance(), distSq);
            WalkingPathfinder.Result pr = WalkingPathfinder.findPath(level, start, candidate.pos());
            if (pr.reachable() && !pr.path().isEmpty()) {
                // best-effort partial path 検出: ノードから 20 ブロック超ならNG (= 接続できていない)。
                // 20 ブロック以内なら「ノード手前まで到達」として graph 開始点に採用 (path 末端に
                // ノード位置を append して見た目の連続性を保つ)。
                BlockPos last = pr.path().get(pr.path().size() - 1);
                int gapSq = (int) last.distSqr(candidate.pos());
                if (gapSq > CONNECT_PARTIAL_MAX_DIST_SQ) {
                    log.info("[GraphPath]   connect PARTIAL_REJECT via {} (last={} gapSq={} > {})",
                            candidate.pos(), last, gapSq, CONNECT_PARTIAL_MAX_DIST_SQ);
                    continue;
                }
                connectResult = pr;
                startNode = candidate;
                log.info("[GraphPath]   connect SUCCESS via {} (last={} gapSq={}) cost={} blocks={}",
                        candidate.pos(), last, gapSq, pr.cost(), pr.path().size());
                break;
            } else {
                log.info("[GraphPath]   connect FAILED via {}", candidate.pos());
            }
        }
        if (startNode == null) {
            log.info("[GraphPath] no graph node reachable from player {} — fallback", start);
            return Result.unreachable();
        }

        // グラフ A* (startNode → goalNode のいずれか)
        NavGraph.Node goalNode = goalNodes.get(0);
        Map<Integer, NavGraph.Node> nodeById = new HashMap<>();
        for (NavGraph.Node n : graph.nodes()) nodeById.put(n.id(), n);
        Map<Integer, List<NavGraph.Edge>> adj = new HashMap<>();
        for (NavGraph.Edge e : graph.edges()) {
            adj.computeIfAbsent(e.fromId(), k -> new ArrayList<>()).add(e);
        }

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        Map<Integer, NavGraph.Edge> cameEdge = new HashMap<>();
        PriorityQueue<int[]> open = new PriorityQueue<>((a, b) ->
                Double.compare(Double.longBitsToDouble(((long) a[1] << 32) | (a[2] & 0xFFFFFFFFL)),
                              Double.longBitsToDouble(((long) b[1] << 32) | (b[2] & 0xFFFFFFFFL))));
        // f-score を long bits で詰めて int[] 配列で扱うのは厄介なので、シンプル化:
        // [nodeId, fScoreBits_high, fScoreBits_low] → 単一配列で表現
        // ただし計算しやすいように改めて Map で f-score 管理
        gScore.put(startNode.id(), 0.0);
        Map<Integer, Double> fScore = new HashMap<>();
        fScore.put(startNode.id(), heuristic(startNode.pos(), goalNode.pos()));
        PriorityQueue<Integer> openNodes = new PriorityQueue<>((a, b) ->
                Double.compare(fScore.getOrDefault(a, Double.POSITIVE_INFINITY),
                              fScore.getOrDefault(b, Double.POSITIVE_INFINITY)));
        openNodes.offer(startNode.id());

        boolean found = false;
        while (!openNodes.isEmpty()) {
            int curId = openNodes.poll();
            if (curId == goalNode.id()) { found = true; break; }
            double curG = gScore.getOrDefault(curId, Double.POSITIVE_INFINITY);
            List<NavGraph.Edge> outs = adj.getOrDefault(curId, List.of());
            for (NavGraph.Edge e : outs) {
                // EdgeType による cost 乗数: 経路選択の方向性を制御
                double tentativeG = curG + e.cost() * edgeTypeMultiplier(e.type());
                if (tentativeG < gScore.getOrDefault(e.toId(), Double.POSITIVE_INFINITY)) {
                    cameFrom.put(e.toId(), curId);
                    cameEdge.put(e.toId(), e);
                    gScore.put(e.toId(), tentativeG);
                    NavGraph.Node toNode = nodeById.get(e.toId());
                    fScore.put(e.toId(), tentativeG + heuristic(toNode.pos(), goalNode.pos()));
                    openNodes.offer(e.toId());
                }
            }
        }

        if (!found) {
            log.info("[GraphPath] graph A* FAILED to reach platform node from {} (visited={})",
                    startNode.pos(), gScore.size());
            return Result.unreachable();
        }

        // ノード列を逆順に組み立て
        List<Integer> nodePath = new ArrayList<>();
        int cur = goalNode.id();
        nodePath.add(cur);
        while (cameFrom.containsKey(cur)) {
            cur = cameFrom.get(cur);
            nodePath.add(0, cur);
        }
        log.info("[GraphPath] graph A* found node path size={} cost={}",
                nodePath.size(), gScore.get(goalNode.id()));

        // 実 BlockPos 列を再構築:
        // [connect path] + (必要なら接続線) + [edge1.blocks] + ... + [final node pos]
        List<BlockPos> finalPath = new ArrayList<>(connectResult.path());
        // connect が partial の場合 (= path 末端 ≠ startNode) は startNode 位置を append して連続性を担保
        if (!finalPath.isEmpty() && !finalPath.get(finalPath.size() - 1).equals(startNode.pos())) {
            finalPath.add(startNode.pos());
        }
        for (int i = 1; i < nodePath.size(); i++) {
            int toId = nodePath.get(i);
            NavGraph.Edge e = cameEdge.get(toId);
            if (e == null) continue;
            List<BlockPos> seq = e.blocks();
            // connect の終端と edge の始端が重複する可能性あり → 重複除去
            int skipFirst = (!finalPath.isEmpty() && !seq.isEmpty()
                    && finalPath.get(finalPath.size() - 1).equals(seq.get(0))) ? 1 : 0;
            for (int k = skipFirst; k < seq.size(); k++) finalPath.add(seq.get(k));
        }

        log.info("[GraphPath] DONE path size={} from={} to={} (connect blocks={})",
                finalPath.size(), start, goalNode.pos(), connectResult.path().size());
        return new Result(true, finalPath, gScore.get(goalNode.id()), startNode, goalNode);
    }

    /** ノード種別の connect 優先度。エントランス系 (= bounds 外から到達可能) を最優先。
     *  小さいほど先に試行される。 */
    private static int entrancePriority(NavGraph.Node n) {
        // 真のエントランス階段/ラダー/ドアは最優先 (= player は外から入ってくる)
        if (n.entrance()) return 0;
        return switch (n.type()) {
            case STAIR_BOTTOM, LADDER_BOTTOM, DOOR -> 1;     // 内部の階段/ドア
            case JUNCTION, STAIR_TOP, LADDER_TOP -> 2;       // 中間 (橋上)
            case PLATFORM -> 3;                               // ホーム上 (player が既に駅内)
        };
    }

    /**
     * エッジ種別による cost 乗数。CROSS_PLATFORM (= 番線間を地表で横断) は強くペナルティ
     * → graph A* が橋経由 (BRIDGE) を選択するようになる。
     */
    private static double edgeTypeMultiplier(NavGraph.EdgeType type) {
        return switch (type) {
            case STAIR_INTERNAL -> 1.0;
            case BRIDGE -> 0.5;            // 橋は強く推奨 (= 正規ルート、コスト半額)
            case PLATFORM_WALK -> 1.0;     // ホーム内 / 階段からの降下はニュートラル
            case GROUND_CONNECTOR -> 1.2;  // 地表接続は橋より少し不利 (= 抜け道防止)
            case CROSS_PLATFORM -> 50.0;   // 番線跨ぎは禁忌
            case UNKNOWN -> 1.5;
        };
    }

    /** 3D Euclidean (= 過小評価で admissible)。 */
    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
