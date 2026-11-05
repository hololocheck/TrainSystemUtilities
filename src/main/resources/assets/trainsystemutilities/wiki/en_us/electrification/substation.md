---
title: Cubicle
id: electrification/substation
tags: [electrification, block, multiblock]
---

# Cubicle

```embed:item id=trainsystemutilities:substation size=48 label=true
```

The Cubicle is the substation: it takes FE in from outside and feeds it to the overhead wires. Clear a space 4 blocks wide, 3 blocks high and 2 blocks deep; when you place it, the rest of the 24-block structure is built automatically.

[[TOC]]

Breaking any one block of the structure removes the whole installation.

Connect an FE power source, then place a [Catenary Insulator](insulator.md) next to the Cubicle. Run wires from that insulator, and the Cubicle sends power to the wire network they connect to.

| Item | Value |
|---|---|
| Storage capacity | 1,000,000 FE |
| Input limit | 10,000 FE per insertion |
| Upkeep | 1 FE/tick for each wire in the network it feeds |

Right-click the main block, the block you clicked when placing it, with an empty hand to show its status, its stored FE and capacity, and its connection count in chat. "Waiting for connection" means FE is left but no wire can be energized (no insulator or wire is connected, or the FE is less than the upkeep). "Insufficient FE" means the FE is 0. For "Waiting for connection", check the insulators and wires; for "Insufficient FE", check the power source. For a closer look, you can use the [Power Checker](power-checker.md).

The installation's power is managed on the server, and it keeps feeding the wires even when its chunk is not loaded. Whether the external power source keeps running, however, depends on the conditions of the machines connected to it, so check those too.
