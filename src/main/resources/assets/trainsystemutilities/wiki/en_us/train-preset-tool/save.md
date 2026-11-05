---
title: Saving a Train Preset
id: train-preset-tool/save
tags: [tool, preset, train]
---

# Saving a Train Preset

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

Save a consist as a preset to place it again or share it. Hold the Train Preset Tool and switch to "Range Selection" mode with Alt + mouse wheel.

[[TOC]]

1. Right-click one corner so that the range encloses the train you want to save.
2. Right-click the opposite corner to fix the range.
3. Right-click once more to open the save screen.
4. Enter a preset name and save with Enter or the save button.

![](bws:trainsystemutilities:wiki/screens/train-preset-save__en_us.png)

Shift + right-click clears the range. The current mode and selection are shown above the hotbar and in the tooltip.

Saved data goes to `<world>/trainsystemutilities/presets/<author-uuid>/<name>.tsupreset` (`<world>` is the world's folder). To use it in another world, copy the file, or publish it on [Preset Place](../preset-place/overview.md) and bring it in from there.

The selection can be up to 256×256×256 blocks in volume. Larger ranges are not processed. Blocks in chunks that are not loaded are left out, so save while the parts you need are loaded.
