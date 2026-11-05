---
title: 電氣化系統概述
id: electrification/index
tags: [electrification, overview]
---

# 電氣化系統概述

```embed:items size=48 cols=7 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:fe_inverter_dummy,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/dummy-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

這套系統把地面 FE 電源的電力，經由變電所、接觸網和受電弓送到列車上。列車把收到的電力儲存在車上搭載的 FE 逆變器中。

[[TOC]]

## 影片演示

觀看從放置箱式變電站到列車靠受電弓取電行駛的完整流程（Manta 的內嵌影片功能）。

```embed:youtube url=https://youtu.be/p5o8KkhweF8 title="Create Train Electrification"
```

## 導入流程

1. 準備一個輸出 FE 的電源。
2. 放置[箱式變電站](substation.md)，並接上電源。
3. 在變電所旁邊和沿線路的支撐點放置[接觸網絕緣子](insulator.md)。
4. 用[接觸網連線工具](wire-connector.md)把絕緣子互相連線。
5. 在列車車頂放置[受電弓](pantograph.md)，在車廂上放置 [FE 逆變器](fe-inverter.md)，然後組裝列車。
6. 升起受電弓，在已通電的接觸網下確認集電和蓄電。

[接觸網支柱](overhead-pole.md)和[接觸網桁架](overhead-truss.md)是支撐絕緣子的底座。接觸網不直接連接到它們，而是連接到絕緣子上。你還可以使用幫助佈置的[接觸網支柱佈置輔助工具](../tools/overhead-pole-auto-tool.md)、裝飾用的[逆變器佔位方塊](dummy-inverter.md)，以及檢查狀態的[電力檢查器](power-checker.md)。

電氣化是附加功能。沒有搭載電氣化設備的列車，可以繼續按 Create 的標準方式運行。同時搭載受電弓和 FE 逆變器的列車被視為電氣化列車，行駛時整列車消耗 1,500 FE/tick。儲存的 FE 降到 0 時列車會停下，在補充 FE 之前無法再次起步。

搭載 FE 逆變器的車廂，每節最多儲存 50,000 FE。升起的受電弓接觸已通電的接觸網期間，每台受電弓集電 2,000 FE/tick。

即使區塊未載入，變電所也會繼續向接觸網供電。位於未載入區塊中的列車既不集電，也不消耗 FE。

進入已通電接觸網兩端絕緣子之間的直線 1 方塊以內的玩家和生物，每 0.5 秒會受到 1 點（半顆心）觸電傷害。創造模式和旁觀模式下不會受到傷害。
