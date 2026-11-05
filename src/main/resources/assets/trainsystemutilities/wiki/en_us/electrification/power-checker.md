---
title: Power Checker
id: electrification/power-checker
tags: [electrification, tool]
---

# Power Checker

```embed:item id=trainsystemutilities:power_checker size=48 label=true
```

A tool that inspects the electrification equipment. Hold it and right-click a target to show the result in chat. It opens no screen of its own.

[[TOC]]

| Target | What it shows |
|---|---|
| Catenary Insulator | How many wires are connected, and how many of them are energized |
| Cubicle | The number of wires and of energized marks in the whole world (it does not show FE) |
| FE Inverter / Pantograph | That block's information (for a Pantograph, whether it is deployed), and for the nearest electrified train within 64 blocks, totalled over the whole train: its stored FE, its number of pantographs and of inverters, and how many pantographs are touching a wire |

When power does not arrive, check the power source, the Cubicle, the insulators and wires, the pantograph and the inverter, in that order. Looking at whether the wire is energized, whether the pantograph touches it and whether the stored FE keeps dropping makes the problem easier to find.

Choosing a block it does not support shows that the block is not a target.
