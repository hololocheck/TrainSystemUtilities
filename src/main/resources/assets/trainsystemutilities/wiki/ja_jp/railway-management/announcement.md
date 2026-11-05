---
title: アナウンス設定（SAS連携）
id: railway-management/announcement
tags: [station, announcement, sas, audio]
---

# アナウンス設定（SAS連携）

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__ja_jp.png)

列車の通過や停車に合わせて音声を流す機能です。[Spatial Audio System（SAS）](https://github.com/hololocheck/SpatialAudioSystem)が必要です。[鉄道管理ブロック](../railway-management.md)の「機能」から「アナウンス」を選びます。

[[TOC]]

## 全体の設定

「検知有効」でアナウンス全体を有効・無効にします。「範囲枠表示」は、範囲指定ボードの範囲と、減衰モードのときの減衰範囲を自分の画面に表示します。「減衰モード」では、距離に応じて音を弱めるかを設定します。

## 再生する条件を登録する

「エントリを追加」で項目を作り、条件と再生音声を設定します。項目は16件まで登録できます。条件欄をクリックして「なし」「通過時」「停止時」から選びます。「なし」では再生しません。右クリックで条件を解除できます。

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__ja_jp.png)

音声は、各項目の媒体スロットにSASの記録媒体を入れて指定します。遅延と再生回数は、値にカーソルを合わせてホイールで調整します。遅延は秒単位で、その項目の最初の再生にだけかかります。負の値は0として扱われます。再生回数は1〜10回です。「テスト再生」では、1番目の項目を1回再生して確認できます。

項目ごとの削除ボタンで項目を削除すると、入れていた記録媒体が戻ります。

## 検知地点と再生範囲

[検知カード](../tools/train-detection-card.md)には列車を検知する地点、範囲指定ボードには音声を届ける範囲を設定します。それぞれ、対応するスロットに入れてください。音声の媒体とは役割が異なります。

## 他の駅と共有する

「共有」を開き、共有先の駅と、検知・範囲指定の各項目を選びます。他の駅から共有を受けている場合は、画面下部に共有元を表示します。
