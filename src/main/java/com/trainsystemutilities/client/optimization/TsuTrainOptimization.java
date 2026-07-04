package com.trainsystemutilities.client.optimization;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 23: Create 列車の Flywheel 描画パスを最適化するためのランタイム設定 + 統計ホルダ。
 *
 * <p>Flywheel が無効 (Iris シェーダ有効 / {@code /flywheel backend off} / 未対応 GPU) の場合、
 * Create は {@code ContraptionEntityRenderer} の immediate path に流れ、ここで対象とする
 * {@code CarriageContraptionVisual} / {@code StandardBogeyVisual} は一切生成されない。
 * したがって本クラスの設定が ON のままでも Mixin が実行経路に入らず、安全に no-op になる。
 *
 * <p>サーバ JVM では一切参照されない (Mixin が client-only)。
 */
@OnlyIn(Dist.CLIENT)
public final class TsuTrainOptimization {

    private TsuTrainOptimization() {}

    /** マスタースイッチ。OFF にすると以下の全最適化が即座に無効化される。 */
    private static volatile boolean enabled = readBoolProp("tsu.optimization.enabled", true);

    /** Carriage 単位の frustum 早期 return。視界外列車の {@code animate()} を丸ごとスキップ。 */
    private static volatile boolean frustumCull = readBoolProp("tsu.optimization.frustum_cull", true);

    /** 視界外判定に使う AABB の inflate 量 (ブロック)。bogey はやや車体外にはみ出るため余裕を持つ。 */
    private static volatile double frustumCullPadding = readDoubleProp("tsu.optimization.frustum_padding", 2.0);

    /** Bogey の {@code update()} 差分キャッシュ。前フレームと行列+wheelAngle が完全一致なら GPU upload 省略。 */
    private static volatile boolean bogeyDiffCache = readBoolProp("tsu.optimization.bogey_cache", true);

    /** 1 秒に 1 回程度ログを吐く診断モード。 */
    private static volatile boolean debugLog = readBoolProp("tsu.optimization.debug_log", false);

    public static boolean isEnabled() { return enabled; }
    public static boolean isFrustumCullEnabled() { return enabled && frustumCull; }
    public static boolean isBogeyDiffCacheEnabled() { return enabled && bogeyDiffCache; }
    public static double frustumCullPadding() { return frustumCullPadding; }
    public static boolean isDebugLog() { return debugLog; }

    public static void setEnabled(boolean v) { enabled = v; }
    public static void setFrustumCull(boolean v) { frustumCull = v; }
    public static void setBogeyDiffCache(boolean v) { bogeyDiffCache = v; }
    public static void setDebugLog(boolean v) { debugLog = v; }

    /** ランタイム統計。スレッド非依存に increment できれば十分なので AtomicLong。 */
    public static final class Stats {
        public final AtomicLong frustumSkips = new AtomicLong();
        public final AtomicLong frustumPasses = new AtomicLong();
        public final AtomicLong bogeyCacheHits = new AtomicLong();
        public final AtomicLong bogeyCacheMisses = new AtomicLong();

        public void reset() {
            frustumSkips.set(0);
            frustumPasses.set(0);
            bogeyCacheHits.set(0);
            bogeyCacheMisses.set(0);
        }
    }

    public static final Stats STATS = new Stats();

    private static boolean readBoolProp(String key, boolean defVal) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return defVal;
        return Boolean.parseBoolean(v);
    }

    private static double readDoubleProp(String key, double defVal) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return defVal;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return defVal;
        }
    }
}
