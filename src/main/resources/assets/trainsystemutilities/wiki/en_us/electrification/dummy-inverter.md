---
title: Dummy Inverter
id: electrification/dummy-inverter
tags: [electrification, item, block, decoration]
---

# Dummy Inverter

```embed:item id=trainsystemutilities:fe_inverter_dummy size=48 label=true
```

A "looks-only" FE Inverter variant for players who want to use **pantographs purely as decoration** without engaging the FE pickup / drive system.

[[TOC]]

## Overview

Visually and in placement behavior this is a 3-block multiblock completely identical to the real [FE Inverter](fe-inverter.md), but it **has no internal FE buffer** — even when mounted on a train, it performs no electrical processing whatsoever.

## What it can / cannot do

| Feature | Real FE Inverter | Dummy Inverter |
|---|---|---|
| Appearance (model / texture) | ✅ | ✅ (same) |
| 3-block multiblock placement | ✅ | ✅ (same) |
| Electrification info panel in management UI | ✅ | ✅ |
| Pantograph deploy / fold from UI | ✅ | ✅ |
| Wire-contact detection (bar push-down visual) | ✅ | ✅ |
| FE pickup (receiving power from wires) | ✅ | ❌ |
| FE keep-alive cost consumption | ✅ | ❌ |
| Force-stop when all pantographs folded | ✅ | ❌ |
| Power adjacent FE machines | ✅ | ❌ |

## Use cases

### 1. Decorative train pantograph

`[car roof: pantograph + dummy inverter]`

→ When you want the train to *look* electrified but want gameplay to stay on Create's kinetic energy drive.
Since you can still deploy / fold the pantograph from the management UI, you can create effects like lowering the pantograph only while stopped at a station.

### 2. Standalone trackside decoration

Placed on its own without mounting it on a car, it works as a pure decorative block — station equipment cabinets, dummy substation parts, trackside ground gear under the wires, etc. Right-clicking does nothing.

## Installation

1. Obtain it from the Creative inventory TSU tab or via `/give @s trainsystemutilities:fe_inverter_dummy`.
2. Hold it in your hand and **right-click** the position where you want it. It is the same 3-block device as the real FE Inverter.
3. When placed, the clicked position becomes the **CENTER**, and it auto-places 3 blocks along the facing direction (HEAD / TAIL in front / behind).
4. It cannot be placed unless there is free space on both the front and back.
5. **Right-clicking it with an empty hand does nothing** (it's a decorative block, and it has no status display).

## Behavior when mounted on a train

```
[Car 1: pantograph + dummy inverter]
       ↓
   Electrification info panel shows in management UI (FE display reads "Decorative mode")
       ↓
   "Extend All" / "Fold All" pantograph operations work from UI
       ↓
   Passing under a wire → pantograph bar gets pressed down by the wire visually
       ↓
   No FE pickup happens (decorative mode)
```

> [!NOTE]
> **Mixing with real FE Inverters is fine too.**
> If you put 1 real + several dummies on the same train, the train as a whole is treated as an "electrified train", and only the real one(s) handle pickup / FE management. The dummies just add cosmetic slots.

## Caveats

- A train whose **only** inverter is a dummy never accumulates any FE, even running under wires. If you want to run it on electric power, install at least one real [FE Inverter](fe-inverter.md).
- In the management UI, you can tell a dummy from a real one by whether a **`(Decorative mode)`** badge is shown on that car's row in the "Electrification Detail" dialog.

## Related

- [FE Inverter](fe-inverter.md) — the real one (FE buffer enabled)
- [Pantograph](pantograph.md) — current collector
- [Electrification overview](index.md)
