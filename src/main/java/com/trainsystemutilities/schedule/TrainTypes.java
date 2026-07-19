package com.trainsystemutilities.schedule;

import net.minecraft.network.chat.Component;

/**
 * 列車種別の単一情報源 (コード / 循環順 / lang key / 表示色)。
 *
 * <p>2026-07-18 以前は {@code TrainScheduleReader.detectTrainType} が停車駅比率から種別を
 * 自動判定していたが、 分母が「同一 {@code TrackGraph} 上の全駅数」だったため路線が大きいほど
 * 特急に寄り、 実質すべての列車が特急表示になっていた。 自動判定は廃止し、 種別は
 * 管理用コンピューターの電子式時刻表タブで **列車ごとに手動設定**する
 * ({@link TrainTypeState} が server-global な source of truth)。
 *
 * <p>色は BelugaExperience §7 の標準 palette のみを使う (R7.1)。
 */
public final class TrainTypes {

    private TrainTypes() {}

    /** 未設定。 バッジは描画しない (= 従来の空文字と同じ扱い)。 */
    public static final String NONE = "";

    /**
     * ホイール循環順 (遅い → 速い)。 index 0 は未設定。
     *
     * <p>注意: {@code EXPRESS} は自動判定時代は「特急」だったが、 8 種別化で本来の
     * 「急行」に戻し、 特急は {@code LIMITED_EXPRESS} を新設した。 旧セーブに残る
     * {@code EXPRESS} は到着列車の表示キャッシュ NBT にしか存在せず、 次回スキャンで
     * 上書きされるため移行処理は不要。
     */
    private static final String[] ORDER = {
            NONE,
            "LOCAL",            // 普通
            "RAPID",            // 快速
            "SECTION_RAPID",    // 区間快速
            "COMMUTER_RAPID",   // 通勤快速
            "SEMI_EXPRESS",     // 準急
            "EXPRESS",          // 急行
            "LIMITED_EXPRESS",  // 特急
    };

    public static int count() { return ORDER.length; }

    /** 設定済みか (= バッジを描画するか)。 */
    public static boolean isSet(String code) { return code != null && !code.isEmpty(); }

    /** 循環順の index。 未知コードは 0 (未設定) 扱い。 */
    public static int indexOf(String code) {
        if (code == null) return 0;
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(code)) return i;
        }
        return 0;
    }

    /** index → コード。 範囲外は未設定。 */
    public static String byIndex(int index) {
        return (index < 0 || index >= ORDER.length) ? NONE : ORDER[index];
    }

    /** {@code dir} 段ぶん循環させたコードを返す (R4.13.0.8 の {@code ((idx+dir)%n+n)%n})。 */
    public static String cycle(String code, int dir) {
        int n = ORDER.length;
        return ORDER[((indexOf(code) + dir) % n + n) % n];
    }

    /** 表示名の lang key。 未設定は管理 UI 用の「未設定」ラベル。 */
    public static String langKey(String code) {
        if (!isSet(code)) return "tsu.mc.train_type_none";
        return switch (code) {
            case "LOCAL" -> "tsu.monitor.train_type_local";
            case "RAPID" -> "tsu.monitor.train_type_rapid";
            case "SECTION_RAPID" -> "tsu.monitor.train_type_section_rapid";
            case "COMMUTER_RAPID" -> "tsu.monitor.train_type_commuter_rapid";
            case "SEMI_EXPRESS" -> "tsu.monitor.train_type_semi_express";
            case "EXPRESS" -> "tsu.monitor.train_type_express";
            case "LIMITED_EXPRESS" -> "tsu.monitor.train_type_limited_express";
            default -> "tsu.mc.train_type_none";
        };
    }

    /** ローカライズ済み表示名。 未設定は空文字 (= バッジに何も出さない)。 */
    public static String localize(String code) {
        if (!isSet(code)) return "";
        return Component.translatable(langKey(code)).getString();
    }

    /** 管理 UI 用: 未設定でも「未設定」と表示する。 */
    public static String localizeForEditor(String code) {
        return Component.translatable(langKey(code)).getString();
    }

    /** バッジ文字色 (BelugaExperience §7 palette)。 */
    public static int colorArgb(String code) {
        return switch (code == null ? "" : code) {
            case "LOCAL" -> 0xFFE0E0E0;           // 明灰
            case "RAPID" -> 0xFF4FC3F7;           // cyan
            case "SECTION_RAPID" -> 0xFF80DEEA;   // cyan inactive
            case "COMMUTER_RAPID" -> 0xFF80DEEA;  // cyan inactive
            case "SEMI_EXPRESS" -> 0xFF66BB6A;    // green
            case "EXPRESS" -> 0xFFFFD54F;         // yellow
            case "LIMITED_EXPRESS" -> 0xFFEF5350; // red
            default -> 0xFF888888;                // 暗灰 (未設定 — 通常は描画されない)
        };
    }
}
