---
title: Overhead Truss
id: electrification/overhead-truss
tags: [electrification, block]
---

# Overhead Truss

```embed:item id=trainsystemutilities:overhead_truss size=48 label=true
```

A portal-frame overhead support structure that spans multiple tracks. On double-track or wider sections, a single structure can suspend several wires.

[[TOC]]

## Placement

Hold the overhead truss from your inventory and **right-click where you want to place it**.

- Orientation supports 8 directions (45° steps), so it can follow diagonal sections.
- **To extend sideways (span a beam)**: right-click a truss that is already placed, and another truss is appended at the tip of the beam in its direction. Keep right-clicking to extend it to a length that spans across the track group.
- **To attach to a diagonal pole**: right-click the side of a diagonally-raised [Overhead Pole](overhead-pole.md), and the truss is placed on the diagonal cell that matches the pole's orientation.
- For bulk placement the [Overhead Pole Auto Tool](../tools/overhead-pole-auto-tool.md) is handy (turn "Place trusses" ON in its settings to auto-line up trusses together with poles).

> [!IMPORTANT]
> You cannot string wire directly onto the truss itself. Right-click to place a [Wire Insulator](insulator.md) on the side or underside of the truss, then right-click that insulator with the [Wire Connector Tool](wire-connector.md) to string the wire.

## Flow up to stringing wire

1. Place the truss so it spans across the track group (right-click to append beams until it reaches the required width).
2. Right-click to place a [Wire Insulator](insulator.md) roughly directly above each track.
3. Put the [Wire Connector Tool](wire-connector.md) into placement mode and right-click **insulator → insulator** in order to string the wire.

> [!NOTE]
> For single track, the [Overhead Pole](overhead-pole.md) is more compact.

## Related pages

- [Electrification overview](index.md)
- [Wire Insulator](insulator.md) — the wire mounting point placed on the truss
- [Wire / Wire Connector Tool](wire-connector.md) — strings wire between insulators
- [Overhead Pole](overhead-pole.md)
- [Overhead Pole Auto Tool](../tools/overhead-pole-auto-tool.md)
