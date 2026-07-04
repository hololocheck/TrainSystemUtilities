---
title: Line Symbol Editor
id: management-computer/symbol-editor
tags: [management-computer, line-symbol, editor]
---

# Line Symbol Editor

```embed:symbol-editor
```

Opened from the [Line Symbols Tab](line-symbols.md). Edits a symbol's text, color, and shape. The **Color Picker** is part of this editor ([below](#color-picker)).

[[TOC]]

## How to open

1. Right-click the **Management Computer** block to open it.
2. Choose **Line Symbols** from the top-left dropdown ([Line Symbols Tab](line-symbols.md)).
3. Click **"+ New"** to create one. **Left-click** an existing symbol to edit, **right-click** to delete.

## Fields

Click a field, or **hover it and scroll the mouse wheel**, to change its value. **Clicking a color field** opens the Color Picker ([below](#color-picker)).

| Field | Content | How |
|---|---|---|
| Symbol text | 2-3 characters (e.g. `JA`, `M01`) | Click the field and type |
| Shape | Circle / rounded square / hexagon / diamond | Click to cycle |
| Background color | Symbol fill | Click color field → Color Picker |
| Text color | Text color | Click color field → Color Picker |
| Border color | Outline color | Click color field → Color Picker |
| Border width | 0 / 1 / 2 / 3 px | Click to cycle |
| Font | Regular / Bold | Click to cycle |

## Preview

A live preview on the right side of the editor updates in real time.

## Default Templates

Starting points for new symbols:
- Yamanote-style: `JY` (green circle)
- Chuo-style: `JC` (orange circle)
- Ginza-style: `G` (orange rounded square)
- Marunouchi-style: `M` (red circle)

## Saving and Assignment

**Save** → the symbol is added to the [Line Symbols Tab](line-symbols.md) list → assignable to stations from the [Stations Tab](stations.md).

## Color Picker {#color-picker}

A color-selection popup opened by **clicking a color field (background / text / border)** in the symbol editor. The same picker is also used by [Color Settings](color-settings.md), the [Layout Editor](layout-editor.md), and [Railway Management color settings](../railway-management/color.md) (click a color preview to open it).

```embed:color-picker
```

### Input modes

| Mode | Content | How |
|---|---|---|
| HSV color wheel | Hue wheel + brightness slider | Click/drag the wheel |
| RGB sliders | Red / Green / Blue 0-255 | Drag sliders |
| HEX input | `#RRGGBB` | Click the field and type |
| Presets | 12 standard colors | Click to apply |

### History, preview, apply

- The last 8 used colors appear in **History**. Click to reapply.
- The selected color updates the **Preview** area instantly.
- The real color only changes when you press **Apply** (close without applying to keep the original).

## Related

- [Line Symbols Tab](line-symbols.md)
- [Stations Tab](stations.md)
- [Route Map](route-map.md)
