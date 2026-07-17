---
title: 変電所キュービクル
id: electrification/substation
tags: [electrification, block, multiblock]
---

# 変電所キュービクル

```embed:item id=trainsystemutilities:substation size=48 label=true
```

3×4×2 = 24 ブロックのマルチブロック構造体。外部 FE 電源 (Mekanism / AE2 / Industrial Foregoing 等) を受け取り、碍子経由で架線に供給する。

[[TOC]]

## 設置方法

1. 設置場所を確保する (= 3 幅 × 4 奥行 × 2 高さ = 24 マスの空間)。
2. キュービクル本体をインベントリから手に持ち、**基準ブロックにしたい位置を右クリック**。プレイヤーの向きに合わせて構造体の向きが決まります。
3. クリックした位置を基準に、ダミーブロック 23 個が自動配置され、3×4×2 の構造体が完成します。
4. 構造体は単一の論理ブロックとして扱われます (どのブロックを右クリックしても本体として反応)。

> [!WARNING]
> 設置スペースが不足していると `設置スペースが足りません (3×4×2 必要)` と表示されて設置できません。
> 撤去するときは 24 個のどれか 1 つを破壊すれば、全ブロックが同時に消えます。

## 状態の確認 (右クリック)

設置した変電所を **素手で右クリック** すると、チャットに現在の状態が表示されます (専用の GUI 画面は開きません)。

| 表示 | 意味 |
|---|---|
| **通電中** | FE があり、碍子経由で架線網に給電できている |
| **接続待ち** | FE はあるが、まだ碍子・架線がつながっていない |
| **FE 不足** | 外部電源から FE が入ってきていない |

あわせて現在の FE 残量 / 容量と、つながっている架線ネットワークの接続数も表示されます。より詳しく調べたいときは [電力チェッカー](power-checker.md) を使ってください。

## 接続 (入力 + 出力)

```
[FE 電源 (Mekanism Cable / Create Energy etc)]
     ↓ 接続 (任意の面)
[変電所キュービクル]
     ↓ 碍子ブロック経由
[架線 (Wire)]
     ↓
[列車のパンタグラフ]
```

### FE 入力

- キュービクルは IEnergyStorage capability を任意の dummy 面で受け付ける
- Mekanism Universal Cable, AE2 Energy Cell, Create Electric Engine など FE 互換ならすべて接続可
- 内部バッファ容量: 100 万 FE
- 受け入れレート: 10000 FE/tick

### 架線出力

変電所から架線に給電するには、変電所に **接する位置** に碍子を立てて、そこから架線を伸ばします。

1. 変電所本体に **隣接するマス** に [架線碍子](insulator.md) を右クリックで設置 (この碍子が変電所と架線をつなぐ入口になります)。
2. その碍子を [架線接続ツール](wire-connector.md) の設置モードで右クリック → 次の碍子を右クリックして架線を伸ばす。
3. 変電所に隣接した碍子から先の架線網が **通電** します (通電すると架線の色が明るくなります)。
4. 1 つの変電所から複数の架線を派生可能です。

## チャンクロード非依存 {#savedata}

`SubstationRegistry` (per-dimension SavedData) で変電所の位置 + FE + facing を記録。  
プレイヤーがチャンクをロードしていなくても:

- 変電所への FE 受け入れは継続 (外部電源側のチャンクが load されていれば)
- 給電中の列車がチャンク外にいても、バッファから FE が引かれ続ける
- 列車が戻ってきた時にバッファ残量が正確

> [!TIP]
> 大規模路線では、駅と駅の間の長距離区間で列車がチャンクロード外に出てもバッファ枯渇しなければ走行継続できる。逆に言うとバッファ容量設計が重要。

## モデル

Geckolib ベースの静的モデル (アニメーションなし)。テクスチャは灰色 + アクセントカラー。  
3×4×2 = 24 ブロック分の外観を 1 つの BlockEntity が描画。

> [!NOTE]
> キュービクル本体ブロックは BlockEntity を持つ。dummy ブロック 23 個は BlockEntity を持たず、capability アクセスは [SubstationMultiblock.findCore](https://github.com/hololocheck/TrainSystemUtilities/blob/master/src/main/java/com/trainsystemutilities/electrification/block/SubstationMultiblock.java) 経由で本体に転送される。

## 関連

- [パンタグラフ](pantograph.md) — 架線下を走る集電装置
- [架線 / 架線接続ツール](wire-connector.md) — 架線敷設
- [FE インバーター](fe-inverter.md) — 列車側 FE バッファ
- [カスタム架線デザイン](custom-wire.md) — 架線の見た目カスタム
