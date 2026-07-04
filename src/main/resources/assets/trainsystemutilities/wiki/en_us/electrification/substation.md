---
title: Substation Cubicle
id: electrification/substation
tags: [electrification, block, multiblock]
---

# Substation Cubicle

```embed:item id=trainsystemutilities:substation size=48 label=true
```

A 3×4×2 = 24-block multiblock structure. It accepts external FE power (Mekanism / AE2 / Industrial Foregoing, etc.) and feeds it to wires via insulators.

[[TOC]]

## Installation

1. Secure the placement space (= a 3 wide × 4 deep × 2 high = 24-block volume).
2. Hold the cubicle body in your hand and **right-click the position you want as the base block**. The structure's orientation is set by the direction the player is facing.
3. Using the clicked position as the base, 23 dummy blocks are placed automatically, completing the 3×4×2 structure.
4. The structure is treated as a single logical block (right-clicking any block responds as the main body).

> [!WARNING]
> If there isn't enough placement space, you'll get a `Not enough space to place (3×4×2 required)` message and placement fails.
> To remove it, break any one of the 24 blocks and all of them disappear at once.

## Checking status (right-click)

When you **right-click a placed substation with an empty hand**, its current status is shown in chat (no dedicated GUI screen opens).

| Display | Meaning |
|---|---|
| **Energized** | FE is available and power is being fed to the wire network via insulators |
| **Waiting for connection** | FE is available, but no insulators / wires are connected yet |
| **FE shortage** | No FE is coming in from the external power source |

The current FE level / capacity and the number of connected wire networks are also shown. When you want to investigate in more detail, use the [Power Checker](power-checker.md).

## Connection (input + output)

```
[FE power source (Mekanism Cable / Create Energy etc)]
     ↓ connect (any face)
[Substation Cubicle]
     ↓ via insulator block
[Wire]
     ↓
[Train Pantograph]
```

### FE input

- The cubicle accepts the IEnergyStorage capability on any dummy face
- Any FE-compatible source works: Mekanism Universal Cable, AE2 Energy Cell, Create Electric Engine, etc.
- Internal buffer capacity: 1,000,000 FE
- Accept rate: 10,000 FE/tick

### Wire output

To feed power from the substation into wires, stand an insulator on a position **touching** the substation and run wires from there.

1. Right-click to place a [Wire Insulator](insulator.md) on a **block adjacent** to the substation body (this insulator becomes the entry point connecting the substation to the wires).
2. Right-click that insulator with the [Wire Connector Tool](wire-connector.md) in place mode → then right-click the next insulator to run a wire.
3. The wire network beyond the insulator adjacent to the substation becomes **energized** (energized wires glow brighter).
4. Multiple wires can branch from a single substation.

## Chunk-load independence {#savedata}

`SubstationRegistry` (per-dimension SavedData) records the substation's location + FE + facing.  
Even when the player hasn't loaded the chunks:

- FE intake to the substation continues (if the external power source's chunk is loaded)
- A powered train outside loaded chunks continues to draw FE from the buffer
- The buffer level remains accurate when the train returns

> [!TIP]
> On a large-scale rail line, a train can keep running across a long-distance section even after leaving loaded chunks, as long as the buffer doesn't deplete. Conversely, buffer capacity design matters.

## Model

A Geckolib-based static model (no animation). The texture is gray + an accent color.  
A single BlockEntity renders the appearance of all 24 blocks in the 3×4×2 structure.

> [!NOTE]
> The cubicle core block has a BlockEntity. The 23 dummy blocks have no BlockEntity; capability access is routed to the core via [SubstationMultiblock.findCore](https://github.com/hololocheck/TrainSystemUtilities/blob/master/src/main/java/com/trainsystemutilities/blockentity/SubstationMultiblock.java).

## Related

- [Pantograph](pantograph.md) — current collector that runs under wires
- [Wire & Wire Connector Tool](wire-connector.md) — wire laying
- [FE Inverter](fe-inverter.md) — train-side FE buffer
- [Custom Wire Designs](custom-wire.md) — wire appearance customization
