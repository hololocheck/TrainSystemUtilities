package com.trainsystemutilities.profiler;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 100編成スケール最適化のためのプロファイラ (Tier 5)。
 *
 * <p>各 phase の {@code count / total / avg / max} を記録する低オーバーヘッド計測器。
 * 無効時は {@link #start()} が即座に 0 を返すため、本番環境でも有効化しない限り
 * オーバーヘッドはほぼゼロ。
 *
 * <p>使用例:
 * <pre>
 *   long t0 = TsuProfiler.start();
 *   try {
 *       // ... heavy work ...
 *   } finally {
 *       TsuProfiler.end(TsuProfiler.Phase.TSU_MEASURE, t0);
 *   }
 * </pre>
 *
 * <p>サーバ・クライアント共通で利用可能。
 */
public final class TsuProfiler {

    /** 計測対象 phase。サーバ側 + クライアント側両方を含む。 */
    public enum Phase {
        // === Server-side ===
        SERVER_TICK("Server tick total"),
        TRAIN_TICK("Create Train.tick (per train)"),
        TSU_COUPLING("TSU coupling tick (process pending)"),
        TSU_MEASURE("TSU measureTrainTravelTimes (per BE call)"),
        TSU_ESTIMATE("TSU estimateArrivalDayTime (per BE call)"),
        TSU_MONITOR_TICK("TSU MonitorMovementBehaviour.tick (per contraption)"),
        // === Client-side ===
        MONITOR_RENDER("Monitor renderInContraption (per frame)"),
        MONITOR_DOM_BUILD("Monitor buildDOM (per frame)"),
        ;
        public final String label;
        Phase(String label) { this.label = label; }
    }

    /** Phase ごとの計測結果。AtomicLong で lock-free 同時更新可。 */
    public static final class PhaseStats {
        public final AtomicLong count = new AtomicLong();
        public final AtomicLong totalNanos = new AtomicLong();
        public final AtomicLong maxNanos = new AtomicLong();

        public void record(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        public void reset() {
            count.set(0);
            totalNanos.set(0);
            maxNanos.set(0);
        }

        public double avgMicros() {
            long c = count.get();
            return c > 0 ? totalNanos.get() / 1000.0 / c : 0;
        }

        public double totalMillis() {
            return totalNanos.get() / 1_000_000.0;
        }

        public double maxMicros() {
            return maxNanos.get() / 1000.0;
        }
    }

    private static final EnumMap<Phase, PhaseStats> STATS = new EnumMap<>(Phase.class);
    static {
        for (Phase p : Phase.values()) STATS.put(p, new PhaseStats());
    }

    private static volatile boolean enabled = false;
    private static volatile long startedAt = 0;

    public static void enable() {
        reset();
        startedAt = System.nanoTime();
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void reset() {
        for (PhaseStats s : STATS.values()) s.reset();
        startedAt = System.nanoTime();
    }

    /**
     * 計測開始。無効時は 0 を返し、後続の {@link #end} は no-op になる。
     * @return 開始 nanoTime (= 0 なら計測無効)
     */
    public static long start() {
        return enabled ? System.nanoTime() : 0;
    }

    /** 計測終了。{@code startNanos == 0} なら何もしない。 */
    public static void end(Phase phase, long startNanos) {
        if (startNanos == 0) return;
        long elapsed = System.nanoTime() - startNanos;
        STATS.get(phase).record(elapsed);
    }

    public static PhaseStats stats(Phase phase) {
        return STATS.get(phase);
    }

    public static long elapsedNanos() {
        return startedAt > 0 ? System.nanoTime() - startedAt : 0;
    }

    /** 人間可読なレポート (in-game / log 用) */
    public static String report() {
        if (startedAt == 0) return "Profiler never started. Use /tsu profile start.";
        double elapsedSec = elapsedNanos() / 1_000_000_000.0;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== TSU Profiler Report (%.2fs, %s) ===%n",
                elapsedSec, enabled ? "RUNNING" : "STOPPED"));
        sb.append(String.format("%-50s %10s %12s %12s %12s%n",
                "Phase", "Count", "Total(ms)", "Avg(us)", "Max(us)"));
        sb.append("-".repeat(98)).append("\n");
        for (Phase phase : Phase.values()) {
            PhaseStats s = STATS.get(phase);
            long count = s.count.get();
            if (count == 0) continue;
            sb.append(String.format("%-50s %10d %12.2f %12.1f %12.1f%n",
                    phase.label, count, s.totalMillis(), s.avgMicros(), s.maxMicros()));
        }
        return sb.toString();
    }

    private TsuProfiler() {}
}
