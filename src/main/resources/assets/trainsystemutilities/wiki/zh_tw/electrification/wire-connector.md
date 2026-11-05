---
title: 接觸網與接觸網連線工具
id: electrification/wire-connector
tags: [electrification, item, tool]
---

# 接觸網與接觸網連線工具

```embed:item id=trainsystemutilities:wire_connector size=48 label=true
```

![](bws:trainsystemutilities:wiki/screens/wire-connector__zh_tw.png)

在[接觸網絕緣子](insulator.md)之間架設接觸網的工具。有「鋪設（連線絕緣子）」和「GUI（選擇設計）」兩種模式，用 Alt + 滑鼠滾輪切換。設計在 GUI 模式的介面中選擇。

[[TOC]]

## 架設接觸網

先放置 2 個絕緣子。切換到鋪設模式後，依次右鍵起點和終點的絕緣子。再次選擇同一個絕緣子，或對著空中 Shift + 右鍵，即可取消起點。連線距離上限為 32 方塊，也可以在工具提示中查看。

## 補充接觸網

![](bws:trainsystemutilities:wiki/screens/wire-connector__refill__zh_tw.png)

在生存模式下，會按架設的距離消耗餘量。在 GUI 模式下右鍵，按「接觸網補給」欄中的「補充接觸網」，在開啟的「裝填接觸網線軸」介面中放入接觸網線軸。每個線軸補充 100 m，最多可補充到 6,400 m。創造模式下不消耗餘量。

## 選擇設計

| 設計 | 內容 |
|---|---|
| 自定義（CUSTOM） | 自由設定粗細和間距 |
| 簡易式（單線）（SIMPLE） | 一條細線 |
| 雙層接觸網（TWO_TIER） | 由承力索和接觸線組成的雙層結構 |
| 雙層 × 2列（複線）（TWIN_2ROW） | 雙層結構左右並排 2 列 |
| 高位偏移式（HIGH_OFFSET） | 上下間距較大的結構 |

可以用搜尋框和篩選器，篩選標準設計和已儲存的預設。「簡易式（單線）」可以使用讓中間下垂的「下垂模式」。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__zh_tw.png)

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__zh_tw.png)

用「應用」確認設定後，會用於之後架設的接觸網。從「自定義」儲存和刪除預設的方法，請參閱[自定義接觸網設計](custom-wire.md)。

![](bws:trainsystemutilities:wiki/screens/wire-connector-confirm__zh_tw.png)
