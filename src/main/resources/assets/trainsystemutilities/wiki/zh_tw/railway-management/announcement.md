---
title: 廣播設定（SAS 聯動）
id: railway-management/announcement
tags: [station, announcement, sas, audio]
---

# 廣播設定（SAS 聯動）

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__zh_tw.png)

配合列車通過或停車播放聲音的功能。需要 [Spatial Audio System（SAS）](https://github.com/hololocheck/SpatialAudioSystem)。從[鐵路管理方塊](../railway-management.md)的「功能」中選擇「廣播」。

[[TOC]]

## 整體設定

用「啟用檢測」啟用・禁用全部廣播。「顯示範圍框」會在自己的畫面上顯示範圍指定板的範圍，以及衰減模式下的衰減範圍。「衰減模式」用於設定是否按距離減弱聲音。

## 登錄播放條件

用「新增條目」建立項目，並設定條件和要播放的聲音。最多可以登錄 16 個項目。點選條件欄，從「無」「透過時」「停車時」中選擇。選擇「無」時不播放。右鍵可以清除條件。

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__zh_tw.png)

在各項目的媒體槽中放入 SAS 的記錄媒體來指定聲音。延遲和播放次數，將游標移到數值上用滾輪調整。延遲以秒為單位，只作用於該項目的第一次播放。負值視為 0。播放次數為 1〜10 次。「測試播放」會播放 1 次第 1 個項目以便確認。

用各項目的刪除按鈕刪除項目時，放入的記錄媒體會退回。

## 檢測地點與播放範圍

[檢測卡](../tools/train-detection-card.md)設定檢測列車的地點，範圍指定板設定聲音傳達的範圍。請分別放入對應的槽位。它們與聲音的媒體作用不同。

## 與其他車站共享

開啟「共享」，選擇要共享的車站，以及檢測・範圍指定的各項目。從其他車站接受共享時，介面下方會顯示共享來源。
