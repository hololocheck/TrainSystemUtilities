---
title: Railway Management Block
id: railway-management
tags: [station, display, block]
---

# Railway Management Block

![](bws:trainsystemutilities:wiki/screens/railway-management__ja_jp.png)

A display board block placed on a station platform. It auto-displays stopped / next trains and integrates with monitors, color settings, and announcements (SAS).

[[TOC]]

## How to open

1. Place the **Railway Management Block** on a station platform. Just like Create's station block, you place it onto a track by **right-clicking a track** you have laid.
2. **Right-click** the placed block to open the GUI.
3. Which station it displays is decided by linking this block to the [Management Computer](management-computer/overview.md) with a [Memory Card](tools/memory-card.md).
4. The first person to right-click it becomes the **owner**. When the face icon in the lower right is set to **Private**, no one but the owner can open it ([Access Mode](getting-started.md#access-mode)).

> [!NOTE]
> This block is placed "on a track", just like a Create station. It cannot be placed on empty ground. Lay a track first, then right-click that track.

## Operation (where to click / scroll)

All operations inside the GUI are done with the **mouse** (no keyboard).

| What you want to do | How |
|---|---|
| Turn the monitor ON / OFF | **Click the toggle** on the monitor row |
| Open monitor settings | **Click the "⚙ Settings" button** on the monitor row → [Monitor Settings](railway-management/settings.md) popup |
| Open color settings | **Click the "▒ Color" button** on the monitor row → [Color Settings](railway-management/color.md) popup |
| Open announcement / platform-door settings | **Click the "Features ▼" button** on the monitor row → from the list that appears, **click "Announcement" or "Platform Door"** |
| Advance the list of next trains | The page switches automatically at set intervals (no manual action needed) |
| Show hints | **Click the "Hint" toggle** in the upper right to turn it ON. In that state, hover the cursor over a button and press **F1** to jump to that feature's wiki description ([How to use F1](getting-started.md#hints-and-f1)) |
| Switch Private / Public | **Click the face icon** in the lower right |

> [!TIP]
> This block is a "display-only" board placed on a station platform. To adjust numbers or colors in bulk, operate from the popup side ([Monitor Settings](railway-management/settings.md) / [Color Settings](railway-management/color.md)). Numbers inside the popup are increased/decreased by **hovering over the value and using the mouse wheel** (there are no ＋ / − buttons).

## Overview

| Feature | Summary |
|---|---|
| Arriving train display | Shows the stopped train on the top row (train name / cars / arrival time / departure time) |
| Next train display | Shows the trains arriving next on the bottom rows (multiple) |
| Line symbol | Displays the [assigned](management-computer/stations.md) line symbol in the header |
| Monitor link | Shows the same content on nearby monitor blocks |
| Color custom | Change the color of each text element via the [color popup](railway-management/color.md) |
| Announcement (SAS) | Departure melodies / announcements via [SpatialAudioSystem](railway-management/announcement.md) integration |
| Batch apply | Apply settings at once to all blocks in the same network |

## GUI primary elements

![](bws:trainsystemutilities:wiki/screens/railway-management__ja_jp.png)

| Element | Function |
|---|---|
| `Hint` toggle | F1 jump + mouse-hover descriptions ON/OFF |
| header-sym | Assigned line symbol (hidden if none) |
| Arriving trains list | 1 entry, the currently stopped train |
| Next trains list | In arrival order from the top, with paging |
| Monitor row | Monitor on/off + status display + Settings / Color / Announcement buttons |
| owner-face | Private/Public toggle |
| Inventory | Player inventory |

## Related popups

| Popup | Content |
|---|---|
| [Monitor Settings](railway-management/settings.md) | Font size / track position / clock display / batch apply |
| [Color Settings](railway-management/color.md) | Colors of 10 text elements (arrTime, depTime, trainName, etc.) |
| [Announcement Settings](railway-management/announcement.md) | SAS-integrated entry management (conditional triggers) |

## Related

- [Management Computer Overview](management-computer/overview.md) — overview management of all stations and trains
- [Monitor Link Card](tools/monitor-link-card.md) — link with nearby monitors
- [Station Range Tool](tools/station-range-tool.md) — register station groups
