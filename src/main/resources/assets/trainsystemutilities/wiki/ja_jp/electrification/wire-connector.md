---
title: 架線・架線接続ツール
id: electrification/wire-connector
tags: [electrification, item, tool]
---

# 架線・架線接続ツール

```embed:item id=trainsystemutilities:wire_connector size=48 label=true
```

![](bws:trainsystemutilities:wiki/screens/wire-connector__ja_jp.png)

[架線碍子](insulator.md)の間に架線を張るツールです。「設置 (碍子接続)」と「GUI (デザイン選択)」の2モードがあり、Alt＋ホイールで切り替えます。デザインの選択は、GUIモードの画面内で行います。

[[TOC]]

## 架線を張る

先に2か所の碍子を設置します。設置モードにして、始点、終点の順に碍子を右クリックしてください。同じ碍子を再度選ぶか、空中でShift＋右クリックすると始点を解除できます。接続距離の上限は32ブロックで、ツールチップでも確認できます。

## 架線を補充する

![](bws:trainsystemutilities:wiki/screens/wire-connector__refill__ja_jp.png)

サバイバルでは、張った距離に応じて残量を消費します。GUIモードで右クリックし、「架線ロード」欄の「架線を補充」を押して開く「架線スプール装填」画面へ、架線スプールを入れます。1個で100m、最大6,400mまで補充できます。クリエイティブでは残量を消費しません。

## デザインを選ぶ

| デザイン | 内容 |
|---|---|
| カスタム（CUSTOM） | 太さや間隔を自由に設定 |
| シンプル (1 本)（SIMPLE） | 細い1本の線 |
| 二段架線（TWO_TIER） | 吊架線とトロリ線による2段構成 |
| 二段 × 2 列 (複線)（TWIN_2ROW） | 2段構成を左右2列に配置 |
| 高所オフセット（HIGH_OFFSET） | 上下の間隔を広く取った構成 |

検索欄とフィルターで、標準デザインと保存したプリセットを絞り込めます。「シンプル (1 本)」では、中央が垂れ下がる「たるみモード」を使えます。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__ja_jp.png)

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__ja_jp.png)

「適用」で設定を確定すると、その後に張る架線へ反映されます。「カスタム」からの保存・削除は「[カスタム架線デザイン](custom-wire.md)」をご覧ください。

![](bws:trainsystemutilities:wiki/screens/wire-connector-confirm__ja_jp.png)
