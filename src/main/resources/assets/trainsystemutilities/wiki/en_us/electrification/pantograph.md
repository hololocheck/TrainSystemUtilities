---
title: Pantograph
id: electrification/pantograph
tags: [electrification, item, block]
---

# Pantograph

```embed:item id=trainsystemutilities:pantograph size=48 label=true
```

A current-collecting device mounted on the roof of a train car. While running under a wire, it auto-contacts and transfers FE / Create energy to the car's buffer.

[[TOC]]

## Installation

1. Hold the pantograph in your hand and **right-click** the **roof (top face)** of a train car to install it.
2. Mount it on the car's roof before assembling it into a Create train (once assembled, it moves together with the train).
3. The pantograph is a Geckolib animation model that moves up and down (extend / retract).
4. While the train runs, it auto-**extends** when it enters under a wire and **retracts** when it leaves.

> [!NOTE]
> The standard is "one pantograph per car". In multi-car consists, a design where only some cars carry a pantograph and the others share the buffer via [FE Inverters](fe-inverter.md) also works.

## Manually extending / retracting

When you **right-click a placed pantograph with an empty hand**, you can manually toggle between extended and folded ("Pantograph: Extended / Folded" is shown on the action bar). Useful for effects such as lowering the pantograph only while stopped at a station.

When you want to operate the whole train's pantographs at once, you can select the train from the [Trains tab](../management-computer/trains.md) of the management computer and use "Extend All Pantographs" / "Fold All Pantographs" in the electrification details.

## Power principle

```
[Wire]
     ↓ contact (while moving)
[Pantograph]
     ↓ FE transfer
[Any car's buffer on the same train]
     - Own car's buffer (pantograph-equipped)
     - Other cars' FE Inverters (coupled cars)
     - Onboard Create machines
```

## Connection diagram (overall)

```
[FE power source / Create energy]
     ↓
[Substation Cubicle] (FE → wire feed)
     ↓ insulator connection
[Wire]
     ↓ contact
[Train Pantograph]
     ↓
[FE Inverter]
     ↓
[Create machines / other-mod electrical mechanisms]
```

## Multi-car behavior {#複数連結時の挙動}

- Even if **only one car** has a pantograph, FE is shared across the entire train
- Put [FE Inverters](fe-inverter.md) on other cars to also feed their buffers
- Even after the train leaves the wire, it can run a certain distance on remaining buffer FE
- Train pool-level electrification check (`isTrainElectrified`) — if one car is under a wire, the entire train counts as electrified

> [!TIP]
> Placing a pantograph on one car (e.g., the front car) + FE Inverters on the other cars lets even a long consist be fully powered by a single pantograph.

## Chunk-load independence

Even when the train sits at a station outside loaded chunks, its buffer doesn't decay, and re-contact continues when it returns.  
The substation side keeps feeding power chunk-independently via [SubstationRegistry](substation.md#savedata) (SavedData).

## Related

- [Wire & Wire Connector Tool](wire-connector.md) — wire laying
- [Substation Cubicle](substation.md) — the power source that feeds wires
- [FE Inverter](fe-inverter.md) — buffer sharing across other cars
- [Custom Wire Designs](custom-wire.md) — free settings for thickness / rows / spacing
