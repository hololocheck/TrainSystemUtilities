---
title: Layout Editor
id: management-computer/layout-editor
tags: [management-computer, layout, editor]
---

# Layout Editor

```embed:layout-editor
```

The Layout Editor of the Management Computer. Edits which panels (route map, train list, clock, etc.) are placed where and at what size on the monitor display.

[[TOC]]

## How to open

1. **Place** a **Management Computer** block and **right-click** it to open the screen.
2. **Click the "▒ Layout" button at the bottom** of the screen to open the Layout Editor.
3. The center of the editor has a **preview frame** representing the actual monitor, and a **palette** of placeable panels runs down the left side (or across the top).

## Placeable panels (palette)

The palette holds the following tiles. **Drag them onto the preview frame** to place them.

| Tile | Content |
|---|---|
| 🗺 Route Map | Map of the line network |
| 🚂 Trains | Train list |
| 🕒 Schedule | Schedule |
| 🏯 Station count | Total number of stations |
| 🚆 Train count | Total number of trains |
| 🚦 Signal count | Total number of signals |
| 🕓 Clock | Time display |

## Controls

Inside the preview frame you place, move, and resize panels as follows.

| Action | How | Result |
|---|---|---|
| Add a panel | **Hold a palette tile, drag it onto the preview frame, and release** | A panel is added at that spot |
| Move a panel | **Left-drag** a panel inside the preview | Move its position (auto-adjusted to stay inside the frame) |
| Resize a panel | **Hover over the panel and roll the mouse wheel** (up = larger / down = smaller) | Scales the panel about its center |
| Select a panel | **Click** a panel | Puts it in the selected state |
| Delete a panel | Select it and press the **Delete key** | Deletes the selected panel |
| Per-panel settings | **Click the panel with the middle mouse button (wheel press)** | Opens that panel's feature-specific settings popup ([below](#機能別設定-popup)) |

## Feature-specific settings popup {#機能別設定-popup}

**Middle-clicking** a panel opens a popup where you can individually adjust the size of the text and icons inside that panel.

| Item | Content |
|---|---|
| Text size | Base text size inside the panel |
| Text / line size | Thickness of the text and lines on the map |
| Train icon | Size of the train icons on the map |
| Station icon | Size of the station icons on the map |
| Signal icon | Size of the signal icons on the map |

- **Change a value**: **Hover the cursor over the number and roll the mouse wheel** (up increases / down decreases). **Setting it to 0 makes it "Auto (recommended)"**, computing the optimal value from the monitor and panel sizes.
- **Reset everything to auto**: **Click the "✨ Recommended (Auto)" button**.
- **Close**: **Click the "Close" button**.

## Buttons at the bottom of the editor

| Button | Action | Result |
|---|---|---|
| 🗑 Clear all | Click | Removes all placed panels |
| ✨ Recommended | Click | Auto-arranges a recommended layout |
| ✓ Save | Click | Saves the current layout and closes |

## Saving layouts

"✓ Save" stores the layout inside the Management Computer.  
The same layout is applied to every monitor linked through the [Monitor Link Card](../tools/monitor-link-card.md).

## Related

- [Monitor Display](monitor.md)
- [Color Settings](color-settings.md)
- [Color Picker](symbol-editor.md#color-picker)
- [Monitor Link Card](../tools/monitor-link-card.md)
