---
title: TrainSystem Utilities ってなに
id: about
---

# TrainSystem Utilities ってなに

TrainSystem Utilities (TSU) は、Minecraft の **Create MOD (1.21.1)** に鉄道周りの便利機能を足す拡張 MOD。  
Create が用意している「線路 / 駅 / スケジュール / 信号」をベースに、駅の電光掲示板・路線網の俯瞰管理・モニター連携・路線記号・**電化システム**などを **リアルっぽいけど誰でも簡単に扱える** 形で提供します。

[[TOC]]

## 何ができる MOD なの?

| 機能 | 概要 |
|---|---|
| 駅単位の電光掲示板 | 駅ホームに「鉄道管理ブロック」を置くと、停車中・次列車を自動表示 |
| 路線網の俯瞰管理 | 「管理用コンピューター」で全駅・全列車・時刻表を一括管理 |
| 駅へのモニター連携 | モニターブロックを駅近くに置いてリアルタイム表示 |
| 路線記号 | JA01 / JB02 のような独自路線記号を作って駅に割当 |
| 連結 / 切り離し | スケジュール条件で 2 編成を動的に連結 / 分離 |
| ポスター掲示 | PNG/JPG 画像をスライドショー表示する広告掲示板 |
| **電化システム** | **パンタグラフ + 架線 + 変電所 + FE インバーターで FE/Create エネルギーを列車へ給電** |
| 列車プリセット | 列車丸ごとを JSON 化して保存 / 復元 / 共有 |
| Preset Place | 列車プリセットをオンライン共有 (BelugaExperience 基盤) |

### 追加アイテム / ブロック

> [!TIP]
> 各アイコンの右下に **青いマーカー** が付いているものはクリックでそのページに飛びます。

#### 駅・表示系 (9)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:railway_management_block,trainsystemutilities:management_computer,trainsystemutilities:poster_management_block,trainsystemutilities:monitor,trainsystemutilities:double_monitor,trainsystemutilities:monitor_half,trainsystemutilities:double_monitor_half,trainsystemutilities:monitor_slim,trainsystemutilities:double_monitor_slim links=railway-management,management-computer/overview,poster-management,-,-,-,-,-,-
```

#### ツール (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:station_range_tool,trainsystemutilities:train_preset_tool,trainsystemutilities:transit_terminal links=tools/station-range-tool,tools/train-preset-tool,tools/transit-terminal
```

#### データカード (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:memory_card,trainsystemutilities:monitor_link_card,trainsystemutilities:train_detection_card links=tools/memory-card,tools/monitor-link-card,tools/train-detection-card
```

#### 電化システム (6)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

→ 詳しくは [電化システム 概要](electrification/index.md) を参照。

> [!TIP]
> **「列車そのものを動かす」MOD ではありません**。動かすのは Create のスケジュール。
> TSU は「Create の列車を見やすく、管理しやすく、電化しやすくする」周辺ツールです。

## どんな MOD と一緒に使う想定? {#recommended-mods}

| MOD | 役割 | 必須? |
|---|---|---|
| **Create** | 鉄道機構の本体 | ✅ 必須 |
| **Manta** | GUI / モニター / Wiki / BelugaExperience 描画基盤 | ✅ 必須 |
| **SpatialAudioSystem** | 駅の発車メロディ・案内放送など音響演出 (作者の別 MOD) | おすすめ |
| **Mekanism / Applied Energistics 2** | FE 電源として電化システムに給電 | 電化使用時 |
| Create: New Age 等 | 列車の追加機構 | 任意 |
| BSL Shaders 等 | 見た目強化 | 任意 |

> [!NOTE]
> **SpatialAudioSystem** と組み合わせると、TSU の駅掲示と同じ駅で発車メロディや車内放送が鳴り、駅の臨場感がぐっと上がります。

## 何が向いてる?

向いてる:

- **大規模ネットワーク運営**: 多数駅 / 列車を一覧管理したい
- **イメージ豊かな駅作り**: モニター・路線記号・ポスター案内で駅らしさを出したい
- **マルチプレイ路線運営**: 共同で時刻表を組み、誤操作を防ぎながら運用したい
- **電化路線の構築**: 架線敷設 + FE 給電による電車運行を再現したい
- **列車プリセット共有**: 自作列車を JSON で保存して他ワールドや他プレイヤーと共有したい

向いてない:

- **単発の貨物列車 1 本だけ動かす**: そこまでの規模だと管理用コンピューターは過剰装備
- **Create 抜き運用**: TSU 単体では何もしません

## アーキテクチャ要素 (上級者向け)

- **BelugaExperience UI System**: V3 GUI の widget framework (controller + json builder + auto-sizing)。`belugalab.experience.*` パッケージ
- **MCSS Wiki**: in-game markdown wiki + JSON 駆動 embed (`embed:screen` / `embed:item` / `embed:items`)
- **GUI capture pipeline**: ログイン時に全 layout JSON を off-screen FBO 経由でキャプチャ → DynamicTexture として wiki に即時反映
- **i18n**: ja_jp / en_us 切替時に `/tsu-wiki-prebuild` で言語別キャプチャ再生成

## まず読むページ

- [はじめに](getting-started.md) — 触り始めの最短ルート
- [管理用コンピューター 概要](management-computer/overview.md) — 中枢 GUI のツアー
- [鉄道管理ブロック](railway-management.md) — 駅の電光掲示板
- [ポスター管理ブロック](poster-management.md) — 画像掲示板
- [電化システム](electrification/pantograph.md)

## 開発ステータス

> [!IMPORTANT]
> 開発中の MOD です。設定保存形式や API は変更される可能性があります。
> 大規模ワールドで本番運用する前に、テストワールドで挙動を確認することを推奨します。
