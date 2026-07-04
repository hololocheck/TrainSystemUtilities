---
title: Platform Screen Door
id: structure/platform-screen-door
tags: [structure, block]
---

# Platform Screen Door

```embed:item id=trainsystemutilities:platform_screen_door size=48 label=true
```

A movable platform screen door for station platforms. It supports 4 facings and **opens/closes automatically in sync with train arrival/departure**. One unit is 6 blocks wide (fences at each end, a 4-block door that opens in the middle).

[[TOC]]

## Placement {#place}

1. Hold the platform screen door in your inventory.
2. **Right-click** toward the platform edge to place it.
3. The door is placed **extending left-right from your point of view**. Face the direction you want before right-clicking.

> [!NOTE]
> A platform screen door is a single 6-block-wide block. **It cannot be placed without enough empty blocks on either side (3 left / 2 right)** (the item is not consumed). Place it on an open platform edge, not up against front/back walls.

> [!TIP]
> Lining several up at even spacing to match your train's door positions gives a realistic look. Use it together with the [Platform Fence](platform-fence.md).

## How to open the door (important) {#how-to-open}

**Right-clicking a platform screen door does not open it.** Manual opening/closing has been removed; the door **only opens/closes automatically when a train arrives at / departs from the station**. To make it open, you must link it to a station (Railway Management Block) using the steps below.

## Linking to a station {#link}

Linking a platform screen door to trains takes the following 3 steps. It uses the exact same "memory-card group" mechanism as fences.

### Step 1: Register the door in a group with the memory card {#group}

1. Hold the **memory card**.
2. **Right-click** any one of the platform screen doors you want to link (connected fences may be included too).
3. **Doors/fences that are adjacent and connected are registered together automatically**. Chat shows "Added ○ to the platform door group (total ○)".
4. While you hold the card, registered members are shown in the world with a **green outline**. Check the extent.
5. To remove an unwanted member, **Shift + right-click** that part.
6. You can check the card's registration count in the item's **tooltip**.

### Step 2: Insert the card into the Railway Management Block {#insert-card}

1. **Right-click** that station's [Railway Management Block](../railway-management.md) to open its GUI.
2. From the function dropdown, open the **Platform Screen Door** settings popup.
3. Put the memory card from Step 1 into the **card slot** in the popup (only memory cards with a registered group will fit).

### Step 3: Set the open/close conditions {#conditions}

In the platform screen door settings popup, register **conditions** for how the door should move at which moment of a train's stop. Up to 16 conditions can be added.

| Condition field | Selectable values | Meaning |
|---|---|---|
| Track | Number | Target track (track matching is simplified in the MVP version) |
| Event | Arrive (STOP) / Depart (DEPART) | The moment that fires the condition |
| Action | Open / Close / Change band color | What the door does |

Typical example:

- Register two conditions, "Arrive → Open" and "Depart → Close" → the door opens when a train arrives and closes when it departs.

You can verify the configured motion with the **Test** control in the popup, without waiting for a train. The band color (the band color of all fences/doors in the group) can also be changed from the color button in the same popup.

> [!NOTE]
> Conditions are registered on "the Railway Management Block that holds the memory card." Train detection is done by the Railway Management Block, so **insert the card into the Railway Management Block of the station where you want detection**.

## Related pages

- [Platform Fence](platform-fence.md)
- [Memory Card](../tools/memory-card.md)
- [Railway Management Block](../railway-management.md)
- [Ticket Vending Machine](ticket-vending-machine.md)
