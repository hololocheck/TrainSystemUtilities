package com.trainsystemutilities.station;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * (fromGroupId, toGroupId) ペアごとに「実走所要 ticks の指数移動平均 (EMA)」を保持する
 * server-side savedData。
 *
 * <p>学習則:
 * <ul>
 *   <li>α = 0.2 (新サンプル 20%、過去 80%)</li>
 *   <li>初回サンプルは ema = sample でブートストラップ</li>
 *   <li>標本分散は Welford 法で incremental に更新</li>
 *   <li>sampleCount ≥ 5 で「学習済み」扱い、それ以下はキネマティクス推定にフォールバック</li>
 * </ul>
 *
 * <p>Listener ({@link com.trainsystemutilities.station.LegMeasurementListener}) がこのストアに
 * `record(...)` を呼んでサンプルを追加する。
 */
public final class SegmentStatsStore extends SavedData {

    public static final String DATA_NAME = "tsu_segment_stats";
    private static final double EMA_ALPHA = 0.2;
    private static final int MIN_SAMPLES_TRUSTED = 5;

    /** 時刻帯バケット数 (0=朝 6-12, 1=昼 12-18, 2=夕 18-24, 3=夜 0-6)。 */
    public static final int TIME_BUCKETS = 4;

    /** dayTime (0..23999) からバケット index を返す。MC dayTime 0 = 6:00。 */
    public static int bucketOf(long dayTime) {
        long hours = ((dayTime % 24000L + 24000L) % 24000L) / 1000L;
        // MC dayTime 0 = 6 AM. 1000 ticks = 1 hour.
        long realHour = (hours + 6) % 24;
        if (realHour >= 6 && realHour < 12) return 0; // 朝
        if (realHour >= 12 && realHour < 18) return 1; // 昼
        if (realHour >= 18 && realHour < 24) return 2; // 夕
        return 3; // 夜
    }

    public static final class Stats {
        public double emaTicks;
        public double mean;
        public double m2;
        public int sampleCount;
        public long lastObservedDayTime;
        public int scheduleHash;
        public double dwellEma;
        public int dwellSampleCount;
        /** 時刻帯別 EMA (4 バケット)。-1 = 未学習。 */
        public final double[] bucketEma = new double[TIME_BUCKETS];
        public final int[] bucketSampleCount = new int[TIME_BUCKETS];

        Stats() {}
        Stats(double emaTicks, double mean, double m2, int sampleCount, long lastObservedDayTime,
              int scheduleHash, double dwellEma, int dwellSampleCount,
              double[] bucketEma, int[] bucketSampleCount) {
            this.emaTicks = emaTicks;
            this.mean = mean;
            this.m2 = m2;
            this.sampleCount = sampleCount;
            this.lastObservedDayTime = lastObservedDayTime;
            this.scheduleHash = scheduleHash;
            this.dwellEma = dwellEma;
            this.dwellSampleCount = dwellSampleCount;
            if (bucketEma != null && bucketEma.length == TIME_BUCKETS)
                System.arraycopy(bucketEma, 0, this.bucketEma, 0, TIME_BUCKETS);
            if (bucketSampleCount != null && bucketSampleCount.length == TIME_BUCKETS)
                System.arraycopy(bucketSampleCount, 0, this.bucketSampleCount, 0, TIME_BUCKETS);
        }

        /** 指定 bucket の EMA がトレーニング済か。 */
        public boolean bucketTrusted(int bucket) {
            return bucket >= 0 && bucket < TIME_BUCKETS && bucketSampleCount[bucket] >= 3;
        }
        public double bucketEmaOrFallback(int bucket) {
            if (bucketTrusted(bucket)) return bucketEma[bucket];
            return emaTicks;
        }
        public double variance() { return sampleCount < 2 ? 0 : m2 / (sampleCount - 1); }
        public double stdDev() { return Math.sqrt(variance()); }
        public boolean isTrusted() { return sampleCount >= MIN_SAMPLES_TRUSTED; }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("ema", emaTicks);
            t.putDouble("mean", mean);
            t.putDouble("m2", m2);
            t.putInt("n", sampleCount);
            t.putLong("last", lastObservedDayTime);
            t.putInt("schash", scheduleHash);
            t.putDouble("dwellEma", dwellEma);
            t.putInt("dwellN", dwellSampleCount);
            for (int i = 0; i < TIME_BUCKETS; i++) {
                t.putDouble("bEma" + i, bucketEma[i]);
                t.putInt("bN" + i, bucketSampleCount[i]);
            }
            return t;
        }
        public static Stats load(CompoundTag t) {
            double[] bEma = new double[TIME_BUCKETS];
            int[] bN = new int[TIME_BUCKETS];
            for (int i = 0; i < TIME_BUCKETS; i++) {
                bEma[i] = t.getDouble("bEma" + i);
                bN[i] = t.getInt("bN" + i);
            }
            return new Stats(
                    t.getDouble("ema"), t.getDouble("mean"),
                    t.getDouble("m2"), t.getInt("n"), t.getLong("last"),
                    t.getInt("schash"), t.getDouble("dwellEma"), t.getInt("dwellN"),
                    bEma, bN);
        }
    }

    /** Key = "fromUUID|toUUID"。 */
    private final Map<String, Stats> map = new HashMap<>();

    public SegmentStatsStore() {}

    public static SegmentStatsStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SegmentStatsStore::new, SegmentStatsStore::load),
                DATA_NAME);
    }

    private static String key(UUID from, UUID to) {
        return from + "|" + to;
    }

    public Stats get(UUID from, UUID to) {
        if (from == null || to == null) return null;
        return map.get(key(from, to));
    }

    /**
     * 新サンプル ticks を記録。スケジュールハッシュが変わっていたら自動リセット。
     * 時刻帯バケット (Phase D) も同時に更新。
     */
    public void record(UUID from, UUID to, int observedTicks, long dayTime, int scheduleHash) {
        if (from == null || to == null || observedTicks <= 0) return;
        String k = key(from, to);
        Stats s = map.computeIfAbsent(k, kk -> new Stats());
        if (s.scheduleHash != 0 && s.scheduleHash != scheduleHash) {
            s.emaTicks = 0; s.mean = 0; s.m2 = 0; s.sampleCount = 0;
            s.dwellEma = 0; s.dwellSampleCount = 0;
            for (int i = 0; i < TIME_BUCKETS; i++) {
                s.bucketEma[i] = 0; s.bucketSampleCount[i] = 0;
            }
        }
        s.scheduleHash = scheduleHash;
        if (s.sampleCount == 0) {
            s.emaTicks = observedTicks;
            s.mean = observedTicks;
            s.m2 = 0;
        } else {
            s.emaTicks = EMA_ALPHA * observedTicks + (1 - EMA_ALPHA) * s.emaTicks;
            double delta = observedTicks - s.mean;
            s.mean += delta / (s.sampleCount + 1);
            double delta2 = observedTicks - s.mean;
            s.m2 += delta * delta2;
        }
        s.sampleCount++;
        s.lastObservedDayTime = dayTime;
        // バケット更新
        int b = bucketOf(dayTime);
        if (s.bucketSampleCount[b] == 0) s.bucketEma[b] = observedTicks;
        else s.bucketEma[b] = EMA_ALPHA * observedTicks + (1 - EMA_ALPHA) * s.bucketEma[b];
        s.bucketSampleCount[b]++;
        setDirty();
    }

    /** 駅 from の停車時間 ticks を学習 (Listener の dwell tracking から)。 */
    public void recordDwell(UUID from, UUID to, int dwellTicks, long dayTime) {
        if (from == null || to == null || dwellTicks <= 0) return;
        String k = key(from, to);
        Stats s = map.computeIfAbsent(k, kk -> new Stats());
        if (s.dwellSampleCount == 0) {
            s.dwellEma = dwellTicks;
        } else {
            s.dwellEma = EMA_ALPHA * dwellTicks + (1 - EMA_ALPHA) * s.dwellEma;
        }
        s.dwellSampleCount++;
        s.lastObservedDayTime = dayTime;
        setDirty();
    }

    /** 旧 API 互換 (scheduleHash 無視)。新規コードは別 overload を使うこと。 */
    public void record(UUID from, UUID to, int observedTicks, long dayTime) {
        record(from, to, observedTicks, dayTime, 0);
    }

    /** P0-5 #3: NBT schema version。 将来の format 変更で migration 可能にする。 */
    private static final int SCHEMA_VERSION = 1;

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (var e : map.entrySet()) {
            CompoundTag t = e.getValue().save();
            t.putString("k", e.getKey());
            list.add(t);
        }
        tag.put("entries", list);
        return tag;
    }

    public static SegmentStatsStore load(CompoundTag tag, HolderLookup.Provider registries) {
        // P0-5 #3: schema version 検査 (= 旧 world は schemaVersion 欠落 → 0 扱いで legacy load)
        int version = tag.contains("schemaVersion") ? tag.getInt("schemaVersion") : 0;
        if (version > SCHEMA_VERSION) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                    "[SegmentStatsStore] future schemaVersion={} (current={}); best-effort load",
                    version, SCHEMA_VERSION);
        }
        SegmentStatsStore store = new SegmentStatsStore();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            String k = t.getString("k");
            store.map.put(k, Stats.load(t));
        }
        return store;
    }
}
