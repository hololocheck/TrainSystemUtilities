---
title: Monitor Link Card
id: tools/monitor-link-card
tags: [tool, item, monitor, link]
---

# Monitor Link Card

```embed:item id=trainsystemutilities:monitor_link_card size=48 label=true
```

A **dedicated held item** that links a monitor block to another block (railway management / poster management / management computer).

[[TOC]]

## Holding / usage

This card has no dedicated GUI. You use it simply by **holding it and right-clicking a monitor**.

1. **Put the Monitor Link Card on your hotbar and hold it.**
2. **Right-click the monitor block** you want to link (by default, the **right mouse button**).
   - Adjacent, connected monitors are **automatically registered together as a single group** (you do not need to click each face one by one).
   - Right-clicking an already-registered group again **unregisters** that group.
3. When registered, "Registered (○ groups)" is shown at the bottom of the screen (above the hotbar).
4. The registered content can be checked in the **tooltip** when you hover the card: "Registered: ○".
5. **Shift + right-click** (right-click while sneaking) **clears all** of the card's registered content.

> [!NOTE]
> This card is one that remembers "registered monitors". To actually output display content to a monitor,  
> put this registered card **into the monitor slot of a railway management block, etc.** (see "Usage flow" below).

## Operation summary

| Operation | What happens |
|---|---|
| **Right-click** a monitor | Register that monitor (connected group) / unregister if already registered |
| **Shift + right-click** | Clear all of the card's registered content |
| **Hover** over the card | Check the current registration count in the tooltip |

## Usage flow

1. **Register** monitors on the card with the steps above.
2. Put the registered card into the **monitor slot** of the block you want as the source ([Railway Management Block](../railway-management.md) / [Poster Management Block](../poster-management.md) / [Management Computer](../management-computer/monitor.md)).
3. That block's display content is now instantly synced and shown on the registered monitors.

## Linkable combinations

| Source | Displayed content |
|---|---|
| [Railway Management Block](../railway-management.md) | Station arrivals / next trains + line symbol |
| [Poster Management Block](../poster-management.md) | Slideshow images |
| [Management Computer Monitor](../management-computer/monitor.md) | Custom layout |

## Monitor block placement

A monitor can be a single block or a **multi-face monitor** (= a grid of rows × columns).  
When linked with the card, the content is stretched across the entire grid.

## Related

- [Memory Card](memory-card.md) — general-purpose version
- [Railway Management Block](../railway-management.md)
- [Poster Management Block](../poster-management.md)
- [Management Computer Monitor Display](../management-computer/monitor.md)
