---
title: 連結・切り離し
id: trains/coupling
tags: [train, coupling]
---

# 連結・切り離し

Createのスケジュール条件で、2編成の連結や切り離しを行います。専用キーではなく、「連結・切り離し」という待機条件を追加して設定します。

[[TOC]]

## 連結する

1. 連結する両方の列車に「連結・切り離し」条件を追加します。
2. モードの値をホイールで「連結」にします。
3. 両方の列車を同じ駅へ到着させます。
4. 相手の到着を待ち、条件がそろうと連結します。

## 切り離す

切り離す駅で、先に着いた列車のスケジュールに同じ条件を追加し、モードを「切り離し」にします。切り離した後、前方の列車が発車するまでの待機時間は、ホイールで最大30秒まで調整できます。

![](bws:trainsystemutilities:wiki/screens/management-computer-sched-editor__ja_jp.png)

条件はCreate標準の編集画面と、TSUの[電子式時刻表](../management-computer/schedule.md#sched-editor)から設定できます。連結は最大2列車という制約があります。

連結中は、編成内で電力を共有します。[パンタグラフ](../electrification/pantograph.md)と[FEインバーター](../electrification/fe-inverter.md)を別の車両に搭載することもできます。切り離し後は、各編成に対応する電力の扱いへ戻ります。
