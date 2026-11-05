---
title: 车站范围指定工具
id: tools/station-range-tool
tags: [tool, station]
---

# 车站范围指定工具

```embed:item id=trainsystemutilities:station_range_tool size=48 label=true
```

为两点围成的空间命名站名，并登记为车站组。[站名牌](../structure/station-name-sign.md)、[自动售票机](../structure/ticket-vending-machine.md)、自动检票机、[换乘查询](transit-terminal.md)等会参照共同的车站信息。

[[TOC]]

## 登记车站

1. 用 Alt + 滚轮切换到「范围选择」模式。
2. 右键车站区域的一个角。
3. 右键对角。
4. 右键打开保存界面，输入站名后按 Enter 保存。

![](bws:trainsystemutilities:wiki/screens/station-group-save__zh_cn.png)

范围内需要包含 Create 的车站方块，体积在 64×64×64 个方块以内。站台的编号方式，可以在保存前于范围选择模式下用 Ctrl + 按下滚轮，在「自动」「从左侧开始」「从右侧开始」之间切换。「从左侧开始」以坐标较小的一端（西或北）为 1 号站台，「从右侧开始」以坐标较大的一端（东或南）为 1 号站台。

## 管理已登记的车站

![](bws:trainsystemutilities:wiki/screens/station-group-manage__zh_cn.png)

切换到「管理 GUI」模式后右键，可以更改站名、查看成员和删除。删除时会显示确认界面。在「查看（显示范围框）」模式下，可以用边框确认已登记车站的范围。

![](bws:trainsystemutilities:wiki/screens/station-group-manage-delete__zh_cn.png)

没有管理用命令。车站的管理请通过本工具的「管理 GUI」模式进行。设备所属的车站根据放置位置判定，因此在使用站名牌、自动售票机等之前，请先登记车站的范围。
