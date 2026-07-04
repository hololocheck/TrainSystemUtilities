package com.trainsystemutilities.electrification.wire;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 架線セグメントの chunk 単位空間インデックス。
 *
 * <p>「ある点の近傍にある架線」を高速に列挙するためのバケット。
 * 1 接続を、その線分が通過する 2D chunk (cx, cz) の bounding box に属する全 chunk へ登録する。
 * 1 本 32m の架線なら最大 4 chunk に登録される。
 *
 * <p>使い方:
 * <ul>
 *   <li>感電判定: 周囲 ±1 chunk を {@link #queryRangeXZ} で取得して entity との距離を計算</li>
 *   <li>パンタ集電: パンタ座標の chunk + 8 隣 chunk を取得</li>
 * </ul>
 *
 * <p>注: chunk 判定には BlockPos の整数座標を使う (= 実際の attach Vec3 ではなく block center)。
 * 0.4 ブロック程度の誤差は 16 ブロック chunk グリッドに対して無視できる。
 * 厳密な距離判定は呼び出し側で行う。
 */
public final class WireSpatialIndex {

    /** chunkKey (cx, cz) → そこを通過する接続群。同期は ConcurrentHashMap に委ねる。 */
    private final Map<Long, Set<WireConnection>> buckets = new ConcurrentHashMap<>();

    public void add(WireConnection conn) {
        for (long ckey : chunksOfSegment(conn.nodeA(), conn.nodeB())) {
            buckets.computeIfAbsent(ckey, k -> ConcurrentHashMap.newKeySet()).add(conn);
        }
    }

    public void remove(WireConnection conn) {
        for (long ckey : chunksOfSegment(conn.nodeA(), conn.nodeB())) {
            Set<WireConnection> bucket = buckets.get(ckey);
            if (bucket != null) {
                bucket.remove(conn);
                if (bucket.isEmpty()) buckets.remove(ckey);
            }
        }
    }

    public void clear() {
        buckets.clear();
    }

    public int bucketCount() {
        return buckets.size();
    }

    /** {@code origin} を中心とする XZ 半径 {@code radius} に重なる可能性のある接続を列挙。 */
    public List<WireConnection> queryRangeXZ(Vec3 origin, double radius) {
        int minCx = ((int) Math.floor(origin.x - radius)) >> 4;
        int maxCx = ((int) Math.floor(origin.x + radius)) >> 4;
        int minCz = ((int) Math.floor(origin.z - radius)) >> 4;
        int maxCz = ((int) Math.floor(origin.z + radius)) >> 4;
        Set<WireConnection> result = new HashSet<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Set<WireConnection> bucket = buckets.get(chunkKey(cx, cz));
                if (bucket != null) result.addAll(bucket);
            }
        }
        return new ArrayList<>(result);
    }

    /** 指定 chunk + 8 隣接 chunk の接続をまとめて返す。 */
    public List<WireConnection> queryAround(int cx, int cz) {
        Set<WireConnection> result = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Set<WireConnection> bucket = buckets.get(chunkKey(cx + dx, cz + dz));
                if (bucket != null) result.addAll(bucket);
            }
        }
        return new ArrayList<>(result);
    }

    /** 全接続をバケットから再構築 (= load 後の rebuild 用)。 */
    public void rebuild(Collection<WireConnection> connections) {
        buckets.clear();
        for (WireConnection c : connections) {
            add(c);
        }
    }

    /**
     * 線分が通過する chunk 群を XZ 平面の bounding box で求める。
     * 厳密な DDA でなく box 化 (= 余分な chunk が含まれることがある) だが、32m 架線では
     * 最大 4 chunk なのでオーバーヘッドは無視できる。
     */
    private static long[] chunksOfSegment(BlockPos a, BlockPos b) {
        int ax = a.getX() >> 4;
        int az = a.getZ() >> 4;
        int bx = b.getX() >> 4;
        int bz = b.getZ() >> 4;
        int minCx = Math.min(ax, bx);
        int maxCx = Math.max(ax, bx);
        int minCz = Math.min(az, bz);
        int maxCz = Math.max(az, bz);
        int n = (maxCx - minCx + 1) * (maxCz - minCz + 1);
        long[] keys = new long[n];
        int i = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                keys[i++] = chunkKey(cx, cz);
            }
        }
        return keys;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
