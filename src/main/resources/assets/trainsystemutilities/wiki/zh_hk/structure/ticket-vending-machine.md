---
title: 自動售票機
id: structure/ticket-vending-machine
tags: [structure, block, ticket]
---

# 自動售票機

```embed:item id=trainsystemutilities:ticket_vending_machine size=48 label=true
```

車站自動售票機。它是一個 2 格高的櫃體；右鍵它會打開類似真實車站售票機的 UI，可在其中選擇目的地並出票。

[[TOC]]

## 放置與車站鏈接 {#place}

1. 手持自動售票機物品。
2. 在你想放置的位置**右鍵**（櫃體需要 2 格垂直空間，因此上方要留 1 格空位）。它朝向你放置。
3. **將其放置在用**[**車站範圍指定工具**](../tools/station-range-tool.md)**創建的車站組範圍內，會自動鏈接到該車站**（= 該車站成為出發站）。
4. 之後放置在已有範圍內也會連接。若之後再創建範圍，則下次打開售票機時會重新鏈接。

> [!WARNING]
> **放置在任何車站範圍之外的售票機無法使用。** 右鍵它不會打開 UI，而是以紅色顯示「請將其放置在車站範圍內」。請始終將其放置在車站組範圍內。如何創建車站組見[車站範圍指定工具](../tools/station-range-tool.md)。

## 打開 UI 與出票 {#open}

對已放置的售票機**右鍵**打開售票 UI。

- 目的地（= 設為可售的車站）以圓角按鈕列出。較多時用**鼠標滾輪**滾動。
- **左鍵**點擊你要去的車站按鈕，即向你的物品欄出一張**車票**（v1 中免費）。
- 列出的目的地僅是與本機**同屬一個鐵路網絡**且可售的車站（本機所在的車站除外）。
- 標題欄遵循 BelugaExperience 標準（**× 關閉** / **提示開關** / **📖 wiki**）。用 × 按鈕或 Esc 鍵關閉。

## 車票

```embed:item id=trainsystemutilities:ticket size=32 label=true
```

已出票的車票記錄其**出發站與目的地**，在物品提示信息中顯示為「出發站: ○○ / 目的地: △△（有效期至）」。在 v1 中這是信息性物品；檢票閘門驗證功能計劃在未來實現。

## 選擇可售車站

售票機中列出的目的地在[**管理用計算機的車票標籤頁**](../management-computer/tickets.md)中按車站設定，通過切換各車站是否可售來決定。該設置全網共享，對所有售票機生效。

## 相關頁面

- [管理用計算機：車票標籤頁](../management-computer/tickets.md)
- [車站範圍指定工具](../tools/station-range-tool.md)
- [月台柵欄](platform-fence.md) / [月台屏門](platform-screen-door.md)
