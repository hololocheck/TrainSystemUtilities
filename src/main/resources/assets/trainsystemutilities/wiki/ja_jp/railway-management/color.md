---
title: 色設定
id: railway-management/color
tags: [station, color]
---

# 色設定

![](bws:trainsystemutilities:wiki/screens/railway-management-color__ja_jp.png)

鉄道管理ブロックの「色」ボタンで開く popup。10 種類のテキスト要素ごとに色を変更可能。

[[TOC]]

## 開き方

1. [鉄道管理ブロック](../railway-management.md) を **右クリック** して GUI を開く。
2. モニター行の **「▒ 色」ボタンをクリック** すると、 この色設定 popup がダイアログの右側に出ます。
3. もう一度「▒ 色」ボタンを押すと閉じます。

## 編集対象 (10 種)

| key | 表示要素 |
|---|---|
| `arrTime` | 到着時刻 |
| `depTime` | 出発時刻 |
| `stopInfo` | 停車情報 |
| `routeType` | 路線種別（環状 / 折返）。列車種別とは別物 |
| `stopSec` | 停車秒数 |
| `trainName` | 列車名 |
| `nextName` | 次列車名 |
| `sectionTitle` | セクション見出し |
| `countdown` | カウントダウン |
| `trackNumber` | 番線番号 |

## プリセット色 (12 色)

popup 下部のプリセットグリッドから色をワンクリックで適用:

```
#4fc3f7 (cyan)   #80deea (light cyan)  #ff8a65 (orange)  #ffc107 (yellow)
#66bb6a (green)  #ef5350 (red)         #ab47bc (purple)  #ffffff (white)
#888888 (gray)   #555555 (dim)         #444444 (darker)  #333333 (darkest)
```

## 操作 (どこをクリックするか)

1. popup 上部の **ドロップダウン (▾ 付き) をクリック** → 一覧から色を変えたい **編集対象** (着予定 / 列車名 / 番線 など上記 10 種) を **クリック** して選ぶ。
2. popup 下部の **プリセット色をクリック** すると、 その色が選択中の対象に即適用される。
3. 元に戻したいときは **「個別リセット」ボタン** (選択中の対象だけ初期化) か **「全リセット」ボタン** (10 種すべて初期化) をクリック。

> [!NOTE]
> この popup は 12 色のプリセットから **クリックで選ぶ** 方式です。 色相環や HEX 入力を使う本格的な [カラーピッカー](../management-computer/symbol-editor.md#color-picker) は、 [路線記号エディタ](../management-computer/symbol-editor.md) など別の画面で使います。

## 両面別管理

**「↻ 表/裏 切替」ボタンをクリック** して表 / 裏を切り替えると、 面ごとに別の色セットを設定できます。  
[一括適用 (batch apply)](settings.md#batch-apply) にも対応しています ([モニター設定](settings.md#batch-apply) 参照)。

## 関連

- [鉄道管理ブロック](../railway-management.md)
- [モニター設定](settings.md)
- [カラーピッカー (管理用コンピューター)](../management-computer/symbol-editor.md#color-picker)
