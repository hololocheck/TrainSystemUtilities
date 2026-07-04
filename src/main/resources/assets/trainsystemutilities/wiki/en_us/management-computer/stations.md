---
title: Stations Tab
id: management-computer/stations
tags: [management-computer, station]
---

# Stations Tab

![](bws:trainsystemutilities:wiki/screens/management-computer__stations__ja_jp.png)

The Stations tab of the Management Computer. List of all stations, station group management, and line symbol assignment.

[[TOC]]

## How to open

1. **Place** the **Management Computer** block and **right-click** it to open the screen.
2. **Click** the top-left dropdown and choose **"🏯 Stations"**.
3. When there are too many stations to fit the list, turn the **mouse wheel** over the list to scroll.

## Displayed content

| Column | Content |
|---|---|
| Station name | Create station name |
| Group | Owning station group (registered with [Station Range Tool](../tools/station-range-tool.md)) |
| Line symbol | Assigned symbol (e.g. JA01) |
| Train detection | Most recent passing train |

## Station group management

Lists the station groups created with the [Station Range Tool](../tools/station-range-tool.md), where you can:
- Check member stations
- Rename a group
- Delete a group

## Station details and controls

- **Select a station**: **click a station's row** in the list to open its details (station name, position, owning group, door direction, etc.).
- **Return to the list**: **click the "◀ Back" button** in the details.
- **Set the door opening side**: **click** a direction button in the details (**North / South / East / West / Auto / None**) to choose.

## Line symbol assignment (assign popup)

![](bws:trainsystemutilities:wiki/screens/management-computer-station-assign__ja_jp.png)

- **Open the assign popup**: **click the assign button (＋)** on each station's row in the list to open a line symbol list just below that station.
- **Assign a symbol**: **click the line symbol** you want to assign from the list (choosing "None" clears the assignment).
- The symbols you can assign are created in advance in the [Line Symbols Tab](line-symbols.md).

## Line symbols

Symbols created in the [Line Symbols Tab](line-symbols.md) are assigned to stations here.  
Assigned symbols appear in the [Railway Management Block header](../railway-management.md) and on the [Route Map](route-map.md).

## Related

- [Station Range Tool](../tools/station-range-tool.md)
- [Line Symbols Tab](line-symbols.md)
- [Route Map](route-map.md)
- [Railway Management Block](../railway-management.md)
