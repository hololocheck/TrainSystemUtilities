---
title: 駅タブ
id: management-computer/stations
tags: [management-computer, station]
---

# 駅タブ

![](bws:trainsystemutilities:wiki/screens/management-computer__stations__ja_jp.png)

管理用コンピューターの駅タブ。全駅のリストと駅グループ管理 + 路線記号割当。

[[TOC]]

## 開き方

1. **管理用コンピューター** ブロックを **設置** して **右クリック** で画面を開く。
2. 左上のドロップダウンを **クリック** し、**「🏯 駅」** を選ぶ。
3. 駅が一覧に収まらないときは、リストの上で **マウスホイール** を回してスクロールします。

## 表示内容

| 列 | 内容 |
|---|---|
| 駅名 | Create 駅名 |
| グループ | 所属駅グループ ([駅範囲指定](../tools/station-range-tool.md) で登録) |
| 路線記号 | 割当済み記号 (例: JA01) |
| 列車検知 | 直近通過列車 |

## 駅グループ管理

[駅範囲指定ツール](../tools/station-range-tool.md) で作成された駅グループの:
- メンバー駅の確認
- グループ名変更
- グループ削除

を一覧表示。

## 駅の詳細と操作

- **駅を選ぶ**: 一覧の **駅の行をクリック** すると、その駅の詳細が開きます（駅名・位置・所属グループ・ドア方向などが表示されます）。
- **一覧へ戻る**: 詳細の **「◀ 戻る」ボタンをクリック**。
- **ドアの開く方向を決める**: 詳細内の方向ボタン（**北 / 南 / 東 / 西 / 自動 / なし**）を **クリック** して選びます。

## 路線記号の割当（割当 popup）

![](bws:trainsystemutilities:wiki/screens/management-computer-station-assign__ja_jp.png)

- **割当 popup を開く**: 一覧の各駅の行にある **割当ボタン（＋）をクリック** すると、その駅のすぐ下に路線記号の一覧が開きます。
- **記号を割り当てる**: 一覧から **割り当てたい路線記号をクリック**（「なし」を選ぶと割当解除）。
- 割り当てられる記号は、あらかじめ [路線記号タブ](line-symbols.md) で作成しておきます。

## 路線記号

[路線記号タブ](line-symbols.md) で作成した記号をここで駅に割当。  
割当済み記号は [鉄道管理ブロック header](../railway-management.md) と [路線マップ](route-map.md) に表示される。

## 関連

- [駅範囲指定ツール](../tools/station-range-tool.md)
- [路線記号タブ](line-symbols.md)
- [路線マップ](route-map.md)
- [鉄道管理ブロック](../railway-management.md)
