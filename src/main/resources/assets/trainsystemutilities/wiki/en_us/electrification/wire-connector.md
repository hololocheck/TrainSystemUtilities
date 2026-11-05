---
title: Wire & Wire Connector
id: electrification/wire-connector
tags: [electrification, item, tool]
---

# Wire & Wire Connector

```embed:item id=trainsystemutilities:wire_connector size=48 label=true
```

![](bws:trainsystemutilities:wiki/screens/wire-connector__en_us.png)

A tool that strings wires between [Catenary Insulators](insulator.md). It has two modes, "Place (connect insulators)" and "GUI (design select)", and you switch between them with Alt + mouse wheel. You choose the design on the screen of GUI mode.

[[TOC]]

## Stringing a wire

Place two insulators first. In Placement Mode, right-click the start insulator and then the end insulator. To clear the start point, pick the same insulator again or Shift + right-click in the air. A wire can be up to 32 blocks long, which the tooltip also shows.

## Refilling wire

![](bws:trainsystemutilities:wiki/screens/wire-connector__refill__en_us.png)

In survival, stringing a wire uses up the remaining wire by the distance strung. Right-click in GUI mode, press "Refill Wire" in the "Load Wire" section, and put Overhead Wire Spools into the "Load Wire Spools" screen that opens. Each spool adds 100 m, up to 6,400 m. In creative, no wire is used up.

## Choosing a design

| Design | What it is |
|---|---|
| Custom (CUSTOM) | Set the thickness and spacing freely |
| Simple (single line) — SIMPLE | One thin line |
| Two-tier catenary (TWO_TIER) | Two tiers: a messenger wire and a contact wire |
| Twin 2-row (double track) — TWIN_2ROW | The two-tier set, twice, side by side |
| High offset (HIGH_OFFSET) | Two tiers with a wide gap between them |

Use the search box and the filter to narrow down the standard designs and your saved presets. "Simple (single line)" can use "Sag Mode", which makes the middle of the wire hang down.

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__en_us.png)

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__en_us.png)

Confirm the settings with "Apply", and they are used for the wires you string from then on. For saving and deleting presets made from "Custom", see [Custom Wire Designs](custom-wire.md).

![](bws:trainsystemutilities:wiki/screens/wire-connector-confirm__en_us.png)
