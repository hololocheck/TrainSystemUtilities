---
title: 广播设置（SAS 联动）
id: railway-management/announcement
tags: [station, announcement, sas, audio]
---

# 广播设置（SAS 联动）

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__zh_cn.png)

配合列车通过或停车播放声音的功能。需要 [Spatial Audio System（SAS）](https://github.com/hololocheck/SpatialAudioSystem)。从[铁路管理方块](../railway-management.md)的「功能」中选择「广播」。

[[TOC]]

## 整体设置

用「启用检测」启用・禁用全部广播。「显示范围框」会在自己的画面上显示范围指定板的范围，以及衰减模式下的衰减范围。「衰减模式」用于设置是否按距离减弱声音。

## 登记播放条件

用「添加条目」创建项目，并设置条件和要播放的声音。最多可以登记 16 个项目。点击条件栏，从「无」「通过时」「停车时」中选择。选择「无」时不播放。右键可以清除条件。

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__zh_cn.png)

在各项目的媒体槽中放入 SAS 的记录媒体来指定声音。延迟和播放次数，将光标移到数值上用滚轮调整。延迟以秒为单位，只作用于该项目的第一次播放。负值视为 0。播放次数为 1〜10 次。「测试播放」会播放 1 次第 1 个项目以便确认。

用各项目的删除按钮删除项目时，放入的记录媒体会退回。

## 检测地点与播放范围

[检测卡](../tools/train-detection-card.md)设置检测列车的地点，范围指定板设置声音传达的范围。请分别放入对应的槽位。它们与声音的媒体作用不同。

## 与其他车站共享

打开「共享」，选择要共享的车站，以及检测・范围指定的各项目。从其他车站接受共享时，界面下方会显示共享来源。
