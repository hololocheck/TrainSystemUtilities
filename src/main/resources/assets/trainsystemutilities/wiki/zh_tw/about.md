---
title: 什麼是 TrainSystem Utilities
id: about
---

# 什麼是 TrainSystem Utilities

列車管理系統（TrainSystem Utilities，簡稱 TSU）是 Minecraft **Create MOD（1.21.1）** 的擴充套件 MOD，為遊戲新增與鐵路相關的便捷功能。  
在 Create 現有的「軌道 / 車站 / 時刻表 / 訊號」基礎上，TSU 以 **造型逼真、人人易用** 的形式提供車站顯示板、路網總覽管理、顯示器整合、線路編號、**電氣化系統** 等功能。

[[TOC]]

## 本 MOD 能做哪些事？

| 功能 | 說明 |
|---|---|
| 車站級顯示板 | 在車站月臺上放置「鐵路管理方塊」，自動顯示已停靠 / 即將到站的列車 |
| 路網總覽管理 | 使用「管理用計算機」集中管理全部車站 / 列車 / 時刻表 |
| 車站顯示器整合 | 在車站附近放置顯示器方塊即可實時顯示資訊 |
| 線路編號 | 建立 JA01 / JB02 等自定義線路編號並分配給車站 |
| 連掛 / 解編 | 透過時刻表條件動態地讓兩列列車連掛 / 解編 |
| 海報顯示 | 以輪播方式顯示 PNG/JPG 圖片的廣告板 |
| **電氣化系統** | **受電弓 + 接觸網 + 箱式變電所 + FE 逆變器，為列車提供 FE / Create 能量** |
| 列車預設 | 將整列列車以 JSON 格式儲存 / 恢復 / 分享 |
| Preset Place | 線上共享列車預設（基於 BelugaExperience 平臺） |

### 新增物品 / 方塊

> [!TIP]
> **右下角帶有藍色標記** 的圖示可以點選，跳轉到對應頁面。

#### 車站與顯示 (11)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:railway_management_block,trainsystemutilities:management_computer,trainsystemutilities:poster_management_block,trainsystemutilities:monitor,trainsystemutilities:double_monitor,trainsystemutilities:monitor_half,trainsystemutilities:double_monitor_half,trainsystemutilities:monitor_slim,trainsystemutilities:double_monitor_slim,trainsystemutilities:station_name_sign,trainsystemutilities:station_name_sign_pole links=railway-management,management-computer/overview,poster-management,-,-,-,-,-,-,structure/station-name-sign,structure/station-name-sign
```

#### 工具 (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:station_range_tool,trainsystemutilities:train_preset_tool,trainsystemutilities:transit_terminal links=tools/station-range-tool,train-preset-tool/save,tools/transit-terminal
```

#### 資料卡 (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:memory_card,trainsystemutilities:monitor_link_card,trainsystemutilities:train_detection_card links=tools/memory-card,tools/monitor-link-card,tools/train-detection-card
```

#### 電氣化 (6)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

→ 詳見 [電氣化系統概述](electrification/index.md)。

> [!TIP]
> **本 MOD 並不會「自動駕駛列車」**。列車執行由 Create 的時刻表負責。
> TSU 是「讓 Create 列車的檢視、管理與供電更加方便」的輔助工具集。

## 適合搭配哪些 MOD 使用？ {#recommended-mods}

| MOD | 作用 | 是否必需？ |
|---|---|---|
| **Create** | 鐵路機制核心 | ✅ 必需 |
| **Manta** | GUI / 顯示器 / Wiki / BelugaExperience 渲染框架 | ✅ 必需 |
| **SpatialAudioSystem** | 車站發車旋律與廣播等音效（作者的另一款 MOD） | 推薦 |
| **Mekanism / Applied Energistics 2** | 為電氣化系統提供 FE 能量源 | 使用電氣化系統時需要 |
| Create: New Age 等 | 額外的列車機制 | 可選 |
| BSL Shaders 等 | 視覺增強 | 可選 |

> [!NOTE]
> 搭配 **SpatialAudioSystem** 使用時，發車旋律與車內廣播會與 TSU 車站顯示在同一車站同步播放，大幅提升車站氛圍。

## 適合用來做什麼？

適合：

- **大規模路網運營**：希望透過單一列表管理眾多車站 / 列車
- **氛圍感車站建造**：希望透過顯示器、線路編號和海報導引賦予車站個性
- **多人聯機鐵路運營**：希望協同編輯時刻表的同時防止誤操作
- **電氣化線路建設**：希望透過架空接觸網與 FE 供電重現電力列車執行
- **列車預設分享**：希望將自己的列車儲存為 JSON，分享到其他世界或與其他玩家共享

不適合：

- **只跑一列貨運列車**：在這種規模下，管理用計算機有些大材小用
- **不搭配 Create 使用**：TSU 單獨使用時沒有任何作用

## 架構元件（面向進階使用者）

- **BelugaExperience UI 系統**：V3 GUI 部件框架（控制器 + JSON 構建器 + 自動尺寸）。位於 `com.manta.api.controller.*` / `com.manta.api.render.*` 包中。
- **MCSS Wiki**：遊戲內 markdown wiki + JSON 驅動的 embed（`embed:screen` / `embed:item` / `embed:items`）
- **GUI 擷取管線**：登入時所有佈局 JSON 透過離屏 FBO 擷取 → 作為 DynamicTexture 立即反映到 wiki 中
- **i18n**：在 ja_jp / en_us 之間切換時，執行 `/tsu-wiki-prebuild` 重新生成各語言的擷取內容

## 建議先閱讀的頁面

- [快速開始](getting-started.md) — 最快上手路徑
- [管理用計算機概述](management-computer/overview.md) — 中樞 GUI 導覽
- [鐵路管理方塊](railway-management.md) — 車站顯示板
- [海報管理方塊](poster-management.md) — 圖片顯示板
- [電氣化系統](electrification/pantograph.md)

## 開發狀態

> [!IMPORTANT]
> 本 MOD 仍在開發中。設定儲存格式與 API 可能會發生變化。
> 在大型世界正式投入使用前，建議先在測試世界中確認其行為。
