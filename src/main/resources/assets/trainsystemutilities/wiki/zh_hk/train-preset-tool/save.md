---
title: 保存列車預設
id: train-preset-tool/save
tags: [tool, preset, train]
---

# 保存列車預設

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

將編組保存為預設，用於重新放置或共享。手持列車預設工具，用 Alt + 鼠標滾輪切換到「範圍選擇」模式。

[[TOC]]

1. 以包圍要保存的列車的方式，右鍵一個角。
2. 右鍵對角確定範圍。
3. 再次右鍵打開保存界面。
4. 輸入預設名稱，按 Enter 或保存按鈕保存。

![](bws:trainsystemutilities:wiki/screens/train-preset-save__zh_hk.png)

Shift + 右鍵清除範圍。當前模式和指定狀態可以在快捷欄上方的顯示或工具提示中查看。

保存的數據位於 `<world>/trainsystemutilities/presets/<author-uuid>/<name>.tsupreset`（`<world>` 為世界的文件夾）。要在其他世界中使用時，請複製文件，或在 [Preset Place](../preset-place/overview.md) 中公開後再導入。

選擇範圍的上限按體積計為 256×256×256 個方塊。超出上限的範圍不會處理。未加載區塊中的方塊不會包含在內，請在需要的部分已加載的狀態下保存。
