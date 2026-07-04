---
title: Ticket Vending Machine
id: structure/ticket-vending-machine
tags: [structure, block, ticket]
---

# Ticket Vending Machine

```embed:item id=trainsystemutilities:ticket_vending_machine size=48 label=true
```

A station ticket vending machine. It is a 2-block-tall cabinet; right-click it to open a UI like a real station's vending machine, where you pick a destination and issue a ticket.

[[TOC]]

## Placement and station linking {#place}

1. Hold the ticket vending machine item.
2. **Right-click** where you want to place it (the cabinet needs 2 blocks of vertical space, so leave 1 block empty above). It faces toward you.
3. **Placing it inside the range of a station group created with the [Station Range Tool](../tools/station-range-tool.md) auto-links it to that station** (= that station becomes the origin).
4. Placing it later inside an existing range also connects it. If you create the range afterwards, it re-links the next time the machine is opened.

> [!WARNING]
> **A machine placed outside any station range cannot be used.** Right-clicking it won't open the UI; instead it shows "Place this inside a station range" in red. Always place it inside a station group's range. See the [Station Range Tool](../tools/station-range-tool.md) for how to create station groups.

## Opening the UI and issuing tickets {#open}

**Right-click** a placed machine to open the vending UI.

- Destinations (= stations set as sellable) are listed as rounded buttons. When there are many, scroll with the **mouse wheel**.
- **Left-click** the button for the station you want and a **ticket** is issued into your inventory (free in v1).
- The listed destinations are only the sellable stations that are **connected to the same rail network** as this machine (the machine's own station is excluded).
- The header follows the BelugaExperience standard (**× to close** / **hint toggle** / **📖 wiki**). Close with the × button or the Esc key.

## Ticket

```embed:item id=trainsystemutilities:ticket size=32 label=true
```

An issued ticket records its **origin and destination**, shown in the item tooltip as "From: ○○ / To: △△ (valid until)". In v1 it is an informational item; fare-gate validation is planned for the future.

## Choosing which stations are sold

The destinations listed at a machine are decided per station in the **[management computer's Tickets tab](../management-computer/tickets.md)**, by toggling each station sellable or not. The setting is shared network-wide and applies to every ticket machine.

## Related pages

- [Management Computer: Tickets tab](../management-computer/tickets.md)
- [Station Range Tool](../tools/station-range-tool.md)
- [Platform Fence](platform-fence.md) / [Platform Screen Door](platform-screen-door.md)
