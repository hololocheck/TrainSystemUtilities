---
title: Preset Place 概要
id: preset-place/overview
tags: [preset-place, community, online]
---

# Preset Place 概要

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

TSU のコミュニティ共有機能。自作列車プリセットを公開し、他ユーザーのプリセットを閲覧 / ダウンロードできる。

[[TOC]]

## 全体像

```
[ローカルプリセット] ── upload ──> [Preset Place サーバー]
                                          │
                       browse ←────────────┤
                       download <──────────┘
[他ユーザのワールド] <─── 配置
```

バックエンド: BelugaExperience 基盤の Supabase。  
認証: Minecraft アカウント連携 (Microsoft account → JWT)。

## ページ

| ページ | 内容 |
|---|---|
| [プリセット詳細](detail.md) | 単一プリセットの詳細 + 3D プレビュー + ダウンロード |
| [プロフィール](profile.md) | ユーザのプロフィール + 公開プリセット一覧 + フォロー |
| [アップロード](upload.md) | 自作プリセットの公開ダイアログ (Markdown 説明文対応) |
| [クリエイターセンター](creator-center.md) | クリエイターアカウントの統計 / ダッシュボード |

## 主な機能

| 機能 | 動作 |
|---|---|
| いいね | 気に入ったプリセットに♥ |
| ダウンロード数表示 | 配信中のプリセットの累計 DL 数 |
| 通報 | 不適切プリセットの報告 (理由付き) |
| フォロー | クリエイターをフォロー |
| プロフィールアイコン | SVG アイコンのカスタム ([profile-icon-editor](../management-computer/overview.md#owner-face)) |

## 開き方 (アクセス手順) {#access}

Preset Place の各画面は、すべて **[列車プリセットツール](../train-preset-tool/browse.md)** から開きます。

1. **列車プリセットツール** を手に持ちます。
2. **Alt + マウスホイール** でツールを **GUI モード** に切り替えます (持っている間、ホットバー上に現在のモードが表示されます)。
3. **右クリック** で列車プリセット閲覧画面を開きます。
4. 画面上部のモード切替 (dropdown) を **`Place` (= 公開)** に切り替えます。
5. 一覧のプリセットタイルを **左クリック** すると [詳細ページ](detail.md) が開きます。

各画面へは詳細ページ経由でつながります。

- **プロフィール** … 公開モードで自分の名前/アイコン部分をクリック、または詳細ページで投稿者名をクリック。
- **アップロード** … `Mine` (自作) モードで自分のプリセットのアップロードアイコンをクリック ([アップロード](upload.md))。
- **クリエイターセンター** … 自分の[プロフィール](profile.md)ページの「クリエイターセンター」ボタンから。

> [!NOTE]
> Preset Place の利用には Microsoft アカウント連携が必要です。初回は認証を求められます (詳細は各ページ参照)。

## 関連

- [プリセット詳細](detail.md)
- [プロフィール](profile.md)
- [アップロード](upload.md)
- [クリエイターセンター](creator-center.md)
- [列車プリセット閲覧](../train-preset-tool/browse.md)
