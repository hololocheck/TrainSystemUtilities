---
title: 关于 Train System Utilities
id: about
---

# 关于 Train System Utilities

Train System Utilities（TSU）是扩展 Create 铁路的 Mod。它以线路、车站、时刻表和信号为基础，添加运行管理、车站导向显示、电气化，以及列车预设的保存与共享。

[[TOC]]

## 功能一览

| 功能 | 内容 |
|---|---|
| 车站列车信息 | 显示停靠中的列车和下一班到站的列车。 |
| 线路管理 | 在管理用计算机上查看车站、列车和时刻表。 |
| 显示器联动 | 在显示器上显示线路图、列车信息和海报。 |
| 线路编号 | 制作编号的颜色和形状，并分配给车站。 |
| 连挂・解编 | 用时刻表条件连挂、拆分编组。 |
| 电气化 | 经由变电所和接触网向列车供应 FE。 |
| 列车预设 | 保存自制的编组，用于重新放置或在线共享。 |

### 车站・显示类（11）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:railway_management_block,trainsystemutilities:management_computer,trainsystemutilities:poster_management_block,trainsystemutilities:monitor,trainsystemutilities:double_monitor,trainsystemutilities:monitor_half,trainsystemutilities:double_monitor_half,trainsystemutilities:monitor_slim,trainsystemutilities:double_monitor_slim,trainsystemutilities:station_name_sign,trainsystemutilities:station_name_sign_pole links=railway-management,management-computer/overview,poster-management,-,-,-,-,-,-,structure/station-name-sign,structure/station-name-sign
```

### 工具（3）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:station_range_tool,trainsystemutilities:train_preset_tool,trainsystemutilities:transit_terminal links=tools/station-range-tool,train-preset-tool/save,tools/transit-terminal
```

### 数据卡（3）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:memory_card,trainsystemutilities:monitor_link_card,trainsystemutilities:train_detection_card links=tools/memory-card,tools/monitor-link-card,tools/train-detection-card
```

### 电气化系统（6）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

## 搭配使用的 Mod {#recommended-mods}

列车的基本行驶和时刻表由 Create 负责，TSU 在此基础上扩展运行和设备。Manta 作为操作界面等的公共基础使用，当前支持的版本已内置 Manta。需要另行安装的前置 Mod，请查看各自的下载页面。

同时使用 SAS 时，可以设置配合列车动作的广播。电气化需要搭配能够供应 FE 的设备。附加设备能否连接，请确认各 Mod 提供的 FE 支持情况。

## 适用场景

想管理多个车站和列车、想建造导向显示和设备齐全的车站、想运营有接触网的线路时，都可以使用。

## 开发与更新

更新可能会改变存档格式或 API。变更内容请查看更新日志。初次搭建时，先在测试用的世界中确认运作，会更容易上手。
