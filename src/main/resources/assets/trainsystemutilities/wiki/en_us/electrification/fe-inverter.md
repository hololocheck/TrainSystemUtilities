---
title: FE Inverter
id: electrification/fe-inverter
tags: [electrification, item, block]
---

# FE Inverter

```embed:item id=trainsystemutilities:fe_inverter size=48 label=true
```

Equipment that stores the FE a train collects and shares it within the train. The block you click becomes the center, and the inverter takes 3 blocks along the front-to-back direction. Leave enough room in the direction you place it. Breaking any one of its blocks removes all 3 together.

[[TOC]]

| Item | Value |
|---|---|
| Capacity | 50,000 FE |
| Input limit | 5,000 FE per insertion |
| Output | None (it sends FE to no other block) |

Inverters on the same train share power even when they are on different cars. In a train, each car with an inverter stores up to 50,000 FE. Putting inverters on more cars raises the whole train's capacity; putting several on the same car does not raise that car's capacity.

While it is placed as a block, right-click it with an empty hand to show "Drivable" or "Not drivable" and its stored FE and capacity in chat. You can see the state of the whole train in the Electrification Detail of the Management Computer or with the [Power Checker](power-checker.md).
