---
title: Management Computer Overview
id: management-computer/overview
tags: [management-computer, overview]
---

# Management Computer Overview

![](bws:trainsystemutilities:wiki/screens/management-computer__map__ja_jp.png)

The central control GUI that oversees the entire railway network from a single block. It holds 6 management screens, switchable via tabs.

[[TOC]]

## How to open

1. **Place** the **Management Computer** block in the world.
2. **Right-click** the block to open this screen.
3. Once open, first **click** the **dropdown at the top-left** (default: "📁 My Route Map ▾"), then **click** the tab you want from the list that appears.

> [!TIP]
> If you are unsure what a button does, **click the "Hints" toggle at the top-right to turn it ON** (green). After that, **hovering** the mouse over a button or item shows a short description. While hovering, press **F1** to jump directly to the wiki page for the feature under the cursor ([Getting Started > Hints and F1](../getting-started.md#hints-and-f1)).

## Basic controls

This screen (and TSU block GUIs in general) is operated by the following rules. Keep them in mind and you will not get lost on any tab.

- **Switch tabs**: click the top-left dropdown → click a tab from the list.
- **Change a number**: **hover the cursor over the value and turn the mouse wheel** (up increases / down decreases). There are no dedicated `+`/`-` buttons.
- **Toggle ON / OFF**: **click** the toggle switch (ON = green).
- **Scroll a list**: when there are too many items to fit, turn the **mouse wheel** over the list (a scrollbar appears on the right).
- **Buttons**: click to execute.
- **Switch access mode**: **click** the face icon at the bottom-right ([below](#owner-face)).

## Tab list

| Tab | Content |
|---|---|
| [Monitor](monitor.md) | Custom layout editor / preview |
| [Route Map](route-map.md) | Map of all stations and tracks |
| [Trains Tab](trains.md) | List and details of all trains (position / speed / electrification) |
| [Schedule Tab](schedule.md) | Schedule list + edit popup |
| [Stations Tab](stations.md) | Station list + station group settings |
| [Line Symbols Tab](line-symbols.md) | Create / assign line symbols |

## Main controls

Shared parts around the screen and how to use them.

| Element | Position | Action | Function |
|---|---|---|---|
| Tab dropdown | Top-left | Click to expand list → click a tab | Switch the active tab |
| Hints toggle | Top-right | Click to turn ON/OFF | When ON, enables hover descriptions + F1 jump |
| Wiki button | Top-right | Click | Opens this page (wiki) |
| owner-face (face icon) | Bottom-right | Click to switch | Private / public toggle ([below](#owner-face)) |
| 🎨 Color button | Bottom | Click | Opens the [Color Settings](color-settings.md) popup |
| ▒ Layout button | Bottom | Click | Opens the [Layout Editor](layout-editor.md) |
| Inventory | Bottom | Click to move items | Player inventory (Container-type V3 screen) |

## Access mode {#owner-face}

Each **click of the face icon** at the bottom-right toggles between private and public.

| Mode | Appearance | Access |
|---|---|---|
| Public | Face with blue border | Anyone can edit |
| Private | Face with red border | Only the placer can edit |

> [!WARNING]
> For multiplayer and mainline operation, **Private (red border)** is recommended. Left public, other players can overwrite your schedules and color settings.

The profile icon can be customized as SVG in [Preset Place > Profile](../preset-place/profile.md).

## Related popups

| Popup | Use |
|---|---|
| [Layout Editor](layout-editor.md) | Free editing of the monitor layout |
| [Color Settings](color-settings.md) | Colors of monitor elements (10 types) |
| [Color Picker](symbol-editor.md#color-picker) | HSV custom colors |
| [Line Symbol Editor](symbol-editor.md) | Create SVG icons |
| [Electrification Detail](#) | Train FE / catenary status |

## Related

- [Railway Management Block](../railway-management.md)
- [Station Range Tool](../tools/station-range-tool.md)
- [Getting Started](../getting-started.md)
