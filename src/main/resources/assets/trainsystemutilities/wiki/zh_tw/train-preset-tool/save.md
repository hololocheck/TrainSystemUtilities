---
title: 儲存列車預設
id: train-preset-tool/save
tags: [tool, preset, train]
---

# 儲存列車預設

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

將編組儲存為預設，用於重新放置或共享。手持列車預設工具，用 Alt + 滑鼠滾輪切換到「範圍選擇」模式。

[[TOC]]

1. 以包圍要儲存的列車的方式，右鍵一個角。
2. 右鍵對角確定範圍。
3. 再次右鍵開啟儲存介面。
4. 輸入預設名稱，按 Enter 或儲存按鈕儲存。

![](bws:trainsystemutilities:wiki/screens/train-preset-save__zh_tw.png)

Shift + 右鍵清除範圍。當前模式和指定狀態可以在快捷欄上方的顯示或工具提示中檢視。

儲存的資料位於 `<world>/trainsystemutilities/presets/<author-uuid>/<name>.tsupreset`（`<world>` 為世界的資料夾）。要在其他世界中使用時，請複製檔案，或在 [Preset Place](../preset-place/overview.md) 中公開後再導入。

選擇範圍的上限按體積計為 256×256×256 個方塊。超出上限的範圍不會處理。未載入區塊中的方塊不會包含在內，請在需要的部分已載入的狀態下儲存。
