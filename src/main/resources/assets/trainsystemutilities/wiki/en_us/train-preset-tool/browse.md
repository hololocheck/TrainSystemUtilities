---
title: Preset Browse / Place
id: train-preset-tool/browse
tags: [tool, preset, train]
---

# Preset Browse / Place

![](bws:trainsystemutilities:wiki/screens/train-preset-browse-mine__ja_jp.png)

The main GUI of the **Train Preset Tool** for browsing / placing / uploading / deleting saved presets.

[[TOC]]

## Opening / Holding

This screen is opened with the Train Preset Tool in **GUI mode** (the tool is in GUI mode right after you switch to it).

1. **Hold** the Train Preset Tool.
2. **Right-click** as-is (default: **right mouse button**). This screen opens.
3. To close it, press **Esc** or **left-click the "×"** at the top right of the screen.

> [!NOTE]
> If right-clicking opens a different screen (the save screen) or performs a placement action, the tool is in a mode other than GUI.  
> Return to **GUI mode** with **Alt + mouse wheel**, then right-click (the current mode is shown above the hotbar).

## Mode switch (dropdown)

**Left-click the mode indicator (dropdown)** at the top of the screen to open the list, and **left-click Mine / Place** to switch.

| Mode | Content |
|---|---|
| **Mine** (yours) | Locally saved presets (= ones you saved) |
| **Place** (public) | Online presets downloadable from [Preset Place](../preset-place/overview.md) |

## Main operations

| Operation | Behavior |
|---|---|
| **Left-click** a tile | Select preset → 3D preview in right panel |
| **Right-click** a tile | Delete confirmation (Mine) |
| **Drag & drop** a tile to the right panel | Prepare placement. Preview and required materials appear with "▶ Proceed / ✖ Cancel" buttons |
| Left-click **"▶ Proceed"** | Confirm placement. This screen closes and the tool switches to **Place mode** |
| Left-click the **refresh button** | Reload the list |
| Left-click the upload icon | Upload to Preset Place ([upload](../preset-place/upload.md)) |

## 3D preview

The right panel shows the selected preset in 3D. Operate the mouse over the preview:
- **Left drag**: rotate
- **Right drag**: pan
- **Mouse wheel**: zoom

## Search (Place mode)

Search public presets by name. **Left-click the search field** at the top, type a name, and combine it with the sorts below to narrow down:
- Created date / likes / downloads

## Materials display

Lists the blocks / items required by the selected preset.  
Shortfalls are shown in **red**, sufficient amounts in **green**. Details: [Material Refill](refill.md).

## Material source

Switch where the required materials are pulled from by scrolling the **mouse wheel over the material-source pill button** in the right panel:

| Source | Behavior |
|---|---|
| **Chest** | Auto-pull from the linked chest |
| **ME** | Pull from the AE2 network |

> [!TIP]
> To link a chest, **Shift + middle-click** the target chest while holding the tool (with the GUI closed, in normal holding mode).

## Glue tank

The amount of Create Super Glue (adhesive) required at placement time is shown here.

- **Left-click the `Refill` button** → the [Material Refill](refill.md) screen opens, where you can refill the tank with slime balls, etc.
- **Left-click the `Dump` button** → discards all of the tank contents.

## Placement flow

Confirming with "▶ Proceed" closes the GUI and switches the tool to **Place mode**. Then place it in the world:

1. **Right-click the track position** where you want the train (default: **right mouse button**) to set the **origin**.
2. **Alt + wheel** rotates the orientation in 90° steps (as needed).
3. **Right-click** again to execute placement. Materials and adhesive are consumed here.
4. If placement fails and can be retried, **middle-click** to retry, or **Shift + middle-click** to **cancel** Place mode and return to GUI mode.

## Related

- [Preset Save](save.md)
- [Material Refill](refill.md)
- [Preset Place Overview](../preset-place/overview.md)
- [Preset Detail](../preset-place/detail.md)
- [Preset Upload](../preset-place/upload.md)
