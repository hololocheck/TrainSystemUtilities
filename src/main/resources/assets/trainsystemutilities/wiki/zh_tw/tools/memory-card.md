---
title: 儲存卡
id: tools/memory-card
tags: [tool, item, link]
---

# 儲存卡

```embed:item id=trainsystemutilities:memory_card size=48 label=true
```

用於連結線路網和車站設備並儲存設定的卡片。它與顯示器專用的[連線卡](monitor-link-card.md)、[列車檢測卡](train-detection-card.md)是不同的物品。

[[TOC]]

## 登錄與連結

| 對象 | 右鍵的效果 |
|---|---|
| Create 的軌道 | 登錄線路網路 |
| 站臺遮蔽門・護欄 | 一併登錄相連的設備 |

將登錄了線路網的卡片放入管理用計算機的卡槽，即可連結線路網。要移除站臺遮蔽門・護欄時，Shift + 右鍵該設備（相連的設備會一起移除）。卡片的內容可以在工具提示中檢視。對站臺遮蔽門・護欄以外的對象 Shift + 右鍵，會開啟初始化確認介面。

![](bws:trainsystemutilities:wiki/screens/memory-card-reset__main__zh_tw.png)

## 儲存計算機的設定

1.0.10 及以後，線路編號、對車站的分配、顯示器的佈局・顏色、電子時刻表的共享設定、連結目標等會儲存到插在管理用計算機卡槽中的卡片上。

- 已有設定的卡片：將卡片的內容恢復到計算機。
- 空卡片：儲存計算機當前的設定。
- 插入期間：之後的編輯也會自動儲存。

即使重新放置計算機，也可以用同一張卡片恢復設定。拔出卡片後，車站的線路編號會暫時不顯示，重新插入後恢復。請注意不要丟失或初始化卡片本身。
