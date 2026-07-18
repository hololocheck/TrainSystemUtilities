package com.trainsystemutilities.station.routing;

import com.trainsystemutilities.station.StationGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects practical walking targets inside a station group.
 *
 * <p>The Create station block itself is often not a walkable space, so the
 * navigation target is a reachable standing position inside the station range,
 * biased toward the requested platform's station block.
 */
public final class StationWalkTargetSelector {

    private static final int PLATFORM_RADIUS_XZ = 10;
    /** 駅ブロック中心の **広めの** scan 半径。指定番線専用の候補集合を作るため。
     *  これより外側の候補は別番線扱いとなる可能性があるので除外する。 */
    private static final int STATION_SCAN_RADIUS_XZ = 18;
    private static final int PLATFORM_RADIUS_Y_BELOW = 4;
    private static final int PLATFORM_RADIUS_Y_ABOVE = 6;
    private static final int RANGE_SCAN_VOLUME_LIMIT = 1_000_000;
    /** 1 リクエストで試行する候補の最大数 (1 候補 ≈ 4.5 秒 worst case)。 */
    private static final int MAX_CANDIDATES = 3;
    /** 全候補試行の deadline (ms)。NavField 初回構築 (~30 秒) + voxel fallback 余裕。 */
    private static final long TOTAL_DEADLINE_MS = 42_000L;

    private StationWalkTargetSelector() {}

    public record PathResult(boolean reachable,
                             int platform,
                             BlockPos goal,
                             WalkingPathfinder.Result path) {
        public static PathResult unreachable() {
            return new PathResult(false, 0, null, WalkingPathfinder.Result.unreachable());
        }
    }

    public static PathResult findPathToGroup(ServerLevel serverLevel, BlockPos start, StationGroup group, int platform) {
        var log = com.trainsystemutilities.TrainSystemUtilities.LOGGER;
        if (serverLevel == null || start == null || group == null) {
            log.warn("[NavSelector] null input level={} start={} group={}", serverLevel, start, group);
            return PathResult.unreachable();
        }
        // NavPathDispatcher の worker thread で走る。 ServerLevel を直接読むと 1 ブロックにつき
        // 1 回 server main thread への往復が発生するため、 chunk キャッシュ付きビュー経由で読む。
        BlockGetter level = new OffThreadBlockView(serverLevel);
        log.info("[NavSelector] start={} group={} '{}' platform={} stationBlocks={} bounds=[{},{},{}]→[{},{},{}]",
                start, group.id(), group.name(), platform,
                group.stationBlockPositions() == null ? -1 : group.stationBlockPositions().size(),
                group.minPos().getX(), group.minPos().getY(), group.minPos().getZ(),
                group.maxPos().getX(), group.maxPos().getY(), group.maxPos().getZ());

        BlockPos pathStart = nearestStandable(level, start, 2, 2);
        if (pathStart == null) {
            log.warn("[NavSelector] no standable near start={} (within radius 2,2)", start);
            // start 周辺をデバッグ用に詳細出力
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos p = start.offset(dx, dy, dz);
                        log.warn("[NavSelector]   sample {}: standable={} below={} foot={} head={}",
                                p, WalkingPathfinder.standableAt(level, p),
                                level.getBlockState(p.below()).getBlock(),
                                level.getBlockState(p).getBlock(),
                                level.getBlockState(p.above()).getBlock());
                    }
                }
            }
            return PathResult.unreachable();
        }
        if (!pathStart.equals(start)) {
            log.info("[NavSelector] adjusted start {}→{} (snap to standable)", start, pathStart);
        }

        // === E2E NavField 経路 (= 逆 Dijkstra による完全な親ポインタフィールド) ===
        // platform 指定がある場合のみ field を使用。0 の場合は旧 voxel candidates に fallback。
        if (platform > 0 && platform <= group.stationBlockPositions().size()) {
            var field = com.trainsystemutilities.station.routing.navfield.NavFieldCache
                    .get(serverLevel, group.id(), platform);
            if (field == null) {
                log.info("[NavSelector] NavField cache MISS for group={} platform={} — building",
                        group.id(), platform);
                try {
                    var br = com.trainsystemutilities.station.routing.navfield
                            .NavFieldBuilder.build(serverLevel, group, platform);
                    if (br.field() != null) {
                        com.trainsystemutilities.station.routing.navfield.NavFieldCache
                                .put(serverLevel, br.field());
                        field = br.field();
                        log.info("[NavSelector] field built cells={} elapsed={}ms truncated={}",
                                field.cellCount(), br.elapsedMs(), br.truncated());
                    }
                } catch (Throwable t) {
                    log.error("[NavSelector] NavField build FAILED: {}", t.toString(), t);
                }
            }
            if (field != null && field.cellCount() > 0) {
                // Phase D-4: Multi-target A* — field 内のいずれかのセルに到達したら早期終了
                WalkingPathfinder.Result connect = WalkingPathfinder.findPathToField(level, pathStart, field);
                if (connect.reachable() && !connect.path().isEmpty()) {
                    BlockPos hitCell = connect.path().get(connect.path().size() - 1);
                    java.util.List<BlockPos> fieldPath = field.tracePath(hitCell);
                    if (!fieldPath.isEmpty()) {
                        java.util.List<BlockPos> fullPath = new java.util.ArrayList<>(connect.path());
                        int skip = (!fullPath.isEmpty() && !fieldPath.isEmpty()
                                && fullPath.get(fullPath.size() - 1).equals(fieldPath.get(0))) ? 1 : 0;
                        for (int i = skip; i < fieldPath.size(); i++) fullPath.add(fieldPath.get(i));
                        BlockPos goal = field.goal();
                        int cost = fullPath.size();
                        int n2 = fullPath.size();
                        log.info("[NavSelector] FIELD SUCCESS multi-target hit={} pathBlocks={}",
                                hitCell, n2);
                        if (n2 >= 5) {
                            log.info("[NavSelector] PATH waypoints (sample): start={} mid_q={} mid_h={} mid_3q={} end={}",
                                    fullPath.get(0), fullPath.get(n2 / 4), fullPath.get(n2 / 2),
                                    fullPath.get(n2 * 3 / 4), fullPath.get(n2 - 1));
                        }
                        WalkingPathfinder.Result wpr = new WalkingPathfinder.Result(
                                true, cost, (int) (cost * 4.6), fullPath);
                        return new PathResult(true, platform, goal, wpr);
                    }
                }
                log.info("[NavSelector] multi-target connect failed — fallback");
            }
            log.info("[NavSelector] NavField unavailable — fallback to voxel candidates");
        }

        List<Candidate> candidates = candidates(level, serverLevel, pathStart, group, platform);
        log.info("[NavSelector] {} candidate goal positions inside group", candidates.size());
        if (candidates.isEmpty()) {
            log.warn("[NavSelector] no candidates! group has {} station blocks, range volume might be empty",
                    group.stationBlockPositions() == null ? 0 : group.stationBlockPositions().size());
            return PathResult.unreachable();
        }

        // 候補は既に「プレイヤー寄り + プラットフォーム寄り」スコア昇順なので、
        // 最初に到達可能なものを即採用 (early-exit)。+全体 deadline で上限保証。
        WalkingPathfinder.Result bestPath = null;
        Candidate bestCandidate = null;
        int tried = 0;
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TOTAL_DEADLINE_MS * 1_000_000L;
        for (Candidate candidate : candidates) {
            tried++;
            // 全体 deadline チェック
            if (System.nanoTime() > deadlineNanos) {
                log.info("[NavSelector] total deadline {}ms exceeded after {} candidates - abort",
                        TOTAL_DEADLINE_MS, tried);
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                log.info("[NavSelector] interrupted after {} candidates - abort", tried);
                break;
            }
            long candStart = System.nanoTime();
            WalkingPathfinder.Result path = WalkingPathfinder.findPath(level, pathStart, candidate.pos());
            long candMs = (System.nanoTime() - candStart) / 1_000_000L;
            if (path.reachable()) {
                log.info("[NavSelector]   try[{}/{}]={} REACHABLE platform={} cost={} elapsed={}ms",
                        tried, candidates.size(), candidate.pos(),
                        candidate.isPlatform(), path.cost(), candMs);
                bestPath = path;
                bestCandidate = candidate;
                break;
            } else {
                // 最初の 5 候補だけ詳細ログ (ノイズ削減)
                if (tried <= 5) {
                    int dx = candidate.pos().getX() - pathStart.getX();
                    int dz = candidate.pos().getZ() - pathStart.getZ();
                    log.info("[NavSelector]   try[{}/{}]={} UNREACHABLE platform={} dist=({},{}) elapsed={}ms",
                            tried, candidates.size(), candidate.pos(), candidate.isPlatform(),
                            dx, dz, candMs);
                }
            }
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("[NavSelector] tried={} (of {}) best={} elapsed={}ms",
                tried, candidates.size(),
                bestCandidate == null ? "none" : bestCandidate.pos(), elapsedMs);
        if (bestPath == null || bestCandidate == null) {
            // 最初の 5 候補までを詳細表示
            int n = Math.min(5, candidates.size());
            for (int i = 0; i < n; i++) {
                Candidate c = candidates.get(i);
                log.warn("[NavSelector]   unreachable candidate[{}]={} platform={}", i, c.pos(), c.platform());
            }
            return PathResult.unreachable();
        }
        return new PathResult(true, bestCandidate.platform(), bestCandidate.pos(), bestPath);
    }

    /** serverLevel は {@link PlatformCache} の gameTime 取得専用 — ブロック読みは level (= view) 経由。 */
    private static List<Candidate> candidates(BlockGetter level, ServerLevel serverLevel,
                                              BlockPos start, StationGroup group, int platform) {
        var log = com.trainsystemutilities.TrainSystemUtilities.LOGGER;
        // キャッシュヒットチェック
        var cached = PlatformCache.get(group, platform, serverLevel);
        if (cached != null) {
            log.info("[NavSelector] cache HIT for group={} platform={} → {} cached candidates",
                    group.id(), platform, cached.candidates().size());
            List<Candidate> cands = new ArrayList<>(cached.candidates().size());
            for (BlockPos p : cached.candidates()) cands.add(new Candidate(p, platform, true));
            BlockPos preferred = cands.isEmpty() ? groupCenter(group) : cands.get(0).pos();
            cands.sort(Comparator.comparingDouble(c -> score(c.pos(), preferred, start)));
            return cands;
        }
        log.info("[NavSelector] cache MISS, scanning bounds for group={} platform={}",
                group.id(), platform);

        Map<Long, Candidate> out = new LinkedHashMap<>();
        List<StationRef> stations = stationRefs(group, start, platform);
        log.info("[NavSelector] {} station refs (platform={})", stations.size(), platform);

        long scanStart = System.nanoTime();
        // 全 stationBlock の周辺をスキャン (PLATFORM_RADIUS_XZ では狭すぎるので、駅範囲全体を使う)
        for (StationRef ref : stations) {
            collectAroundStationWide(level, group, ref, out);
        }
        if (out.isEmpty()) {
            log.info("[NavSelector] collectAroundStationWide returned 0, falling back to range scan");
            collectRangeFallback(level, group, start, out);
        }
        long scanMs = (System.nanoTime() - scanStart) / 1_000_000L;
        log.info("[NavSelector] scan complete in {}ms, {} unique candidates", scanMs, out.size());

        BlockPos preferred = stations.isEmpty() ? groupCenter(group) : stations.get(0).pos();
        List<Candidate> sorted = new ArrayList<>(out.values());
        // collectAroundStationWide で半径制限済 = 全候補が「指定番線エリア」内にある前提。
        // 純プレイヤー距離 (3D) でソート → 同 XZ なら最も近い Y (= player Y) が優先される。
        // 過去に Y ペナルティを入れたが、y=-58 の goal が y=-60 から到達不可なケースで詰まったため撤廃。
        sorted.sort(Comparator
                .comparing((Candidate c) -> !c.isPlatform())
                .thenComparingDouble(c -> c.pos().distSqr(start))
                .thenComparingDouble(c -> c.pos().distSqr(preferred)));
        // Y 別の standable 統計をログ
        java.util.Map<Integer, Integer> yDist = new java.util.TreeMap<>();
        for (Candidate c : sorted) yDist.merge(c.pos().getY(), 1, Integer::sum);
        log.info("[NavSelector] Y distribution of {} candidates: {}", sorted.size(), yDist);

        // 統計ログ
        int platformCount = 0, nonPlatformCount = 0;
        for (Candidate c : sorted) { if (c.isPlatform()) platformCount++; else nonPlatformCount++; }
        log.info("[NavSelector] candidates: {} platform + {} non-platform, MAX_CANDIDATES={}",
                platformCount, nonPlatformCount, MAX_CANDIDATES);

        if (sorted.size() > MAX_CANDIDATES) {
            sorted = sorted.subList(0, MAX_CANDIDATES);
        }
        // キャッシュ保存 (ホーム判定された候補のみ)
        List<BlockPos> platformOnly = new ArrayList<>();
        for (Candidate c : sorted) {
            if (c.isPlatform()) platformOnly.add(c.pos());
        }
        if (!platformOnly.isEmpty()) {
            PlatformCache.put(group, platform, platformOnly, serverLevel);
            log.info("[NavSelector] cached {} platform positions", platformOnly.size());
        } else {
            log.warn("[NavSelector] NO platform candidates found! Using non-platform fallback");
        }
        // 上位 5 候補をログ出力 (debug)
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            Candidate c = sorted.get(i);
            int dx = c.pos().getX() - start.getX();
            int dz = c.pos().getZ() - start.getZ();
            int manhattan = Math.abs(dx) + Math.abs(dz);
            log.info("[NavSelector]   top[{}]={} platform={} dist=({},{}) manhattan={}",
                    i, c.pos(), c.isPlatform(), dx, dz, manhattan);
        }
        return List.copyOf(sorted);
    }

    /**
     * 駅ブロックを基準に、{@link #STATION_SCAN_RADIUS_XZ} ブロックの XZ 半径をスキャンする。
     * 「指定番線の周囲」に限定することで、別番線の候補が混入するのを防ぐ。
     * 半径外は (1) 別番線の領域 / (2) コンコース等のため、当該番線の goal としては不適切。
     */
    private static void collectAroundStationWide(BlockGetter level, StationGroup group, StationRef station,
                                                  Map<Long, Candidate> out) {
        var log = com.trainsystemutilities.TrainSystemUtilities.LOGGER;
        BlockPos s = station.pos();
        Bounds b = bounds(group);
        int rangeX = b.maxX() - b.minX();
        int rangeZ = b.maxZ() - b.minZ();
        // 駅ブロック中心の半径制限スキャン (番線分離のため)
        int minX = Math.max(b.minX(), s.getX() - STATION_SCAN_RADIUS_XZ);
        int maxX = Math.min(b.maxX(), s.getX() + STATION_SCAN_RADIUS_XZ);
        int minZ = Math.max(b.minZ(), s.getZ() - STATION_SCAN_RADIUS_XZ);
        int maxZ = Math.min(b.maxZ(), s.getZ() + STATION_SCAN_RADIUS_XZ);
        int minY = Math.max(b.minY(), s.getY() - 2);
        int maxY = Math.min(b.maxFootY(), s.getY() + 4);

        long volume = ((long)(maxX - minX + 1)) * (maxY - minY + 1) * (maxZ - minZ + 1);
        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                "[NavSelector]   wideScan station={} rail={} bounds X[{}..{}] Y[{}..{}] Z[{}..{}] volume={}",
                s, rangeX > rangeZ ? "X-axis" : "Z-axis",
                minX, maxX, minY, maxY, minZ, maxZ, volume);
        if (volume > RANGE_SCAN_VOLUME_LIMIT) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                    "[NavSelector]   wideScan volume {} > limit {}, falling back to narrow",
                    volume, RANGE_SCAN_VOLUME_LIMIT);
            collectAroundStation(level, group, station, out);
            return;
        }

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        int scanned = 0, standable = 0, platformHits = 0;
        boolean diagLogged = false;
        BlockPos firstNearStandable = null;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cur.set(x, y, z);
                    scanned++;
                    if (!insideFootBounds(b, cur)) continue;
                    if (!WalkingPathfinder.standableAt(level, cur)) continue;
                    standable++;
                    if (isOnTrack(level, cur)) continue;
                    boolean isPlatform = isAdjacentToTrack(level, cur);
                    if (isPlatform) platformHits++;
                    // 駅ブロックの近くに見つかった最初の standable 位置のブロック種別を1回だけ詳細ダンプ
                    if (!diagLogged && Math.abs(cur.getX() - s.getX()) <= 4
                            && Math.abs(cur.getZ() - s.getZ()) <= 4
                            && Math.abs(cur.getY() - s.getY()) <= 2) {
                        diagLogged = true;
                        firstNearStandable = cur.immutable();
                        log.info("[NavSelector]   diag pos={} isAdjTrack={} (4-dir block types below)",
                                firstNearStandable, isPlatform);
                        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                            BlockPos n = firstNearStandable.relative(d);
                            String b0 = level.getBlockState(n).getBlock().getDescriptionId();
                            String b1 = level.getBlockState(n.below()).getBlock().getDescriptionId();
                            String b2 = level.getBlockState(n.below().below()).getBlock().getDescriptionId();
                            log.info("[NavSelector]     dir={} same={} below1={} below2={}", d, b0, b1, b2);
                        }
                    }
                    add(out, cur.immutable(), station.platform(), isPlatform);
                }
            }
        }
        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                "[NavSelector]   wideScan scanned={} standable={} platformHits={} (final unique={})",
                scanned, standable, platformHits, out.size());
    }

    private static List<StationRef> stationRefs(StationGroup group, BlockPos start, int platform) {
        List<BlockPos> positions = group.stationBlockPositions();
        if (positions == null || positions.isEmpty()) return List.of();
        List<StationRef> refs = new ArrayList<>();
        if (platform > 0 && platform <= positions.size()) {
            refs.add(new StationRef(positions.get(platform - 1), platform));
            return refs;
        }
        for (int i = 0; i < positions.size(); i++) {
            refs.add(new StationRef(positions.get(i), i + 1));
        }
        refs.sort(Comparator.comparingDouble(r -> r.pos().distSqr(start)));
        return refs;
    }

    /**
     * 駅範囲の Y 軸を「フロア」に分類。同じ Y 値 ±1 でグループ化。
     * 多階建てホームの上下層を区別するために使う。
     */
    private static java.util.NavigableSet<Integer> detectFloorYs(StationGroup group) {
        java.util.NavigableSet<Integer> ys = new java.util.TreeSet<>();
        if (group.stationBlockPositions() != null) {
            for (BlockPos p : group.stationBlockPositions()) ys.add(p.getY());
        }
        return ys;
    }

    private static void collectAroundStation(BlockGetter level, StationGroup group, StationRef station,
                                             Map<Long, Candidate> out) {
        BlockPos s = station.pos();
        Bounds b = bounds(group);
        int minX = Math.max(b.minX(), s.getX() - PLATFORM_RADIUS_XZ);
        int maxX = Math.min(b.maxX(), s.getX() + PLATFORM_RADIUS_XZ);
        int minZ = Math.max(b.minZ(), s.getZ() - PLATFORM_RADIUS_XZ);
        int maxZ = Math.min(b.maxZ(), s.getZ() + PLATFORM_RADIUS_XZ);
        // 多階層ホーム: 駅 Y を中心として ±2 のフロアレンジに限定
        // これで上下フロアの候補が混入せず、目的の番線のホームに正しく案内できる
        int floorRangeBelow = 2;
        int floorRangeAbove = 4;
        int minY = Math.max(b.minY(), s.getY() - floorRangeBelow);
        int maxY = Math.min(b.maxFootY(), s.getY() + floorRangeAbove);

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cur.set(x, y, z);
                    if (!insideFootBounds(b, cur)) continue;
                    if (!WalkingPathfinder.standableAt(level, cur)) continue;
                    if (isOnTrack(level, cur)) continue;
                    boolean isPlatform = isAdjacentToTrack(level, cur);
                    add(out, cur.immutable(), station.platform(), isPlatform);
                }
            }
        }
    }

    /** 足元 (= pos.below()) が Create の線路ブロックなら true。 */
    private static boolean isOnTrack(BlockGetter level, BlockPos pos) {
        try {
            return level.getBlockState(pos.below()).getBlock()
                    instanceof com.simibubi.create.content.trains.track.ITrackBlock;
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[StationWalk] on-track check failed", e); return false; }
    }

    /**
     * pos の水平 4 方向 + 1 マス下に線路ブロックがあれば「ホーム上」とみなす。
     * 線路から横へ 1 マス + 上へ 1 マスでアクセスできる関係 = 標準的なホーム配置。
     */
    private static boolean isAdjacentToTrack(BlockGetter level, BlockPos pos) {
        try {
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos n = pos.relative(d);
                // 同じ高さ
                if (level.getBlockState(n.below()).getBlock()
                        instanceof com.simibubi.create.content.trains.track.ITrackBlock) return true;
                // 1 マス下 (ホームが線路より +1 高いケース)
                if (level.getBlockState(n.below().below()).getBlock()
                        instanceof com.simibubi.create.content.trains.track.ITrackBlock) return true;
            }
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[StationWalk] adjacent-track check failed", e); }
        return false;
    }

    private static void collectRangeFallback(BlockGetter level, StationGroup group, BlockPos start,
                                             Map<Long, Candidate> out) {
        Bounds b = bounds(group);
        long volume = ((long) b.maxX() - b.minX() + 1)
                * ((long) b.maxFootY() - b.minY() + 1)
                * ((long) b.maxZ() - b.minZ() + 1);
        if (volume <= 0 || volume > RANGE_SCAN_VOLUME_LIMIT) return;

        List<Candidate> all = new ArrayList<>();
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int y = b.minY(); y <= b.maxFootY(); y++) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    cur.set(x, y, z);
                    if (!WalkingPathfinder.standableAt(level, cur)) continue;
                    if (isOnTrack(level, cur)) continue;
                    boolean isPlatform = isAdjacentToTrack(level, cur);
                    all.add(new Candidate(cur.immutable(), 0, isPlatform));
                }
            }
        }
        // フォールバック: ホーム → 距離の順で sort (collectAroundStation と同じ priority)
        all.sort(Comparator
                .comparing((Candidate c) -> !c.isPlatform())
                .thenComparingDouble(c -> c.pos().distSqr(start)));
        int n = Math.min(MAX_CANDIDATES, all.size());
        for (int i = 0; i < n; i++) add(out, all.get(i).pos(), all.get(i).platform(), all.get(i).isPlatform());
    }

    private static BlockPos nearestStandable(BlockGetter level, BlockPos origin, int radius, int verticalRadius) {
        if (WalkingPathfinder.standableAt(level, origin)) return origin;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cur.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!WalkingPathfinder.standableAt(level, cur)) continue;
                    double dist = cur.distSqr(origin);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cur.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static void add(Map<Long, Candidate> out, BlockPos pos, int platform) {
        out.putIfAbsent(pos.asLong(), new Candidate(pos, platform, false));
    }
    private static void add(Map<Long, Candidate> out, BlockPos pos, int platform, boolean isPlatform) {
        Candidate existing = out.get(pos.asLong());
        if (existing == null || (!existing.isPlatform() && isPlatform)) {
            out.put(pos.asLong(), new Candidate(pos, platform, isPlatform));
        }
    }

    private static double score(BlockPos pos, BlockPos preferred, BlockPos start) {
        return pos.distSqr(preferred) * 0.7 + pos.distSqr(start) * 0.3;
    }

    private static BlockPos groupCenter(StationGroup group) {
        Bounds b = bounds(group);
        return new BlockPos((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2);
    }

    private static Bounds bounds(StationGroup group) {
        int minX = Math.min(group.minPos().getX(), group.maxPos().getX());
        int minY = Math.min(group.minPos().getY(), group.maxPos().getY());
        int minZ = Math.min(group.minPos().getZ(), group.maxPos().getZ());
        int maxX = Math.max(group.minPos().getX(), group.maxPos().getX());
        int maxY = Math.max(group.minPos().getY(), group.maxPos().getY());
        int maxZ = Math.max(group.minPos().getZ(), group.maxPos().getZ());
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean insideFootBounds(Bounds b, BlockPos pos) {
        return pos.getX() >= b.minX() && pos.getX() <= b.maxX()
                && pos.getY() >= b.minY() && pos.getY() <= b.maxFootY()
                && pos.getZ() >= b.minZ() && pos.getZ() <= b.maxZ();
    }

    private record Candidate(BlockPos pos, int platform, boolean isPlatform) {}
    private record StationRef(BlockPos pos, int platform) {}
    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int maxFootY() {
            return maxY + 2;
        }
    }
}
