---
title: 關於 Train System Utilities
id: about
---

# 關於 Train System Utilities

Train System Utilities（TSU）是擴展 Create 鐵路的 Mod。它以線路、車站、時刻表和訊號為基礎，新增運行管理、車站導向顯示、電氣化，以及列車預設的儲存與共享。

[[TOC]]

## 功能一覽

| 功能 | 內容 |
|---|---|
| 車站列車資訊 | 顯示停靠中的列車和下一班到站的列車。 |
| 線路管理 | 在管理用計算機上檢視車站、列車和時刻表。 |
| 顯示器聯動 | 在顯示器上顯示線路圖、列車資訊和海報。 |
| 線路編號 | 製作編號的顏色和形狀，並分配給車站。 |
| 連掛・解編 | 用時刻表條件連掛、拆分編組。 |
| 電氣化 | 經由變電所和接觸網向列車供應 FE。 |
| 列車預設 | 儲存自製的編組，用於重新放置或線上共享。 |

### 車站・顯示類（11）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:railway_management_block,trainsystemutilities:management_computer,trainsystemutilities:poster_management_block,trainsystemutilities:monitor,trainsystemutilities:double_monitor,trainsystemutilities:monitor_half,trainsystemutilities:double_monitor_half,trainsystemutilities:monitor_slim,trainsystemutilities:double_monitor_slim,trainsystemutilities:station_name_sign,trainsystemutilities:station_name_sign_pole links=railway-management,management-computer/overview,poster-management,-,-,-,-,-,-,structure/station-name-sign,structure/station-name-sign
```

### 工具（3）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:station_range_tool,trainsystemutilities:train_preset_tool,trainsystemutilities:transit_terminal links=tools/station-range-tool,train-preset-tool/save,tools/transit-terminal
```

### 資料卡（3）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:memory_card,trainsystemutilities:monitor_link_card,trainsystemutilities:train_detection_card links=tools/memory-card,tools/monitor-link-card,tools/train-detection-card
```

### 電氣化系統（6）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

## 搭配使用的 Mod {#recommended-mods}

列車的基本行駛和時刻表由 Create 負責，TSU 在此基礎上擴展運行和設備。Manta 作為操作介面等的公共基礎使用，當前支援的版本已內建 Manta。需要另行安裝的前置 Mod，請檢視各自的下載頁面。

同時使用 SAS 時，可以設定配合列車動作的廣播。電氣化需要搭配能夠供應 FE 的設備。附加設備能否連線，請確認各 Mod 提供的 FE 支援情況。

## 適用場景

想管理多個車站和列車、想建造導向顯示和設備齊全的車站、想運營有接觸網的線路時，都可以使用。

## 開發與更新

更新可能會改變存檔格式或 API。變更內容請檢視更新日誌。初次搭建時，先在測試用的世界中確認運作，會更容易上手。
