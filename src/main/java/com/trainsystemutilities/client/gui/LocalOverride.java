package com.trainsystemutilities.client.gui;

/**
 * Settings popup の "ローカルオーバーライド" 値を保持するシンプルなジェネリック holder。
 *
 * <p>RM/Poster の settings popup では、ユーザがホイールやボタンで値を変えると即座に
 * BE に書き込まず、ローカルに保持して BE 更新のラウンドトリップ遅延を吸収する。
 * その後 BE が更新されたら local 値を破棄して BE を見るように戻す。
 *
 * <p>従来は {@code private Boolean localBatchApply = null;} のように nullable 型で
 * 各フィールドを実装していたが、{@code null} 判定 + getter 三項演算子のボイラープレートが
 * 重複していた。本クラスでこれを集約。
 *
 * <p>使用例:
 * <pre>{@code
 * private final LocalOverride<Boolean> localBatch = new LocalOverride<>();
 * boolean batch() { return localBatch.get(be().isBatch()); }
 * void onClick() { localBatch.set(!batch()); clickButton(1); }
 * void onPopupClose() { localBatch.clear(); }
 * }</pre>
 *
 * @param <T> 保持する値の型 (Boolean / Integer / String 等)
 */
public final class LocalOverride<T> {
    private T value;

    /** 値がセットされていれば true、未セット (= BE を見るべき状態) なら false。 */
    public boolean has() { return value != null; }

    /** ローカル値があればそれを、なければ {@code fallback} を返す。 */
    public T get(T fallback) { return value != null ? value : fallback; }

    /** ローカル値を設定 (null OK = clear と同じ)。 */
    public void set(T v) { value = v; }

    /** ローカル値を破棄 (= 次回 get() は fallback を返す)。 */
    public void clear() { value = null; }
}
