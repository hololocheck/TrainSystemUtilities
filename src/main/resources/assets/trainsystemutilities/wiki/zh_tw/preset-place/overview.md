---
title: Preset Place 概述
id: preset-place/overview
tags: [preset-place, community, online]
---

# Preset Place 概述

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

TSU 的社群分享功能。釋出你自己創作的列車預設，並瀏覽 / 下載其他使用者製作的預設。

[[TOC]]

## 整體概覽

```
[本地預設] ── 上傳 ──> [Preset Place 伺服器]
                          │
            瀏覽 ←────────┤
            下載 ←────────┘
[其他使用者的世界] <─── 放置
```

後端：基於 BelugaExperience 的 Supabase。
認證：Minecraft 賬號關聯（Microsoft 賬號 → JWT）。

## 各頁面

| 頁面 | 內容 |
|---|---|
| [預設詳情](detail.md) | 單個預設的詳情 + 3D 預覽 + 下載 |
| [個人資料](profile.md) | 使用者主頁 + 公開預設 + 關注 |
| [上傳](upload.md) | 釋出你自己預設的對話方塊（支援 Markdown 說明） |
| [創作者中心](creator-center.md) | 創作者賬號的資料統計 / 儀表盤 |

## 主要功能

| 功能 | 行為 |
|---|---|
| 點贊 | 給你喜歡的預設點 ♥ |
| 下載量 | 線上預設的累計下載次數 |
| 舉報 | 附帶理由舉報不當預設 |
| 關注 | 關注某位創作者 |
| 主頁圖示 | 自定義 SVG 圖示（[主頁圖示編輯器](../management-computer/overview.md#owner-face)） |

## 如何訪問 {#access}

所有 Preset Place 介面都從[**列車預設工具**](../train-preset-tool/browse.md)開啟。

1. **手持****列車預設工具**。
2. 用 **Alt + 滑鼠滾輪**將工具切換到 **GUI 模式**（手持時，當前模式會顯示在快捷欄上方）。
3. **右鍵**開啟列車預設瀏覽介面。
4. 將介面頂部的模式下拉選單切換到 **`Place`（= 公開）**。
5. **左鍵**列表中的某個預設卡片，開啟其[詳情頁](detail.md)。

各介面均經由詳情頁到達。

- **個人資料** … 在公開模式下，點選你自己的名字 / 圖示區域；或在詳情頁點選上傳者的名字。
- **上傳** … 在 `Mine`（你的）模式下，點選你自己預設上的上傳圖示（[上傳](upload.md)）。
- **創作者中心** … 從你自己的[個人資料](profile.md)頁上的"創作者中心"按鈕進入。

> [!NOTE]
> 使用 Preset Place 需要 Microsoft 賬號關聯。首次使用時會要求認證（詳見各頁面）。

## 相關

- [預設詳情](detail.md)
- [個人資料](profile.md)
- [上傳](upload.md)
- [創作者中心](creator-center.md)
- [列車預設瀏覽](../train-preset-tool/browse.md)
