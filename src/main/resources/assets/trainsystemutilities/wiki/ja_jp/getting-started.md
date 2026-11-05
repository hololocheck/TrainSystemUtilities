---
title: はじめに
id: getting-started
tags: [tutorial, beginner]
---

# はじめに

TSUを初めて使う方向けに、画面の開き方と共通操作を紹介します。対応するCreateの導入が必要です。詳しい前提バージョンは配布ページをご確認ください。

[[TOC]]

## 画面を開く

| 操作するもの | 開き方・役割 |
|---|---|
| [管理用コンピューター](management-computer/overview.md) | 設置して右クリック。路線図や列車、時刻表を管理します。 |
| [鉄道管理ブロック](railway-management.md) | Createの駅ブロックを右クリックして駅を登録し、設置して右クリック。駅の列車案内や連携機能を設定します。 |
| [ポスター管理ブロック](poster-management.md) | 設置して右クリック。画像とスライドショーを設定します。 |
| [券売機](structure/ticket-vending-machine.md) | 駅グループ内に設置して右クリック。行き先を選んで発券します。 |
| [乗り換え案内端末](tools/transit-terminal.md) | 手に持って右クリック。経路や時刻表を調べます。 |
| [駅範囲指定ツール](tools/station-range-tool.md) | Alt＋ホイールでモードを選び、範囲指定や駅の管理を行います。 |
| [列車プリセットツール](train-preset-tool/save.md) | GUIモードで右クリック。編成の閲覧・配置・共有を行います。 |
| [架線接続ツール](electrification/wire-connector.md) | 設置モードで碍子を接続。GUIモードで右クリックすると設定画面が開きます。 |
| [架線柱配置補助ツール](tools/overhead-pole-auto-tool.md) | GUIモードで設定し、選択モードで線路上に配置します。 |

![](bws:trainsystemutilities:wiki/screens/railway-management__ja_jp.png)

*鉄道管理ブロックの画面*

![](bws:trainsystemutilities:wiki/screens/wire-connector__ja_jp.png)

*架線接続ツールの画面*

管理用コンピューターの各機能は、左上の一覧から切り替えます。「色」「レイアウト」などのボタンからは、個別の設定画面を開けます。

## 共通操作

ボタンやスイッチはクリック、数値は値にカーソルを合わせてホイールで変更します。一覧が長いときは、一覧上でホイールを回してスクロールしてください。

## ヒントとF1 {#hints-and-f1}

画面右上の「ヒント」を有効にすると、項目にカーソルを合わせて1秒ほど止めたときに、その説明が左下に表示されます。説明が出ている間に操作（カーソルの移動・クリック・ホイール・キー）を2秒続けると消え、また止めると表示されます。F1は止めずに押しても、カーソルの下の機能のWikiへ移動します。Wikiボタンから、画面全体の解説を開くこともできます。

## 公開・非公開を選ぶ {#access-mode}

顔アイコンから、公開・非公開を切り替えられます。非公開にすると、画面を開けるのは所有者だけになります（OPも開けません）。共同で設定する設備は公開、管理者が設定を維持したい設備は非公開にすると便利です。カードによる読み取り・リンクは、画面の設定変更とは扱いが異なります。

## 画面の大きさ

操作画面はMinecraftのGUIスケールに合わせて表示されます。サイズ調整に対応するダイアログでは、右下のグリップをドラッグして調整できます。

## 次に読む

運行管理は「[管理用コンピューター](management-computer/overview.md)」、駅の表示は「[鉄道管理ブロック](railway-management.md)」、{{if-mod spatialaudiosystem}}音声放送は「[アナウンス設定（SAS導入時）](railway-management/announcement.md)」、{{/if}}列車への給電は「[電化システム](electrification/index.md)」をご覧ください。
