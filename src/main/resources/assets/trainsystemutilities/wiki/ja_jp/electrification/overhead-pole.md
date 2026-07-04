---
title: 架線柱
id: electrification/overhead-pole
tags: [electrification, block]
---

# 架線柱 (Overhead Pole)

```embed:item id=trainsystemutilities:overhead_pole size=48 label=true
```

単線用の架線柱です。線路脇に設置し、この上に [架線碍子](insulator.md) を載せて [架線](wire-connector.md) を張るための土台になります。

[[TOC]]

## 設置

架線柱はインベントリから手に持ち、**地面や置きたいブロックの上面を右クリック** で立てます。

- 向きは線路の角度に合わせて自動で選ばれます (8 方向・45° 刻み)。線路脇で使うと自然に線路と平行に向きます。
- **高さを足したいとき**: すでに立っている架線柱を **上から右クリック** すると、その柱の一番上に同じ向きの柱がもう 1 段積み上がります。連続で右クリックすればどんどん高くできます。
- 等間隔の大量設置には [架線柱自動配置ツール](../tools/overhead-pole-auto-tool.md) が便利です (設定した高さ・本数で線路沿いに柱・トラス・碍子をまとめて配置)。

> [!IMPORTANT]
> 架線柱そのものには架線を直接張れません。柱の上に [架線碍子](insulator.md) を右クリックで設置し、その碍子を [架線接続ツール](wire-connector.md) で右クリックして架線を張ります。

## 架線を張るまでの流れ

1. 線路脇に架線柱を設置し、必要な高さまで積み上げる。
2. 柱の上面に [架線碍子](insulator.md) を右クリックで設置。
3. 隣の柱にも同様に碍子を設置。
4. [架線接続ツール](wire-connector.md) を設置モードにして、**碍子 → 碍子** の順に右クリックで架線を張る。

> [!TIP]
> 複線・多線区間をまたぐ場合は [架線トラス](overhead-truss.md) を使ってください。

## 関連ページ

- [電化システム概要](index.md)
- [架線碍子](insulator.md) — 柱の上に載せる架線の取付点
- [架線 / 架線接続ツール](wire-connector.md) — 碍子間に架線を張る
- [架線トラス](overhead-truss.md)
- [架線柱自動配置ツール](../tools/overhead-pole-auto-tool.md)
