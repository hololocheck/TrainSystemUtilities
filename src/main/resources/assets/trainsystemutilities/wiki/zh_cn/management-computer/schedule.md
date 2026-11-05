---
title: 电子时刻表标签页
id: management-computer/schedule
tags: [management-computer, schedule]
---

# 电子时刻表标签页

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule__zh_cn.png)

将 Create 的时刻表作为电子时刻表管理。可以编辑、导出到物品，以及共享给其他列车。

[[TOC]]

## 打开方式与时刻表的种类

从管理用计算机的列表中选择「电子时刻表」，然后点击列车。

| 状态 | 内容 |
|---|---|
| 电子时刻表 | 在管理用计算机上编辑・共享的时刻表 |
| 普通时刻表 | 以物品形式交给司机的时刻表。无法在此编辑。 |
| 无 | 未设置时刻表的状态 |

用界面上方的「停止全部列车」「恢复全部列车」，可以一并操作同一线路网中的列车。停止会在各列车到站后依次进行。

## 设置列车种别 {#種別}

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule-detail__zh_cn.png)

将光标移到详情中的「种别」上，用滚轮选择。可以选择未设置、普通、快速、区间快速、通勤快速、准急、急行、特急。车站和车内的显示器也会显示相同的种别。未设置时不显示标记。

种别为手动设置。不使用旧版中按停靠站数量自动判定的方式。

## 编辑 {#sched-editor}

编辑需要列车处于停止状态，并且有生物或烈焰人燃烧室司机乘坐。经共享得到的时刻表为只读。无法编辑时，请查看按钮上显示的原因。

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule-editor__zh_cn.png)

按「编辑」会打开设置动作和等待条件的界面。从「添加条目」中选择指定车站、配送、回收等，并指定需要的车站。各项用箭头调整顺序，用 × 删除。等待条件的数值用滚轮调整。

选择循环运行・单向运行，按「应用」保存。按「取消」会放弃更改。右键正在使用电子时刻表的烈焰人燃烧室司机，可以切换运行的停止・恢复。

## 导出到物品 {#export}

打开列车详情，将未填写的时刻表放入导出栏的输入槽，会输出写有该列车时刻表的物品。启用「全部导出」后，会一并处理放入的所有时刻表。

## 与其他列车共享 {#share}

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule-share__zh_cn.png)

按详情中的「共享」，并启用要共享的列车。编辑共享源时，也会反映到共享目标。共享目标的列车在启用时以及每次反映编辑时都会停止。经共享得到的时刻表无法直接编辑。
