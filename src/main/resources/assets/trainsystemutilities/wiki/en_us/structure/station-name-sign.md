---
title: Station Name Sign
id: structure/station-name-sign
tags: [structure, block, station, sign]
---

# Station Name Sign

```embed:items size=48 cols=2 label=true ids=trainsystemutilities:station_name_sign,trainsystemutilities:station_name_sign_pole
```

A sign that, placed inside a [station group](../tools/station-range-tool.md), shows the station name, line symbol, line color and neighboring stations automatically. It is 4 blocks wide and 1 block high, and shows on both faces. Signs have no settings screen of their own.

[[TOC]]

Right-click where you want it, and the display face turns toward you. It needs 4 blocks of horizontal space. The pole version is 2 blocks high; placed on the underside of a ceiling, the sign body hangs 1 block below.

The station name comes from the station group, and the symbol and band color from the assigned [line symbol](../management-computer/line-symbols.md). The neighboring stations are taken from how the tracks connect. Right after you change tracks or station groups, the neighboring stations can take up to about 30 seconds to update.

If nothing is shown, check the station group's range; if the band or symbol is missing, check the assignment in the [Stations Tab](../management-computer/stations.md) and the [Memory Card](../tools/memory-card.md). If a neighboring station is missing, check the track connections and that the neighbor is registered as a group.
