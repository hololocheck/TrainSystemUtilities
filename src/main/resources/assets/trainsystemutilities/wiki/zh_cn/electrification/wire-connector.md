---
title: 接触网与接触网连接工具
id: electrification/wire-connector
tags: [electrification, item, tool]
---

# 接触网与接触网连接工具

```embed:item id=trainsystemutilities:wire_connector size=48 label=true
```

![](bws:trainsystemutilities:wiki/screens/wire-connector__zh_cn.png)

在[接触网绝缘子](insulator.md)之间架设接触网的工具。有"铺设（连接绝缘子）"和"GUI（选择设计）"两种模式，用 Alt + 鼠标滚轮切换。设计在 GUI 模式的界面中选择。

[[TOC]]

## 架设接触网

先放置 2 个绝缘子。切换到铺设模式后，依次右键起点和终点的绝缘子。再次选择同一个绝缘子，或对着空中 Shift + 右键，即可取消起点。连接距离上限为 32 方块，也可以在工具提示中查看。

## 补充接触网

![](bws:trainsystemutilities:wiki/screens/wire-connector__refill__zh_cn.png)

在生存模式下，会按架设的距离消耗余量。在 GUI 模式下右键，按"接触网补给"栏中的"补充接触网"，在打开的"装填接触网线轴"界面中放入接触网线轴。每个线轴补充 100 m，最多可补充到 6,400 m。创造模式下不消耗余量。

## 选择设计

| 设计 | 内容 |
|---|---|
| 自定义（CUSTOM） | 自由设置粗细和间距 |
| 简易式（单线）（SIMPLE） | 一条细线 |
| 双层接触网（TWO_TIER） | 由承力索和接触线组成的双层结构 |
| 双层 × 2列（复线）（TWIN_2ROW） | 双层结构左右并排 2 列 |
| 高位偏移式（HIGH_OFFSET） | 上下间距较大的结构 |

可以用搜索框和筛选器，筛选标准设计和已保存的预设。"简易式（单线）"可以使用让中间下垂的"下垂模式"。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__zh_cn.png)

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__zh_cn.png)

用"应用"确认设置后，会用于之后架设的接触网。从"自定义"保存和删除预设的方法，请参阅[自定义接触网设计](custom-wire.md)。

![](bws:trainsystemutilities:wiki/screens/wire-connector-confirm__zh_cn.png)
