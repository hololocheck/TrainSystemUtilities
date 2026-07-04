package com.trainsystemutilities.eta;

/**
 * 1 サンプルずつ平均と分散を数値安定に更新する Welford アルゴリズム。
 *
 * <p>B4: {@code RailwayManagementBlockEntity} から純 Java として切り出し、unit test 可能化した
 * (= 区間走行時間の variance 追跡に使う、Minecraft 非依存の数値計算)。
 */
public final class WelfordStats {

    public double mean = 0;
    public double m2 = 0;
    public long count = 0;

    /** 1 サンプルを取り込んで mean / m2 / count を更新する。 */
    public void update(double sample) {
        count++;
        double delta = sample - mean;
        mean += delta / count;
        double delta2 = sample - mean;
        m2 += delta * delta2;
    }

    /** 標本標準偏差 (count &lt;= 1 なら 0)。 */
    public double stddev() {
        return count > 1 ? Math.sqrt(m2 / (count - 1)) : 0;
    }
}
