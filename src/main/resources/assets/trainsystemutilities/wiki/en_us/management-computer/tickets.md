---
title: Tickets Tab
id: management-computer/tickets
tags: [management-computer, ticket]
---

# Tickets Tab

The management computer's Tickets tab. **Choose which stations are sold** at the ticket vending machines.

[[TOC]]

## How to open

1. **Place** a **Management Computer** block and **right-click** it to open the screen.
2. **Click** the top-left dropdown and choose **"🎫 Tickets"**.

## Overview

Lists the stations registered on the network (station groups created with the [Station Range Tool](../tools/station-range-tool.md)), each with a **sellable toggle**. Stations turned ON here appear in the destination list of every [ticket vending machine](../structure/ticket-vending-machine.md).

## Usage

- **Click the toggle switch** on each station row to switch sellable on/off (ON = green).
- When there are many stations, roll the **mouse wheel** over the list to scroll (a scrollbar appears on the right).
- The title shows "Sellable stations (sellable / total)".

## How it connects to machines

- The setting is **shared network-wide** and applies to all machines immediately (machines read the server-side setting).
- If no stations are registered, it shows "No stations on the network". Create stations first with the [Station Range Tool](../tools/station-range-tool.md).

> [!NOTE]
> In the initial state, before you set anything sellable, machines show every station except their own as a destination (a ready-to-use default). Once you curate here, machines are narrowed to just the chosen stations.

## Related

- [Ticket Vending Machine (block)](../structure/ticket-vending-machine.md)
- [Station Range Tool](../tools/station-range-tool.md)
- [Management Computer overview](overview.md)
