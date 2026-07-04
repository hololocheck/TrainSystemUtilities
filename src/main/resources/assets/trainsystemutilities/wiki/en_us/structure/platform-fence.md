---
title: Platform Fence
id: structure/platform-fence
tags: [structure, block]
---

# Platform Fence (1m / 3m / 5m)

```embed:items size=32 cols=3 label=true ids=trainsystemutilities:platform_fence_1m,trainsystemutilities:platform_fence_3m,trainsystemutilities:platform_fence_5m
```

Fall-prevention fences for platform edges. They come in three lengths (1m / 3m / 5m), and the band color can be changed dynamically to match your line color.

[[TOC]]

## Placement {#place}

1. Hold a platform fence (1m / 3m / 5m) in your inventory.
2. **Right-click** toward the platform edge to place it.
3. The fence is placed **extending left-right from your point of view**, with the band face toward you. Face the direction you want before right-clicking.

> [!NOTE]
> The 3m / 5m fences are a single block spanning multiple squares. **They cannot be placed if the direction they extend into is blocked** (the item is not consumed). Clear the empty squares ahead before placing. The 1m fence is a single square, so it fits in tight spots.

> [!TIP]
> Combine 5m for long straight sections and 1m where fine adjustment is needed for a clean fit.

## About the band color {#band-color}

The band color is shown in the same color system as [line symbols](../management-computer/line-symbols.md) and [band color settings](../railway-management/color.md), so it can be aligned to a line's brand color. The default color is green (Yamanote-line style).

The band color **does not change by right-clicking a fence on its own**. Set the color in bulk using the same **memory-card group + Railway Management Block "change band color" action** as the [Platform Screen Door](platform-screen-door.md). The steps are:

1. Hold the **memory card** and **right-click** the fences (and platform screen doors) whose color you want to align, to register them in a group (see [Group registration](#group) below).
2. Open the station's [Railway Management Block](../railway-management.md) and put that memory card into the **platform screen door slot**.
3. In the platform screen door settings, pick the **band color** and add a condition whose action is "change band color". When a train arrives/departs, the band color of all fences/doors in the group changes together.

For the detailed settings screen, see the [Platform Screen Door](platform-screen-door.md) page (fences and platform screen doors share color/group through the exact same mechanism).

## Group registration with the memory card {#group}

Used to bundle multiple fences/doors into one "group" so band color and the like can be handled in bulk.

1. Hold the **memory card**.
2. **Right-click** any one of the fences (or platform screen doors) you want to add to the group.
3. **Fences/doors that are adjacent and connected are registered together automatically** (no need to register them one by one). Chat shows "Added ○ to the platform door group (total ○)".
4. While you hold the card, registered members are shown in the world with a **green outline**, so you can visually check the extent.
5. To remove an unwanted member, **Shift + right-click** that part (it shows "Removed ○").
6. You can check the card's current registration count in the item's **tooltip**.

> [!NOTE]
> The group you build here is the "target list" handed to the Railway Management Block for automatic door open/close and band color changes. For what to do next, see [Platform Screen Door > Linking to a station](platform-screen-door.md#link).

## Related pages

- [Platform Screen Door](platform-screen-door.md)
- [Memory Card](../tools/memory-card.md)
- [Ticket Vending Machine](ticket-vending-machine.md)
