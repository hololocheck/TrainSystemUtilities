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
4. **Shift + right-click** (on empty space or a non-target block) to **reset the card**.

## What the Memory Card can operate on

| Target | Right-click | Shift + right-click |
|---|---|---|
| Create **track** | Save the rail network (records station / signal / train counts) | (reset) |
| **Railway Management Block** | Save that station block (with station name) | (reset) |
| **Management Computer** | **Link** the saved rail network / railway management block | (reset) |
| **Platform Fence / Platform Screen Door** | **Register the connected fences / doors together as a group** | **Remove that member from the group** |

> [!NOTE]
> **Linking to a monitor uses the dedicated [Monitor Link Card](monitor-link-card.md)** (a separate item from the Memory Card). For train detection, use the [Train Detection Card](train-detection-card.md).

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

## Related

- [Monitor Link Card](monitor-link-card.md) — monitor-specific link card
- [Train Detection Card](train-detection-card.md)
- [Platform Screen Door](../structure/platform-screen-door.md) / [Platform Fence](../structure/platform-fence.md)
- [Railway Management Block](../railway-management.md)
- [Management Computer Overview](../management-computer/overview.md)
