---
title: 保存列车预设
id: train-preset-tool/save
tags: [tool, preset, train]
---

# 保存列车预设

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

将编组保存为预设，用于重新放置或共享。手持列车预设工具，用 Alt + 鼠标滚轮切换到「范围选择」模式。

[[TOC]]

1. 以包围要保存的列车的方式，右键一个角。
2. 右键对角确定范围。
3. 再次右键打开保存界面。
4. 输入预设名称，按 Enter 或保存按钮保存。

![](bws:trainsystemutilities:wiki/screens/train-preset-save__zh_cn.png)

Shift + 右键清除范围。当前模式和指定状态可以在快捷栏上方的显示或工具提示中查看。

保存的数据位于 `<world>/trainsystemutilities/presets/<author-uuid>/<name>.tsupreset`（`<world>` 为世界的文件夹）。要在其他世界中使用时，请复制文件，或在 [Preset Place](../preset-place/overview.md) 中公开后再导入。

选择范围的上限按体积计为 256×256×256 个方块。超出上限的范围不会处理。未加载区块中的方块不会包含在内，请在需要的部分已加载的状态下保存。
