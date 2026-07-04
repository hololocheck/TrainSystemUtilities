---
title: Wire Insulator
id: electrification/insulator
tags: [electrification, block]
---

# Wire Insulator

```embed:item id=trainsystemutilities:insulator size=48 label=true
```

A support point that physically holds wire and electrically connects it to a block network (a substation / another insulator).
A substation alone cannot expose wire; it must always connect via an insulator.

[[TOC]]

## Roles

| Function | Description |
|---|---|
| **Wire support point** | The endpoints (= pins) that you click with the [Wire Connector Tool](wire-connector.md) |
| **Electrical relay** | Receives FE from the substation and **energizes** every wire connected to it |
| **Isolation** | A boundary when you want to connect wires of different power grids (for future expansion) |

## How to place

Hold the insulator from your inventory and **right-click the face where you want to place it**.

- **The insulator grows out of the face you right-clicked.** Right-click a floor (top face of a block) and it stands upward; right-click a ceiling (bottom face) and it hangs downward; right-click a wall (side face) and it juts out sideways.
- The wire mounting point (pin) is the tip of the insulator. Because the mounting point moves with the placement orientation, choose the face to match the height and direction where you want the wire.
- To string wire up high, first raise an [Overhead Pole](overhead-pole.md) or [Overhead Truss](overhead-truss.md), then right-click to place an insulator on its top or side face.

## How to string wire (connecting)

1. Place insulators around the substation cubicle body, or as relay points along the wire route.
2. Hold a [Wire Connector Tool](wire-connector.md) and switch to **Placement (insulator connect)** mode with Alt+wheel.
3. **Right-click the first insulator** → recorded as the start point ("Connect from: X, Y, Z" is shown).
4. **Right-click the second insulator** → wire is strung between the two points.
5. If one of the insulators is adjacent to the substation, the wire becomes energized as a live wire (its color brightens).

> [!TIP]
> A single insulator can branch into **multiple wires**. You can build star or loop networks.

> [!NOTE]
> Breaking an insulator automatically removes every wire that was strung to it (so no dangling wire is left behind).

## Energization {#energization}

Insulators are recorded as edges (= wire connections) in **WireNetworkSavedData**.
The SubstationTickHandler:

1. BFS from every substation through connected insulators
2. Marks every reachable insulator and wire as **energized**
3. A train pantograph decides pickup by "is the segment directly above me an energized wire?"

> [!NOTE]
> When a substation's buffer goes empty (0 FE), **all energization drops instantly**. Sections with no power arriving render with a darker wire color.

## How to check

Right-click an insulator with the [Power Checker](power-checker.md) to show, in chat, the total number of wires attached to that insulator and how many are currently energized.

## Related

- [Wire / Wire Connector Tool](wire-connector.md) — the tool that strings wire between insulators (right-click the insulator)
- [Overhead Pole](overhead-pole.md) / [Overhead Truss](overhead-truss.md) — bases that hold insulators up high
- [Substation Cubicle](substation.md) — the power source that supplies FE to the insulator
- [Power Checker](power-checker.md) — the status inspection tool
