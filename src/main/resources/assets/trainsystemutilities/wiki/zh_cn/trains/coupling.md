---
title: 连挂・解编
id: trains/coupling
tags: [train, coupling]
---

# 连挂・解编

使用 Create 的时刻表条件，对 2 列编组进行连挂或解编。不使用专用按键，而是添加名为「连挂・解编」的等待条件来设置。

[[TOC]]

## 连挂

1. 为要连挂的两列列车都添加「连挂・解编」条件。
2. 用滚轮将模式的值设为「连挂」。
3. 让两列列车到达同一车站。
4. 列车会等待对方到达，条件满足后进行连挂。

## 解编

在要解编的车站，为先到达的列车的时刻表添加同一条件，并将模式设为「解编」。解编后，前方列车发车前的等待时间可以用滚轮调整，最长 30 秒。

![](bws:trainsystemutilities:wiki/screens/management-computer-sched-editor__zh_cn.png)

该条件可以在 Create 标准的编辑界面和 TSU 的[电子时刻表](../management-computer/schedule.md#sched-editor)中设置。连挂最多 2 列列车。

连挂期间，编组内共享电力。[受电弓](../electrification/pantograph.md)和 [FE 逆变器](../electrification/fe-inverter.md)也可以搭载在不同的车厢上。解编后，各编组恢复各自的电力处理方式。
