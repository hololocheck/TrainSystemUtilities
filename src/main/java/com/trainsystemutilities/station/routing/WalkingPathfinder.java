package com.trainsystemutilities.station.routing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 徒歩経路探索 (3D voxel A*)。プレイヤーが現在位置から目的地まで「ブロックを壊さずに」
 * 通れる最短経路を計算する。
 *
 * <p>ルール:
 * <ul>
 *   <li>水平 4 方向 (N/S/E/W) のみ移動。斜め移動は対応しない (実装簡素化)。</li>
 *   <li>1 ブロック上方 step-up 可 (= 階段または歩いて登れる程度の段差)。</li>
 *   <li>1 ブロック下方 step-down 可 (= 落ちる)。</li>
 *   <li>2 ブロック以上の段差は通過不可 (= 迂回が必要)。</li>
 *   <li>固体ブロックは突き抜けない。階段ブロックは通過可。</li>
 *   <li>足元は固体 (or 階段) でなければ通れない。頭上 2 マスは空気必要。</li>
 * </ul>
 *
 * <p>コスト: 平地 1 / 階段昇降 1.4 / 通常 step-up/step-down 1.2。
 * Heuristic: chebyshev (admissible で xy 平面に対し最適)。
 *
 * <p>探索半径上限 {@link #MAX_DISTANCE} (= 128 ブロック)。これを超えれば失敗扱い。
 */
public final class WalkingPathfinder {

    /**
     * 探索する最大マンハッタン距離 (xz 平面)。これを超えれば「徒歩到達不可能」。
     * 大規模駅 (300m+) でも案内が成立するよう 400 に拡張。
     */
    public static final int MAX_DISTANCE = 400;
    /** 探索 node 数の hard cap (DoS 防御 + 性能上限)。 */
    private static final int MAX_NODES = 200_000;
    /** 1 回の探索の deadline (ms)。connect は partial 許容 (20 ブロック以内) のため短くて OK。 */
    private static final long PATHFIND_DEADLINE_MS = 4_500L;

    private WalkingPathfinder() {}

    public record Result(boolean reachable, int cost, int approxTicks, List<BlockPos> path) {
        public static Result unreachable() {
            return new Result(false, Integer.MAX_VALUE, Integer.MAX_VALUE, List.of());
        }
    }

    /**
     * 経路探索を実行する。
     *
     * @param level   ワールド (server-side)
     * @param start   出発座標 (プレイヤーの足元 BlockPos)
     * @param goal    目的座標
     * @return        到達可否 + コスト + 推定 tick + 経路
     */
    /**
     * Phase D-4: Multi-target A* — start から「field 内のいずれかのセル」へ到達する経路を返す。
     *
     * <p>field のすべてのセルがゴール候補。最初に当たったセル (= 最寄り) で早期終了。
     * heuristic は field.goal() への距離 (= 任意 field cell への下界として有効)。
     * 単一ゴール A* と比べて 2-5x 高速、ベストエフォート partial 不要。
     *
     * @return 到達した field cell 含む path、または unreachable
     */
    public static Result findPathToField(Level level, BlockPos start,
            com.trainsystemutilities.station.routing.navfield.NavField field) {
        var log = com.trainsystemutilities.TrainSystemUtilities.LOGGER;
        if (level == null || start == null || field == null) return Result.unreachable();
        if (!standableAt(level, start)) {
            log.info("[WalkPathField] NOT_STANDABLE start={}", start);
            return Result.unreachable();
        }
        // 既に field 内なら 0-step
        if (field.contains(start)) {
            return new Result(true, 0, 0, java.util.List.of(start));
        }
        BlockPos heuristicGoal = field.goal();
        Map<Long, Float> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) ->
                Float.compare(Float.intBitsToFloat((int) a[1]), Float.intBitsToFloat((int) b[1])));
        long startKey = start.asLong();
        gScore.put(startKey, 0f);
        open.offer(new long[]{startKey, Float.floatToIntBits(heuristic(start, heuristicGoal))});
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + PATHFIND_DEADLINE_MS * 1_000_000L;
        int[] dxs = {0, 1, 0, -1, 1, 1, -1, -1};
        int[] dzs = {-1, 0, 1, 0, -1, 1, 1, -1};
        boolean[] diagonal = {false, false, false, false, true, true, true, true};
        float[] baseCost = {1.0f, 1.0f, 1.0f, 1.0f, 1.414f, 1.414f, 1.414f, 1.414f};
        int explored = 0;
        while (!open.isEmpty() && explored++ < MAX_NODES) {
            if ((explored & 63) == 0 && System.nanoTime() > deadlineNanos) {
                log.info("[WalkPathField] DEADLINE {}ms explored={} - abort",
                        PATHFIND_DEADLINE_MS, explored);
                return Result.unreachable();
            }
            long[] top = open.poll();
            long curKey = top[0];
            BlockPos cur = BlockPos.of(curKey);
            // 「field 内のセル」に到達したら終了
            if (field.contains(cur)) {
                Float g = gScore.get(curKey);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                log.info("[WalkPathField] FOUND field-hit start={} hit={} cost={} explored={} elapsedMs={}",
                        start, cur, g, explored, elapsedMs);
                return reconstruct(cameFrom, curKey, g == null ? 0f : g);
            }
            float curG = gScore.getOrDefault(curKey, Float.POSITIVE_INFINITY);
            for (int dirIdx = 0; dirIdx < 8; dirIdx++) {
                int hx = dxs[dirIdx], hz = dzs[dirIdx];
                boolean isDiag = diagonal[dirIdx];
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos nb = new BlockPos(cur.getX() + hx, cur.getY() + dy, cur.getZ() + hz);
                    if (Math.abs(nb.getX() - start.getX()) + Math.abs(nb.getZ() - start.getZ()) > MAX_DISTANCE)
                        continue;
                    if (!standableAt(level, nb)) continue;
                    if (dy != 0 && !walkableTransition(level, cur, nb)) continue;
                    if (isDiag) {
                        BlockPos sa = new BlockPos(cur.getX() + hx, cur.getY(), cur.getZ());
                        BlockPos sb = new BlockPos(cur.getX(), cur.getY(), cur.getZ() + hz);
                        if (!passableSpace(level, sa) || !passableSpace(level, sb)) continue;
                        if (dy != 0) continue;
                    }
                    float stepCost = baseCost[dirIdx];
                    if (dy != 0) stepCost *= 1.30f;
                    if (isTrackBlockBelow(level, nb)) stepCost += TRACK_PENALTY;
                    float tentative = curG + stepCost;
                    long nbKey = nb.asLong();
                    if (tentative < gScore.getOrDefault(nbKey, Float.POSITIVE_INFINITY)) {
                        cameFrom.put(nbKey, curKey);
                        gScore.put(nbKey, tentative);
                        float f = tentative + heuristic(nb, heuristicGoal);
                        open.offer(new long[]{nbKey, Float.floatToIntBits(f)});
                    }
                }
            }
        }
        log.info("[WalkPathField] no field cell reached from {} (explored={})", start, explored);
        return Result.unreachable();
    }

    public static Result findPath(Level level, BlockPos start, BlockPos goal) {
        var log = com.trainsystemutilities.TrainSystemUtilities.LOGGER;
        if (level == null || start == null || goal == null) {
            log.warn("[WalkPath] null arg level={} start={} goal={}", level, start, goal);
            return Result.unreachable();
        }
        int dx = Math.abs(goal.getX() - start.getX());
        int dz = Math.abs(goal.getZ() - start.getZ());
        if (dx + dz > MAX_DISTANCE) {
            log.info("[WalkPath] DIST_REJECT start={} goal={} manhattan={} > MAX={}",
                    start, goal, dx + dz, MAX_DISTANCE);
            return Result.unreachable();
        }
        boolean startOk = standableAt(level, start);
        boolean goalOk = standableAt(level, goal);
        if (!startOk || !goalOk) {
            log.info("[WalkPath] NOT_STANDABLE start={}({}), goal={}({}) — start floor={}/foot={}/head={} goal floor={}/foot={}/head={}",
                    start, startOk, goal, goalOk,
                    level.getBlockState(start.below()).getBlock().getDescriptionId(),
                    level.getBlockState(start).getBlock().getDescriptionId(),
                    level.getBlockState(start.above()).getBlock().getDescriptionId(),
                    level.getBlockState(goal.below()).getBlock().getDescriptionId(),
                    level.getBlockState(goal).getBlock().getDescriptionId(),
                    level.getBlockState(goal.above()).getBlock().getDescriptionId());
            return Result.unreachable();
        }

        // A*
        Map<Long, Float> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) ->
                Float.compare(Float.intBitsToFloat((int) a[1]), Float.intBitsToFloat((int) b[1])));

        long startKey = start.asLong();
        gScore.put(startKey, 0f);
        open.offer(new long[]{startKey, Float.floatToIntBits(heuristic(start, goal))});

        int explored = 0;
        long goalKey = goal.asLong();
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + PATHFIND_DEADLINE_MS * 1_000_000L;
        // 進捗追跡: 「ゴールに最も近づいた node」を保持。stuck 時のデバッグ用。
        BlockPos closestSeen = start;
        int closestDistSq = (int) start.distSqr(goal);

        log.info("[WalkPath] BEGIN start={} goal={} dx={} dz={} dy={} heuristic={}",
                start, goal, dx, dz, Math.abs(goal.getY() - start.getY()),
                heuristic(start, goal));

        // 8 方向ベクトル: (dx, dz) — N/E/S/W + NE/SE/SW/NW
        int[] dxs = {0, 1, 0, -1, 1, 1, -1, -1};
        int[] dzs = {-1, 0, 1, 0, -1, 1, 1, -1};
        boolean[] diagonal = {false, false, false, false, true, true, true, true};
        // 対角は 1.414、軸は 1.0
        float[] baseCost = {1.0f, 1.0f, 1.0f, 1.0f, 1.414f, 1.414f, 1.414f, 1.414f};

        while (!open.isEmpty() && explored++ < MAX_NODES) {
            // Deadline + 割り込み check (64 ノード毎、オーバーヘッド最小化)
            if ((explored & 63) == 0) {
                if (System.nanoTime() > deadlineNanos) {
                    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    // ベストエフォート: ゴール直接到達できなくても、ゴール近傍 (8 ブロック以内) に
                    // 着いていればそこまでのパスを返して "ほぼ目的地" として案内する。
                    if (closestDistSq <= 400 && cameFrom.containsKey(closestSeen.asLong())) {
                        Float g = gScore.get(closestSeen.asLong());
                        log.info("[WalkPath] BEST_EFFORT deadline={}ms explored={} closest={} distSq={} cost={} - returning partial path",
                                PATHFIND_DEADLINE_MS, explored, closestSeen, closestDistSq, g);
                        return reconstruct(cameFrom, closestSeen.asLong(), g == null ? 0f : g);
                    }
                    log.info("[WalkPath] DEADLINE {}ms explored={} closest={} closestDistSq={} (goal={}) - abort",
                            PATHFIND_DEADLINE_MS, explored, closestSeen, closestDistSq, goal);
                    return Result.unreachable();
                }
                if (Thread.currentThread().isInterrupted()) {
                    log.info("[WalkPath] interrupted explored={} - abort", explored);
                    return Result.unreachable();
                }
            }
            // 200 ノードごとに進捗ログ (frontier の動きを可視化)
            if (explored % 200 == 0) {
                log.info("[WalkPath]   progress explored={} openSize={} closest={} closestDistSq={}",
                        explored, open.size(), closestSeen, closestDistSq);
            }
            long[] top = open.poll();
            long curKey = top[0];
            BlockPos cur = BlockPos.of(curKey);
            // closest tracking
            int curDistSq = (int) cur.distSqr(goal);
            if (curDistSq < closestDistSq) {
                closestDistSq = curDistSq;
                closestSeen = cur;
            }
            if (curKey == goalKey) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                log.info("[WalkPath] FOUND start={} goal={} cost={} explored={} elapsedMs={}",
                        start, goal, gScore.get(curKey), explored, elapsedMs);
                return reconstruct(cameFrom, curKey, gScore.get(curKey));
            }

            float curG = gScore.getOrDefault(curKey, Float.POSITIVE_INFINITY);
            // ラダー: 現在位置がラダーなら垂直 (+1, -1) 移動を許可
            if (isLadder(level.getBlockState(cur)) || isLadder(level.getBlockState(cur.above()))) {
                for (int dy = -1; dy <= 1; dy += 2) {
                    BlockPos nb = cur.above(dy);
                    if (!isLadder(level.getBlockState(nb)) && !isLadder(level.getBlockState(nb.above()))
                            && !standableAt(level, nb)) continue;
                    float lcost = 1.5f; // ラダー垂直移動は walk より遅い
                    float tentative = curG + lcost;
                    long nbKey = nb.asLong();
                    if (tentative < gScore.getOrDefault(nbKey, Float.POSITIVE_INFINITY)) {
                        cameFrom.put(nbKey, curKey);
                        gScore.put(nbKey, tentative);
                        float f = tentative + heuristic(nb, goal);
                        open.offer(new long[]{nbKey, Float.floatToIntBits(f)});
                    }
                }
            }

            for (int dirIdx = 0; dirIdx < 8; dirIdx++) {
                int hx = dxs[dirIdx], hz = dzs[dirIdx];
                boolean isDiag = diagonal[dirIdx];
                // 各方向で 3 つの y オフセット (同高 / +1 / -1) を試す
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos nb = new BlockPos(cur.getX() + hx, cur.getY() + dy, cur.getZ() + hz);
                    if (Math.abs(nb.getX() - start.getX()) + Math.abs(nb.getZ() - start.getZ()) > MAX_DISTANCE)
                        continue;
                    if (!standableAt(level, nb)) continue;
                    if (dy != 0 && !walkableTransition(level, cur, nb)) continue;
                    // 対角の場合: 隣接 2 つの軸セルが両方 passable でないとコーナークリップ
                    if (isDiag) {
                        BlockPos sideA = new BlockPos(cur.getX() + hx, cur.getY(), cur.getZ());
                        BlockPos sideB = new BlockPos(cur.getX(), cur.getY(), cur.getZ() + hz);
                        if (!passableSpace(level, sideA) || !passableSpace(level, sideB)) continue;
                        // 対角の段差移動は無効化 (両軸の高低差を曖昧化するため)
                        if (dy != 0) continue;
                    }

                    // === 連続 step-up 検査: 装飾段差を踏み台にした 2 段乗り越えを禁止 ===
                    // 階段列の一部でない場所で step-up を 2 連発しようとすると reject
                    if (dy > 0 && !isPartOfStaircase(level, nb) && !isPartOfStaircase(level, cur)) {
                        Long parentKey = cameFrom.get(curKey);
                        if (parentKey != null) {
                            BlockPos parentPos = BlockPos.of(parentKey);
                            int prevDy = cur.getY() - parentPos.getY();
                            if (prevDy > 0) continue; // 2 連続の非階段 step-up を拒否
                        }
                    }

                    // === コスト計算 ===
                    float stepCost = baseCost[dirIdx];
                    if (dy != 0) {
                        // 段差: 「正規階段 (= 隣接 ±Y に他の階段あり)」なら 1.05、装飾段差・素の jump は 8.0
                        // 装飾用の単発 stair / slab を踏み台にした 2 段壁突破を強く嫌うため。
                        boolean realStair = isPartOfStaircase(level, nb) || isPartOfStaircase(level, cur);
                        stepCost *= realStair ? 1.05f : 8.0f;
                    }
                    // 線路ブロック penalty (足元が ITrackBlock = 線路は強く避ける)
                    if (isTrackBlockBelow(level, nb)) {
                        stepCost += TRACK_PENALTY;
                    }
                    // 正規階段歩行は flat でも微減 (= 階段列を選好)
                    if (isPartOfStaircase(level, nb)) stepCost *= 0.95f;
                    // 水中泳ぎ penalty (歩行より遅い)
                    if (isWaterAt(level, nb)) stepCost *= WATER_PENALTY;

                    float tentative = curG + stepCost;
                    long nbKey = nb.asLong();
                    if (tentative < gScore.getOrDefault(nbKey, Float.POSITIVE_INFINITY)) {
                        cameFrom.put(nbKey, curKey);
                        gScore.put(nbKey, tentative);
                        float f = tentative + heuristic(nb, goal);
                        open.offer(new long[]{nbKey, Float.floatToIntBits(f)});
                    }
                }
            }
        }
        // ベストエフォート: 探索 exhausted でも近傍 (8 ブロック以内) に着いていれば返す
        if (closestDistSq <= 400 && cameFrom.containsKey(closestSeen.asLong())) {
            Float g = gScore.get(closestSeen.asLong());
            log.info("[WalkPath] BEST_EFFORT exhausted explored={} closest={} distSq={} - returning partial path",
                    explored, closestSeen, closestDistSq);
            return reconstruct(cameFrom, closestSeen.asLong(), g == null ? 0f : g);
        }
        log.info("[WalkPath] exhausted start={} goal={} dist=({},{}) explored={} (max={}) — unreachable",
                start, goal, dx, dz, explored, MAX_NODES);
        return Result.unreachable();
    }

    /** 線路ブロック (= Create の ITrackBlock) を強く避けるための penalty。 */
    private static final float TRACK_PENALTY = 30f;
    /** 水中歩行 (泳ぎ) のコスト乗数。歩行より遅い。 */
    private static final float WATER_PENALTY = 2.5f;

    /** Octile heuristic — 8 方向対応で admissible (対角 √2)。 */
    private static float heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        int max = Math.max(dx, dz);
        int min = Math.min(dx, dz);
        // octile = max + (√2 - 1) * min
        return (float) (max + 0.414f * min + dy * 0.2f);
    }

    /** 足元が Create の線路ブロックか? (経路 penalty 用)。 */
    private static boolean isTrackBlockBelow(Level level, BlockPos pos) {
        try {
            return level.getBlockState(pos.below()).getBlock()
                    instanceof com.simibubi.create.content.trains.track.ITrackBlock;
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[WalkPath] track-below check failed", e); return false; }
    }

    /**
     * pos が「階段列の一部」か判定: pos 自身が階段で、かつ隣接位置 (XZ + ±Y) に他の階段がある。
     *
     * <p>装飾用に単発で置かれた階段ブロック (= 駅エッジの装飾、ホーム端等) を「踏み台にした
     * 2 段壁の突破」を禁止するための判定。本物の階段列だけが low-cost step-up の対象になる。
     */
    private static boolean isPartOfStaircase(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!isStairBlock(s)) {
            // 立ち位置自体が階段でなくても、足元が階段列の一部ならOK (= 階段の上に立っている)
            BlockState below = level.getBlockState(pos.below());
            if (!isStairBlock(below)) return false;
            return hasAdjacentStair(level, pos.below());
        }
        return hasAdjacentStair(level, pos);
    }

    private static boolean isStairBlock(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
    }

    /** stairPos の 4 水平方向で、上下 ±1 Y に別の階段ブロックがあるか? */
    private static boolean hasAdjacentStair(Level level, BlockPos stairPos) {
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos n = stairPos.relative(d);
            if (isStairBlock(level.getBlockState(n.above()))) return true;
            if (isStairBlock(level.getBlockState(n.below()))) return true;
            // 同じ Y で連続して階段が並ぶケース (横長の階段) も許容
            if (isStairBlock(level.getBlockState(n))) return true;
        }
        return false;
    }

    /** 「ブロックそのものが通れる空間か」(立てるかは無関係)。対角コーナーチェック用。 */
    private static boolean passableSpace(Level level, BlockPos pos) {
        return passable(level.getBlockState(pos)) && passable(level.getBlockState(pos.above()));
    }

    /**
     * pos に立てるか: 足元のブロックが固体 (or 階段 / 圧力プレート / カーペット / ラダー) で、
     * 足の高さ (pos) と頭の高さ (pos+1) がプレイヤーが通れる空間 であること。
     *
     * <p>水中も「立てる」扱い (= 泳ぎ可能)、ただしコストはペナルティ。
     */
    public static boolean standableAt(Level level, BlockPos pos) {
        BlockState foot = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        // ラダー: 足元が空気でも、その柱 (pos) が ladder なら立てる
        if (isLadder(foot) || isLadder(head)) {
            return passableHead(head);
        }
        // 水中: foot が水なら泳ぎ可能 (head は air or water)
        if (isWaterAt(level, pos)) {
            return passable(head) || isWaterAt(level, pos.above());
        }
        BlockState floor = level.getBlockState(pos.below());
        if (floor.isAir() || !isSupportive(floor)) return false;
        return passable(foot) && passable(head);
    }

    /** 階段 / フルブロック / 圧力プレート / カーペット / その他 supportive。 */
    private static boolean isSupportive(BlockState s) {
        if (s.getBlock() instanceof StairBlock) return true;
        if (s.isAir()) return false;
        // 圧力プレート / カーペット → 表面の上に立てる
        if (s.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock) return true;
        if (s.getBlock() instanceof net.minecraft.world.level.block.WoolCarpetBlock) return true;
        // 簡易: collision shape が空でなければ supportive とみなす
        try {
            VoxelShape shape = s.getCollisionShape(null, BlockPos.ZERO);
            return !shape.isEmpty();
        } catch (Throwable e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[WalkPath] supportive collision check failed", e);
            // AE2 CableBus 等で NPE → supportive でないと扱う (= 立てない)
            return false;
        }
    }

    /** プレイヤーがその空間を通れるか (空気 or 通り抜け可能ブロック)。 */
    private static boolean passable(BlockState s) {
        if (!s.getFluidState().isEmpty()) {
            return s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        }
        if (s.isAir()) return true;
        var block = s.getBlock();
        // フェンスゲート: 木製ドアと同じく「プレイヤーが開閉する」扱い → 常に通行可
        // (= 閉じていてもプレイヤーが開けて通る前提でナビ)
        if (block instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return true;
        }
        // 開いたトラップドア (水平面): 上下移動の関係で複雑なので、ここでは閉じてれば通れない扱い
        if (block instanceof net.minecraft.world.level.block.TrapDoorBlock) {
            try { return s.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN); }
            catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[WalkPath] trapdoor open read failed", e); return false; }
        }
        // 木のドア: 鉄製でなければプレイヤーは押し開けて通れる扱い
        if (block instanceof net.minecraft.world.level.block.DoorBlock door) {
            // 鉄ドア (= マテリアルが metal) は redstone 制御なので通行不可扱い
            // 実装簡易判定: BlockState の登録名で判定
            String name = block.getDescriptionId();
            if (name != null && name.contains("iron")) {
                try { return s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN); }
                catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[WalkPath] iron-door open read failed", e); return false; }
            }
            // 木製ドア類は常に通行可 (プレイヤーが開閉)
            return true;
        }
        try {
            VoxelShape shape = s.getCollisionShape(null, BlockPos.ZERO);
            return shape.isEmpty();
        } catch (Throwable ignored) {
            // 一部のブロック (AE2 CableBus 等) は null BlockGetter で NPE → 非通行として扱う
            return false;
        }
    }

    /** 頭部空間限定の passable check (ラダー時は足元 collision を無視するため)。 */
    private static boolean passableHead(BlockState head) {
        if (head.isAir() || isLadder(head)) return true;
        if (!head.getFluidState().isEmpty()) {
            return head.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        }
        try {
            VoxelShape shape = head.getCollisionShape(null, BlockPos.ZERO);
            return shape.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** ラダー / つる 判定。 */
    private static boolean isLadder(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    /** 水ブロック判定。 */
    private static boolean isWaterAt(Level level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /** 階段判定 (cost バイアス用)。 */
    private static boolean isStair(Level level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof StairBlock;
    }

    /**
     * cur → next に移動する際、間の対角空間 (上/下) が通れるか追加 check。
     * step-up: cur の頭上 (cur+2) も空気が必要 (1m ジャンプの飛び越しスペース)。
     * step-down: 落下先足元の支持は standableAt で check 済 → 追加 check 不要。
     */
    private static boolean walkableTransition(Level level, BlockPos cur, BlockPos next) {
        if (next.getY() > cur.getY()) {
            // step-up: cur の頭上 +1 (= cur+2) と next の頭上 (= next+1 = cur+2) が空気
            return passable(level.getBlockState(cur.above().above()));
        }
        return true;
    }

    private static Result reconstruct(Map<Long, Long> cameFrom, long endKey, float endG) {
        List<BlockPos> raw = new ArrayList<>();
        long cur = endKey;
        while (true) {
            raw.add(BlockPos.of(cur));
            Long prev = cameFrom.get(cur);
            if (prev == null) break;
            cur = prev;
        }
        Collections.reverse(raw);
        // Line-of-sight smoothing で zigzag を緩和
        List<BlockPos> smoothed = smooth(raw);
        // 推定 tick: プレイヤーの歩行速度 ~ 4.317 m/s = 0.216 blocks/tick → 1 block ≈ 4.6 ticks
        int approxTicks = (int) Math.ceil(endG * 4.6);
        return new Result(true, (int) Math.ceil(endG), approxTicks, Collections.unmodifiableList(smoothed));
    }

    /**
     * パス平滑化: 連続する 3 点 (a, b, c) を見て、a→c が直線的に「同方向か対角」なら b を消す。
     * これで A* が出した「軸→対角→軸」の階段状zigzag を「対角だけ」に圧縮できる。
     * 完全な line-of-sight 検証ではなく、軽量な「方向ベクトル一致」チェック。
     */
    private static List<BlockPos> smooth(List<BlockPos> raw) {
        if (raw.size() < 3) return raw;
        List<BlockPos> out = new ArrayList<>();
        out.add(raw.get(0));
        int i = 0;
        while (i + 2 < raw.size()) {
            BlockPos a = raw.get(i);
            BlockPos b = raw.get(i + 1);
            BlockPos c = raw.get(i + 2);
            int dx1 = sign(b.getX() - a.getX()), dz1 = sign(b.getZ() - a.getZ()), dy1 = sign(b.getY() - a.getY());
            int dx2 = sign(c.getX() - b.getX()), dz2 = sign(c.getZ() - b.getZ()), dy2 = sign(c.getY() - b.getY());
            if (dx1 == dx2 && dz1 == dz2 && dy1 == dy2) {
                // 同方向 → b は冗長
                i++;
            } else {
                out.add(b);
                i++;
            }
        }
        // 残り
        while (i + 1 < raw.size()) { out.add(raw.get(i + 1)); i++; }
        return out;
    }

    private static int sign(int v) { return Integer.compare(v, 0); }

    /** 経路の頂点数からおおよその所要秒を計算 (UI 表示用)。 */
    public static int approxSecondsFromTicks(int ticks) {
        return Math.max(1, (int) Math.round(ticks / 20.0));
    }

    /** ブロックが通常の歩行ブロックか (Blocks. 既知一覧)。試験用。 */
    @SuppressWarnings("unused")
    private static boolean isKnownWalkable(BlockState s) {
        return s.is(Blocks.AIR) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.STONE);
    }
}
