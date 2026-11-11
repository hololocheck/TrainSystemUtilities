---
title: Electrification System Overview
id: electrification/index
tags: [electrification, overview]
---

# Electrification System Overview

```embed:items size=48 cols=7 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:fe_inverter_dummy,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/dummy-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

This system carries power from an FE source on the ground to trains, through a substation, overhead wires and pantographs. A train stores the power it receives in the FE Inverters it carries.

[[TOC]]

## Video walkthrough

Watch the full flow from placing the Cubicle to a train running on the power its pantograph collects (Manta's embedded video feature).

```embed:youtube url=https://youtu.be/p5o8KkhweF8 title="Create Train Electrification" url.ja=https://youtu.be/-oco9pSUWQ4 url.zh=https://youtu.be/L1cS29JTroY title.ja="TSU ガイド: Create の列車を電化する" title.zh="TSU 指南：Create 列车电气化"
```

## How to set it up

1. Prepare a power source that outputs FE.
2. Place a [Cubicle](substation.md) and connect the power source to it.
3. Place [Catenary Insulators](insulator.md) next to the substation and at the support points along the track.
4. Connect the insulators to each other with the [Wire Connector](wire-connector.md).
5. Put a [Pantograph](pantograph.md) on the train's roof and an [FE Inverter](fe-inverter.md) on a car, then assemble the train.
6. Deploy the pantograph, then check under an energized wire that the train collects and stores power.

[Overhead Poles](overhead-pole.md) and [Overhead Trusses](overhead-truss.md) are the bases that hold the insulators. Wires are not connected to them directly, only to insulators. You can also use the [Overhead Pole Placement Helper](../tools/overhead-pole-auto-tool.md) to help with placement, the decorative [Dummy Inverter](dummy-inverter.md), and the [Power Checker](power-checker.md) to inspect the system.

Electrification is an add-on. Trains with no electrification equipment keep running as ordinary Create trains. A train that carries both a Pantograph and an FE Inverter is treated as an electrified train, and while it moves it uses 1,500 FE/tick for the whole train. When its stored FE reaches 0, the train stops and cannot start again until it gets more FE.

Each car with an FE Inverter stores up to 50,000 FE. While a deployed pantograph touches an energized wire, it collects 2,000 FE/tick per pantograph.

The substation keeps feeding its wires even when its chunk is not loaded. A train in an unloaded chunk neither collects nor uses FE.

Players and mobs within 1 block of the straight line between the two insulators of an energized wire take 1 damage (half a heart) from electric shock every 0.5 seconds. Players in creative or spectator mode take none.
