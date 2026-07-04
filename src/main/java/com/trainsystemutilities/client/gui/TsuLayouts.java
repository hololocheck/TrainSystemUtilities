package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutScreen;
import belugalab.mcss3.screen.JsonLayoutPlainScreen;

/**
 * TSU 共通: 自 mod のレイアウト JSON を読み込む 1 行ヘルパ。
 * MCSS 基底の {@code loadModResourceJson} を namespace="trainsystemutilities" で
 * 呼び出すラッパー。各 V2 Screen は private な loadResourceJson(path) を持つ代わりに
 * このクラスを呼び出す。
 *
 * <p>例:
 * <pre>{@code
 *   protected String overlayJson() {
 *       return TsuLayouts.load("layouts/management-computer.json");
 *   }
 * }</pre>
 */
public final class TsuLayouts {
    public static final String NAMESPACE = "trainsystemutilities";
    private TsuLayouts() {}

    /** Container/Plain 共通の JSON ロード (常に空文字を返す。null は返さない)。 */
    public static String load(String path) {
        // JsonLayoutScreen と JsonLayoutPlainScreen の loadModResourceJson は同じ実装。
        // protected static なので外から直接呼ぶには両方 public 公開するか、ここで再実装する必要がある。
        // 同実装なのでどちらか一方経由で呼べば OK。MCSS base を public にするより
        // ここで委譲する方が API 公開面を絞れる。
        return JsonLayoutScreen.loadModResourceJson(NAMESPACE, path);
    }
}
