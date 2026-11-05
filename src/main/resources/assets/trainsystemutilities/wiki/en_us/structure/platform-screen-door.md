---
title: Platform Screen Door
id: structure/platform-screen-door
tags: [structure, block]
---

# Platform Screen Door

```embed:item id=trainsystemutilities:platform_screen_door size=48 label=true
```

A platform screen door that opens and closes as trains arrive and depart. One unit is 6 blocks wide, and the middle 4 blocks open. It is not opened or closed by hand with a right-click.

[[TOC]]

## Placing {#place}

Face the direction to place it in and right-click the edge of the platform. It needs 6 blocks of width, including the point you place it on. Combine it with [fences](platform-fence.md) to finish the platform.

## Linking with a station {#link}

1. Right-click a door with a [Memory Card](../tools/memory-card.md) to register the connected doors and fences.
2. Check the targets by their outline, and remove the parts you don't need with Shift + right-click (the connected part comes off as a whole).
3. Open the platform screen door settings from "Function" on the [Railway Management Block](../railway-management.md) and put the card in.
4. Register combinations of an event and an action.

Set "Stop → Open" and "Depart → Close", and the doors move with the trains. Choose the event from "Stop" and "Depart" and the action from "Open", "Close" and "Color"; up to 16 conditions can be registered. Check them with the test controls on the settings screen.

It is the Railway Management Block that detects trains. Put the card in the equipment of the station concerned. There is no filtering by track: the doors work when a train on any track stops or departs.
