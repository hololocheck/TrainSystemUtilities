---
title: カスタム架線デザイン
id: electrification/custom-wire
tags: [electrification, customization, tool]
---

# カスタム架線デザイン

![](bws:trainsystemutilities:wiki/screens/wire-connector__custom__ja_jp.png)

[架線接続ツール](wire-connector.md)のGUIモードで設定画面を開き、「カスタム」を選ぶと編集できます。数値の上でホイールを回すと、右側のプレビューへ反映されます。

[[TOC]]

| 項目 | 範囲 | 変更単位 |
|---|---|---|
| 太さ | 0.01〜0.30 | 0.01 |
| 上下間隔 | 0〜2.00m | 0.05m |
| ドロッパ間隔 | 0.50〜10.00m | 0.25m |
| 2列配置 | 有効・無効 | 切り替え |

上下間隔を0にすると1段、それより大きい値では2段になります。設定した値は上限・下限の範囲内に収まります。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__ja_jp.png)

「プリセット保存」で名前を入力し、Enterで保存、Escで取り消します。保存後は一覧から選べます。保存済みプリセットを選択中は値が固定されるため、編集するときは「カスタム」へ戻ってください。変更版は新しいプリセットとして保存し、不要なものは右クリックから削除できます。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__ja_jp.png)

たるみモードは「シンプル (1 本)」専用です。「カスタム」は直線の構成になります。架線はワールド側の設備であり、列車プリセットには含まれません。架線デザインのプリセットは架線接続ツールのアイテム自体に保存され、ツール1つにつき64個まで持てます。
