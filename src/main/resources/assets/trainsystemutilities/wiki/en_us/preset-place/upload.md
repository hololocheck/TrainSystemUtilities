---
title: Preset Upload
id: preset-place/upload
tags: [preset-place, upload]
---

# Preset Upload

![](bws:trainsystemutilities:wiki/screens/preset-place-upload__ja_jp.png)

Dialog for requesting to publish your own preset to Preset Place.

[[TOC]]

## Opening {#open}

First, you must save the train you want to publish locally as a [train preset](../train-preset-tool/browse.md).

1. **Right-click** the [Train Preset Tool](../train-preset-tool/browse.md) in **GUI mode** to open the browse screen (switch modes with **Alt + wheel**).
2. Set the mode to **`Mine` (yours)**.
3. **Left-click** the **upload icon** on the tile of the preset you want to publish to open this dialog.

> [!NOTE]
> The upload icon is shown only on your own presets that have Microsoft account linking set up and were not downloaded from Preset Place.

## Upload items

| Item | Summary |
|---|---|
| Images (up to 5) | PNG / JPG for preview. Add with the + button |
| Markdown description | 4096 chars. Supports newline / paste / copy / Ctrl+Enter |
| Publish button | Submits the publish request after input validation |

## Description editor

Multi-line Markdown editing. Use the `Preview` toggle to check the rendered result:

- Standard Markdown such as headings / lists / links / emphasis / quotes
- Ctrl + Enter executes `Publish`

## Publish flow

1. `Publish` is enabled with 1+ image and 1+ char of description
2. Publish confirm dialog
3. The client sends it to the Preset Place server
4. Goes live after review (= automatic or moderator)

## Authentication

Publishing requires Microsoft account authentication.  
Check auth status / re-authenticate in the [Creator Center](creator-center.md).

## Related

- [Preset Place Overview](overview.md)
- [Preset Detail](detail.md)
- [Creator Center](creator-center.md)
- [Train Preset Browse](../train-preset-tool/browse.md)
