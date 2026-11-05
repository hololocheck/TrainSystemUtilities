---
title: 自定义接触网设计
id: electrification/custom-wire
tags: [electrification, customization, tool]
---

# 自定义接触网设计

![](bws:trainsystemutilities:wiki/screens/wire-connector__custom__zh_cn.png)

在[接触网连接工具](wire-connector.md)的 GUI 模式下打开设置界面，选择"自定义"即可编辑。在数值上滚动鼠标滚轮，结果会反映到右侧的预览中。

[[TOC]]

| 项目 | 范围 | 变化单位 |
|---|---|---|
| 粗细 | 0.01～0.30 | 0.01 |
| 上下间距 | 0～2.00 m | 0.05 m |
| 吊杆间距 | 0.50～10.00 m | 0.25 m |
| 2列布局 | 启用 / 禁用 | 切换 |

上下间距为 0 时为单层，大于 0 时为双层。设置的数值会保持在上下限范围内。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__zh_cn.png)

用"保存预设"输入名称，按 Enter 保存，按 Esc 取消。保存后可以从列表中选择。选中已保存的预设时数值会被固定，要编辑时请返回"自定义"。修改后的版本请另存为新预设，不需要的预设可以右键删除。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__zh_cn.png)

下垂模式仅适用于"简易式（单线）"，"自定义"为直线结构。接触网属于世界一侧的设施，不包含在列车预设中。接触网设计的预设保存在接触网连接工具这个物品本身，每个工具最多可保存 64 个。
