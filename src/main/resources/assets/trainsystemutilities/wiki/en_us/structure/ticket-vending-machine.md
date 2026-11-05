---
title: Ticket Vending Machine
id: structure/ticket-vending-machine
tags: [structure, block, ticket]
---

# Ticket Vending Machine

```embed:item id=trainsystemutilities:ticket_vending_machine size=48 label=true
```

Equipment that issues tickets for the destination you choose. Make room 2 blocks high and place it inside a [station group](../tools/station-range-tool.md). The station it belongs to is judged from its position and used as the departure station.

[[TOC]]

![](bws:trainsystemutilities:wiki/screens/ticket-vending-machine__main__en_us.png)

Right-click to open its screen and choose a destination button, and a ticket goes into your inventory. Tickets are free. When there are many destinations, scroll with the mouse wheel. Close it with the close button or Esc.

Set the destinations on sale in the [Tickets Tab of the Management Computer](../management-computer/tickets.md). The destinations shown are the stations connected to the same track network, excluding the machine's own station. If no station is selected in the Tickets Tab, all of those stations are shown. The Tickets Tab's settings are shared across the whole server and apply to every ticket vending machine. It cannot be used outside a station group, so register the station's range first.

```embed:item id=trainsystemutilities:ticket size=32 label=true
```

A ticket records its departure and arrival stations; check the section in its tooltip.

At an automatic ticket gate, you enter at the ticket's departure station. You can exit at the arrival station or at a station partway along the section you bought. Exiting collects the ticket. When a ticket is passed through, the gate opens for 3 seconds.
