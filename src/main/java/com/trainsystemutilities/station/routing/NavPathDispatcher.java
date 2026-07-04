package com.trainsystemutilities.station.routing;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * 経路探索の非同期ディスパッチャ。
 *
 * <p>サーバーの tick スレッドをブロックしないように、専用のワーカープールで
 * {@link StationWalkTargetSelector#findPathToGroup} を実行する。
 *
 * <p>機能:
 * <ul>
 *   <li>固定ワーカー数 (2) — 並列度 + 安定性のバランス</li>
 *   <li>プレイヤー毎の進行中タスクを保持。新規リクエストは旧タスクを cancel</li>
 *   <li>5 秒タイムアウト (それ以上かかる経路は失敗扱い)</li>
 *   <li>結果は server 側 callback (主スレッド復帰後に送信用)</li>
 * </ul>
 */
public final class NavPathDispatcher {

    private static final int WORKER_COUNT = 2;
    private static final long TIMEOUT_MS = 45_000;

    private static ExecutorService newExecutor() {
        return Executors.newFixedThreadPool(WORKER_COUNT, r -> {
            Thread t = new Thread(r, "TSU-NavPath-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    // non-final: 統合サーバを同一 JVM で開き直すと shutdown() でプールが終了するため、
    // 停止時に新しいプールへ差し替えて以後の submit() が拒否されないようにする。
    private static volatile ExecutorService EXECUTOR = newExecutor();

    /** プレイヤー UUID → 現在実行中の Future。新リクエストで cancel。 */
    private static final ConcurrentMap<UUID, Future<?>> PENDING = new ConcurrentHashMap<>();

    private NavPathDispatcher() {}

    /** 結果コールバック (サーバ側 main thread にディスパッチされる)。 */
    @FunctionalInterface
    public interface ResultCallback {
        void onResult(StationWalkTargetSelector.PathResult result, String error);
    }

    /**
     * 非同期で経路探索を発行。直前のリクエストがあれば cancel する。
     */
    public static void submit(ServerPlayer player, ServerLevel level, BlockPos start,
                               StationGroup group, int platform, ResultCallback callback) {
        if (player == null) return;
        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile().getName();
        // 旧タスク cancel
        Future<?> prev = PENDING.remove(playerId);
        if (prev != null && !prev.isDone()) {
            prev.cancel(true);
            TrainSystemUtilities.LOGGER.info("[NavPath] cancelled previous request for {}", playerName);
        }
        TrainSystemUtilities.LOGGER.info("[NavPath] dispatching to worker player={} group={} platform={}",
                playerName, group.id(), platform);

        MinecraftServer server = player.server;
        Future<?> future = EXECUTOR.submit(() -> {
            long start_ns = System.nanoTime();
            try {
                TrainSystemUtilities.LOGGER.info("[NavPath] worker START player={} thread={}",
                        playerName, Thread.currentThread().getName());
                StationWalkTargetSelector.PathResult result =
                        StationWalkTargetSelector.findPathToGroup(level, start, group, platform);
                long elapsedMs = (System.nanoTime() - start_ns) / 1_000_000L;
                if (Thread.currentThread().isInterrupted()) {
                    TrainSystemUtilities.LOGGER.info("[NavPath] worker INTERRUPTED player={} elapsed={}ms",
                            playerName, elapsedMs);
                    server.execute(() -> callback.onResult(null, "cancelled"));
                    return;
                }
                if (elapsedMs > TIMEOUT_MS) {
                    TrainSystemUtilities.LOGGER.warn("[NavPath] TIMEOUT {}ms (>{}ms) player={} reachable={}",
                            elapsedMs, TIMEOUT_MS, playerName, result == null ? "null" : result.reachable());
                    server.execute(() -> callback.onResult(null, "timeout"));
                    return;
                }
                TrainSystemUtilities.LOGGER.info("[NavPath] worker DONE player={} elapsed={}ms reachable={}",
                        playerName, elapsedMs, result == null ? "null" : result.reachable());
                server.execute(() -> callback.onResult(result, null));
            } catch (Throwable t) {
                long elapsedMs = (System.nanoTime() - start_ns) / 1_000_000L;
                TrainSystemUtilities.LOGGER.error("[NavPath] worker FAILED player={} elapsed={}ms: {}",
                        playerName, elapsedMs, t.toString(), t);
                server.execute(() -> callback.onResult(null, "internal error: " + t.getClass().getSimpleName()));
            } finally {
                PENDING.remove(playerId);
                TrainSystemUtilities.LOGGER.debug("[NavPath] worker exit player={}", playerName);
            }
        });
        PENDING.put(playerId, future);
    }

    /** プレイヤー切断時の cleanup (任意呼び出し)。 */
    public static void cancelFor(UUID playerId) {
        Future<?> f = PENDING.remove(playerId);
        if (f != null && !f.isDone()) f.cancel(true);
    }

    /**
     * サーバ停止時の終了処理。
     *
     * <p>進行中タスクを cancel し、 ワーカープールを終了する。 統合サーバを同一 JVM で
     * 開き直すケースに備えて、 終了後に新しいプールへ差し替える (= 次セッションの submit()
     * が {@code RejectedExecutionException} にならないようにする)。
     */
    public static void shutdown() {
        for (Future<?> f : PENDING.values()) {
            if (!f.isDone()) f.cancel(true);
        }
        PENDING.clear();
        EXECUTOR.shutdownNow();
        EXECUTOR = newExecutor();
    }
}
