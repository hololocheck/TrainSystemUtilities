---
title: 連掛・解編
id: trains/coupling
tags: [train, coupling]
---

# 連掛・解編

使用 Create 的時刻表條件，對 2 列編組進行連掛或解編。不使用專用按鍵，而是添加名為「連掛・解編」的等待條件來設置。

[[TOC]]

## 連掛

1. 為要連掛的兩列列車都添加「連掛・解編」條件。
2. 用滾輪將模式的值設為「連掛」。
3. 讓兩列列車到達同一車站。
4. 列車會等待對方到達，條件滿足後進行連掛。

## 解編

在要解編的車站，為先到達的列車的時刻表添加同一條件，並將模式設為「解編」。解編後，前方列車發車前的等待時間可以用滾輪調整，最長 30 秒。

![](bws:trainsystemutilities:wiki/screens/management-computer-sched-editor__zh_hk.png)

該條件可以在 Create 標準的編輯界面和 TSU 的[電子時刻表](../management-computer/schedule.md#sched-editor)中設置。連掛最多 2 列列車。

連掛期間，編組內共享電力。[受電弓](../electrification/pantograph.md)和 [FE 逆變器](../electrification/fe-inverter.md)也可以搭載在不同的車廂上。解編後，各編組恢復各自的電力處理方式。
