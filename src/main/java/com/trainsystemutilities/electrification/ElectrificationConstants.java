package com.trainsystemutilities.electrification;

/**
 * FE 電化システム全体で参照する定数。
 *
 * <p>Phase 21 (FE 電化)。サブシステム間で共有するチューニング値の置き場。
 * 個々のフェーズ内部でしか使わない値はここに置かず、各クラスに留めること。
 */
public final class ElectrificationConstants {
    private ElectrificationConstants() {}

    // ===== 架線 (wire) =====
    /** 1 接続あたりの最大長 (碍子間距離)。 */
    public static final double MAX_WIRE_LENGTH = 32.0;
    /** 最小長 (= 同じ碍子に接続するのを防ぐ)。 */
    public static final double MIN_WIRE_LENGTH = 0.5;
    /** 碍子ポスト先端のオフセット (ブロック中心からの距離)。 */
    public static final double INSULATOR_TIP_OFFSET = 0.4;

    // ===== 誘導感電 =====
    /** 通電中の架線から何ブロック以内で感電するか。 */
    public static final double INDUCTION_RANGE = 1.0;
    /** 感電判定の tick 間隔 (= 0.5s)。 */
    public static final int INDUCTION_TICK_INTERVAL = 10;
    /** 感電 1 回あたりのダメージ (HP 単位、0.5 = 1 ハート)。 */
    public static final float INDUCTION_DAMAGE = 1.0F;

    // ===== 集電 (パンタグラフ) =====
    /** パンタグラフから架線までの最大集電距離 (ブロック)。 */
    public static final double PANTOGRAPH_PICKUP_RANGE = 0.6;
}
