---
title: 電子時刻表標籤頁
id: management-computer/schedule
tags: [management-computer, schedule]
---

# 電子時刻表標籤頁

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule__zh_hk.png)

將 Create 的時刻表作為電子時刻表管理。可以編輯、導出到物品，以及共享給其他列車。

[[TOC]]

## 打開方式與時刻表的種類

從管理用計算機的列表中選擇「電子時刻表」，然後點擊列車。

| 狀態 | 內容 |
|---|---|
| 電子時刻表 | 在管理用計算機上編輯・共享的時刻表 |
| 普通時刻表 | 以物品形式交給司機的時刻表。無法在此編輯。 |
| 無 | 未設置時刻表的狀態 |

用界面上方的「停止全部列車」「恢復全部列車」，可以一併操作同一線路網中的列車。停止會在各列車到站後依次進行。

## 設置列車種別 {#種別}

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule-detail__zh_hk.png)

將光標移到詳情中的「種別」上，用滾輪選擇。可以選擇未設置、普通、快速、區間快速、通勤快速、準急、急行、特急。車站和車內的顯示器也會顯示相同的種別。未設置時不顯示標記。

種別為手動設置。不使用舊版中按停靠站數量自動判定的方式。

## 編輯 {#sched-editor}

編輯需要列車處於停止狀態，並且有生物或烈焰人燃燒室司機乘坐。經共享得到的時刻表為只讀。無法編輯時，請查看按鈕上顯示的原因。

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule-editor__zh_hk.png)

按「編輯」會打開設置動作和等待條件的界面。從「添加條目」中選擇指定車站、配送、回收等，並指定需要的車站。各項用箭頭調整順序，用 × 刪除。等待條件的數值用滾輪調整。

選擇循環運行・單向運行，按「應用」保存。按「取消」會放棄更改。右鍵正在使用電子時刻表的烈焰人燃燒室司機，可以切換運行的停止・恢復。

## 導出到物品 {#export}

打開列車詳情，將未填寫的時刻表放入導出欄的輸入槽，會輸出寫有該列車時刻表的物品。啓用「全部導出」後，會一併處理放入的所有時刻表。

## 與其他列車共享 {#share}

![](bws:trainsystemutilities:wiki/screens/management-computer__schedule-share__zh_hk.png)

按詳情中的「共享」，並啓用要共享的列車。編輯共享源時，也會反映到共享目標。共享目標的列車在啓用時以及每次反映編輯時都會停止。經共享得到的時刻表無法直接編輯。
