package com.trainsystemutilities.station.routing.navgraph;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.routing.WalkingPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 駅範囲を 1 度走査し、以下を検出して {@link NavGraph} を生成する:
 *
 * <ul>
 *   <li>StairBlock 群 → 26 隣接でクラスタリング → 階段列 (bottom/top ノード)</li>
 *   <li>LadderBlock 群 → 同様 → ラダー列 (bottom/top ノード)</li>
 *   <li>DoorBlock / FenceGateBlock / TrapDoorBlock → ドアノード</li>
 *   <li>StationGroup.stationBlockPositions → 番線ホームノード (1-based platform)</li>
 * </ul>
 *
 * <p>Phase 1: ノードのみ生成。エッジは Phase 2 で追加 (同フロア内の到達可能性を短距離 voxel A*)。
 */
public final class StationStructureAnalyzer {

    /** 最大走査ボリューム (DoS 防御)。これ以上の駅範囲は解析をスキップ。 */
    private static final long MAX_VOLUME = 2_000_000L;
    /** 駅 bounds 外側もスキャンする拡張量。
     *  橋・歩道橋などの「駅の付帯構造」は範囲指定に含めなくても検出するため。 */
    private static final int SCAN_EXTENSION_XZ = 16;
    private static final int SCAN_EXTENSION_Y_UP = 32;
    private static final int SCAN_EXTENSION_Y_DOWN = 4;

    private StationStructureAnalyzer() {}

    public record Result(
            NavGraph graph,
            int stairColumns,
            int ladderColumns,
            int doors,
            int platforms,
            int edges,
            int blocksScanned,
            long elapsedMs,
            boolean skipped
    ) {}

    /** 同フロアエッジ生成時の最大ペア候補距離 (BlockPos manhattan)。これより遠ければスキップ。 */
    private static final int EDGE_MAX_MANHATTAN = 200;
    /** 同フロアエッジ生成時の Y 差許容。dy=2+ のエッジは「voxel A* で偶然見つかった越境ショートカット」
     *  になりやすく、橋階段ルートをバイパスしてしまうので、dy ≤ 1 の真の同フロアのみエッジ化する。 */
    private static final int EDGE_MAX_DY = 1;
    /** 階段 / ラダー / プラットフォーム anchor の standable 探索半径。 */
    private static final int ANCHOR_SEARCH_RADIUS = 3;
    /** PLATFORM_REGION の flood-fill 上限セル数 (DoS 防御)。 */
    private static final int PLATFORM_REGION_SIZE = 400;
    /** BRIDGE エッジ判定: 両端の Y がこの値以上なら橋扱い (絶対値ではなく相対判定にしたいが
     *  簡易的には station block Y より +3 以上なら橋とみなす)。 */
    private static final int BRIDGE_Y_OFFSET_FROM_STATION = 3;

    public static Result analyze(ServerLevel level, StationGroup group) {
        long startNanos = System.nanoTime();
        var log = TrainSystemUtilities.LOGGER;
        BlockPos min = group.minPos();
        BlockPos max = group.maxPos();
        // 解析範囲は user 指定 bounds より広い「拡張範囲」: 橋・歩道橋などの付帯構造を
        // 駅 bounds に含めなくても検出できるようにするため。
        BlockPos scanMin = new BlockPos(
                min.getX() - SCAN_EXTENSION_XZ,
                min.getY() - SCAN_EXTENSION_Y_DOWN,
                min.getZ() - SCAN_EXTENSION_XZ);
        BlockPos scanMax = new BlockPos(
                max.getX() + SCAN_EXTENSION_XZ,
                max.getY() + SCAN_EXTENSION_Y_UP,
                max.getZ() + SCAN_EXTENSION_XZ);
        long volume = ((long) (scanMax.getX() - scanMin.getX() + 1))
                * (scanMax.getY() - scanMin.getY() + 1)
                * (scanMax.getZ() - scanMin.getZ() + 1);
        log.info("[Analyzer] BEGIN group={} '{}' bounds=({},{},{})..({},{},{}) scan=({},{},{})..({},{},{}) extVolume={}",
                group.id(), group.name(),
                min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ(),
                scanMin.getX(), scanMin.getY(), scanMin.getZ(),
                scanMax.getX(), scanMax.getY(), scanMax.getZ(), volume);

        if (volume <= 0 || volume > MAX_VOLUME) {
            log.warn("[Analyzer] volume {} > limit {} — skip analysis", volume, MAX_VOLUME);
            return new Result(new NavGraph(group.id(), List.of(), List.of()),
                    0, 0, 0, group.stationBlockPositions().size(),
                    0, 0, (System.nanoTime() - startNanos) / 1_000_000L, true);
        }

        // Step 1: ブロック種別収集 (拡張範囲で 1 pass scan)
        Set<Long> stairBlocks = new HashSet<>();
        Set<Long> ladderBlocks = new HashSet<>();
        Set<Long> doorBlocks = new HashSet<>();
        int trackCount = 0;
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        int blocksScanned = 0;
        for (int y = scanMin.getY(); y <= scanMax.getY(); y++) {
            for (int x = scanMin.getX(); x <= scanMax.getX(); x++) {
                for (int z = scanMin.getZ(); z <= scanMax.getZ(); z++) {
                    cur.set(x, y, z);
                    blocksScanned++;
                    BlockState s = level.getBlockState(cur);
                    Block b = s.getBlock();
                    if (b instanceof StairBlock) {
                        stairBlocks.add(cur.asLong());
                    } else if (b instanceof LadderBlock) {
                        ladderBlocks.add(cur.asLong());
                    } else if (b instanceof DoorBlock || b instanceof FenceGateBlock
                            || b instanceof TrapDoorBlock) {
                        doorBlocks.add(cur.asLong());
                    } else if (isTrackBlock(b)) {
                        trackCount++;
                    }
                }
            }
        }
        log.info("[Analyzer]   raw counts: stairs={} ladders={} doors={} tracks={}",
                stairBlocks.size(), ladderBlocks.size(), doorBlocks.size(), trackCount);

        // Step 2: クラスタリング (26-neighbor BFS)
        List<List<BlockPos>> stairColumns = clusterByAdjacency(stairBlocks);
        List<List<BlockPos>> ladderColumns = clusterByAdjacency(ladderBlocks);
        log.info("[Analyzer]   clustered: stair_columns={} ladder_columns={}",
                stairColumns.size(), ladderColumns.size());

        // Step 3: ノード生成
        List<NavGraph.Node> nodes = new ArrayList<>();
        int idCounter = 0;

        // 階段列 → bottom/top ノード (anchor 補正: 最寄り standable に snap)
        for (List<BlockPos> col : stairColumns) {
            BlockPos bottom = lowest(col);
            BlockPos top = highest(col);
            BlockPos bottomStand = nearestStandable(level, bottom, ANCHOR_SEARCH_RADIUS);
            BlockPos topStand = nearestStandable(level, top.above(), ANCHOR_SEARCH_RADIUS);
            if (bottomStand == null) {
                log.warn("[Analyzer]   stair_col bottom={} no standable within {} — using stair pos as-is",
                        bottom, ANCHOR_SEARCH_RADIUS);
                bottomStand = bottom;
            }
            if (topStand == null) {
                log.warn("[Analyzer]   stair_col top={} no standable within {} — using top.above as-is",
                        top, ANCHOR_SEARCH_RADIUS);
                topStand = top.above();
            }
            // ENTRANCE 判定: bottom から bounds 外まで歩いて到達できれば「外部入口」
            boolean isEntrance = canReachOutsideBounds(level, bottomStand, group.minPos(), group.maxPos(), 200);
            nodes.add(new NavGraph.Node(idCounter++, NavGraph.NodeType.STAIR_BOTTOM,
                    bottomStand, 0, isEntrance, java.util.Set.of()));
            nodes.add(NavGraph.Node.simple(idCounter++, NavGraph.NodeType.STAIR_TOP, topStand, 0));
            int dy = top.getY() - bottom.getY();
            log.info("[Analyzer]     stair_col blocks={} stairBottom={}→stand={} stairTop={}→stand={} riseY={} entrance={}",
                    col.size(), bottom, bottomStand, top, topStand, dy, isEntrance);
        }

        // ラダー列 (同様に anchor 補正 + ENTRANCE 判定)
        for (List<BlockPos> col : ladderColumns) {
            BlockPos bottom = lowest(col);
            BlockPos top = highest(col);
            BlockPos bottomStand = nearestStandable(level, bottom, ANCHOR_SEARCH_RADIUS);
            BlockPos topStand = nearestStandable(level, top.above(), ANCHOR_SEARCH_RADIUS);
            if (bottomStand == null) bottomStand = bottom;
            if (topStand == null) topStand = top.above();
            boolean isEntrance = canReachOutsideBounds(level, bottomStand, group.minPos(), group.maxPos(), 200);
            nodes.add(new NavGraph.Node(idCounter++, NavGraph.NodeType.LADDER_BOTTOM,
                    bottomStand, 0, isEntrance, java.util.Set.of()));
            nodes.add(NavGraph.Node.simple(idCounter++, NavGraph.NodeType.LADDER_TOP, topStand, 0));
            log.info("[Analyzer]     ladder_col blocks={} bottom={} top={} entrance={}",
                    col.size(), bottomStand, topStand, isEntrance);
        }

        // ドア (各々独立ノード) — ドアもエントランス候補
        for (Long key : doorBlocks) {
            BlockPos p = BlockPos.of(key);
            boolean isEntrance = canReachOutsideBounds(level, p, group.minPos(), group.maxPos(), 100);
            nodes.add(new NavGraph.Node(idCounter++, NavGraph.NodeType.DOOR,
                    p, 0, isEntrance, java.util.Set.of()));
        }
        if (!doorBlocks.isEmpty()) {
            log.info("[Analyzer]     doors total={}", doorBlocks.size());
        }

        // 番線ホームノード: 各 station block の上を最寄り standable に補正 + region flood-fill
        List<BlockPos> stationPositions = group.stationBlockPositions();
        for (int i = 0; i < stationPositions.size(); i++) {
            int platformNum = i + 1;
            BlockPos sp = stationPositions.get(i);
            BlockPos anchor = nearestStandable(level, sp.above(), ANCHOR_SEARCH_RADIUS);
            if (anchor == null) {
                log.warn("[Analyzer]   platform {} stationBlock={} no standable within {} — using above as-is",
                        platformNum, sp, ANCHOR_SEARCH_RADIUS);
                anchor = sp.above();
            }
            // PLATFORM_REGION: anchor から同フロア (Y±1) で連続 standable をBFS、最大 PLATFORM_REGION_SIZE
            java.util.Set<Long> region = floodFillStandable(level, anchor, PLATFORM_REGION_SIZE);
            nodes.add(new NavGraph.Node(idCounter++, NavGraph.NodeType.PLATFORM,
                    anchor, platformNum, false, region));
            log.info("[Analyzer]     platform {} stationBlock={} anchor={} region={} cells",
                    platformNum, sp, anchor, region.size());
        }

        // Step 4: エッジ生成
        // 4a) 階段内部 link (bottom → top): voxel A* で **実際の歩行経路** を取得 (= 階段を踏む)。
        //     直線補間にすると描画が空中を斜めに突き抜けるため必ず A* で実経路を取る。
        List<NavGraph.Edge> edges = new ArrayList<>();
        long edgeStartNanos = System.nanoTime();
        for (int i = 0; i < nodes.size(); i++) {
            NavGraph.Node n = nodes.get(i);
            if (n.type() != NavGraph.NodeType.STAIR_BOTTOM && n.type() != NavGraph.NodeType.LADDER_BOTTOM) continue;
            if (i + 1 >= nodes.size()) continue;
            NavGraph.Node top = nodes.get(i + 1);
            boolean valid = (n.type() == NavGraph.NodeType.STAIR_BOTTOM
                    && top.type() == NavGraph.NodeType.STAIR_TOP)
                    || (n.type() == NavGraph.NodeType.LADDER_BOTTOM
                    && top.type() == NavGraph.NodeType.LADDER_TOP);
            if (!valid) continue;
            WalkingPathfinder.Result pr = WalkingPathfinder.findPath(level, n.pos(), top.pos());
            List<BlockPos> seq;
            double cost;
            BlockPos prLast = (pr.reachable() && !pr.path().isEmpty())
                    ? pr.path().get(pr.path().size() - 1) : null;
            if (pr.reachable() && !pr.path().isEmpty() && top.pos().equals(prLast)) {
                seq = pr.path();
                cost = pr.cost();
                log.info("[Analyzer]     edge stair_internal {}↔{} A*ok cost={} blocks={}",
                        n.pos(), top.pos(), (int) cost, seq.size());
            } else {
                // フォールバック: 直線補間 (A* がゴール未到達 / partial の場合)
                int riseY = top.pos().getY() - n.pos().getY();
                cost = Math.abs(riseY) * 1.4
                        + Math.abs(top.pos().getX() - n.pos().getX())
                        + Math.abs(top.pos().getZ() - n.pos().getZ());
                seq = interpolate(n.pos(), top.pos());
                log.warn("[Analyzer]     edge stair_internal {}↔{} A*FAIL/PARTIAL — fallback interpolate cost={} blocks={}",
                        n.pos(), top.pos(), (int) cost, seq.size());
            }
            edges.add(new NavGraph.Edge(n.id(), top.id(), cost, seq, NavGraph.EdgeType.STAIR_INTERNAL));
            edges.add(new NavGraph.Edge(top.id(), n.id(), cost, reverse(seq), NavGraph.EdgeType.STAIR_INTERNAL));
        }

        // 駅ブロック平均 Y を計算 (BRIDGE 判定用)
        int stationAvgY = 0;
        if (!stationPositions.isEmpty()) {
            int sum = 0;
            for (BlockPos sp : stationPositions) sum += sp.getY();
            stationAvgY = sum / stationPositions.size();
        }
        final int bridgeY = stationAvgY + BRIDGE_Y_OFFSET_FROM_STATION;

        // 4b) 同フロアエッジ生成 + タイプ分類
        int sameFloorAttempts = 0, sameFloorSuccess = 0;
        java.util.EnumMap<NavGraph.EdgeType, Integer> edgeTypeCount =
                new java.util.EnumMap<>(NavGraph.EdgeType.class);
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                NavGraph.Node a = nodes.get(i);
                NavGraph.Node b = nodes.get(j);
                int dy = Math.abs(a.pos().getY() - b.pos().getY());
                int dxz = Math.abs(a.pos().getX() - b.pos().getX())
                        + Math.abs(a.pos().getZ() - b.pos().getZ());
                if (dy > EDGE_MAX_DY) continue;
                if (dxz > EDGE_MAX_MANHATTAN) continue;
                // 階段の bottom-top はすでに張られたので skip
                if (Math.abs(a.id() - b.id()) == 1
                        && (a.type() == NavGraph.NodeType.STAIR_BOTTOM || a.type() == NavGraph.NodeType.STAIR_TOP)
                        && (b.type() == NavGraph.NodeType.STAIR_BOTTOM || b.type() == NavGraph.NodeType.STAIR_TOP))
                    continue;
                sameFloorAttempts++;
                WalkingPathfinder.Result pr = WalkingPathfinder.findPath(level, a.pos(), b.pos());
                if (!pr.reachable() || pr.path().isEmpty()) {
                    log.info("[Analyzer]     edge SAME_FLOOR {}↔{} UNREACHABLE (dxz={} dy={})",
                            a.pos(), b.pos(), dxz, dy);
                    continue;
                }
                List<BlockPos> seq = pr.path();
                // BEST_EFFORT で「途中までのパス」が返ってきたエッジは graph に登録しない。
                // 最終 waypoint が target と一致しなければ partial = 偽エッジ。
                BlockPos last = seq.get(seq.size() - 1);
                if (!last.equals(b.pos())) {
                    log.info("[Analyzer]     edge SAME_FLOOR {}↔{} PARTIAL_REJECT last={} != target={} (dxz={})",
                            a.pos(), b.pos(), last, b.pos(), dxz);
                    continue;
                }
                sameFloorSuccess++;
                NavGraph.EdgeType etype = classifyEdge(a, b, seq, bridgeY);
                edgeTypeCount.merge(etype, 1, Integer::sum);
                edges.add(new NavGraph.Edge(a.id(), b.id(), pr.cost(), seq, etype));
                edges.add(new NavGraph.Edge(b.id(), a.id(), pr.cost(), reverse(seq), etype));
                log.info("[Analyzer]     edge {} {}↔{} cost={} blocks={}",
                        etype, a.pos(), b.pos(), pr.cost(), seq.size());
            }
        }
        log.info("[Analyzer]   edge type breakdown: {}", edgeTypeCount);
        long edgeMs = (System.nanoTime() - edgeStartNanos) / 1_000_000L;
        log.info("[Analyzer]   edges: stair_internal={} same_floor_attempts={} success={} (took {}ms)",
                stairColumns.size() + ladderColumns.size(),
                sameFloorAttempts, sameFloorSuccess, edgeMs);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        NavGraph graph = new NavGraph(group.id(), nodes, edges);
        Result r = new Result(graph,
                stairColumns.size(), ladderColumns.size(),
                doorBlocks.size(), stationPositions.size(),
                edges.size(), blocksScanned, elapsedMs, false);
        log.info("[Analyzer] DONE elapsedMs={} scanned={} total_nodes={} edges={} (stairs={} ladders={} doors={} platforms={})",
                elapsedMs, blocksScanned, nodes.size(), edges.size(),
                stairColumns.size(), ladderColumns.size(), doorBlocks.size(), stationPositions.size());
        return r;
    }

    /** 2 点間を「軸ごとに補間」する単純な BlockPos 列。描画用。 */
    private static List<BlockPos> interpolate(BlockPos a, BlockPos b) {
        List<BlockPos> out = new ArrayList<>();
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps == 0) { out.add(a); return out; }
        for (int i = 0; i <= steps; i++) {
            int x = a.getX() + dx * i / steps;
            int y = a.getY() + dy * i / steps;
            int z = a.getZ() + dz * i / steps;
            out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    private static List<BlockPos> reverse(List<BlockPos> in) {
        List<BlockPos> out = new ArrayList<>(in.size());
        for (int i = in.size() - 1; i >= 0; i--) out.add(in.get(i));
        return out;
    }

    /** 26 方向隣接でクラスタリング (連結成分 BFS)。 */
    private static List<List<BlockPos>> clusterByAdjacency(Set<Long> blocks) {
        List<List<BlockPos>> clusters = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (Long key : blocks) {
            if (visited.contains(key)) continue;
            List<BlockPos> cluster = new ArrayList<>();
            Deque<Long> q = new ArrayDeque<>();
            q.push(key);
            visited.add(key);
            while (!q.isEmpty()) {
                long curKey = q.poll();
                BlockPos p = BlockPos.of(curKey);
                cluster.add(p);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            long nb = new BlockPos(p.getX() + dx, p.getY() + dy, p.getZ() + dz).asLong();
                            if (blocks.contains(nb) && !visited.contains(nb)) {
                                visited.add(nb);
                                q.push(nb);
                            }
                        }
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    /**
     * origin から (Y±1) 連続 standable を BFS で flood-fill。最大 maxCells で打ち切り。
     * 戻り値は packed BlockPos の Set。
     */
    private static java.util.Set<Long> floodFillStandable(ServerLevel level, BlockPos origin, int maxCells) {
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        if (origin == null) return visited;
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        q.push(origin);
        visited.add(origin.asLong());
        // 8 方向 + 3 dy
        int[] hxs = {0, 1, 0, -1, 1, 1, -1, -1};
        int[] hzs = {-1, 0, 1, 0, -1, 1, 1, -1};
        while (!q.isEmpty() && visited.size() < maxCells) {
            BlockPos cur = q.poll();
            for (int d = 0; d < 8; d++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos nb = new BlockPos(cur.getX() + hxs[d], cur.getY() + dy, cur.getZ() + hzs[d]);
                    long k = nb.asLong();
                    if (visited.contains(k)) continue;
                    if (!WalkingPathfinder.standableAt(level, nb)) continue;
                    visited.add(k);
                    q.push(nb);
                    if (visited.size() >= maxCells) break;
                }
                if (visited.size() >= maxCells) break;
            }
        }
        return visited;
    }

    /**
     * origin から flood-fill で歩行可能領域を辿り、bounds の外側に到達できるか判定。
     * 到達可能 = 「外部入口階段 (= ENTRANCE)」とみなす。最大 maxCells で打ち切り (false 扱い)。
     */
    private static boolean canReachOutsideBounds(ServerLevel level, BlockPos origin,
                                                  BlockPos minP, BlockPos maxP, int maxCells) {
        if (origin == null) return false;
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        q.push(origin);
        visited.add(origin.asLong());
        int[] hxs = {0, 1, 0, -1, 1, 1, -1, -1};
        int[] hzs = {-1, 0, 1, 0, -1, 1, 1, -1};
        while (!q.isEmpty() && visited.size() < maxCells) {
            BlockPos cur = q.poll();
            // bounds 外に達したら ENTRANCE 確定
            if (cur.getX() < minP.getX() || cur.getX() > maxP.getX()
                    || cur.getZ() < minP.getZ() || cur.getZ() > maxP.getZ()) {
                return true;
            }
            for (int d = 0; d < 8; d++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos nb = new BlockPos(cur.getX() + hxs[d], cur.getY() + dy, cur.getZ() + hzs[d]);
                    long k = nb.asLong();
                    if (visited.contains(k)) continue;
                    if (!WalkingPathfinder.standableAt(level, nb)) continue;
                    visited.add(k);
                    q.push(nb);
                }
            }
        }
        return false;
    }

    /**
     * 2 ノード a-b 間のエッジを距離ベースで分類:
     * <ol>
     *   <li>両端が橋レベル (bridgeY 以上) → BRIDGE (= 高架歩行、正規ルート)</li>
     *   <li>PLATFORM が片方/両方関与 + 距離 ≤ 35 → PLATFORM_WALK (= 階段から番線への正規降下 / 隣接番線間)</li>
     *   <li>PLATFORM が片方/両方関与 + 距離 > 35 → CROSS_PLATFORM (= 番線を物理的に跨いで横断する不適切経路)</li>
     *   <li>その他 (= stair_bottom ↔ stair_bottom) → GROUND_CONNECTOR</li>
     * </ol>
     *
     * 距離ベースの理由: region flood-fill だけでは「線路で分断された同フロア内のホーム」を区別できず、
     * 隣の番線への正規降下経路 (= 短距離) と番線跨ぎ (= 長距離) の判別に距離が最も信頼できる。
     */
    private static final int PLATFORM_WALK_MAX_DIST = 35;

    private static NavGraph.EdgeType classifyEdge(NavGraph.Node a, NavGraph.Node b,
                                                    List<BlockPos> path, int bridgeY) {
        if (a.pos().getY() >= bridgeY && b.pos().getY() >= bridgeY) {
            return NavGraph.EdgeType.BRIDGE;
        }
        boolean hasPlatform = a.type() == NavGraph.NodeType.PLATFORM
                || b.type() == NavGraph.NodeType.PLATFORM;
        if (hasPlatform) {
            int dxz = Math.abs(a.pos().getX() - b.pos().getX())
                    + Math.abs(a.pos().getZ() - b.pos().getZ());
            if (dxz <= PLATFORM_WALK_MAX_DIST) return NavGraph.EdgeType.PLATFORM_WALK;
            return NavGraph.EdgeType.CROSS_PLATFORM;
        }
        return NavGraph.EdgeType.GROUND_CONNECTOR;
    }

    /** origin から radius 立方体内で最も近い standable を返す (なければ null)。 */
    private static BlockPos nearestStandable(ServerLevel level, BlockPos origin, int radius) {
        if (WalkingPathfinder.standableAt(level, origin)) return origin;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!WalkingPathfinder.standableAt(level, p)) continue;
                    double d = origin.distSqr(p);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos lowest(List<BlockPos> col) {
        BlockPos best = col.get(0);
        for (BlockPos p : col) if (p.getY() < best.getY()) best = p;
        return best;
    }

    private static BlockPos highest(List<BlockPos> col) {
        BlockPos best = col.get(0);
        for (BlockPos p : col) if (p.getY() > best.getY()) best = p;
        return best;
    }

    private static boolean isTrackBlock(Block b) {
        try {
            return b instanceof com.simibubi.create.content.trains.track.ITrackBlock;
        } catch (Throwable e) {
            return false;
        }
    }
}
