package com.trainsystemutilities.electrification.autoplace;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Create の TrackGraph 全 edges を sample して、 視線 ray との最近接点を返す。
 *
 * <p>通常の {@code level.clip()} は BlockState の AABB に hit するため、 Create の
 * Bezier 曲線 (= 中間 cell が air) には hit しない。 このクラスは:
 * <ol>
 *   <li>同 dimension の全 TrackGraph を traverse</li>
 *   <li>各 TrackEdge を t=0..1 で密に sample</li>
 *   <li>各 sample 点と ray の最近接距離を計算</li>
 *   <li>**閾値 (= 0.8 ブロック) 以内で 距離最小の sample 点** を hit として返す</li>
 * </ol>
 *
 * <p>これにより carve 中間部分でも raycast 配置が可能になる。
 */
public final class TrackCurveRaycast {

    /** ray からの許容距離 (= ブロック)。 Bezier curve まで これ以内なら hit。 */
    private static final double HIT_THRESHOLD = 0.8;
    /** 最大 raycast 距離 (= 64 ブロック、 駅範囲指定ツールと統一)。 */
    private static final double MAX_DISTANCE = 64.0;
    /** edge ごとの sample 数 (= 高いほど精度上がるが負荷増)。 */
    private static final int SAMPLES_PER_EDGE = 32;

    /** carve raycast hit 情報。 worldPoint は曲線上の最近接点、 axis はそこでの接線、
     *  edge / graph は hit した track edge への参照 (= 1 edge 全体配置に使用)。 */
    public record CurveHit(Vec3 worldPoint, Vec3 axis, double distanceAlongRay,
                            TrackGraph graph, TrackEdge edge) {}

    private TrackCurveRaycast() {}

    /**
     * 視線方向の Create 線路 (= 直線/curve 両方) の最近接点を返す。
     * 最大 {@value #MAX_DISTANCE} ブロック以内 + 閾値 {@value #HIT_THRESHOLD} 以内の hit がなければ null。
     */
    public static CurveHit raycast(Level level, Player player) {
        Vec3 eye = player.getEyePosition(1f);
        Vec3 look = player.getLookAngle();
        if (look.lengthSqr() < 1.0E-6) return null;
        Vec3 lookN = look.normalize();

        CurveHit best = null;
        double bestProjDist = MAX_DISTANCE;
        double bestPerpDist = HIT_THRESHOLD;
        String currentDim = level.dimension().location().toString();

        try {
            for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
                if (graph == null) continue;
                for (var nodeLoc : graph.getNodes()) {
                    TrackNode node = graph.locateNode(nodeLoc);
                    if (node == null) continue;
                    var conns = graph.getConnectionsFrom(node);
                    if (conns == null) continue;
                    for (var entry : conns.entrySet()) {
                        TrackEdge edge = entry.getValue();
                        if (edge == null) continue;
                        if (!isInCurrentDimension(currentDim, edge)) continue;

                        double length;
                        try { length = edge.getLength(); } catch (Throwable t) { continue; }
                        if (!Double.isFinite(length) || length <= 0) continue;

                        // edge を t=0..1 で sample
                        for (int s = 0; s <= SAMPLES_PER_EDGE; s++) {
                            double t = (double) s / SAMPLES_PER_EDGE;
                            Vec3 pt;
                            try { pt = edge.getPosition(graph, t); } catch (Throwable tx) { continue; }
                            if (pt == null) continue;

                            // ray と pt の最近接距離
                            Vec3 toPt = pt.subtract(eye);
                            double projDist = toPt.dot(lookN);
                            if (projDist < 0 || projDist > MAX_DISTANCE) continue;
                            Vec3 projection = eye.add(lookN.scale(projDist));
                            double perpDist = projection.distanceTo(pt);

                            // 閾値以内 + projDist が最小なら 採用
                            if (perpDist < bestPerpDist && projDist < bestProjDist) {
                                Vec3 axis;
                                try {
                                    Vec3 dir = edge.getDirectionAt(t * length);
                                    axis = horizontal(dir);
                                } catch (Throwable tx) { continue; }
                                if (axis == null) continue;
                                bestProjDist = projDist;
                                bestPerpDist = perpDist;
                                best = new CurveHit(pt, axis, projDist, graph, edge);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackCurve] raycast op failed", ignored); }
        return best;
    }

    /**
     * 指定 worldPoint (= carve raycast hit) の周辺の **並走 carve / 直線** を検出する。
     *
     * <p>同 dimension の全 TrackGraph を traverse し、 各 edge を sample。 hit point から
     * <ul>
     *   <li>axis 方向距離 < 2 cells (= 同じ portal 位置)</li>
     *   <li>perp 方向距離 ≤ scanRange (= 並走範囲)</li>
     *   <li>axis 平行性 dot ≥ 0.9 (= 25° 以内)</li>
     * </ul>
     * を満たす sample 点を収集し、 **perp 方向 sort + 1.5 ブロック以内マージ** で並走 N 本の
     * trackCenter list を返す。 hit point 自身も含む。
     *
     * <p>findParallelOuterTracks の curve 対応版 (= 中央 air でも機能する)。
     */
    public static java.util.List<Vec3> findParallelCurveCenters(net.minecraft.world.level.Level level,
                                                                 Vec3 hitPoint, Vec3 axis, int scanRange) {
        java.util.List<Vec3> result = new java.util.ArrayList<>();
        if (hitPoint == null || axis == null) {
            result.add(hitPoint);
            return result;
        }
        Vec3 axisN = horizontal(axis);
        if (axisN == null) {
            result.add(hitPoint);
            return result;
        }
        Vec3 perp = new Vec3(-axisN.z, 0, axisN.x).normalize();
        result.add(hitPoint);
        String currentDim = level.dimension().location().toString();

        try {
            for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
                if (graph == null) continue;
                for (var nodeLoc : graph.getNodes()) {
                    TrackNode node = graph.locateNode(nodeLoc);
                    if (node == null) continue;
                    var conns = graph.getConnectionsFrom(node);
                    if (conns == null) continue;
                    for (var entry : conns.entrySet()) {
                        TrackEdge edge = entry.getValue();
                        if (edge == null) continue;
                        if (!isInCurrentDimension(currentDim, edge)) continue;
                        double length;
                        try { length = edge.getLength(); } catch (Throwable t) { continue; }
                        if (!Double.isFinite(length) || length <= 0) continue;

                        for (int s = 0; s <= SAMPLES_PER_EDGE; s++) {
                            double t = (double) s / SAMPLES_PER_EDGE;
                            Vec3 pt;
                            try { pt = edge.getPosition(graph, t); } catch (Throwable tx) { continue; }
                            if (pt == null) continue;

                            Vec3 delta = pt.subtract(hitPoint);
                            double along = delta.x * axisN.x + delta.z * axisN.z;
                            // axis 方向に厳しく判定 (= curve 上の同じ track の異なる位置を除外)
                            if (Math.abs(along) >= 0.5) continue;
                            double perpDist = delta.x * perp.x + delta.z * perp.z;
                            if (Math.abs(perpDist) > scanRange) continue;
                            // 並走 track の最小間隔 = 3 block 程度。 それ以下は同 track or 隣接
                            if (Math.abs(perpDist) < 2.5) continue;

                            // axis 平行性
                            Vec3 edgeAxis;
                            try { edgeAxis = horizontal(edge.getDirectionAt(t * length)); } catch (Throwable tx) { continue; }
                            if (edgeAxis == null) continue;
                            if (Math.abs(axisN.dot(edgeAxis)) < 0.9) continue;

                            result.add(pt);
                        }
                    }
                }
            }
        } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[TrackCurve] raycast op failed", ignored); }

        // perp 方向 sort + 1.5 ブロック以内マージ
        final Vec3 hp = hitPoint;
        final Vec3 pr = perp;
        result.sort((a, b) -> {
            double ap = (a.x - hp.x) * pr.x + (a.z - hp.z) * pr.z;
            double bp = (b.x - hp.x) * pr.x + (b.z - hp.z) * pr.z;
            return Double.compare(ap, bp);
        });
        java.util.List<Vec3> merged = new java.util.ArrayList<>();
        double lastPerp = -Double.MAX_VALUE;
        for (Vec3 pt : result) {
            double p = (pt.x - hp.x) * pr.x + (pt.z - hp.z) * pr.z;
            if (p - lastPerp < 1.5) continue;
            merged.add(pt);
            lastPerp = p;
        }
        return merged;
    }

    private static Vec3 horizontal(Vec3 v) {
        if (v == null) return null;
        Vec3 flat = new Vec3(v.x, 0, v.z);
        return flat.lengthSqr() < 1.0E-4 ? null : flat.normalize();
    }

    private static boolean isInCurrentDimension(String currentDim, TrackEdge edge) {
        try {
            if (edge.isInterDimensional()) return false;
            return currentDim.equals(edge.node1.getLocation().getDimension().location().toString())
                    && currentDim.equals(edge.node2.getLocation().getDimension().location().toString());
        } catch (Throwable t) {
            return false;
        }
    }
}
