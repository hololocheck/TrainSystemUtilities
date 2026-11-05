---
title: 自定義接觸網設計
id: electrification/custom-wire
tags: [electrification, customization, tool]
---

# 自定義接觸網設計

![](bws:trainsystemutilities:wiki/screens/wire-connector__custom__zh_tw.png)

在[接觸網連線工具](wire-connector.md)的 GUI 模式下開啟設定介面，選擇「自定義」即可編輯。在數值上滾動滑鼠滾輪，結果會反映到右側的預覽中。

[[TOC]]

| 項目 | 範圍 | 變化單位 |
|---|---|---|
| 粗細 | 0.01～0.30 | 0.01 |
| 上下間距 | 0～2.00 m | 0.05 m |
| 吊杆間距 | 0.50～10.00 m | 0.25 m |
| 2列布局 | 啟用 / 禁用 | 切換 |

上下間距為 0 時為單層，大於 0 時為雙層。設定的數值會保持在上下限範圍內。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__zh_tw.png)

用「儲存預設」輸入名稱，按 Enter 儲存，按 Esc 取消。儲存後可以從清單中選擇。選中已儲存的預設時數值會被固定，要編輯時請返回「自定義」。修改後的版本請另存為新預設，不需要的預設可以右鍵刪除。

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__zh_tw.png)

下垂模式僅適用於「簡易式（單線）」，「自定義」為直線結構。接觸網屬於世界一側的設施，不包含在列車預設中。接觸網設計的預設儲存在接觸網連線工具這個物品本身，每個工具最多可儲存 64 個。
