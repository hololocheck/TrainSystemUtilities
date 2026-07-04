---
title: Station Range Tool
id: tools/station-range-tool
tags: [tool, station]
---

# Station Range Tool

```embed:item id=trainsystemutilities:station_range_tool size=48 label=true
```

![](bws:trainsystemutilities:wiki/screens/station-group-save__ja_jp.png)

A tool that registers a station area as a **station group** by specifying two points.  
It bundles multiple railway management blocks as one station, letting you apply common settings (color / settings / announcement) all at once.

[[TOC]]

## Mode switching

This tool switches between three modes with **Alt + mouse wheel** (while held, the current mode is shown above the hotbar).

| Mode | Behavior |
|---|---|
| Select (default) | Left-click the two corners of the range to create a station group |
| GUI | Right-click to open the station group management GUI |
| Show | Display the outlines of existing station groups in the world |

## Usage (Select mode)

1. Hold the tool and **left-click** the first corner of the station area.
2. **Left-click** the opposite corner.
3. Right-click the tool → station group save GUI is shown.
4. Enter the station group name → Enter to save.

## Track numbering modes

At save time, you can choose a **track auto-numbering** mode:

| Mode | Behavior |
|---|---|
| AUTO | Auto-numbered with inside = track 1 |
| LEFT | Left edge = track 1 |
| RIGHT | Right edge = track 1 |

## Station group management

![](bws:trainsystemutilities:wiki/screens/station-group-manage__ja_jp.png)

Open the management GUI via `/tsu sg manage` etc. to:
- Rename
- Delete (with confirmation dialog)
- Check member stations

of a saved station group.

![](bws:trainsystemutilities:wiki/screens/station-group-manage-delete__ja_jp.png)

## Where station groups are used

- [Management Computer Stations Tab](../management-computer/stations.md) assigns a line symbol to the group
- [Railway Management Block batch apply](../railway-management/settings.md#batch-apply) applies settings to all in the same group at once
- Share destination (share) for [SAS Announcement](../railway-management/announcement.md)
- **Candidate sale stations** for [Ticket Vending Machines](../structure/ticket-vending-machine.md) (chosen from station groups in the [Tickets Tab](../management-computer/tickets.md))
- Recognition of stations targeted by **automatic ticket gates**
- Target of **route search** for the [Transit Terminal](transit-terminal.md) (search using station groups as origin / destination)

> [!NOTE]
> Ticket vending machines, automatic ticket gates, and transit routing all **reference the station groups created with this tool as shared data.** Before using them, first register the target stations as station groups.

## Related

- [Railway Management Block](../railway-management.md)
- [Management Computer Stations Tab](../management-computer/stations.md)
- [Memory Card](memory-card.md)
