---
title: Preset Place Overview
id: preset-place/overview
tags: [preset-place, community, online]
---

# Preset Place Overview

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

TSU's community sharing feature. Publish your own train presets, and browse / download presets made by other users.

[[TOC]]

## Big picture

```
[Local presets] ── upload ──> [Preset Place server]
                                       │
                browse ←────────────────┤
                download <──────────────┘
[Other users' worlds] <─── placement
```

Backend: BelugaExperience-powered Supabase.  
Auth: Minecraft account linking (Microsoft account → JWT).

## Pages

| Page | Content |
|---|---|
| [Preset Detail](detail.md) | Single preset details + 3D preview + download |
| [Profile](profile.md) | User profile + public presets + follow |
| [Upload](upload.md) | Publish dialog for your own presets (Markdown description supported) |
| [Creator Center](creator-center.md) | Creator account stats / dashboard |

## Main features

| Feature | Behavior |
|---|---|
| Like | ♥ presets you enjoyed |
| Download count | Cumulative DL count for live presets |
| Report | Report inappropriate presets (with a reason) |
| Follow | Follow a creator |
| Profile icon | Custom SVG icon ([profile-icon-editor](../management-computer/overview.md#owner-face)) |

## How to access {#access}

Every Preset Place screen is opened from the **[Train Preset Tool](../train-preset-tool/browse.md)**.

1. **Hold** the **Train Preset Tool**.
2. Switch the tool to **GUI mode** with **Alt + mouse wheel** (while held, the current mode is shown above the hotbar).
3. **Right-click** to open the train preset browse screen.
4. Switch the mode dropdown at the top of the screen to **`Place` (= public)**.
5. **Left-click** a preset tile in the list to open its [detail page](detail.md).

Each screen is reached via the detail page.

- **Profile** … In public mode, click your own name/icon area, or click the uploader's name on a detail page.
- **Upload** … In `Mine` (yours) mode, click the upload icon on your own preset ([Upload](upload.md)).
- **Creator Center** … From the "Creator Center" button on your own [Profile](profile.md) page.

> [!NOTE]
> Using Preset Place requires Microsoft account linking. You will be asked to authenticate the first time (see each page for details).

## Related

- [Preset Detail](detail.md)
- [Profile](profile.md)
- [Upload](upload.md)
- [Creator Center](creator-center.md)
- [Train Preset Browse](../train-preset-tool/browse.md)
