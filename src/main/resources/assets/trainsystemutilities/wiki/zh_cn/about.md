---
title: 什么是 TrainSystem Utilities
id: about
---

# 什么是 TrainSystem Utilities

列车管理系统（TrainSystem Utilities，简称 TSU）是 Minecraft **Create MOD（1.21.1）** 的扩展 MOD，为游戏添加与铁路相关的便捷功能。  
在 Create 现有的「轨道 / 车站 / 时刻表 / 信号」基础上，TSU 以 **造型逼真、人人易用** 的形式提供车站显示板、路网总览管理、显示器整合、线路编号、**电气化系统** 等功能。

[[TOC]]

## 本 MOD 能做哪些事？

| 功能 | 说明 |
|---|---|
| 车站级显示板 | 在车站月台上放置「铁路管理方块」，自动显示已停靠 / 即将到站的列车 |
| 路网总览管理 | 使用「管理用计算机」集中管理全部车站 / 列车 / 时刻表 |
| 车站显示器整合 | 在车站附近放置显示器方块即可实时显示信息 |
| 线路编号 | 创建 JA01 / JB02 等自定义线路编号并分配给车站 |
| 连挂 / 解编 | 通过时刻表条件动态地让两列列车连挂 / 解编 |
| 海报显示 | 以轮播方式显示 PNG/JPG 图片的广告板 |
| **电气化系统** | **受电弓 + 接触网 + 箱式变电所 + FE 逆变器，为列车提供 FE / Create 能量** |
| 列车预设 | 将整列列车以 JSON 格式保存 / 恢复 / 分享 |
| Preset Place | 在线共享列车预设（基于 BelugaExperience 平台） |

### 新增物品 / 方块

> [!TIP]
> **右下角带有蓝色标记** 的图标可以点击，跳转到对应页面。

#### 车站与显示 (11)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:railway_management_block,trainsystemutilities:management_computer,trainsystemutilities:poster_management_block,trainsystemutilities:monitor,trainsystemutilities:double_monitor,trainsystemutilities:monitor_half,trainsystemutilities:double_monitor_half,trainsystemutilities:monitor_slim,trainsystemutilities:double_monitor_slim,trainsystemutilities:station_name_sign,trainsystemutilities:station_name_sign_pole links=railway-management,management-computer/overview,poster-management,-,-,-,-,-,-,structure/station-name-sign,structure/station-name-sign
```

#### 工具 (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:station_range_tool,trainsystemutilities:train_preset_tool,trainsystemutilities:transit_terminal links=tools/station-range-tool,train-preset-tool/save,tools/transit-terminal
```

#### 数据卡 (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:memory_card,trainsystemutilities:monitor_link_card,trainsystemutilities:train_detection_card links=tools/memory-card,tools/monitor-link-card,tools/train-detection-card
```

#### 电气化 (6)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

→ 详见 [电气化系统概述](electrification/index.md)。

> [!TIP]
> **本 MOD 并不会「自动驾驶列车」**。列车运行由 Create 的时刻表负责。
> TSU 是「让 Create 列车的查看、管理与供电更加方便」的辅助工具集。

## 适合搭配哪些 MOD 使用？ {#recommended-mods}

| MOD | 作用 | 是否必需？ |
|---|---|---|
| **Create** | 铁路机制核心 | ✅ 必需 |
| **Manta** | GUI / 显示器 / Wiki / BelugaExperience 渲染框架 | ✅ 必需 |
| **SpatialAudioSystem** | 车站发车旋律与广播等音效（作者的另一款 MOD） | 推荐 |
| **Mekanism / Applied Energistics 2** | 为电气化系统提供 FE 能量源 | 使用电气化系统时需要 |
| Create: New Age 等 | 额外的列车机制 | 可选 |
| BSL Shaders 等 | 视觉增强 | 可选 |

> [!NOTE]
> 搭配 **SpatialAudioSystem** 使用时，发车旋律与车内广播会与 TSU 车站显示在同一车站同步播放，大幅提升车站氛围。

## 适合用来做什么？

适合：

- **大规模路网运营**：希望通过单一列表管理众多车站 / 列车
- **氛围感车站建造**：希望通过显示器、线路编号和海报导引赋予车站个性
- **多人联机铁路运营**：希望协同编辑时刻表的同时防止误操作
- **电气化线路建设**：希望通过架空接触网与 FE 供电重现电力列车运行
- **列车预设分享**：希望将自己的列车保存为 JSON，分享到其他世界或与其他玩家共享

不适合：

- **只跑一列货运列车**：在这种规模下，管理用计算机有些大材小用
- **不搭配 Create 使用**：TSU 单独使用时没有任何作用

## 架构组件（面向进阶用户）

- **BelugaExperience UI 系统**：V3 GUI 部件框架（控制器 + JSON 构建器 + 自动尺寸）。位于 `com.manta.api.controller.*` / `com.manta.api.render.*` 包中。
- **MCSS Wiki**：游戏内 markdown wiki + JSON 驱动的 embed（`embed:screen` / `embed:item` / `embed:items`）
- **GUI 截取管线**：登录时所有布局 JSON 通过离屏 FBO 截取 → 作为 DynamicTexture 立即反映到 wiki 中
- **i18n**：在 ja_jp / en_us 之间切换时，运行 `/tsu-wiki-prebuild` 重新生成各语言的截取内容

## 建议先阅读的页面

- [快速开始](getting-started.md) — 最快上手路径
- [管理用计算机概述](management-computer/overview.md) — 中枢 GUI 导览
- [铁路管理方块](railway-management.md) — 车站显示板
- [海报管理方块](poster-management.md) — 图片显示板
- [电气化系统](electrification/pantograph.md)

## 开发状态

> [!IMPORTANT]
> 本 MOD 仍在开发中。设置保存格式与 API 可能会发生变化。
> 在大型世界正式投入使用前，建议先在测试世界中确认其行为。
