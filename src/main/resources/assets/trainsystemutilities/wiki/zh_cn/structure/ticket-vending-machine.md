---
title: 自动售票机
id: structure/ticket-vending-machine
tags: [structure, block, ticket]
---

# 自动售票机

```embed:item id=trainsystemutilities:ticket_vending_machine size=48 label=true
```

车站自动售票机。它是一个 2 格高的柜体；右键它会打开类似真实车站售票机的 UI，可在其中选择目的地并出票。

[[TOC]]

## 放置与车站链接 {#place}

1. 手持自动售票机物品。
2. 在你想放置的位置**右键**（柜体需要 2 格垂直空间，因此上方要留 1 格空位）。它朝向你放置。
3. **将其放置在用**[**车站范围指定工具**](../tools/station-range-tool.md)**创建的车站组范围内，会自动链接到该车站**（= 该车站成为出发站）。
4. 之后放置在已有范围内也会连接。若之后再创建范围，则下次打开售票机时会重新链接。

> [!WARNING]
> **放置在任何车站范围之外的售票机无法使用。** 右键它不会打开 UI，而是以红色显示「请将其放置在车站范围内」。请始终将其放置在车站组范围内。如何创建车站组见[车站范围指定工具](../tools/station-range-tool.md)。

## 打开 UI 与出票 {#open}

对已放置的售票机**右键**打开售票 UI。

- 目的地（= 设为可售的车站）以圆角按钮列出。较多时用**鼠标滚轮**滚动。
- **左键**点击你要去的车站按钮，即向你的物品栏出一张**车票**（v1 中免费）。
- 列出的目的地仅是与本机**同属一个铁路网络**且可售的车站（本机所在的车站除外）。
- 标题栏遵循 BelugaExperience 标准（**× 关闭** / **提示开关** / **📖 wiki**）。用 × 按钮或 Esc 键关闭。

## 车票

```embed:item id=trainsystemutilities:ticket size=32 label=true
```

已出票的车票记录其**出发站与目的地**，在物品提示信息中显示为「出发站: ○○ / 目的地: △△（有效期至）」。在 v1 中这是信息性物品；检票闸门验证功能计划在未来实现。

## 选择可售车站

售票机中列出的目的地在[**管理用计算机的车票标签页**](../management-computer/tickets.md)中按车站设定，通过切换各车站是否可售来决定。该设置全网共享，对所有售票机生效。

## 相关页面

- [管理用计算机：车票标签页](../management-computer/tickets.md)
- [车站范围指定工具](../tools/station-range-tool.md)
- [月台栅栏](platform-fence.md) / [月台屏门](platform-screen-door.md)
