---
title: 存储卡
id: tools/memory-card
tags: [tool, item, link]
---

# 存储卡

```embed:item id=trainsystemutilities:memory_card size=48 label=true
```

用于链接线路网和车站设备并保存设置的卡片。它与显示器专用的[连接卡](monitor-link-card.md)、[列车检测卡](train-detection-card.md)是不同的物品。

[[TOC]]

## 登记与链接

| 对象 | 右键的效果 |
|---|---|
| Create 的轨道 | 登记线路网络 |
| 站台屏蔽门・护栏 | 一并登记相连的设备 |

将登记了线路网的卡片放入管理用计算机的卡槽，即可链接线路网。要移除站台屏蔽门・护栏时，Shift + 右键该设备（相连的设备会一起移除）。卡片的内容可以在工具提示中查看。对站台屏蔽门・护栏以外的对象 Shift + 右键，会打开初始化确认界面。

![](bws:trainsystemutilities:wiki/screens/memory-card-reset__main__zh_cn.png)

## 保存计算机的设置

1.0.10 及以后，线路编号、对车站的分配、显示器的布局・颜色、电子时刻表的共享设置、链接目标等会保存到插在管理用计算机卡槽中的卡片上。

- 已有设置的卡片：将卡片的内容恢复到计算机。
- 空卡片：保存计算机当前的设置。
- 插入期间：之后的编辑也会自动保存。

即使重新放置计算机，也可以用同一张卡片恢复设置。拔出卡片后，车站的线路编号会暂时不显示，重新插入后恢复。请注意不要丢失或初始化卡片本身。
