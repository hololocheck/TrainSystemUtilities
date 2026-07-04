---
title: Overhead Pole
id: electrification/overhead-pole
tags: [electrification, block]
---

# Overhead Pole

```embed:item id=trainsystemutilities:overhead_pole size=48 label=true
```

A single-track catenary pole. Place it beside the track; it serves as the base on which you mount a [Wire Insulator](insulator.md) to then string [Wire](wire-connector.md).

[[TOC]]

## Placement

Hold the overhead pole from your inventory and **right-click the top face of the ground or a block** where you want it to stand.

- The orientation is chosen automatically to match the track angle (8 directions, 45° steps). Used beside a track, it naturally aligns parallel to the rail.
- **To add height**: **right-click the top** of a pole that is already standing, and another pole of the same orientation stacks on top. Keep right-clicking to raise it higher and higher.
- For evenly-spaced bulk placement, the [Overhead Pole Auto Tool](../tools/overhead-pole-auto-tool.md) is handy (bulk-places poles, trusses, and insulators along the track at your configured height and count).

> [!IMPORTANT]
> You cannot string wire directly onto the pole itself. Right-click to place a [Wire Insulator](insulator.md) on top of the pole, then right-click that insulator with the [Wire Connector Tool](wire-connector.md) to string the wire.

## Flow up to stringing wire

1. Place overhead poles beside the track and stack them to the required height.
2. Right-click to place a [Wire Insulator](insulator.md) on the top face of a pole.
3. Place an insulator on the neighboring pole the same way.
4. Put the [Wire Connector Tool](wire-connector.md) into placement mode and right-click **insulator → insulator** in order to string the wire.

> [!TIP]
> To span double-track or multi-track sections, use the [Overhead Truss](overhead-truss.md).

## Related pages

- [Electrification overview](index.md)
- [Wire Insulator](insulator.md) — the wire mounting point placed on top of the pole
- [Wire / Wire Connector Tool](wire-connector.md) — strings wire between insulators
- [Overhead Truss](overhead-truss.md)
- [Overhead Pole Auto Tool](../tools/overhead-pole-auto-tool.md)
