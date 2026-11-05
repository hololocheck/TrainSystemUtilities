---
title: 列车检测卡
id: tools/train-detection-card
tags: [tool, item, detection]
---

# 列车检测卡

```embed:item id=trainsystemutilities:train_detection_card size=48 label=true
```

记录轨道上一个检测列车地点的卡片。与 SAS 联动，用于配合列车通过播放广播。

[[TOC]]

手持卡片，右键 Create 的轨道。记录的坐标可以在工具提示中查看。Shift + 右键清除。只能登记轨道。

登记后，打开[铁路管理方块](../railway-management.md)的广播设置，将卡片放入检测卡槽位。要播放的音源和条件在铁路管理方块一侧设置。卡片本身没有设置声音或播放条件的界面。

{{if-mod spatialaudiosystem}}
- [广播设置（安装 SAS 时）](../railway-management/announcement.md)
{{/if}}
