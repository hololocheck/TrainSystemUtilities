package com.trainsystemutilities.station.routing.navfield;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.routing.WalkingPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * NavField を逆 Dijkstra で構築する。
 *
 * <p>ゴール = 指定番線の anchor (= station block の上の standable)。
 * そこから外側に向けて歩行可能セルを Dijkstra 展開し、各セルに「次に踏むべき 1 歩」を記録。
 *
 * <p>コストモデル: WalkingPathfinder と互換 (= 同じ重みで track 回避 / stair 優遇)。
 */
public final class NavFieldBuilder {

    /** field の最大セル数 (= 「ホーム周辺で歩ける最大領域」の上限)。 */
    private static final int MAX_CELLS = 60_000;
    /** 構築の deadline (ms)。初回のみで以降キャッシュ参照。 */
    private static final long BUILD_DEADLINE_MS = 30_000L;
    /** anchor 補正の探索半径。 */
    private static final int ANCHOR_RADIUS = 3;

    /** 線路ブロック上に立つ場合の penalty (= 線路を強く避ける)。 */
    private static final float TRACK_PENALTY = 200f;
    /** 線路ブロックから 2 ブロック以内で「線路と同 Y」なら rail-bed (= track territory) ペナルティ。 */
    private static final float TRACK_NEIGHBOR_PENALTY = 100f;
    /** 水中歩行ペナルティ。 */
    private static final float WATER_PENALTY = 2.5f;
    /** 「目的外のホーム占有領域」に入った時のペナルティ (= 別番線を踏み台に横断するのを禁止)。 */
    private static final float OTHER_PLATFORM_PENALTY = 500f;
    /** 占有領域の XZ 半径。 */
    private static final int CLAIM_RADIUS_XZ = 6;
    /** 高 Y (= 駅ブロック Y + Y_HIGH_THRESHOLD 以上) で「stair 付近にない」セルへの penalty。
     *  屋根 (= solid blocks の上面) を歩行ルートとして使われないようにするため。 */
    private static final float HIGH_Y_NO_STAIR_PENALTY = 200f;
    private static final int Y_HIGH_THRESHOLD = 2; // 駅ブロックより 2 以上上は「高 Y」
    /** stair ブロックから何 block 以内なら「歩行可能高 Y」とみなすか。 */
    private static final int NEAR_STAIR_RADIUS = 16;
    /** フェンスゲート通過の追加コスト (= 開閉動作の所要時間反映)。
     *  小さい値 = 普通の歩行とほぼ同等、大きい値 = ゲート回避を促進。 */
    private static final float FENCE_GATE_PASS_COST = 3.0f;
    /** 既に開いているフェンスゲート: 上記コストを軽減する係数 (= 0 で完全 cancel)。 */
    private static final float FENCE_GATE_OPEN_DISCOUNT = 0.3f;
    /** フェンスゲート近接 (= 1 ブロック以内) cellへの軽微なコスト
     *  (= 「ゲート前の足元で立ち止まる」を表現)。 */
    private static final float FENCE_GATE_APPROACH_COST = 0.5f;
    /** y=stationY level (= 線路と同 Y) で goal claim でも near-stair でもないセルへの「rail-bed penalty」。
     *  線路の脇 / foundation 上を歩くショートカットを禁止するため。 */
    private static final float RAIL_BED_PENALTY = 300f;
    /** Goal platform claim の半径 (= 目的ホーム周辺は通常通り歩ける範囲)。 */
    private static final int GOAL_CLAIM_RADIUS_XZ = 12;

    private NavFieldBuilder() {}

    public record Result(NavField field, int cellsExpanded, long elapsedMs, boolean truncated) {}

    /**
     * 指定番線について逆 Dijkstra で field を構築。
     */
    public static Result build(ServerLevel level, StationGroup group, int platform) {
        long startNanos = System.nanoTime();
        var log = TrainSystemUtilities.LOGGER;
        if (platform <= 0 || platform > group.stationBlockPositions().size()) {
            log.warn("[NavField] invalid platform={} for group={}", platform, group.id());
            return new Result(null, 0, 0, false);
        }
        BlockPos sp = group.stationBlockPositions().get(platform - 1);
        BlockPos anchor = nearestStandable(level, sp.above(), ANCHOR_RADIUS);
        if (anchor == null) {
            log.warn("[NavField] no standable anchor near station {} (platform={})", sp, platform);
            return new Result(null, 0, 0, false);
        }
        log.info("[NavField] BEGIN group={} platform={} anchor={}",
                group.id(), platform, anchor);

        // 「目的外ホームの占有領域」を事前計算 = 各 station block 周辺の cells を Set に追加。
        // Dijkstra 拡張時に neighbor がこの Set に含まれていれば OTHER_PLATFORM_PENALTY を加算。
        // 結果: Dijkstra は他番線エリアを避けて橋経由ルートを選好する。
        java.util.HashSet<Long> otherClaimSet = new java.util.HashSet<>();
        java.util.List<BlockPos> stationPositions = group.stationBlockPositions();
        for (int i = 0; i < stationPositions.size(); i++) {
            if (i == platform - 1) continue; // 目的の station は除外
            BlockPos otherSp = stationPositions.get(i);
            int spY = otherSp.getY();
            for (int dx = -CLAIM_RADIUS_XZ; dx <= CLAIM_RADIUS_XZ; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -CLAIM_RADIUS_XZ; dz <= CLAIM_RADIUS_XZ; dz++) {
                        BlockPos p = new BlockPos(otherSp.getX() + dx, spY + dy, otherSp.getZ() + dz);
                        otherClaimSet.add(p.asLong());
                    }
                }
            }
        }
        log.info("[NavField] otherClaimSet={} cells (avoid other platforms)", otherClaimSet.size());

        // Goal claim set: 目的 station 周辺の cells (= ここでは y=stationY level の walking が許可される)
        java.util.HashSet<Long> goalClaimSet = new java.util.HashSet<>();
        BlockPos goalSp = stationPositions.get(platform - 1);
        for (int dx = -GOAL_CLAIM_RADIUS_XZ; dx <= GOAL_CLAIM_RADIUS_XZ; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -GOAL_CLAIM_RADIUS_XZ; dz <= GOAL_CLAIM_RADIUS_XZ; dz++) {
                    goalClaimSet.add(new BlockPos(goalSp.getX()+dx, goalSp.getY()+dy, goalSp.getZ()+dz).asLong());
                }
            }
        }
        log.info("[NavField] goalClaimSet={} cells (y=stationY allowed within)", goalClaimSet.size());

        // 「階段 + 線路 位置リスト」を 1 パスで事前計算
        // XZ +32 まで広くスキャン (= bounds 外に新設された橋などの構造物も検出)
        // Y は stationBaseY 中心に限定 (= 屋根上空の不要なスキャンを省略して時間節約)
        BlockPos minBnd = group.minPos();
        BlockPos maxBnd = group.maxPos();
        int sxMin = minBnd.getX() - 32, sxMax = maxBnd.getX() + 32;
        int syMin = anchor.getY() - 8;             // 階段下端より少し下
        int syMax = anchor.getY() + 14;            // 橋天井より上
        int szMin = minBnd.getZ() - 32, szMax = maxBnd.getZ() + 32;
        // Phase D-3: chunk section 直接アクセスで scan 高速化。
        // level.getBlockState() は座標→chunk lookup→section lookup→palette lookup と複数段。
        // section を一度引いて中の (lx,ly,lz) で直アクセスすれば同じ chunk 内は高速。
        java.util.ArrayList<Integer> stairCoordList = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> trackCoordList = new java.util.ArrayList<>();
        java.util.HashMap<Long, Boolean> fenceGateOpen = new java.util.HashMap<>();
        long scanStartNs = System.nanoTime();
        scanWithSections(level, sxMin, syMin, szMin, sxMax, syMax, szMax,
                stairCoordList, trackCoordList, fenceGateOpen);
        long scanElapsedMs = (System.nanoTime() - scanStartNs) / 1_000_000L;
        log.info("[NavField] scanWithSections elapsed={}ms (fenceGates={})",
                scanElapsedMs, fenceGateOpen.size());
        BlockPos.MutableBlockPos scanMut = new BlockPos.MutableBlockPos();
        int stairCount = stairCoordList.size() / 3;
        int trackCount = trackCoordList.size() / 3;
        int[] stairXYZ = new int[stairCoordList.size()];
        for (int i = 0; i < stairCoordList.size(); i++) stairXYZ[i] = stairCoordList.get(i);
        int[] trackXYZ = new int[trackCoordList.size()];
        for (int i = 0; i < trackCoordList.size(); i++) trackXYZ[i] = trackCoordList.get(i);
        int stationBaseY = anchor.getY() - 1; // station block Y
        int nearStairRadiusSq = NEAR_STAIR_RADIUS * NEAR_STAIR_RADIUS;
        log.info("[NavField] scanned stairs={} tracks={} (1-pass)", stairCount, trackCount);

        // 8 方向 × 3 dy オフセット
        int[] dxs = {0, 1, 0, -1, 1, 1, -1, -1};
        int[] dzs = {-1, 0, 1, 0, -1, 1, 1, -1};
        boolean[] diagonal = {false, false, false, false, true, true, true, true};
        float[] baseCost = {1.0f, 1.0f, 1.0f, 1.0f, 1.414f, 1.414f, 1.414f, 1.414f};

        Map<Long, Long> parentOf = new HashMap<>(MAX_CELLS * 4 / 3);
        Map<Long, Float> distOf = new HashMap<>(MAX_CELLS * 4 / 3);
        // standableAt 結果のキャッシュ (= 隣接セル参照で重複呼出を削減)
        java.util.HashMap<Long, Boolean> standableCache = new java.util.HashMap<>(MAX_CELLS * 8);
        // Phase C: その他の hot-path 判定もキャッシュ化 (= 同セルへの重複 BlockState 呼出を排除)
        java.util.HashMap<Long, Boolean> staircaseCache = new java.util.HashMap<>(MAX_CELLS * 4);
        java.util.HashMap<Long, Boolean> trackBelowCache = new java.util.HashMap<>(MAX_CELLS * 4);
        java.util.HashMap<Long, Boolean> waterCache = new java.util.HashMap<>(MAX_CELLS * 4);
        // 線路位置の HashSet (= 高速検索用)。trackXYZ から作成。
        java.util.HashSet<Long> trackPosSet = new java.util.HashSet<>(trackXYZ.length);
        for (int ti = 0; ti < trackXYZ.length; ti += 3) {
            trackPosSet.add(new BlockPos(trackXYZ[ti], trackXYZ[ti + 1], trackXYZ[ti + 2]).asLong());
        }
        // 「線路を横切る」を禁止するための corridor set (= 線路と同 XZ ±2 + Y±2 範囲)。
        // 並走する線路の gap (= 線路間の rail-bed) も含めるため XZ にも拡張。
        java.util.HashSet<Long> trackCorridorSet = new java.util.HashSet<>(trackXYZ.length * 50);
        for (int ti = 0; ti < trackXYZ.length; ti += 3) {
            int tx = trackXYZ[ti], ty = trackXYZ[ti + 1], tz = trackXYZ[ti + 2];
            for (int dxc = -2; dxc <= 2; dxc++) {
                for (int dzc = -2; dzc <= 2; dzc++) {
                    for (int corDy = -2; corDy <= 2; corDy++) {
                        trackCorridorSet.add(new BlockPos(tx + dxc, ty + corDy, tz + dzc).asLong());
                    }
                }
            }
        }
        log.info("[NavField] trackCorridorSet={} cells (forbid walking near tracks XZ±2 Y±2)",
                trackCorridorSet.size());
        // 開放集合: [posKey, distScaled]
        // dist の保持は別 map なので、queue は (dist, posKey) の triple で持つ
        PriorityQueue<DijEntry> open = new PriorityQueue<>();

        long anchorKey = anchor.asLong();
        parentOf.put(anchorKey, anchorKey); // self-parent at goal
        distOf.put(anchorKey, 0f);
        open.offer(new DijEntry(0f, anchor));

        long deadlineNanos = startNanos + BUILD_DEADLINE_MS * 1_000_000L;
        boolean truncated = false;
        int popped = 0;

        while (!open.isEmpty() && parentOf.size() < MAX_CELLS) {
            // deadline チェック (256 ノード毎)
            if ((popped & 255) == 0) {
                if (System.nanoTime() > deadlineNanos) {
                    truncated = true;
                    log.info("[NavField] DEADLINE {}ms cells={} popped={} - truncate",
                            BUILD_DEADLINE_MS, parentOf.size(), popped);
                    break;
                }
            }
            DijEntry top = open.poll();
            popped++;
            BlockPos cur = top.pos;
            float curDist = distOf.getOrDefault(cur.asLong(), Float.POSITIVE_INFINITY);
            if (top.dist > curDist + 1e-3f) continue; // 古い entry (stale)

            // 8 方向 × dy ∈ {-1, 0, +1} の neighbor を見る
            // Dijkstra は対称グラフを仮定: 「neighbor n から cur に walk できる」 = 「cur から n に walk できる」
            for (int dirIdx = 0; dirIdx < 8; dirIdx++) {
                int hx = dxs[dirIdx], hz = dzs[dirIdx];
                boolean isDiag = diagonal[dirIdx];
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = new BlockPos(cur.getX() + hx, cur.getY() + dy, cur.getZ() + hz);
                    long nKey = n.asLong();
                    if (parentOf.containsKey(nKey)) continue; // 既に訪問済 (Dijkstra で visited は確定)
                    Boolean cachedStandable = standableCache.get(nKey);
                    if (cachedStandable == null) {
                        cachedStandable = WalkingPathfinder.standableAt(level, n);
                        standableCache.put(nKey, cachedStandable);
                    }
                    if (!cachedStandable) continue;
                    // 線路ブロックを foot 空間 / head 空間に持つセルは完全に通行禁止
                    // (= trackPosSet 高速検索)
                    if (trackPosSet.contains(nKey) || trackPosSet.contains(n.above().asLong())) continue;
                    // 「stair 付近か?」を 1 回だけ判定 (corridor 禁止 / high-Y / rail-bed の 3 箇所で使う)。
                    // 旧実装はこの球判定を neighbor ごとに 2 回 (corridor 用と後段用) 走査していたが同値なので 1 回に統合。
                    boolean nearStair = false;
                    for (int si = 0; si < stairXYZ.length; si += 3) {
                        int dxs2 = stairXYZ[si] - n.getX();
                        int dys2 = stairXYZ[si + 1] - n.getY();
                        int dzs2 = stairXYZ[si + 2] - n.getZ();
                        if (dxs2 * dxs2 + dys2 * dys2 + dzs2 * dzs2 <= nearStairRadiusSq) {
                            nearStair = true;
                            break;
                        }
                    }
                    // 線路の真下/真上 (= 同 XZ ±2 + Y ±2) を歩くのを完全禁止 (= 線路を横切る不可能)。
                    // goal claim 内 OR 階段付近は除外。
                    if (trackCorridorSet.contains(nKey) && !goalClaimSet.contains(nKey) && !nearStair) {
                        continue; // 完全禁止 (= 迂回せざるを得ない)
                    }
                    // 連続 step-up 拒否: player perspective で n → cur → parent(cur) と 2 連続
                    // step-up になり、かつ正規階段でない場合は装飾段差で 2 段壁を乗り越える経路。
                    // 完全に reject。
                    int dyPlayer1 = cur.getY() - n.getY(); // player の n→cur 段差
                    boolean curStair = cachedStair(staircaseCache, level, cur);
                    boolean nStair = cachedStair(staircaseCache, level, n);
                    if (dyPlayer1 > 0 && !nStair && !curStair) {
                        Long parentCurKey = parentOf.get(cur.asLong());
                        if (parentCurKey != null) {
                            BlockPos parentCur = BlockPos.of(parentCurKey);
                            int dyPlayer2 = parentCur.getY() - cur.getY();
                            if (dyPlayer2 > 0 && !cachedStair(staircaseCache, level, parentCur)) {
                                continue; // 2 連続 step-up で stair でない → reject
                            }
                        }
                    }
                    if (isDiag && dy != 0) continue; // 対角段差は除外
                    if (isDiag) {
                        // 対角コーナーチェック
                        BlockPos sideA = new BlockPos(cur.getX() + hx, cur.getY(), cur.getZ());
                        BlockPos sideB = new BlockPos(cur.getX(), cur.getY(), cur.getZ() + hz);
                        if (!passableSpace(level, sideA) || !passableSpace(level, sideB)) continue;
                    }
                    // edge cost: n から cur へ walk するコスト (= cur から n と対称)
                    float edgeCost = baseCost[dirIdx];
                    if (dy != 0) {
                        boolean realStair = nStair || curStair; // 既にキャッシュ取得済
                        edgeCost *= realStair ? 1.05f : 8.0f;
                    }
                    if (cachedTrackBelow(trackBelowCache, level, n)) edgeCost += TRACK_PENALTY;
                    // 線路に近い (= 2 ブロック以内 + Y 差 1 以内) セルへのペナルティ。
                    // 線路の脇 / 線路の下の rail-bed area に経路が降りるのを防ぐ。
                    boolean nearTrack = false;
                    for (int ti = 0; ti < trackXYZ.length; ti += 3) {
                        int tdx = trackXYZ[ti] - n.getX();
                        int tdy = trackXYZ[ti + 1] - n.getY();
                        int tdz = trackXYZ[ti + 2] - n.getZ();
                        if (Math.abs(tdx) <= 2 && Math.abs(tdy) <= 1 && Math.abs(tdz) <= 2) {
                            nearTrack = true;
                            break;
                        }
                    }
                    if (nearTrack) edgeCost += TRACK_NEIGHBOR_PENALTY;
                    if (nStair) edgeCost *= 0.95f;
                    if (cachedWater(waterCache, level, n)) edgeCost *= WATER_PENALTY;
                    // フェンスゲートを通過する場合のコスト調整
                    Boolean gateOpen = fenceGateOpen.get(nKey);
                    if (gateOpen != null) {
                        // 開いていれば軽減 (= 0.3 倍)、閉じていればフルコスト
                        edgeCost += FENCE_GATE_PASS_COST * (gateOpen ? FENCE_GATE_OPEN_DISCOUNT : 1.0f);
                    }
                    // フェンスゲートに「隣接」するセル (= 1 ブロック以内) には軽微なコスト
                    // (= ゲート前で立ち止まる挙動)
                    boolean nearGate = false;
                    if (!fenceGateOpen.isEmpty()) {
                        for (var d : net.minecraft.core.Direction.values()) {
                            BlockPos adj = n.relative(d);
                            if (fenceGateOpen.containsKey(adj.asLong())) {
                                nearGate = true;
                                break;
                            }
                        }
                    }
                    if (nearGate) edgeCost += FENCE_GATE_APPROACH_COST;
                    // 目的外ホームの占有領域に踏み込むことを強くペナルティ
                    if (otherClaimSet.contains(nKey)) edgeCost += OTHER_PLATFORM_PENALTY;
                    // nearStair は neighbor 先頭で算出済 (= corridor 判定と共用)
                    // 高 Y (屋根) で stair 付近にないセル → 強ペナルティ
                    if (n.getY() > stationBaseY + Y_HIGH_THRESHOLD && !nearStair) {
                        edgeCost += HIGH_Y_NO_STAIR_PENALTY;
                    }
                    // y=stationY level (= 線路と同 Y) で goal claim 外 + stair 遠いセル → rail-bed ペナルティ
                    // (= 線路脇の foundation top を歩くのを禁止)
                    if (n.getY() == stationBaseY && !nearStair && !goalClaimSet.contains(nKey)) {
                        edgeCost += RAIL_BED_PENALTY;
                    }
                    // 線路 corridor は上で continue 済み (= ここに到達しない)

                    float tentative = curDist + edgeCost;
                    Float prev = distOf.get(nKey);
                    if (prev == null || tentative < prev) {
                        distOf.put(nKey, tentative);
                        parentOf.put(nKey, cur.asLong()); // n から進むべき次の 1 歩は cur
                        open.offer(new DijEntry(tentative, n));
                    }
                }
            }
        }
        if (parentOf.size() >= MAX_CELLS) {
            truncated = true;
            log.info("[NavField] MAX_CELLS={} reached - truncate", MAX_CELLS);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        NavField field = new NavField(group.id(), platform, anchor, parentOf, distOf);
        log.info("[NavField] DONE group={} platform={} cells={} popped={} elapsed={}ms truncated={}",
                group.id(), platform, parentOf.size(), popped, elapsedMs, truncated);
        return new Result(field, popped, elapsedMs, truncated);
    }

    // --- ヘルパ (WalkingPathfinder と同等の判定を再実装) ---

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

    private static boolean passableSpace(ServerLevel level, BlockPos pos) {
        BlockState foot = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        return passableState(foot) && passableState(head);
    }

    private static boolean passableState(BlockState s) {
        if (s.isAir()) return true;
        Block b = s.getBlock();
        // 水も passable 扱い (NavField は「歩ける = 水中もOK」)
        if (s.getFluidState().isSource() || !s.getFluidState().isEmpty()) return true;
        try {
            return !s.isCollisionShapeFullBlock(null, BlockPos.ZERO);
        } catch (Throwable e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[NavField] passable collision check failed", e);
            return false; // AE2 CableBus 等の null-getter 非対応ブロックは非通行扱い
        }
    }

    /**
     * Phase D-3: chunk section 直接アクセスでブロック走査。
     *
     * <p>同時にフェンスゲート位置と open 状態も記録 → Dijkstra でコスト調整に使う。
     */
    private static void scanWithSections(ServerLevel level,
                                          int sxMin, int syMin, int szMin,
                                          int sxMax, int syMax, int szMax,
                                          java.util.List<Integer> stairCoordList,
                                          java.util.List<Integer> trackCoordList,
                                          java.util.HashMap<Long, Boolean> fenceGateOpen) {
        // H-8 hardening: section の PalettedContainer 直読み (section.getBlockState) は worker thread
        // から tick の chunk 書込み中に読むと torn read / AIOOBE を起こしやすい。 より guard の多い
        // level.getBlockState(pos) 経由に変更 (= chunk/section の null/範囲チェックを通る)。 走査結果は同一。
        // worker 側は try/catch で包んでおり、 万一の例外は graceful failure になる。
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int wx = sxMin; wx <= sxMax; wx++) {
            for (int wz = szMin; wz <= szMax; wz++) {
                for (int wy = syMin; wy <= syMax; wy++) {
                    var state = level.getBlockState(mp.set(wx, wy, wz));
                    Block b = state.getBlock();
                    if (b instanceof StairBlock) {
                        stairCoordList.add(wx);
                        stairCoordList.add(wy);
                        stairCoordList.add(wz);
                    } else if (b instanceof com.simibubi.create.content.trains.track.ITrackBlock) {
                        trackCoordList.add(wx);
                        trackCoordList.add(wy);
                        trackCoordList.add(wz);
                    } else if (b instanceof net.minecraft.world.level.block.FenceGateBlock) {
                        boolean open = false;
                        try { open = state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN); }
                        catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[NavField] fence-gate open read failed", e); }
                        fenceGateOpen.put(new BlockPos(wx, wy, wz).asLong(), open);
                    }
                }
            }
        }
    }

    /** isPartOfStaircase の lazy キャッシュ。Dijkstra で同一 cell が複数回参照されるのを排除。 */
    private static boolean cachedStair(java.util.HashMap<Long, Boolean> cache,
                                        ServerLevel level, BlockPos pos) {
        Long k = pos.asLong();
        Boolean v = cache.get(k);
        if (v != null) return v;
        boolean r = isPartOfStaircase(level, pos);
        cache.put(k, r);
        return r;
    }

    /** isTrackBelow の lazy キャッシュ。 */
    private static boolean cachedTrackBelow(java.util.HashMap<Long, Boolean> cache,
                                             ServerLevel level, BlockPos pos) {
        Long k = pos.asLong();
        Boolean v = cache.get(k);
        if (v != null) return v;
        boolean r = isTrackBelow(level, pos);
        cache.put(k, r);
        return r;
    }

    /** isWaterAt の lazy キャッシュ。 */
    private static boolean cachedWater(java.util.HashMap<Long, Boolean> cache,
                                        ServerLevel level, BlockPos pos) {
        Long k = pos.asLong();
        Boolean v = cache.get(k);
        if (v != null) return v;
        boolean r = isWaterAt(level, pos);
        cache.put(k, r);
        return r;
    }

    private static boolean isTrackBelow(ServerLevel level, BlockPos pos) {
        try {
            return level.getBlockState(pos.below()).getBlock()
                    instanceof com.simibubi.create.content.trains.track.ITrackBlock;
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[NavField] track-below check failed", e); return false; }
    }

    /** セルの足元空間 / 頭上空間に線路ブロックが存在するか? = そのセルを線路が貫通している。 */
    private static boolean cellIntersectsTrack(ServerLevel level, BlockPos pos) {
        try {
            if (level.getBlockState(pos).getBlock()
                    instanceof com.simibubi.create.content.trains.track.ITrackBlock) return true;
            if (level.getBlockState(pos.above()).getBlock()
                    instanceof com.simibubi.create.content.trains.track.ITrackBlock) return true;
        } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[NavField] cell-track intersect check failed", e); }
        return false;
    }

    private static boolean isPartOfStaircase(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof StairBlock)) {
            BlockState below = level.getBlockState(pos.below());
            if (!(below.getBlock() instanceof StairBlock)) return false;
            return hasAdjStair(level, pos.below());
        }
        return hasAdjStair(level, pos);
    }

    private static boolean hasAdjStair(ServerLevel level, BlockPos stair) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = stair.relative(d);
            if (level.getBlockState(n.above()).getBlock() instanceof StairBlock) return true;
            if (level.getBlockState(n.below()).getBlock() instanceof StairBlock) return true;
            if (level.getBlockState(n).getBlock() instanceof StairBlock) return true;
        }
        return false;
    }

    private static boolean isWaterAt(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).isSource()
                || (level.getFluidState(pos).getType() != net.minecraft.world.level.material.Fluids.EMPTY
                && level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER));
    }

    /** Dijkstra queue entry。distance 昇順。 */
    private static final class DijEntry implements Comparable<DijEntry> {
        final float dist;
        final BlockPos pos;
        DijEntry(float dist, BlockPos pos) { this.dist = dist; this.pos = pos; }
        @Override public int compareTo(DijEntry o) { return Float.compare(this.dist, o.dist); }
    }
}
