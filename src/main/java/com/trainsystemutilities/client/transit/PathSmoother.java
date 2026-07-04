package com.trainsystemutilities.client.transit;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 経路描画用ユーティリティ。
 *
 * <ul>
 *   <li>{@link #simplify} — Ramer–Douglas–Peucker による共線 waypoint の間引き</li>
 *   <li>{@link #catmullRom} — Catmull-Rom スプラインで滑らかな曲線に補間</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class PathSmoother {

    private PathSmoother() {}

    /**
     * RDP 法で path を簡略化。直線区間の中間 waypoint を削除し、曲がり角だけ残す。
     *
     * @param tolerance 許容垂直距離 (ブロック単位)。これより近い中間点は除去。
     */
    public static List<BlockPos> simplify(List<BlockPos> path, double tolerance) {
        if (path == null || path.size() < 3) return path == null ? List.of() : path;
        List<Vector3f> pts = new ArrayList<>(path.size());
        for (BlockPos p : path) {
            pts.add(new Vector3f(p.getX() + 0.5f, p.getY() + 0.5f, p.getZ() + 0.5f));
        }
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        rdp(pts, 0, pts.size() - 1, tolerance, keep);
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            if (keep[i]) out.add(path.get(i));
        }
        return out;
    }

    private static void rdp(List<Vector3f> pts, int from, int to, double tol, boolean[] keep) {
        if (to - from < 2) return;
        Vector3f a = pts.get(from);
        Vector3f b = pts.get(to);
        double maxDist = 0;
        int maxIdx = -1;
        for (int i = from + 1; i < to; i++) {
            double d = perpDist(pts.get(i), a, b);
            if (d > maxDist) { maxDist = d; maxIdx = i; }
        }
        if (maxDist > tol && maxIdx > 0) {
            keep[maxIdx] = true;
            rdp(pts, from, maxIdx, tol, keep);
            rdp(pts, maxIdx, to, tol, keep);
        }
    }

    private static double perpDist(Vector3f p, Vector3f a, Vector3f b) {
        Vector3f ab = new Vector3f(b).sub(a);
        Vector3f ap = new Vector3f(p).sub(a);
        float abLen2 = ab.lengthSquared();
        if (abLen2 < 1e-6f) return ap.length();
        float t = ap.dot(ab) / abLen2;
        Vector3f proj = new Vector3f(ab).mul(t).add(a);
        return new Vector3f(p).sub(proj).length();
    }

    /**
     * Catmull-Rom スプラインで補間した点列を返す。
     *
     * @param control 制御点 (= simplify 後の waypoint)
     * @param subdivisions 各区間の分割数 (5-10 程度推奨)
     * @return 補間後の Vector3f 列 (描画用)
     */
    public static List<Vector3f> catmullRom(List<BlockPos> control, int subdivisions) {
        if (control == null || control.isEmpty()) return List.of();
        List<Vector3f> pts = new ArrayList<>(control.size());
        for (BlockPos p : control) {
            pts.add(new Vector3f(p.getX() + 0.5f, p.getY() + 0.5f, p.getZ() + 0.5f));
        }
        if (pts.size() < 2) return pts;
        if (pts.size() == 2) return pts; // 直線

        List<Vector3f> out = new ArrayList<>();
        // 端の ghost point: 端点を反射させて延長
        for (int i = 0; i < pts.size() - 1; i++) {
            Vector3f p0 = i == 0 ? extrapolate(pts.get(0), pts.get(1)) : pts.get(i - 1);
            Vector3f p1 = pts.get(i);
            Vector3f p2 = pts.get(i + 1);
            Vector3f p3 = i + 2 < pts.size()
                    ? pts.get(i + 2)
                    : extrapolate(pts.get(pts.size() - 1), pts.get(pts.size() - 2));

            for (int j = 0; j < subdivisions; j++) {
                float t = (float) j / subdivisions;
                out.add(catmullRomInterpolate(p0, p1, p2, p3, t));
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    /** 端点 b から見て a を延長した位置 (= 端点 ghost): result = a + (a - b) */
    private static Vector3f extrapolate(Vector3f a, Vector3f b) {
        return new Vector3f(a).mul(2).sub(b);
    }

    /**
     * Catmull-Rom 補間: 4 点 (p0, p1, p2, p3) と t (0..1) から p1→p2 区間の点を計算。
     *
     * <pre>
     * Q(t) = 0.5 * (
     *   (2 P1) +
     *   (-P0 + P2) t +
     *   (2 P0 - 5 P1 + 4 P2 - P3) t² +
     *   (-P0 + 3 P1 - 3 P2 + P3) t³
     * )
     * </pre>
     */
    private static Vector3f catmullRomInterpolate(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        Vector3f r = new Vector3f(p1).mul(2);
        Vector3f c1 = new Vector3f(p0).mul(-1).add(p2);
        r.add(c1.mul(t));
        Vector3f c2 = new Vector3f(p0).mul(2)
                .add(new Vector3f(p1).mul(-5))
                .add(new Vector3f(p2).mul(4))
                .add(new Vector3f(p3).mul(-1));
        r.add(c2.mul(t2));
        Vector3f c3 = new Vector3f(p0).mul(-1)
                .add(new Vector3f(p1).mul(3))
                .add(new Vector3f(p2).mul(-3))
                .add(p3);
        r.add(c3.mul(t3));
        return r.mul(0.5f);
    }
}
