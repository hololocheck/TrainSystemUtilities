---
title: 自動售票機
id: structure/ticket-vending-machine
tags: [structure, block, ticket]
---

# 自動售票機

```embed:item id=trainsystemutilities:ticket_vending_machine size=48 label=true
```

選擇目的地並出票的設備。請留出 2 個方塊高的空間，放置在[車站組](../tools/station-range-tool.md)的範圍內。所屬車站根據位置判定，並作為出發站使用。

[[TOC]]

![](bws:trainsystemutilities:wiki/screens/ticket-vending-machine__main__zh_hk.png)

右鍵打開界面，選擇目的地按鈕後，車票會放入物品欄。車票免費。目的地較多時用滾輪滾動。用關閉按鈕或 Esc 退出。

發售的目的地在[管理用計算機的售票機標籤頁](../management-computer/tickets.md)中設置。目的地顯示連接在同一線路網上的車站，並排除本站。如果在售票機標籤頁中一個車站也沒有選擇，則顯示所有這些車站。售票機標籤頁的設置在整個服務器中共享，會反映到所有自動售票機。在車站組外無法使用，請先登記車站的範圍。

```embed:item id=trainsystemutilities:ticket size=32 label=true
```

車票上記錄了出發站和到達站，可以在工具提示中查看區間。

在自動檢票機，用車票的出發站進站。可以在到達站，或所購區間範圍內的中途車站出站。出站時車票會被回收。車票通過後，檢票機會打開 3 秒。
