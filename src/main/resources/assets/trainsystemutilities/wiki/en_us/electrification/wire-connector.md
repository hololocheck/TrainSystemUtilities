---
title: Wire & Wire Connector Tool
id: electrification/wire-connector
tags: [electrification, item, tool]
---

# Wire & Wire Connector Tool

```embed:item id=trainsystemutilities:wire_connector size=48 label=true
```

![](bws:trainsystemutilities:wiki/screens/wire-connector__ja_jp.png)

A dedicated tool for stringing Wire between two points. Supports 5 built-in designs + saving arbitrary custom presets.

[[TOC]]

> [!IMPORTANT]
> Wire is **not** strung directly onto poles / trusses. You must raise two **Wire Insulators** and string the wire **between insulator and insulator**. Poles and trusses are bases that hold insulators. Read [Wire Insulator](insulator.md) first and prepare your support points.

## The two modes of this tool

By **holding the Alt key and scrolling the mouse wheel**, the Wire Connector Tool switches between two modes (while held, the current mode is shown above the hotbar).

| Mode | Hotbar label | What the mode does |
|---|---|---|
| **Placement (insulator connect)** | `Placement Mode` | Right-click insulators to string wire (see "How to string wire" below) |
| **GUI (design selection)** | `GUI Mode` | Right-click to open the wire settings screen and edit design or remaining amount |

> [!NOTE]
> Alt+wheel switches the **mode, not the wire design type**. Design selection (SIMPLE / TWO_TIER, etc.) is done inside the settings screen you open by right-clicking in "GUI Mode."

## How to string wire (placement mode)

1. First place two **Wire Insulators** at the points you want to support (right-click to place on a floor, pole, truss side, etc.).
2. Hold the Wire Connector Tool and switch to **Placement (insulator connect)** mode with Alt+wheel.
3. **Right-click the first insulator** → "Connect from: X, Y, Z" is shown and the start point is recorded.
4. **Right-click the second insulator** → wire is strung between the two points, and "Wire connected [design name]: ○○m" is shown.
5. **To redo**: right-click the same insulator again, or **Shift + right-click (in the air)** to clear the start point.

> [!TIP]
> There is a maximum length you can string (the tool tooltip shows "Max length"). Too close / too far shows a red "Too close / Too long" message and the wire cannot be strung.

## Refilling wire (survival)

In survival, stringing wire consumes the tool's "wire remaining" by the distance strung (m). You can check the remaining amount in the tooltip and on the gauge at the top of the GUI mode screen.

1. Prepare a **Wire Spool** (the `Wire Spool` item; 1 provides 100 m).
2. Switch to **GUI mode** with Alt+wheel and right-click → open the wire settings screen.
3. Put the spool into the **"Wire Load"** slot on the left of the screen and press the **"Refill Wire"** button to fill the tool's internal tank (max 6400 m).

> [!NOTE]
> In Creative mode no wire remaining is needed and you can string as much as you like (the remaining amount shows "Creative: unlimited wire").

## How to open the settings screen (GUI mode)

1. Switch to **GUI (design selection)** mode with Alt+wheel.
2. **Right-click** the tool (on a block or in the air) → the wire settings screen opens.
3. Select a design in the left panel.
4. Verify / edit parameters in the right panel.
5. Confirm the settings with **"Apply"** at the bottom of the screen (a confirmation dialog appears).

## Built-in designs (5)

| Design | Use | Tiers | Rows |
|---|---|---|---|
| **CUSTOM** | Starting point for custom presets | Free | Free |
| **SIMPLE** | Thin single line, decorative | 1 tier | 1 row |
| **TWO_TIER** | Standard two-tier (catenary + trolley) | 2 tiers | 1 row |
| **TWIN_2ROW** | Two-tier in parallel for double track | 2 tiers | 2 rows |
| **HIGH_OFFSET** | Wide vertical spacing for large vehicles | 2 tiers (wide) | 1 row |

> [!TIP]
> CUSTOM is freely configured with numeric sliders. See [Custom Wire Designs](custom-wire.md) for details.

## Search + Filter

Narrow the display with the filter dropdown at the top of the GUI:

- **All**: built-in + presets all shown
- **Basic**: built-in 5 only
- **Custom**: user-saved presets only

Type a name in the search box for instant filtering.

## Sag mode (SIMPLE only)

The "Sag mode" toggle is only operable while the SIMPLE design is selected.  
When ON, the single line droops in the center for a decorative effect (= evokes old-era electrification).

## Preset save

While editing CUSTOM, click "Save Preset" → enter a preset name in the dialog → Enter to save.  
After saving it is added to the tile list in the left panel and can be selected → applied right away.

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-save__ja_jp.png)

## Preset delete

**Right-click** a tile → delete confirmation dialog.

![](bws:trainsystemutilities:wiki/screens/wire-connector-preset-delete__ja_jp.png)

## Apply

The "Apply" button saves the current settings. Next, **right-clicking two insulators in placement mode** strings a new wire with that design.  
A confirmation dialog is shown before applying.

![](bws:trainsystemutilities:wiki/screens/wire-connector-confirm__ja_jp.png)

## Related

- [Wire Insulator](insulator.md) — the support points at both ends of the wire (right-click these)
- [Overhead Pole](overhead-pole.md) / [Overhead Truss](overhead-truss.md) — bases that hold insulators up high
- [Pantograph](pantograph.md) — the current collector that runs under the wire
- [Substation Cubicle](substation.md) — the source that powers the wire
- [FE Inverter](fe-inverter.md) — the train-side FE buffer
- [Custom Wire Designs](custom-wire.md) — CUSTOM detail parameters
