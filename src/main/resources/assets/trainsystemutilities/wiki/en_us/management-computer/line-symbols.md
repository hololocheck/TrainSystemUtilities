---
title: Line Symbols Tab
id: management-computer/line-symbols
tags: [management-computer, line-symbol]
---

# Line Symbols Tab

![](bws:trainsystemutilities:wiki/screens/management-computer__symbol__ja_jp.png)

The Line Symbols tab of the Management Computer. Create, edit, and delete line symbols (e.g. `JA`, `JB`, `M01`).

[[TOC]]

## How to open

1. **Place** a **Management Computer** block and **right-click** it to open the screen.
2. **Click** the top-left dropdown and choose **"Ⓜ Line Symbols"**.
3. When the symbols don't all fit, roll the **mouse wheel** over the list to scroll.

## What is a Line Symbol?

An identifier assigned to each station and train. Example: Yamanote Line = `JY`, Chuo Line = `JC`.  
Assigned to stations in the [Stations Tab](stations.md) → displayed on the [Route Map](route-map.md) and the [Railway Management Block](../railway-management.md).

## What is shown

| Column | Content |
|---|---|
| Symbol text | 2-3 character line abbreviation (e.g. `JA`) |
| Color | Background / text color |
| Shape | Circle / rounded square / hexagon / etc. |
| Stations using it | Number of stations this symbol is assigned to |

## Controls

| Action | How | Result |
|---|---|---|
| Create new | **Click the "＋ New" button** at the top right | The [Symbol Editor](symbol-editor.md) opens so you can make a new symbol |
| Edit | **(Left-)click a symbol tile** in the list | Edit that symbol in the [Symbol Editor](symbol-editor.md) |
| Delete | **Right-click a symbol tile** in the list | A delete confirmation appears ([below](#記号削除時の挙動)) |

> [!TIP]
> Hovering over a symbol tile shows the hint "Click: Edit / Right-click: Delete".

## Symbol delete behaviour {#記号削除時の挙動}

**Right-clicking** a symbol tile shows a **delete confirmation**. **Click "🗑 Delete"** to confirm; just close it to keep the symbol.

Deleting an in-use symbol clears the symbol from every station it was assigned to.  
Reassign from the [Stations Tab](stations.md).

## Related

- [Symbol Editor](symbol-editor.md)
- [Stations Tab](stations.md)
- [Route Map](route-map.md)
- [Railway Management Block](../railway-management.md)
