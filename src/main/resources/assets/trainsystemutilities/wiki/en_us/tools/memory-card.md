---
title: Memory Card
id: tools/memory-card
tags: [tool, item, link]
---

# Memory Card

```embed:item id=trainsystemutilities:memory_card size=48 label=true
```

A card that links track networks and station equipment and saves settings. It is a different item from the [Monitor Link Card](monitor-link-card.md), which is only for monitors, and from the [Train Detection Card](train-detection-card.md).

[[TOC]]

## Registering and linking

| Target | What right-clicking does |
|---|---|
| Create track | Registers the track network |
| Platform screen doors and fences | Registers the connected equipment together |

Put a card with a registered track network in the Management Computer's card slot, and the computer links that track network. To remove platform screen doors or fences, Shift + right-click that equipment (the connected equipment comes off as a whole). Check the card's contents in its tooltip. Shift + right-clicking anything other than platform screen doors and fences opens the reset confirmation.

![](bws:trainsystemutilities:wiki/screens/memory-card-reset__main__en_us.png)

## Saving the computer's settings

From 1.0.10 on, the card inserted in the Management Computer's card slot stores the line symbols, their assignments to stations, the monitor layout and colors, the electronic schedule sharing settings, the link targets and more.

- A card with settings: restores the card's contents to the computer.
- An empty card: saves the computer's current settings.
- While inserted: later edits are saved automatically too.

Even if you place the computer again, the same card restores the settings. While the card is out, the line symbols at stations are hidden for the time being, and they come back when you insert it again. Take care not to lose or reset the card itself.
