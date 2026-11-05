---
title: Announcement Settings (SAS Integration)
id: railway-management/announcement
tags: [station, announcement, sas, audio]
---

# Announcement Settings (SAS Integration)

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__en_us.png)

Plays sound as trains pass or stop. Requires [Spatial Audio System (SAS)](https://github.com/hololocheck/SpatialAudioSystem). Choose "Announce" from "Function" on the [Railway Management Block](../railway-management.md).

[[TOC]]

## Overall settings

"Detection" turns all announcements on or off. "Show range frame" shows the Range Board's range, and in attenuation mode the attenuation range, on your own screen. "Attenuation mode" sets whether the sound weakens with distance.

## Registering when to play

Create an entry with "Add Entry" and set its condition and the sound to play. Up to 16 entries can be registered. Click the condition field and choose "None", "On Pass" or "On Stop". With "None", nothing plays. Right-click to clear the condition.

![](bws:trainsystemutilities:wiki/screens/railway-management-announcement__en_us.png)

Specify the sound by putting an SAS recording medium in each entry's medium slot. Adjust the delay and the play count by pointing the cursor at the value and turning the mouse wheel. The delay is in seconds and applies only to the entry's first play. A negative value is treated as 0. The play count is 1 to 10. "Test Play" plays the first entry once so you can check it.

Deleting an entry with its delete button gives back the recording medium that was in it.

## Detection point and playback range

Set the point where trains are detected on the [Detection Card](../tools/train-detection-card.md), and the range the sound reaches on the Range Board. Put each in its own slot. Their roles differ from that of the sound medium.

## Sharing with other stations

Open "Share" and choose the stations to share with and each detection and range item. When this station receives sharing from another station, the source is shown at the bottom of the screen.
