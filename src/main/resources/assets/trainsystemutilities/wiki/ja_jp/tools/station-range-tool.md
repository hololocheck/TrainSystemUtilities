---
title: 駅範囲指定ツール
id: tools/station-range-tool
tags: [tool, station]
---

# 駅範囲指定ツール

```embed:item id=trainsystemutilities:station_range_tool size=48 label=true
```

![](bws:trainsystemutilities:wiki/screens/station-group-save__ja_jp.png)

駅エリアの範囲を 2 点指定して **駅グループ** として登録するツール。  
複数の鉄道管理ブロックを 1 駅としてまとめ、共通設定 (color / settings / announcement) を一括適用できる。

[[TOC]]

## モード切替

このツールは **Alt + マウスホイール** で 3 つのモードを切り替えられます (持っている間、 ホットバー上に現在のモードが表示されます)。

| モード | 動作 |
|---|---|
| 選択 (既定) | 範囲の 2 点を左クリックで指定して駅グループを作る |
| GUI | 右クリックで駅グループ管理 GUI を開く |
| 表示 | 既存の駅グループの枠をワールド上に表示 |

## 使い方 (選択モード)

1. ツールを持って、駅エリアの 1 つ目の角を **左クリック**
2. 反対側の角を **左クリック**
3. ツールを右クリック → 駅グループ保存 GUI 表示
4. 駅グループ名を入力 → Enter で保存

## 番線連番モード

保存時に **番線の自動連番** モードを選択可能:

| モード | 動作 |
|---|---|
| AUTO | 内側 = 1 番線基準で自動採番 |
| LEFT | 左端を 1 番線 |
| RIGHT | 右端を 1 番線 |

## 駅グループ管理

![](bws:trainsystemutilities:wiki/screens/station-group-manage__ja_jp.png)

ツールを **Alt + マウスホイール** で「GUI」モードに切り替え、 **右クリック** すると管理 GUI が開き、
保存済み駅グループの:
- 名前変更
- 削除 (確認ダイアログあり)
- メンバー駅の確認

を行える。

> [!NOTE]
> 駅グループ管理用のコマンドはありません。 管理はすべてこのツールの GUI モードから行います。

![](bws:trainsystemutilities:wiki/screens/station-group-manage-delete__ja_jp.png)

## 駅グループの使用先

- [管理用コンピューター 駅タブ](../management-computer/stations.md) でグループに路線記号を割当
- [鉄道管理ブロック batch apply](../railway-management/settings.md#batch-apply) で同グループ内に設定を一括適用
- [SAS アナウンス](../railway-management/announcement.md) の共有先 (share)
- [券売機](../structure/ticket-vending-machine.md) の **販売駅の候補** ([券売機タブ](../management-computer/tickets.md) で駅グループから選ぶ)
- **自動改札** が対象とする駅の認識
- [乗り換え案内端末](transit-terminal.md) の **経路探索** の対象 (駅グループを起点 / 終点として検索)

> [!NOTE]
> 券売機・自動改札・乗り換え案内は、 このツールで作った **駅グループを共通のデータとして参照** します。 これらを使う前に、 まず対象駅を駅グループとして登録してください。

## 関連

- [鉄道管理ブロック](../railway-management.md)
- [管理用コンピューター 駅タブ](../management-computer/stations.md)
- [メモリーカード](memory-card.md)
