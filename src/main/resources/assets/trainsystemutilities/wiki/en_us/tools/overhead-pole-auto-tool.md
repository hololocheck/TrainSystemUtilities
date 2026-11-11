---
title: Overhead Pole Auto Tool
id: tools/overhead-pole-auto-tool
tags: [electrification, tool]
---

# Overhead Pole Auto Tool

```embed:item id=trainsystemutilities:overhead_pole_auto_tool size=48 label=true
```

A **held tool** that places an [overhead pole](../electrification/overhead-pole.md) at the point on a track you right-click. First decide the pole height, clearance, number of parallel tracks, etc. on the settings screen, then switch to placement mode and right-click a track to raise one pole at that point.

## Video walkthrough

Watch the full flow from setup to placement (Manta's embedded video feature).

```embed:youtube url=https://youtu.be/ecg9SXoiEd4 title="Overhead Pole Auto Tool" url.ja=https://youtu.be/96rQAbeh5DI url.zh=https://youtu.be/_icdpBLl9O8 title.ja="TSU ガイド: 架線柱の自動設置" title.zh="TSU 指南：自动放置接触网支柱"
```

## Opening / holding

This tool has **two modes**. Right after switching to it, it is in "GUI mode" (the settings screen).

- **GUI mode** (initial state): Right-click opens the **settings screen**. Here you adjust the pole height, clearance from the track, number of parallel tracks, truss / insulator usage, etc.
- **Select mode**: The mode where you right-click a track to **actually place a pole**.

Switch modes with **Alt + mouse wheel** (see "Controls" below). While held, the current mode is shown above the hotbar.

## Usage

1. Hold the tool and simply **right-click** (by default, the **right mouse button**) to open the **settings screen**.
2. On the settings screen, decide the pole height, clearance, number of parallel tracks, truss / insulator, etc. (for settings-screen operations, see [Settings screen](#settings) below).
3. Switch to **Select mode** with **Alt + wheel**, then set it to the **"Place"** sub-mode with **Ctrl + wheel**.
4. **Right-click the track** where you want a pole, and one pole is placed at that point (a translucent preview appears while you aim at the track).
5. When materials are insufficient, "Not enough materials" is shown in red and nothing is placed.

> [!NOTE]
> In the "Place" sub-mode, right-clicking **something other than a track** shows "Please right-click a track" and nothing is placed. Be sure to aim at Create's track.

## Settings screen {#settings}

![](bws:trainsystemutilities:wiki/screens/overhead-pole-auto-settings__main__en_us.png)

- **Height / Clearance / Parallel tracks**: point at the number and turn the **wheel**.
- **Cantilever / Place truss / Insulator above track**: **click** the switch.
- **Linked Storage**: the chest / ME the materials are drawn from (link a chest with **Shift + middle-click** while holding the tool).
- The preview on the right: **drag** to rotate, **Shift + drag** to move, **wheel** to zoom.
- **"Apply -> Placement mode"** asks for confirmation; **"To placement"** switches the tool to Select mode's "Place" sub-mode and closes the screen.

## Controls (wheel / click)

| Input | Action |
|---|---|
| **Right-click** (GUI mode) | Open the settings screen |
| **Right-click** (Select mode / Place) | Place one pole at the aimed track point |
| **Alt + wheel** | Switch tool mode (GUI ⇔ Select). However, while in the "Place" sub-mode, rotate the pole's facing in 8 directions (45° steps) |
| **Ctrl + wheel** | Switch sub-mode within Select mode (back to GUI / Place) |
| **Shift + wheel** | Increase / decrease the pole height (the same value as "Height" on the settings screen) |
| **Shift + middle-click** | **Link an aimed chest as a material store** (poles' materials are drawn from it automatically) |

> [!TIP]
> Height and clearance are adjusted on the **settings screen**. The height can also be changed with **Shift + wheel** (while held).  
> For canted (banked) curves and multi-track sections, you can switch to a configuration that uses the [Overhead Truss](../electrification/overhead-truss.md) via the truss option on the settings screen.

## Related pages

- [Overhead Pole](../electrification/overhead-pole.md) / [Overhead Truss](../electrification/overhead-truss.md)
- [Electrification overview](../electrification/index.md)
