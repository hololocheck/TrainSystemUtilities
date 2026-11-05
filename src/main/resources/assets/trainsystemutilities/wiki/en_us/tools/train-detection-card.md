---
title: Train Detection Card
id: tools/train-detection-card
tags: [tool, item, detection]
---

# Train Detection Card

```embed:item id=trainsystemutilities:train_detection_card size=48 label=true
```

A card that records one point on a track where trains are detected. It is used with SAS to play announcements as trains pass.

[[TOC]]

Hold the card and right-click a Create track. Check the recorded coordinates in the tooltip. Shift + right-click clears it. Only tracks can be registered.

After registering, open the announcement settings of the [Railway Management Block](../railway-management.md) and put the card in the detection card slot. The sound to play and the conditions are set on the Railway Management Block. The card itself has no screen for setting sounds or play conditions.

{{if-mod spatialaudiosystem}}
- [Announcement Settings (with SAS)](../railway-management/announcement.md)
{{/if}}
