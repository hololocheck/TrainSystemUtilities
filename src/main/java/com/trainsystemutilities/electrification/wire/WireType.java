package com.trainsystemutilities.electrification.wire;

import net.minecraft.network.chat.Component;

/**
 * 架線のデザイン種別。{@link WireConnection} に紐付けて、レンダラがタイプ別に描画する。
 *
 * <p>新規タイプを追加する際は:
 * <ol>
 *   <li>このenumに値追加</li>
 *   <li>{@code CatenaryRenderer} の dispatch に分岐追加</li>
 *   <li>必要なら lang 翻訳キー追加</li>
 * </ol>
 *
 * <p>NBT/payload では {@link #ordinal()} ではなく {@link #id} を使う (= ordinal は順序変更で破綻するため)。
 */
public enum WireType {

    /** シンプル黒線 1 本。装飾用または信号系。 */
    SIMPLE(0, "simple", "tsu.wire_type.simple"),

    /** 標準二段 (吊架線 + トロリ線 + dropper)。 */
    TWO_TIER(1, "two_tier", "tsu.wire_type.two_tier"),

    /** 二段が左右に 2 セット並列 (= 複線分)。 */
    TWIN_2ROW(2, "twin_2row", "tsu.wire_type.twin_2row"),

    /** 高所オフセット (= トロリ線をさらに低く配置、大型車両用)。 */
    HIGH_OFFSET(3, "high_offset", "tsu.wire_type.high_offset"),

    /** カスタム: 太さ・トロリオフセット・ドロッパ間隔・列数を接続ごとに自由設定。
     *  {@link WireConnection} の custom* フィールドに値が保存され、{@code CatenaryRenderer} が
     *  それを参照して描画する。 */
    CUSTOM(4, "custom", "tsu.wire_type.custom");

    /** NBT/payload 安定 ID。enum 順序変更に依存しない。 */
    public final int id;
    /** リソース等で使う英語名。 */
    public final String key;
    /** 翻訳キー。 */
    public final String langKey;

    WireType(int id, String key, String langKey) {
        this.id = id;
        this.key = key;
        this.langKey = langKey;
    }

    public Component displayName() {
        return Component.translatable(langKey);
    }

    public static WireType fromId(int id) {
        for (WireType t : values()) {
            if (t.id == id) return t;
        }
        return TWO_TIER; // フォールバック
    }

    public WireType next() {
        WireType[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    public WireType prev() {
        WireType[] all = values();
        return all[(this.ordinal() + all.length - 1) % all.length];
    }
}
