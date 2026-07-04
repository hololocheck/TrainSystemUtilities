---
title: モニター表示
id: management-computer/monitor
tags: [management-computer, monitor]
---

# モニター表示

![](bws:trainsystemutilities:wiki/screens/management-computer__map__ja_jp.png)

管理用コンピューターのモニター表示タブ。リンクされた周辺モニターブロックでカスタムレイアウトを表示する。

[[TOC]]

## 開き方

1. **管理用コンピューター** ブロックを **設置** して **右クリック** で画面を開く。
2. モニター機能の各操作は、この画面の **上部にあるモニタートグル** と **下部のボタン**（🎨 色 / ▒ レイアウト）から行います（下記 [操作](#操作)）。

## 機能

- カスタムレイアウト編集 ([レイアウトエディタ](layout-editor.md))
- 色カスタム ([色設定](color-settings.md) / [カラーピッカー](symbol-editor.md#color-picker))
- モニター連携 ([モニター連携カード](../tools/monitor-link-card.md))
- batch apply / face-flip / private/public

## 操作 {#操作}

| 要素 | 操作 | 機能 |
|---|---|---|
| モニタートグル | **クリック** で ON/OFF（ON = 緑） | ON にすると連携先モニターへの表示を開始（[流れ](#モニター連携の流れ)） |
| 🎨 色 ボタン（下部） | **クリック** | [色設定](color-settings.md) popup を開く |
| ▒ レイアウト ボタン（下部） | **クリック** | [レイアウトエディタ](layout-editor.md) を開く |
| 路線記号の作成 | 左上ドロップダウンで **Ⓜ 路線記号** タブを選ぶ | [路線記号エディタ](symbol-editor.md) で記号を作成・編集 |

> [!NOTE]
> モニタートグルを ON にしても、先にモニターブロックを [モニター連携カード](../tools/monitor-link-card.md) でリンクしていないと表示先がありません。まず下の [モニター連携の流れ](#モニター連携の流れ) を済ませてください。

## モニター連携の流れ {#モニター連携の流れ}

1. 管理用コンピューターを **設置** する。
2. モニターブロックを周辺に **配置** する。
3. [モニター連携カード](../tools/monitor-link-card.md) を手に持ち、モニターブロックを **右クリック** → 管理用コンピューターを **右クリック** でリンク。
4. 管理用コンピューターを開き、**モニタートグルをクリックして ON**（緑）→ リンク先モニターへ即時同期。

## 関連

- [レイアウトエディタ](layout-editor.md)
- [色設定](color-settings.md)
- [モニター連携カード](../tools/monitor-link-card.md)
- [鉄道管理ブロック モニター](../railway-management.md)
