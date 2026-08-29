package com.trainsystemutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 翻訳済みラベルの実測幅。R4.23.1 で lang から control glyph を外し
 * {@code row} + {@code icon} + {@code span} に分けたボタンが、その塊ごと
 * {@code justify:"center"} で中央に載るために {@code dynamicW} へ返す値。
 *
 * <p><b>静的な w では両方の locale を中央にできない</b> — 同じキーでも
 * ja / en / zh で文字幅が変わる。だから中央揃えのボタンだけがこれを要る
 * (左寄せは開始位置が固定なので不要)。
 *
 * <p><b>{@code + 2} は Manta の text box 契約</b>: 左右に {@code borderWidth + 1} の
 * padding を取るので、文字幅ちょうどを返すと available width が 2px 足りず
 * 末尾が {@code …} に切り詰められる。
 *
 * <p>2026-08-29: 元は {@link ManagementComputerDispatch} の private helper 1 本
 * だったが、glyph 除去が 8 画面に広がったので同型を 4 つ作る代わりにここへ出した。
 * <b>本来は Manta 側の関心</b> — {@code +2} は Manta の text box 契約であって TSU の
 * 事情ではないので、consumer 側にこれがあるのは暫定である。Manta に上げるときは
 * 全 consumer の再ビルドを伴うので、次に API を足すときに一緒に運ぶ。
 */
public final class LabelWidth {

    private LabelWidth() {}

    /** {@code langKey} の翻訳結果の描画幅 + 2 (text box padding)。 */
    public static int of(String langKey) {
        return Minecraft.getInstance().font.width(
                Component.translatable(langKey).getString()) + 2;
    }
}
