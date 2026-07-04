---
title: Electrification System Overview
id: electrification/index
tags: [electrification, overview]
---

# Electrification System Overview

```embed:items size=48 cols=7 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:fe_inverter_dummy,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/dummy-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

> [!TIP]
> Icons with a **blue marker** in the bottom-right corner can be clicked to open their detail page.

The TrainSystem Utilities electrification system supplies external FE power to Create trains via **overhead wire + pantograph + substation**.
It lets FE generators from Mekanism / AE2 / Industrial Foregoing and the like serve as a power source or auxiliary supply for Create trains.

[[TOC]]

## Overall flow

```
[FE generator (Mek/AE2/IF)] ──FE──▶ [Substation cubicle]
                                       │
                                  (steps up to the wire via insulator)
                                       │
                                       ▼
[Roof-mounted Pantograph] ◀──runs under wire──▶ [Overhead Wire]
        │
        ▼
[FE Inverter] ──FE──▶ [Onboard Create / Mek / ... machines]
```

1. **Prepare an FE source**: a Mekanism reactor, an AE2 Energy Acceptor, anything works.
2. Build a [Substation Cubicle](substation.md): hold the body and **right-click** to auto-complete the 3×4×2 = 24-block structure. Connect a power cable to the FE input face.
3. Right-click to raise [Overhead Poles](overhead-pole.md) or [Overhead Trusses](overhead-truss.md) beside the track, then right-click to place [Wire Insulators](insulator.md) on top of them and next to the substation.
4. Hold the [Wire Connector Tool](wire-connector.md) and right-click **insulator → insulator** in order to string [Wire](wire-connector.md) over the track (power flows from the insulator adjacent to the substation).
5. Right-click to mount a [Pantograph](pantograph.md) on the train roof and an [FE Inverter](fe-inverter.md) inside the car.
6. While running, the pantograph automatically collects current as it passes under the wire → the inverter feeds the onboard FE machines.

> [!TIP]
> Placing poles and insulators one by one along the track is tedious, so use the [Overhead Pole Auto Tool](../tools/overhead-pole-auto-tool.md) to bulk-place poles, trusses, and insulators at your configured height and count.

## Component blocks / items

| Item | Role | Detail |
|---|---|---|
| [Wire Connector Tool](wire-connector.md) | Strings wire between two insulators | Hold and right-click insulators. 5 designs + [custom](custom-wire.md) |
| [Wire Insulator](insulator.md) | Mounting point at both ends of a wire | Right-click this to string wire. Relay between substation and wire |
| [Overhead Pole](overhead-pole.md) | Single-track support post | Base that holds an insulator. Right-click to place beside the track |
| [Overhead Truss](overhead-truss.md) | Portal support spanning multiple tracks | Base that holds insulators. Spans across track groups |
| [Overhead Pole Auto Tool](../tools/overhead-pole-auto-tool.md) | Auto-places poles / trusses / insulators | Bulk-places along the track at your configured height and count |
| [Pantograph](pantograph.md) | Roof-mounted current collector | Right-click on the roof to place. Auto-connects while running under wire |
| [FE Inverter](fe-inverter.md) | Onboard FE buffer | 3-wide device. Supplies pantograph power to onboard machines |
| [Dummy Inverter](dummy-inverter.md) | Decoration only (no function) | Looks-only FE Inverter, only UI pantograph deploy works |
| [Substation Cubicle](substation.md) | External FE intake + wire supply | 3×4×2 multiblock |
| [Power Checker](power-checker.md) | Debug tool | Right-click an insulator / substation / inverter / pantograph to show FE remaining, etc. |

## FAQ

> [!NOTE]
> **Q: Do non-electrified trains still run as before?**
> A: Yes. Electrification is purely additive, so Create's standard schedule operation still works untouched.

> [!NOTE]
> **Q: I want details on each component.**
> A: Jump from the links in the table above. For troubleshooting, see the "Troubleshooting use" section on the [Power Checker](power-checker.md) page.

> [!IMPORTANT]
> **Q: Can my train run on Create energy alone, without electrification?**
> A: Yes. The electrification system is designed as "auxiliary power beyond Create energy."
> For example, you can use a Mek reactor as the source to conserve Create drum reserves on long runs.

## Related pages

- [Wire / Wire Connector Tool](wire-connector.md)
- [Custom Wire Designs](custom-wire.md)
- [Wire Insulator](insulator.md)
- [Overhead Pole](overhead-pole.md)
- [Overhead Truss](overhead-truss.md)
- [Overhead Pole Auto Tool](../tools/overhead-pole-auto-tool.md)
- [Pantograph](pantograph.md)
- [FE Inverter](fe-inverter.md)
- [Dummy Inverter](dummy-inverter.md)
- [Substation Cubicle](substation.md)
- [Power Checker](power-checker.md)
