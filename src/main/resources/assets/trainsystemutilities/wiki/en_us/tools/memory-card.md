---
title: Memory Card
id: tools/memory-card
tags: [tool, item, link]
---

# Memory Card

```embed:item id=trainsystemutilities:memory_card size=48 label=true
```

A general-purpose link card for linking rail networks and station blocks to a **Management Computer**, or for registering **Platform Screen Doors / Platform Fences** as a group. Used in two steps: first "save" a position, then "apply" it to another block.

[[TOC]]

## Basic usage

1. **Save**: **Right-click** the link source (track / railway management block / platform screen door, etc.) to record its position on the card.
2. **Apply**: **Right-click** the link target (usually a Management Computer) to apply the recorded content.
3. The card's current saved content can be checked in the item's **tooltip**.
4. **Shift + right-click** (on empty space or a non-target block) opens a **confirmation dialog**; confirming **resets the card**.

## What the Memory Card can operate on

| Target | Right-click | Shift + right-click |
|---|---|---|
| Create **track** | Save the rail network (records station / signal / train counts) | (reset) |
| **Railway Management Block** | Save that station block (with station name) | (reset) |
| **Management Computer** | **Link** the saved rail network / railway management block | (reset) |
| **Platform Fence / Platform Screen Door** | **Register the connected fences / doors together as a group** | **Remove that member from the group** |

> [!NOTE]
> **Linking to a monitor uses the dedicated **[**Monitor Link Card**](monitor-link-card.md) (a separate item from the Memory Card). For train detection, use the [Train Detection Card](train-detection-card.md).

## Common procedures

**Link a rail network to a Management Computer**

1. **Right-click any track** on the line → "Rail network saved" is shown.
2. **Right-click the Management Computer** → "Rail network linked".
3. The rail network now appears in the Management Computer's route map / train list.

**Bind a station's railway management block to a computer**

1. **Right-click the railway management block** on the station platform to save it.
2. **Right-click the Management Computer** to link.

**Group platform screen doors / platform fences**

1. **Right-click** one of the doors / fences, and the adjacent connected blocks are automatically registered together.
2. Exclude extra members with **Shift + right-click**.
3. For the group's station linkage, see [Platform Screen Door](../structure/platform-screen-door.md).

## Mode-independent

Linking / reading with the Memory Card works regardless of access mode (Private/Public).

## Saving management computer settings

Since **1.0.10** the memory card is where a management computer's settings live. Insert a card
into the computer's card slot and everything you configure there — line symbols, station
assignments, monitor layout and colours, electronic timetables, and the linked track network —
is written onto the card.

- **Insert a card that already holds settings** → the computer is restored from the card. The
  card wins over whatever the computer had.
- **Insert a blank card** → the computer's current settings are saved onto it. A computer with
  nothing configured does not write an empty save.
- **While a card is inserted** → later edits are written back to the card automatically.

That means breaking the management computer no longer loses anything. Place a new one anywhere,
insert the same card, and the setup comes back.

> [!WARNING]
> **With no card inserted, line symbols are not shown at stations.** The card is the authority,
> so pulling it out (or breaking the computer) takes the symbols off the station monitors until
> a card is inserted again. Assignments are not deleted — they come back with the card.

The information is only lost in two ways: the card item itself is destroyed, or the card is
reset (**Shift + right-click**, which asks for confirmation first).

## Related

- [Monitor Link Card](monitor-link-card.md) — monitor-specific link card
- [Train Detection Card](train-detection-card.md)
- [Platform Screen Door](../structure/platform-screen-door.md) / [Platform Fence](../structure/platform-fence.md)
- [Railway Management Block](../railway-management.md)
- [Management Computer Overview](../management-computer/overview.md)
