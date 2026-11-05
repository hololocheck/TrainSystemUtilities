---
title: 电气化系统概述
id: electrification/index
tags: [electrification, overview]
---

# 电气化系统概述

```embed:items size=48 cols=7 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:fe_inverter_dummy,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/dummy-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

这套系统把地面 FE 电源的电力，经由变电所、接触网和受电弓送到列车上。列车把收到的电力储存在车上搭载的 FE 逆变器中。

[[TOC]]

## 视频演示

观看从放置箱式变电站到列车靠受电弓取电行驶的完整流程（Manta 的内嵌视频功能）。

```embed:youtube url=https://youtu.be/p5o8KkhweF8 title="Create Train Electrification"
```

## 导入流程

1. 准备一个输出 FE 的电源。
2. 放置[箱式变电站](substation.md)，并接上电源。
3. 在变电所旁边和沿线路的支撑点放置[接触网绝缘子](insulator.md)。
4. 用[接触网连接工具](wire-connector.md)把绝缘子相互连接。
5. 在列车车顶放置[受电弓](pantograph.md)，在车厢上放置 [FE 逆变器](fe-inverter.md)，然后组装列车。
6. 升起受电弓，在已通电的接触网下确认集电和蓄电。

[接触网支柱](overhead-pole.md)和[接触网桁架](overhead-truss.md)是支撑绝缘子的底座。接触网不直接连接到它们，而是连接到绝缘子上。你还可以使用帮助布置的[接触网支柱布置辅助工具](../tools/overhead-pole-auto-tool.md)、装饰用的[逆变器占位方块](dummy-inverter.md)，以及检查状态的[电力检查器](power-checker.md)。

电气化是附加功能。没有搭载电气化设备的列车，可以继续按 Create 的标准方式运行。同时搭载受电弓和 FE 逆变器的列车被视为电气化列车，行驶时整列车消耗 1,500 FE/tick。储存的 FE 降到 0 时列车会停下，在补充 FE 之前无法再次起步。

搭载 FE 逆变器的车厢，每节最多储存 50,000 FE。升起的受电弓接触已通电的接触网期间，每台受电弓集电 2,000 FE/tick。

即使区块未加载，变电所也会继续向接触网供电。位于未加载区块中的列车既不集电，也不消耗 FE。

进入已通电接触网两端绝缘子之间的直线 1 方块以内的玩家和生物，每 0.5 秒会受到 1 点（半颗心）触电伤害。创造模式和旁观模式下不会受到伤害。
