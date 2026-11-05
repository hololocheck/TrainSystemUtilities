---
title: 車站範圍指定工具
id: tools/station-range-tool
tags: [tool, station]
---

# 車站範圍指定工具

```embed:item id=trainsystemutilities:station_range_tool size=48 label=true
```

為兩點圍成的空間命名站名，並登錄為車站組。[站名牌](../structure/station-name-sign.md)、[自動售票機](../structure/ticket-vending-machine.md)、自動檢票機、[換乘查詢](transit-terminal.md)等會參照共同的車站資訊。

[[TOC]]

## 登錄車站

1. 用 Alt + 滾輪切換到「範圍選擇」模式。
2. 右鍵車站區域的一個角。
3. 右鍵對角。
4. 右鍵開啟儲存介面，輸入站名後按 Enter 儲存。

![](bws:trainsystemutilities:wiki/screens/station-group-save__zh_tw.png)

範圍內需要包含 Create 的車站方塊，體積在 64×64×64 個方塊以內。站臺的編號方式，可以在儲存前於範圍選擇模式下用 Ctrl + 按下滾輪，在「自動」「從左側開始」「從右側開始」之間切換。「從左側開始」以座標較小的一端（西或北）為 1 號站臺，「從右側開始」以座標較大的一端（東或南）為 1 號站臺。

## 管理已登錄的車站

![](bws:trainsystemutilities:wiki/screens/station-group-manage__zh_tw.png)

切換到「管理 GUI」模式後右鍵，可以更改站名、檢視成員和刪除。刪除時會顯示確認介面。在「檢視（顯示範圍框）」模式下，可以用邊框確認已登錄車站的範圍。

![](bws:trainsystemutilities:wiki/screens/station-group-manage-delete__zh_tw.png)

沒有管理用命令。車站的管理請通過本工具的「管理 GUI」模式進行。設備所屬的車站根據放置位置判定，因此在使用站名牌、自動售票機等之前，請先登錄車站的範圍。
