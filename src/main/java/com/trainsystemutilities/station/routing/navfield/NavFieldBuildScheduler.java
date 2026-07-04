package com.trainsystemutilities.station.routing.navfield;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NavField のバックグラウンド構築スケジューラ (Phase D-1: 並列化版)。
 *
 * <p>各 platform の field を **並列** で構築 (= 4 worker)。1 駅 4 番線 ≈ 15-20s で完了。
 */
public final class NavFieldBuildScheduler {

    /** 並列度 4。worker thread が並走して各 platform を独立に build。 */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "TSU-NavField-Builder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });

    /** group ごとの実行中 Future 群 (= 連続呼出で前回キャンセル)。 */
    private static final ConcurrentMap<UUID, List<Future<?>>> PENDING = new ConcurrentHashMap<>();

    private NavFieldBuildScheduler() {}

    public static void scheduleAll(ServerLevel level, StationGroup group, ServerPlayer player) {
        if (group == null || group.stationBlockPositions().isEmpty()) return;
        UUID groupId = group.id();
        cancelFor(groupId);

        int n = group.stationBlockPositions().size();
        long startNanos = System.nanoTime();
        AtomicInteger doneCounter = new AtomicInteger(0);
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger skippedCounter = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int platform = i + 1;
            Future<?> f = EXECUTOR.submit(() -> {
                if (Thread.currentThread().isInterrupted()) return;
                if (NavFieldCache.get(level, groupId, platform) != null) {
                    skippedCounter.incrementAndGet();
                } else {
                    try {
                        var br = NavFieldBuilder.build(level, group, platform);
                        if (br.field() != null) {
                            NavFieldCache.put(level, br.field());
                            successCounter.incrementAndGet();
                            notifyProgress(player, group.name(), platform, n,
                                    br.elapsedMs(), br.field().cellCount());
                        }
                    } catch (Throwable t) {
                        TrainSystemUtilities.LOGGER.error(
                                "[NavFieldScheduler] platform={} build FAILED: {}",
                                platform, t.toString(), t);
                    }
                }
                int done = doneCounter.incrementAndGet();
                if (done == n) {
                    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    TrainSystemUtilities.LOGGER.info(
                            "[NavFieldScheduler] DONE group={} {}/{} built (skipped={}) in {}ms (parallel)",
                            groupId, successCounter.get(), n, skippedCounter.get(), elapsedMs);
                    notifyComplete(player, group.name(),
                            successCounter.get(), n, elapsedMs);
                    PENDING.remove(groupId);
                }
            });
            futures.add(f);
        }
        PENDING.put(groupId, futures);
    }

    private static void notifyProgress(ServerPlayer player, String groupName,
                                        int platform, int total, long ms, int cells) {
        if (player == null) return;
        String msg = String.format("解析中 [%s]: %d/%d 番線 完了 (%dcells %dms)",
                groupName, platform, total, cells, ms);
        player.server.execute(() -> player.displayClientMessage(
                Component.literal(msg).withStyle(ChatFormatting.GRAY), true));
    }

    private static void notifyComplete(ServerPlayer player, String groupName,
                                        int success, int total, long ms) {
        if (player == null) return;
        String msg = String.format("駅 '%s' 経路解析完了: %d/%d 番線 (%d秒, 並列)",
                groupName, success, total, ms / 1000);
        player.server.execute(() -> player.displayClientMessage(
                Component.literal(msg).withStyle(ChatFormatting.AQUA), false));
    }

    public static void cancelFor(UUID groupId) {
        if (groupId == null) return;
        List<Future<?>> futures = PENDING.remove(groupId);
        if (futures != null) {
            for (Future<?> f : futures) {
                if (!f.isDone()) f.cancel(true);
            }
            TrainSystemUtilities.LOGGER.info(
                    "[NavFieldScheduler] cancelled {} prior tasks for group={}",
                    futures.size(), groupId);
        }
    }

    /** server stop 時: 全 group の進行中タスクを cancel する。 executor 自体は (統合サーバ
     *  再オープンに備え) 生かしたままにする — 旧 ServerLevel を掴んだタスクの継続だけを止める。 */
    public static void cancelAll() {
        for (UUID groupId : new ArrayList<>(PENDING.keySet())) {
            cancelFor(groupId);
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
