package com.trainsystemutilities.client.transit;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/**
 * 徒歩ナビゲーション (3D 経路案内システム) のクライアント側 state。
 *
 * <p>有効なパスがあるとき、{@link TransitNavRenderer} がワールド内に
 * ベクター線で経路を描画する。
 */
@OnlyIn(Dist.CLIENT)
public final class TransitNavClientState {

    private static volatile UUID targetGroupId = null;
    private static volatile int targetPlatform = 0;
    private static volatile String targetName = "";
    private static volatile List<BlockPos> path = List.of();
    private static volatile int approxTicks = 0;
    private static volatile String lastError = "";
    private static volatile long pathSetNanos = 0;
    private static volatile long pendingSinceNanos = 0;

    // === 多区間チェイン (transfer chain) ===
    /** 現在のルートから順次案内すべき駅の queue (= 列車降車 → 次の乗車駅 → ...)。 */
    private static final java.util.LinkedList<ChainedTarget> chainQueue = new java.util.LinkedList<>();
    public record ChainedTarget(UUID groupId, int platform, String label) {}

    private TransitNavClientState() {}

    public static UUID targetGroupId() { return targetGroupId; }
    public static int targetPlatform() { return targetPlatform; }
    public static String targetName() { return targetName; }
    public static List<BlockPos> path() { return path; }
    public static int approxTicks() { return approxTicks; }
    public static String lastError() { return lastError; }
    public static boolean active() { return !path.isEmpty(); }
    public static long pathAgeMs() {
        return pathSetNanos == 0 ? 0 : (System.nanoTime() - pathSetNanos) / 1_000_000L;
    }

    public static void setActivePath(UUID id, String name, List<BlockPos> p, int ticks) {
        targetGroupId = id;
        targetName = name == null ? "" : name;
        path = List.copyOf(p);
        approxTicks = ticks;
        lastError = "";
        pathSetNanos = System.nanoTime();
        pendingSinceNanos = 0;
    }

    /** ナビ要求送信時に「指定された番線」を保存し、auto-recalc がそれを再利用できるようにする。 */
    public static void setRequestedPlatform(int platform) {
        targetPlatform = Math.max(0, platform);
    }

    public static void setError(String err) {
        lastError = err == null ? "" : err;
        pendingSinceNanos = 0;
    }

    public static void clear() {
        targetGroupId = null;
        targetPlatform = 0;
        targetName = "";
        path = List.of();
        approxTicks = 0;
        lastError = "";
        pathSetNanos = 0;
        pendingSinceNanos = 0;
        chainQueue.clear();
    }

    /** チェインに乗車駅を追加 (出発時にルートから自動構築用)。 */
    public static void enqueueChain(ChainedTarget t) {
        if (t == null) return;
        chainQueue.add(t);
    }
    public static void clearChain() { chainQueue.clear(); }
    public static int chainSize() { return chainQueue.size(); }
    public static ChainedTarget chainPeek() { return chainQueue.peek(); }
    public static ChainedTarget chainPoll() { return chainQueue.poll(); }

    /** リクエスト送信を記録 (連打防止 throttle 用)。 */
    public static void markPending() {
        pendingSinceNanos = System.nanoTime();
        lastError = "";
    }
    public static boolean isPending() {
        if (pendingSinceNanos == 0) return false;
        // 48 秒経過したら timeout (server 側 dispatcher = 45 秒、NavField 構築 30 秒 + α)
        return (System.nanoTime() - pendingSinceNanos) < 48_000_000_000L;
    }

    /**
     * プレイヤーが目的地に十分近ければ自動でパスをクリアし、チェインがあれば次の case を発火。
     * pathRendererから定期的に呼ばれる。
     */
    public static void checkAutoClear(net.minecraft.world.entity.player.Player player) {
        if (path.isEmpty() || player == null) return;
        BlockPos last = path.get(path.size() - 1);
        double dx = last.getX() + 0.5 - player.getX();
        double dy = last.getY() - player.getY();
        double dz = last.getZ() + 0.5 - player.getZ();
        double dxz2 = dx * dx + dz * dz;
        // 「ホーム到着」判定: ゴールは "ホームに足を踏み入れること" であり、駅ブロックまで歩かせない。
        //   ホーム階 (= goal の Y ± 2) に到達 + XZ 距離 20 ブロック以内 → 即終了
        //   |dy| ≤ 2 で橋 (y goal-6) や通路ミッドフライトでの誤クリアを防ぐ
        boolean arrived = dxz2 < 400 && Math.abs(dy) <= 2;
        if (arrived) {
            // 「最寄り駅 (= 乗車駅) への案内」がこの機能の目的。
            // 到着駅 (= 降車駅) への自動チェインは行わない。pathをクリアして終了。
            UUID prevTarget = targetGroupId;
            targetGroupId = null;
            targetName = "";
            path = List.of();
            approxTicks = 0;
            pathSetNanos = 0;
            chainQueue.clear(); // 残ったチェインがあっても破棄
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                    "[NavPath] arrived at {} — guidance ended (no auto-advance)", prevTarget);
        }
    }

    /**
     * パス上でプレイヤーに最も近い waypoint の index を返す。
     * 描画 head-trim と逸脱検知の両方に使う。
     */
    public static int nearestPathIndex(net.minecraft.world.entity.player.Player player) {
        if (path.isEmpty() || player == null) return -1;
        double bestD2 = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            double dx = p.getX() + 0.5 - player.getX();
            double dz = p.getZ() + 0.5 - player.getZ();
            double dy = p.getY() - player.getY();
            double d2 = dx * dx + dy * dy * 0.25 + dz * dz;
            if (d2 < bestD2) { bestD2 = d2; bestIdx = i; }
        }
        return bestIdx;
    }

    /**
     * プレイヤーがパスから大幅に逸脱しているか (= 自動再計算が必要)。
     */
    public static boolean playerOffPath(net.minecraft.world.entity.player.Player player) {
        if (path.isEmpty() || player == null) return false;
        int idx = nearestPathIndex(player);
        if (idx < 0) return false;
        BlockPos near = path.get(idx);
        double dx = near.getX() + 0.5 - player.getX();
        double dz = near.getZ() + 0.5 - player.getZ();
        double dy = near.getY() - player.getY();
        double d2 = dx * dx + dz * dz + dy * dy * 0.25;
        // 12 ブロック以上離れたら逸脱
        return d2 > 144;
    }

    /** 自動再計算リクエスト送信 (1 度送ったら 5 秒は再送しない)。 */
    private static volatile long lastAutoRequestNanos = 0;
    public static boolean shouldAutoRecalc() {
        long now = System.nanoTime();
        if (now - lastAutoRequestNanos < 5_000_000_000L) return false;
        lastAutoRequestNanos = now;
        return true;
    }
}
