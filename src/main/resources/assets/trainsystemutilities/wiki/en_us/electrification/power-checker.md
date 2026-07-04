---
title: Power Checker
id: electrification/power-checker
tags: [electrification, tool]
---

# Power Checker

```embed:item id=trainsystemutilities:power_checker size=48 label=true
```

A **debug / inspection tool** for the electrification system. Right-click a target block to print its electrification state to chat.

[[TOC]]

## How to use

Hold the Power Checker and **right-click** any of:

| Target | Information shown |
|---|---|
| **Wire Insulator** | Number of attached wires / how many are energized / per-wire ON/OFF |
| **Substation Cubicle** (core or dummy) | Buffer FE / capacity / connected insulator & wire counts |
| **FE Inverter** (placed) | Buffer FE / capacity |
| **FE Inverter** (on a train) | Train total `storedEnergy` / total inverter capacity |
| **Pantograph** (placed or on train) | Currently contacted wire segment / FE picked up this tick |
| Anything else | "Not applicable" — passes through |

> [!TIP]
> Output goes to **chat** (not the action bar). Works on dedicated servers and singleplayer alike.
> Values shown for trains come from the server tick, so they're accurate even while running.

## Sample output

```
[Insulator @ (123, 65, -42)]
  Attached wires: 3
  Energized: 2 / 3
  ▸ wire 1: ON (to insulator @ (118, 65, -42))
  ▸ wire 2: ON (to insulator @ (128, 65, -42))
  ▸ wire 3: OFF (to insulator @ (123, 65, -50))  ← cut grid
```

```
[Substation @ (110, 64, -40)]
  Buffer: 425,032 / 1,000,000 FE (42.5%)
  Energized insulators: 6
  Energized wires: 14
```

```
[FE Inverter (Train #train_jb_03)]
  Train storedEnergy: 12,400 / 80,000 FE (15.5%)
  Mounted inverters: 4 (20,000 FE capacity each)
```

## Troubleshooting

| Symptom | Check |
|---|---|
| "Train won't move" | Tap pantograph — if pickup is 0, the wire above is not energized |
| "Wire looks dim" | Tap an insulator at the end → walk the grid back to the hub |
| "Substation isn't filling" | Tap the cubicle — confirm buffer level and input rate |
| "Train drains too fast" | Tap an inverter — compare consumption vs pickup |

## Related

- [Substation Cubicle](substation.md) — primary FE source
- [Wire Insulator](insulator.md) — energization junction
- [Pantograph](pantograph.md) — current collector
- [FE Inverter](fe-inverter.md) — onboard FE buffer
