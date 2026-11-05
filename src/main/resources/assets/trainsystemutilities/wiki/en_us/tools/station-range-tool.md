---
title: Station Range Tool
id: tools/station-range-tool
tags: [tool, station]
---

# Station Range Tool

```embed:item id=trainsystemutilities:station_range_tool size=48 label=true
```

Name the space enclosed by two points and register it as a station group. The [Station Name Sign](../structure/station-name-sign.md), [Ticket Vending Machine](../structure/ticket-vending-machine.md), automatic ticket gates, [transit information](transit-terminal.md) and more refer to the shared station information.

[[TOC]]

## Registering a station

1. Switch to "Range Selection" mode with Alt + mouse wheel.
2. Right-click one corner of the station area.
3. Right-click the opposite corner.
4. Right-click to open the save screen, enter the station name and save with Enter.

![](bws:trainsystemutilities:wiki/screens/station-group-save__en_us.png)

The range must include a Create station block, and its volume must be within 64×64×64 blocks. Before saving, switch how tracks are numbered between "Auto", "From Left" and "From Right" with Ctrl + wheel press in Range Selection mode. "From Left" makes the end with the smaller coordinate (west or north) track 1, and "From Right" the end with the larger coordinate (east or south).

## Managing registered stations

![](bws:trainsystemutilities:wiki/screens/station-group-manage__en_us.png)

In "Manage GUI" mode, right-click to rename a station, check its members or delete it. A confirmation screen appears when you delete. In "View (outlines)" mode, you can see the ranges of registered stations as outlines.

![](bws:trainsystemutilities:wiki/screens/station-group-manage-delete__en_us.png)

There are no management commands. Manage stations from this tool's "Manage GUI" mode. The station a piece of equipment belongs to is judged from where it is placed, so register the station's range before using station name signs, ticket vending machines and the like.
