---
title: プリセット アップロード
id: preset-place/upload
tags: [preset-place, upload]
---

# プリセット アップロード

![](bws:trainsystemutilities:wiki/screens/preset-place-upload__ja_jp.png)

自作プリセットを Preset Place へ公開申請するダイアログ。

[[TOC]]

## 開き方 {#open}

まず公開したい列車を[列車プリセット](../train-preset-tool/browse.md)としてローカルに保存しておく必要があります。

1. [列車プリセットツール](../train-preset-tool/browse.md)を **GUI モード** で **右クリック** して閲覧画面を開きます (モード切替は **Alt + ホイール**)。
2. モードを **`Mine` (自作)** にします。
3. 公開したい自作プリセットのタイルにある **アップロードアイコン** を **左クリック** すると、このダイアログが開きます。

> [!NOTE]
> アップロードアイコンは、Microsoft アカウント連携が設定済みで、かつ Preset Place からダウンロードした物ではない自作プリセットにのみ表示されます。

## アップロード項目

| 項目 | 概要 |
|---|---|
| 画像 (最大 5 枚) | プレビュー用の PNG / JPG。+ ボタンで追加 |
| Markdown 説明文 | 4096 文字。改行 / paste / copy / Ctrl+Enter 対応 |
| 公開ボタン | 入力チェック後に公開申請 |

## 説明文エディタ

multi-line markdown 編集。`プレビュー` トグルで render 結果を確認:

- 見出し / リスト / リンク / 強調 / 引用 など標準 Markdown
- Ctrl + Enter で `公開` 実行

## 公開フロー

1. 画像 1 枚以上 + 説明文 1 文字以上 で `公開` 有効化
2. 公開確認 dialog
3. クライアントが Preset Place サーバーに送信
4. レビュー後 (= 自動 OR モデレータ) 公開

## 認証

公開には Microsoft アカウント認証必須。  
[クリエイターセンター](creator-center.md) で認証状態確認 / 再認証可能。

## 関連

- [Preset Place 概要](overview.md)
- [プリセット詳細](detail.md)
- [クリエイターセンター](creator-center.md)
- [列車プリセット閲覧](../train-preset-tool/browse.md)
