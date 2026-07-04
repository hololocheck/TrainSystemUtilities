---
title: FE Inverter
id: electrification/fe-inverter
tags: [electrification, item, block]
---

# FE Inverter

```embed:item id=trainsystemutilities:fe_inverter size=48 label=true
```

A FE buffer block placed on a train car. It stores the current collected from the pantograph and supplies FE to other-mod machines.

[[TOC]]

## Installation

The FE Inverter is a **3-block linked device**. When you hold it and **right-click** the position where you want it, the clicked position becomes the center and 3 blocks are placed automatically in front of and behind it.

1. Hold the inverter and **right-click** the position where you want it, such as under a car's floor.
2. You need **3 blocks of free space in front of and behind the facing direction**. It cannot be placed without the free space.
3. Breaking any one of the 3 linked blocks removes all 3 at once.
4. Even if the pantograph is mounted on a different car, it auto-shares electricity with FE Inverters on the same train.
5. The inverter outputs FE to adjacent blocks via the IEnergyStorage capability.

> [!TIP]
> When you **right-click a placed FE Inverter with an empty hand**, the current FE level / capacity and "drivable / not drivable" are shown in chat (for status checks).

## Buffer specs

| Item | Value |
|---|---|
| Capacity | 1,000,000 FE |
| Input rate | 10,000 FE/tick (combined pantograph + adjacent FE input) |
| Output rate | 10,000 FE/tick (adjacent FE output) |

## Train pool electrification

Multi-car behavior (= related to [Pantograph](pantograph.md#複数連結時の挙動)):

```
[Car 1: Pantograph]   [Car 2: FE Inverter]   [Car 3: FE Inverter]
       ↓                          ↓                          ↓
       └─────────────── FE shared across the train pool ───────────────┘
```

- If the pantograph is on Car 1, the FE Inverters on Car 2 / Car 3 are also powered
- Any FE Inverter can output to adjacent FE machines
- Buffer aggregation: with 3 units = 3,000,000 FE capacity

## Compatible external mods

| Mod | Connection method |
|---|---|
| **Mekanism** | Adjacent connection via Universal Cable / Ultimate Energy Cube, etc. |
| **Applied Energistics 2** | Connect via Energy Cell / Energy Acceptor |
| **Industrial Foregoing** | Connect via Power Conduit |
| **Create** (via Electric Engine) | Adjacent for FE → Rotational Force conversion |
| Other FE-compatible mods | All connectable via IEnergyStorage |

## GUI / status check

The FE Inverter body has no dedicated GUI (it's a simple buffer block). You can check its status in the following 2 ways.

- **Right-click a placed inverter with an empty hand** → shows that single unit's FE level / capacity in chat.
- **Management Computer > Train Detail > Electrification Detail** → check the whole train's stored energy at a glance ([Electrification Detail popup](../management-computer/trains.md)).
- **Right-click with the [Power Checker](power-checker.md)** → shows single-unit info when placed, or the total stored energy of the owning train when built into a train.

> [!TIP]
> Even when a train goes out-of-chunk in a long-distance section, the FE Inverter's buffer level is preserved on the server (= chunk-load independent).

## Model

A vanilla block model (= JSON-defined). The texture is black + green LED-style accents.  
It's not Geckolib, so it's lightweight (a simple cube shape).

## Related

- [Dummy Inverter](dummy-inverter.md) — decoration-only (function-less variant)
- [Pantograph](pantograph.md) — current collector
- [Substation Cubicle](substation.md) — the power source that feeds wires
- [Wire & Wire Connector Tool](wire-connector.md) — wire laying
- [Custom Wire Designs](custom-wire.md) — wire appearance customization
