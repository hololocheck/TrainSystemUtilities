package com.trainsystemutilities.eta;

/**
 * ETA 推定の純数値計算ユーティリティ (異常値拒絶付き EWMA)。
 *
 * <p>B4: {@code RailwayManagementBlockEntity} から切り出し、unit test 可能化した。Minecraft 非依存。
 */
public final class EtaMath {

    private EtaMath() {}

    /** EWMA 更新結果: accepted=true なら value は新値、false なら既存値 (異常値で拒絶)。 */
    public record EwmaLong(long value, boolean accepted) {}

    /** EWMA 更新結果 (double 版)。 */
    public record EwmaDouble(double value, boolean accepted) {}

    /**
     * 案E3: 異常値拒絶付き EWMA 更新 (long)。
     * existing が null/0 のときはブートストラップ (sample をそのまま採用)。
     * sample/existing 比が [1/maxRatio, maxRatio] 外なら拒絶 (= 既存値を返す)。
     */
    public static EwmaLong applyEwmaLong(Long existing, long sample, double alpha, double maxRatio) {
        if (existing == null || existing <= 0) return new EwmaLong(sample, true);
        double ratio = (double) sample / existing;
        if (ratio < 1.0 / maxRatio || ratio > maxRatio) {
            return new EwmaLong(existing, false);
        }
        return new EwmaLong(Math.round(existing * (1.0 - alpha) + sample * alpha), true);
    }

    /** 案E3: 異常値拒絶付き EWMA 更新 (double)。 */
    public static EwmaDouble applyEwmaDouble(Double existing, double sample, double alpha, double maxRatio) {
        if (existing == null || existing <= 0) return new EwmaDouble(sample, true);
        double ratio = sample / existing;
        if (ratio < 1.0 / maxRatio || ratio > maxRatio) {
            return new EwmaDouble(existing, false);
        }
        return new EwmaDouble(existing * (1.0 - alpha) + sample * alpha, true);
    }
}
