---
title: Train Detection Card
id: tools/train-detection-card
tags: [tool, item, detection]
---

# Train Detection Card

```embed:item id=trainsystemutilities:train_detection_card size=48 label=true
```

A **held auxiliary card** that records **a single specific point** on a track as a "place that reacts when a train passes."  
The recorded point is used in the [Railway Management Block](../railway-management.md)'s [SAS Announcement](../railway-management/announcement.md) as a trigger to "play an announcement when a train passes this point."

[[TOC]]

## Holding / usage

This card has no dedicated settings GUI. You just **hold it, right-click a track, and record a point.** The detailed conditions (target train, announcement content, etc.) are set afterward in the **railway management block's GUI**.

1. **Put the Train Detection Card on your hotbar and hold it.**
2. **Right-click the track block (Create's track)** you want to record (by default, the **right mouse button**).
   - Right-clicking something other than a track shows a message to the effect of "This is not a track" and nothing is recorded.
3. On a successful record, "Point recorded (coordinates)" is shown at the bottom of the screen.
4. The recorded coordinates can be checked in the **tooltip** when you **hover** the card.
5. **Shift + right-click** (right-click while sneaking) **clears** the recorded point.

> [!NOTE]
> This card only remembers **a single point on the track**. Detailed conditions such as a "detection range (radius)," "target-train filtering," or "trigger-timing type" **are not on the card.**  
> Those are adjusted on the [SAS Announcement Settings](../railway-management/announcement.md) GUI after you insert the recorded card into the railway management block.

## Operation summary

| Operation | What happens |
|---|---|
| **Right-click** a track | Record that track point as a detection point |
| **Shift + right-click** | Clear the recorded point |
| **Hover** over the card | Check the recorded coordinates in the tooltip |

## Usage with the Railway Management Block

The recorded card becomes an actual trigger through the following flow.

1. With the steps above, record the track point where you want an announcement to play.
2. Right-click to open the [Railway Management Block](../railway-management.md), then open the [SAS Announcement Settings](../railway-management/announcement.md).
3. In the announcement settings, **insert this recorded card into the detection-card slot**.
4. Now, when a train passes the recorded track point, the configured announcement plays.

## Integration

- Used as the playback trigger (pass-through detection point) for [SAS Announcement](../railway-management/announcement.md)

## Related

- [Railway Management Block](../railway-management.md)
- [SAS Announcement Settings](../railway-management/announcement.md)
- [Station Range Tool](station-range-tool.md)
