---
title: 電化システム 概要
id: electrification/index
tags: [electrification, overview]
---

# 電化システム 概要

```embed:items size=48 cols=7 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:fe_inverter_dummy,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/dummy-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

> [!TIP]
> 各アイコンの右下に **青いマーカー** が付いているものはクリックすると詳細ページに飛びます。

TrainSystem Utilities の電化システムは、Create の列車に **架線 + パンタグラフ + 変電所** で外部 FE 電源を給電する仕組みです。
Mekanism / AE2 / Industrial Foregoing 等の FE 発電設備を、Create 列車の動力源 / 補機電源として使えるようにします。

[[TOC]]

## 全体のながれ

```
[FE 発電機 (Mek/AE2/IF)] ──FE──▶ [変電所キュービクル]
                                       │
                                  (碍子経由で架線へ昇圧)
                                       │
                                       ▼
[列車屋根の パンタグラフ] ◀──架線下を走行──▶ [架線 (Wire)]
        │
        ▼
[FE インバーター] ──FE──▶ [列車の Create 機械 / Mek 機械 / ...]
```

1. **FE 電源を用意する**: Mekanism のリアクター、AE2 の Energy Acceptor 等、何でも可。
2. [変電所キュービクル](substation.md) を組む: 本体を手に持って **右クリック** すると 3×4×2 = 24 ブロック構造が自動で完成。FE 入力面に電源ケーブルを接続。
3. 線路脇に [架線柱](overhead-pole.md) や [架線トラス](overhead-truss.md) を右クリックで立て、その上と変電所の隣に [架線碍子](insulator.md) を右クリックで設置。
4. [架線接続ツール](wire-connector.md) を手に持ち、**碍子 → 碍子** の順に右クリックして [架線](wire-connector.md) を線路上空に張る (変電所に隣接した碍子から通電)。
5. 列車の屋根に [パンタグラフ](pantograph.md) を、車両に [FE インバーター](fe-inverter.md) を右クリックで設置。
6. 走行中、パンタが架線下を通過すると自動で集電 → インバーターが車内 FE 機械に給電。

> [!TIP]
> 架線柱・碍子を線路沿いに大量に置くのは手間なので、[架線柱自動配置ツール](../tools/overhead-pole-auto-tool.md) を使うと設定した高さ・本数で柱・トラス・碍子をまとめて自動配置できます。

## 構成ブロック / アイテム

| アイテム | 役割 | 詳細 |
|---|---|---|
| [架線接続ツール](wire-connector.md) | 碍子と碍子の間に架線を張る | 手に持って碍子を右クリック。5 種デザイン + [カスタム](custom-wire.md) |
| [架線碍子](insulator.md) | 架線を張る両端の取付点 | これを右クリックして架線を張る。変電所と架線の中継点 |
| [架線柱](overhead-pole.md) | 単線用の支柱 | 碍子を載せる土台。線路脇に右クリックで設置 |
| [架線トラス](overhead-truss.md) | 複線をまたぐ門型支持 | 碍子を載せる土台。線路群をまたいで設置 |
| [架線柱自動配置ツール](../tools/overhead-pole-auto-tool.md) | 柱・トラス・碍子を自動配置 | 設定した高さ・本数で線路沿いに一括設置 |
| [パンタグラフ](pantograph.md) | 列車屋根の集電装置 | 屋根に右クリックで設置。架線下走行で自動接続 |
| [FE インバーター](fe-inverter.md) | 車内 FE バッファ | 3 連機器。パンタの電力を車内機械に供給 |
| [ダミーインバーター](dummy-inverter.md) | 装飾のみ (機能なし) | 見た目だけ FE インバーター、UI からのパンタ展開操作のみ可能 |
| [変電所キュービクル](substation.md) | 外部 FE 受電 + 架線給電 | 3×4×2 マルチブロック |
| [電力チェッカー](power-checker.md) | デバッグ用ツール | 碍子 / 変電所 / インバータ / パンタに右クリックで FE 残量等を表示 |

## よくある質問

> [!NOTE]
> **Q: 電化していない列車も今まで通り動きますか？**
> A: はい。電化システムは完全に追加機能なので、Create 標準のスケジュール運行はそのまま動きます。

> [!NOTE]
> **Q: 各コンポーネントの詳細を知りたい**
> A: 上の表の各リンクからジャンプできます。トラブルシューティングは [電力チェッカー](power-checker.md) ページの「トラブルシューティング用途」を参照。

> [!IMPORTANT]
> **Q: 電化なしで Create のエネルギーだけで走れますか？**
> A: 走れます。電化システムは「Create エネルギー以外の補助動力」として設計されています。
> 例えば Mek のリアクターを電源にして、長距離走行で Create のドラム残量を節約する、といった使い方ができます。

## 関連ページ

- [架線 / 架線接続ツール](wire-connector.md)
- [カスタム架線デザイン](custom-wire.md)
- [架線碍子](insulator.md)
- [架線柱](overhead-pole.md)
- [架線トラス](overhead-truss.md)
- [架線柱自動配置ツール](../tools/overhead-pole-auto-tool.md)
- [パンタグラフ](pantograph.md)
- [FE インバーター](fe-inverter.md)
- [ダミーインバーター](dummy-inverter.md)
- [変電所キュービクル](substation.md)
- [電力チェッカー](power-checker.md)
