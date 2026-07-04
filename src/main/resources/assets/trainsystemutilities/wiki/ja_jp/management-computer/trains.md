---
title: 列車タブ
id: management-computer/trains
tags: [management-computer, train]
---

# 列車タブ

![](bws:trainsystemutilities:wiki/screens/management-computer__trains__ja_jp.png)

管理用コンピューターの列車タブ。全列車のリスト + 詳細表示。

[[TOC]]

## 開き方

1. **管理用コンピューター** ブロックを **設置** して **右クリック** で画面を開く。
2. 左上のドロップダウンを **クリック** し、**「🚂 列車」** を選ぶ。
3. 列車が多くて一覧に収まらないときは、リストの上で **マウスホイール** を回してスクロールします。

## 表示内容

| 列 | 内容 |
|---|---|
| 列車名 | Create スケジュールから取得 |
| 両数 | 連結車両数 |
| 現在位置 | 駅名 or 走行中区間 |
| 速度 | リアルタイム速度 |
| 次駅 | 次の停車駅 |
| 電化状態 | パンタグラフ / FE バッファ ON/OFF |

## 列車詳細 popup

![](bws:trainsystemutilities:wiki/screens/management-computer-train-detail__ja_jp.png)

**リストの列車の行をクリック** すると、画面の右隣（入りきらない場合は左隣）に詳細 popup が開きます。

| 情報 | 内容 |
|---|---|
| 列車名 / 両数 | 基本情報 |
| スケジュール | 現在のエントリと次エントリ |
| 車両構成（3D モデル） | 編成の 3D プレビュー |
| 電化詳細 | 「⚡ 電化状態を見る」ボタンから [電化詳細 popup](#電化詳細-popup) を開く |
| 路線記号 | 割当された記号 |

**popup 内の操作:**

- **3D モデルの回転**: モデルの上で **マウス左ボタンを押したままドラッグ**。**Shift を押しながらドラッグ** で平行移動（パン）、**マウスホイール** で拡大縮小。
- **電化状態を開く**: popup 内の **「⚡ 電化状態を見る」ボタンをクリック**。
- **閉じる**: popup 右上の **✕（閉じる）ボタンをクリック**。

## 電化詳細 popup {#電化詳細-popup}

![](bws:trainsystemutilities:wiki/screens/management-computer-electrification-detail__ja_jp.png)

列車詳細 popup の **「⚡ 電化状態を見る」ボタンをクリック** すると、画面中央に重なって開きます。列車の FE バッファ / パンタグラフ / 架線接続状況を表示します。

- バッファ容量 + 残量 (車両ごと)
- パンタグラフ装着車両一覧
- FE インバーター装着車両一覧
- 現在の集電中区間 / 集電源変電所

**操作:**

- **パンタグラフの上げ下げ**: 車両一覧に描かれた **パンタグラフのアイコンを個別にクリック** すると、その車両のパンタグラフを上げる/下げる（集電の ON/OFF）を切り替えられます。
- **閉じる**: popup 右上の **✕（閉じる）ボタンをクリック**（元の列車詳細 popup に戻ります）。

詳細: [電化システム](../electrification/pantograph.md)

## 関連

- [時刻表タブ](schedule.md)
- [路線マップ](route-map.md)
- [連結 / 切り離し](../trains/coupling.md)
- [パンタグラフ](../electrification/pantograph.md)
