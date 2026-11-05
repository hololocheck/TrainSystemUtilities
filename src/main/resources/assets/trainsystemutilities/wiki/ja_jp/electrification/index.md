---
title: 電化システムの概要
id: electrification/index
tags: [electrification, overview]
---

# 電化システムの概要

```embed:items size=48 cols=7 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:fe_inverter_dummy,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/dummy-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

地上のFE電源から、変電所、架線、パンタグラフを通して列車へ電力を送る仕組みです。受け取った電力は、列車に搭載したFEインバーターで蓄えます。

[[TOC]]

## 動画で見る

キュービクルの設置から、パンタグラフで集めた電力で列車が走るまでの流れを、動画で確認できます（Manta のゲーム内動画機能）。

```embed:youtube url=https://youtu.be/p5o8KkhweF8 title=列車の電化方法
```

## 導入の流れ

1. FEを出力する電源を用意します。
2. [キュービクル](substation.md)を設置し、電源を接続します。
3. 変電所の隣と、線路沿いの支持点に[架線碍子](insulator.md)を設置します。
4. [架線接続ツール](wire-connector.md)で碍子同士をつなぎます。
5. 列車の屋根に[パンタグラフ](pantograph.md)、車両に[FEインバーター](fe-inverter.md)を設置して組み立てます。
6. パンタグラフを展開し、通電した架線の下で集電と蓄電を確認します。

[架線柱](overhead-pole.md)と[架線トラス](overhead-truss.md)は、碍子を支える土台です。架線はそれらに直接つながず、碍子に接続します。配置を助ける[ツール](../tools/overhead-pole-auto-tool.md)、装飾用の[ダミーインバーター](dummy-inverter.md)、状態を調べる[電力チェッカー](power-checker.md)も使えます。

電化は追加機能です。電化設備を搭載しない列車は、Createの標準的な運行を続けられます。パンタグラフとFEインバーターの両方を搭載した列車は電化列車として扱われ、走行中は列車全体で1,500 FE／tickを消費します。蓄えたFEが0になると列車は停止し、FEが補充されるまで走り出せません。

FEインバーターを搭載した車両は、1両につき50,000 FEまで蓄えます。展開したパンタグラフが通電した架線に接触している間、パンタグラフ1基につき2,000 FE／tickを集電します。

変電所は、チャンクが読み込まれていなくても架線への給電を続けます。読み込まれていないチャンクにいる列車は、集電もFEの消費も行いません。

通電中の架線の、両端の碍子を結ぶ直線から1ブロック以内に入ったプレイヤーやモブは、0.5秒ごとに1（ハート半分）の感電ダメージを受けます。クリエイティブモードとスペクテイターモードでは受けません。
