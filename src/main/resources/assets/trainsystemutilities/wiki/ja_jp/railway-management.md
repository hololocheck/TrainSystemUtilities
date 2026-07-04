---
title: 鉄道管理ブロック
id: railway-management
tags: [station, display, block]
---

# 鉄道管理ブロック

![](bws:trainsystemutilities:wiki/screens/railway-management__ja_jp.png)

駅ホームに置く電光掲示板ブロック。停車中・次列車を自動表示し、モニター・色設定・アナウンス (SAS) と連携する。

[[TOC]]

## 開き方

1. **鉄道管理ブロック** を駅ホームに設置します。 Create の駅ブロックと同じく、 敷いた **線路を右クリック** して線路上に設置する方式です。
2. 設置したブロックを **右クリック** すると GUI が開きます。
3. どの駅を表示するかは、 [メモリーカード](tools/memory-card.md) でこのブロックと [管理用コンピューター](management-computer/overview.md) をリンクして決めます。
4. 初めて右クリックした人が **所有者** になります。 右下の顔アイコンが **プライベート** のときは所有者以外は開けません ([アクセスモード](getting-started.md#access-mode))。

> [!NOTE]
> このブロックは Create の駅と同様に「線路の上」に置きます。 何もない地面には設置できません。 まず線路を敷いてから、 その線路を右クリックしてください。

## 操作 (どこをクリック / ホイールするか)

GUI 内の操作はすべて **マウス** で行います (キーボードは使いません)。

| 操作したいもの | やり方 |
|---|---|
| モニターの ON / OFF | モニター行の **トグルをクリック** |
| モニター設定を開く | モニター行の **「⚙ 設定」ボタンをクリック** → [モニター設定](railway-management/settings.md) popup |
| 色設定を開く | モニター行の **「▒ 色」ボタンをクリック** → [色設定](railway-management/color.md) popup |
| アナウンス / ホームドア設定を開く | モニター行の **「機能 ▼」ボタンをクリック** → 出てきた一覧から **「アナウンス」または「ホームドア」をクリック** |
| 次列車の一覧を送る | 一定時間ごとに自動でページが切り替わります (手動操作は不要) |
| ヒント表示 | 右上の **「ヒント」トグルをクリック** して ON。 その状態でボタンにカーソルを合わせて **F1** を押すと、 その機能の wiki 説明へ飛びます ([F1 の使い方](getting-started.md#hints-and-f1)) |
| プライベート / パブリック切替 | 右下の **顔アイコンをクリック** |

> [!TIP]
> このブロックは駅ホームに置く「表示専用」の掲示板です。 数値や色をまとめて整えるときは popup 側 ([モニター設定](railway-management/settings.md) / [色設定](railway-management/color.md)) で操作します。 popup 内の数値は **値にカーソルを合わせてマウスホイール** で増減します (＋ / − ボタンはありません)。

## 概要

| 機能 | 概要 |
|---|---|
| 到着列車表示 | 停車中の列車を上段に表示 (列車名 / 両数 / 到着時刻 / 出発時刻) |
| 次列車表示 | 次に到着する列車を下段に表示 (複数列) |
| 路線記号 | header に [割当](management-computer/stations.md) 済み路線記号を表示 |
| モニター連携 | 周辺モニターブロックで同じ内容を表示 |
| 色カスタム | 各テキスト要素の色を [color popup](railway-management/color.md) で変更 |
| アナウンス (SAS) | [SpatialAudioSystem](railway-management/announcement.md) 連携で発車メロディ / 案内放送 |
| Batch apply | 同一ネットワーク内の全ブロックに設定を一括適用 |

## GUI 主要要素

![](bws:trainsystemutilities:wiki/screens/railway-management__ja_jp.png)

| 要素 | 機能 |
|---|---|
| `ヒント` トグル | F1 ジャンプ + マウスホバー説明 ON/OFF |
| header-sym | 割当済み路線記号 (なければ非表示) |
| 到着中の列車 リスト | 1 件、現在停車中の列車 |
| 次に到着する列車 リスト | 上から到着順、ページ送り |
| モニター行 | モニターオン/オフ + 状態表示 + 設定 / 色 / アナウンス ボタン |
| owner-face | プライベート/パブリック切替 |
| インベントリ | プレイヤーインベントリ |

## 関連 popup

| popup | 内容 |
|---|---|
| [モニター設定](railway-management/settings.md) | フォントサイズ / 番線位置 / 時計表示 / batch apply |
| [色設定](railway-management/color.md) | 10 種テキスト要素 (arrTime, depTime, trainName 等) の色 |
| [アナウンス設定](railway-management/announcement.md) | SAS 連携のエントリ管理 (条件付きトリガ) |

## 関連

- [管理用コンピューター 概要](management-computer/overview.md) — 全駅・全列車の俯瞰管理
- [モニター連携カード](tools/monitor-link-card.md) — 周辺モニターとリンク
- [駅範囲指定ツール](tools/station-range-tool.md) — 駅グループの登録
