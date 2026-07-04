package com.trainsystemutilities.station.routing.navfield;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「あるホームに到達するための完全な親ポインタフィールド」。
 *
 * <p>解析時に「ホーム anchor から逆 Dijkstra」で構築。各セル (= packed BlockPos) について
 * 「次に踏むべき 1 歩 = parent」を保持する。実行時は parent ポインタを辿るだけで経路完成。
 *
 * <p>特性:
 * <ul>
 *   <li>逆 Dijkstra なので「最短コスト経路 (track 回避 / stair 優遇)」が自動的に得られる</li>
 *   <li>ナビ実行は O(path length) で確定 (deadline / partial / connect 不要)</li>
 *   <li>橋・階段・歩道橋を含む構造の連結性が自然に表現される</li>
 * </ul>
 */
public final class NavField {

    private final UUID groupId;
    private final int platform;
    /** ゴール = 番線 anchor (Dijkstra の起点)。 */
    private final BlockPos goal;
    /** packed BlockPos → packed parent BlockPos (= 次の 1 歩)。goal の parent は自身。 */
    private final Map<Long, Long> parentOf;
    /** 任意で各セルのゴールまでの累積コスト (デバッグ・選択用)。null 可。 */
    private final Map<Long, Float> distOf;
    /** Phase D-2 spatial index: chunk バケット (= chunkKey → cell array)。 */
    private final Map<Long, long[]> chunkBuckets;

    public NavField(UUID groupId, int platform, BlockPos goal,
                     Map<Long, Long> parentOf, Map<Long, Float> distOf) {
        this.groupId = groupId;
        this.platform = platform;
        this.goal = goal;
        this.parentOf = Map.copyOf(parentOf);
        this.distOf = distOf == null ? Map.of() : Map.copyOf(distOf);
        this.chunkBuckets = buildChunkBuckets(this.parentOf);
    }

    /** chunk 単位の空間インデックスを構築。 findNearestReachable で 10x 以上高速化。 */
    private static Map<Long, long[]> buildChunkBuckets(Map<Long, Long> parents) {
        java.util.HashMap<Long, java.util.ArrayList<Long>> tmp = new java.util.HashMap<>();
        for (Long key : parents.keySet()) {
            BlockPos p = BlockPos.of(key);
            long ckey = chunkKey(p.getX() >> 4, p.getZ() >> 4);
            tmp.computeIfAbsent(ckey, k -> new java.util.ArrayList<>()).add(key);
        }
        java.util.HashMap<Long, long[]> built = new java.util.HashMap<>(tmp.size() * 4 / 3);
        for (var e : tmp.entrySet()) {
            long[] arr = new long[e.getValue().size()];
            for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
            built.put(e.getKey(), arr);
        }
        return Map.copyOf(built);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public UUID groupId() { return groupId; }
    public int platform() { return platform; }
    public BlockPos goal() { return goal; }
    public int cellCount() { return parentOf.size(); }
    public boolean contains(BlockPos pos) {
        return pos != null && parentOf.containsKey(pos.asLong());
    }
    public Float distance(BlockPos pos) {
        return pos == null ? null : distOf.get(pos.asLong());
    }

    /**
     * start (= プレイヤー位置に近い field 内セル) から goal までのパスを構築。
     * start が field 外なら空リスト。
     */
    public List<BlockPos> tracePath(BlockPos start) {
        if (start == null || !contains(start)) return List.of();
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = start;
        // 最大ステップ数 = field のセル数 (循環防止)
        int safety = parentOf.size() + 16;
        while (safety-- > 0) {
            path.add(cur);
            if (cur.equals(goal)) break;
            Long pkey = parentOf.get(cur.asLong());
            if (pkey == null) break;
            BlockPos next = BlockPos.of(pkey);
            if (next.equals(cur)) break; // self-parent (= goal の特殊ケース)
            cur = next;
        }
        return path;
    }

    /**
     * origin に最も近い field 内セルを返す。
     *
     * <p>Phase D-2: chunk buckets を使った螺旋検索で O(N) → O(局所セル数) 化。
     * 60k cells フィールドでも数 μs - 数 100μs で完了 (= 旧 brute force 1-5ms)。
     */
    public BlockPos findNearestReachable(BlockPos origin, double maxDistSq) {
        if (origin == null) return null;
        if (contains(origin)) return origin;
        if (chunkBuckets.isEmpty()) return null;
        int ocx = origin.getX() >> 4;
        int ocz = origin.getZ() >> 4;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        // 螺旋的に半径 R の chunk リングを探索。最初に当たったら +1 リング探索して終了。
        // (= 探索半径より遠い chunk には絶対 nearest がいないので)
        int maxRadius = 16; // 16 chunk = 256 ブロック
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue; // ring のみ
                    long ckey = chunkKey(ocx + dx, ocz + dz);
                    long[] arr = chunkBuckets.get(ckey);
                    if (arr == null) continue;
                    for (long k : arr) {
                        BlockPos p = BlockPos.of(k);
                        double d = origin.distSqr(p);
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = p;
                        }
                    }
                }
            }
            // B3: chunk ring を距離認識で打ち切る。 ring radius の最近可能距離 ((radius-1)*16 ブロック)
            // が現 best を超えたら以降のどの ring にも nearest はいない。 旧「+1 ring」heuristic は
            // chunk 距離と実距離を混同し、 chunk 角付近で真の最近傍を逃していた。
            if (best != null) {
                double minRingDist = Math.max(0, radius - 1) * 16.0;
                if (minRingDist * minRingDist > bestDistSq) break;
            }
        }
        if (best != null && bestDistSq <= maxDistSq) return best;
        return null;
    }

    /** 同 groupId の全 platform をクリアする時等に使う key 比較用ユーティリティ。 */
    public boolean matches(UUID groupId, int platform) {
        return this.groupId.equals(groupId) && this.platform == platform;
    }

    /** SavedData 永続化用の parent map export (= 内部 map のコピー)。 */
    public Map<Long, Long> exportParentMap() {
        return new java.util.HashMap<>(parentOf);
    }
}
