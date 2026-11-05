---
title: Pantograph
id: electrification/pantograph
tags: [electrification, item, block]
---

# Pantograph

```embed:item id=trainsystemutilities:pantograph size=48 label=true
```

A current collector that goes on a train's roof. Place it on the top face of the roof before you assemble the train. It sends the power it takes from an energized wire to the [FE Inverters](fe-inverter.md) on the same train.

[[TOC]]

While it is placed as a block, right-click it with an empty hand to switch it between deployed and folded. Once the train is assembled, you operate it from the [train detail](../management-computer/trains.md) of the Management Computer: you can switch each pantograph on its own, or deploy or fold all of them at once. The Management Computer can switch only a pantograph whose car also carries an inverter (a Dummy Inverter counts), and only while you are within 64 blocks of the train.

Deploying and folding are done by hand: a pantograph never moves by itself to follow the wire. Whichever way you switch it, the last operation is the one that counts. It collects power only while it is deployed and touching an energized wire.

## Trains with several cars {#複数連結時の挙動}

The pantograph and the inverter do not have to be on the same car: power is shared within the train. On stretches away from the wire, the train runs on the power it has stored. When the whole train's FE reaches 0, the train stops. A train in an unloaded chunk neither collects nor uses FE.
