---
title: 自动售票机
id: structure/ticket-vending-machine
tags: [structure, block, ticket]
---

# 自动售票机

```embed:item id=trainsystemutilities:ticket_vending_machine size=48 label=true
```

选择目的地并出票的设备。请留出 2 个方块高的空间，放置在[车站组](../tools/station-range-tool.md)的范围内。所属车站根据位置判定，并作为出发站使用。

[[TOC]]

![](bws:trainsystemutilities:wiki/screens/ticket-vending-machine__main__zh_cn.png)

右键打开界面，选择目的地按钮后，车票会放入物品栏。车票免费。目的地较多时用滚轮滚动。用关闭按钮或 Esc 退出。

发售的目的地在[管理用计算机的售票机标签页](../management-computer/tickets.md)中设置。目的地显示连接在同一线路网上的车站，并排除本站。如果在售票机标签页中一个车站也没有选择，则显示所有这些车站。售票机标签页的设置在整个服务器中共享，会反映到所有自动售票机。在车站组外无法使用，请先登记车站的范围。

```embed:item id=trainsystemutilities:ticket size=32 label=true
```

车票上记录了出发站和到达站，可以在工具提示中查看区间。

在自动检票机，用车票的出发站进站。可以在到达站，或所购区间范围内的中途车站出站。出站时车票会被回收。车票通过后，检票机会打开 3 秒。
