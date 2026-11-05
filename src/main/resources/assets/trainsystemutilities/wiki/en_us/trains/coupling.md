---
title: Coupling and Decoupling
id: trains/coupling
tags: [train, coupling]
---

# Coupling and Decoupling

Couple two consists, or split one, with a Create schedule condition. There is no dedicated key: you set it up by adding a wait condition called "Coupling/Decoupling".

[[TOC]]

## Coupling

1. Add the "Coupling/Decoupling" condition to both trains that are to couple.
2. Set the mode value to "Couple" with the mouse wheel.
3. Have both trains arrive at the same station.
4. Each waits for the other to arrive, and they couple once the condition is met.

## Decoupling

At the station where you want to split, add the same condition to the schedule of the train that arrived first, and set the mode to "Decouple". After splitting, the wait before the front train departs can be set with the mouse wheel, up to 30 seconds.

![](bws:trainsystemutilities:wiki/screens/management-computer-sched-editor__en_us.png)

The condition can be set in Create's standard schedule editor and in TSU's [Electronic Schedule](../management-computer/schedule.md#sched-editor). Coupling is limited to two trains.

While coupled, the consist shares its power. The [Pantograph](../electrification/pantograph.md) and the [FE Inverter](../electrification/fe-inverter.md) can be on different cars. After decoupling, each consist goes back to handling its own power.
