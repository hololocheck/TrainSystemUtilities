---
title: What is TrainSystem Utilities
id: about
---

# What is TrainSystem Utilities

TrainSystem Utilities (TSU) is an extension MOD that adds rail-related conveniences to Minecraft's **Create MOD (1.21.1)**.  
Built on top of Create's existing "tracks / stations / schedules / signals", TSU provides station display boards, network overview management, monitor integration, line symbols, an **electrification system**, and more, in a **realistic-looking but easy-for-anyone-to-use** form.

[[TOC]]

## What can this MOD do?

| Feature | Summary |
|---|---|
| Station-level display boards | Place a "Railway Management Block" on a station platform to auto-display stopped / next trains |
| Network overview management | Use the "Management Computer" to manage all stations / trains / schedules in one place |
| Station monitor integration | Place monitor blocks near a station for real-time display |
| Line symbols | Create custom line symbols like JA01 / JB02 and assign them to stations |
| Coupling / decoupling | Dynamically couple / decouple two trains via schedule conditions |
| Poster display | An advertisement board that displays PNG/JPG images as a slideshow |
| **Electrification system** | **Pantograph + wire + substation + FE inverter to supply FE/Create energy to trains** |
| Train preset | Save / restore / share an entire train as JSON |
| Preset Place | Share train presets online (BelugaExperience platform) |

### Added items / blocks

> [!TIP]
> Icons with a **blue marker in the bottom-right corner** are clickable and jump to that page.

#### Station & display (9)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:railway_management_block,trainsystemutilities:management_computer,trainsystemutilities:poster_management_block,trainsystemutilities:monitor,trainsystemutilities:double_monitor,trainsystemutilities:monitor_half,trainsystemutilities:double_monitor_half,trainsystemutilities:monitor_slim,trainsystemutilities:double_monitor_slim links=railway-management,management-computer/overview,poster-management,-,-,-,-,-,-
```

#### Tools (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:station_range_tool,trainsystemutilities:train_preset_tool,trainsystemutilities:transit_terminal links=tools/station-range-tool,tools/train-preset-tool,tools/transit-terminal
```

#### Data cards (3)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:memory_card,trainsystemutilities:monitor_link_card,trainsystemutilities:train_detection_card links=tools/memory-card,tools/monitor-link-card,tools/train-detection-card
```

#### Electrification (6)

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

→ See [Electrification System Overview](electrification/index.md) for details.

> [!TIP]
> **It is NOT a MOD that "drives trains itself"**. Driving is handled by Create's schedules.
> TSU is a peripheral toolset that "makes Create trains easier to view, manage, and electrify".

## What MODs is it designed to be used with? {#recommended-mods}

| MOD | Role | Required? |
|---|---|---|
| **Create** | The rail mechanism core | ✅ Required |
| **Manta** | GUI / monitor / Wiki / BelugaExperience rendering framework | ✅ Required |
| **SpatialAudioSystem** | Sound effects like station departure melodies and announcements (author's other MOD) | Recommended |
| **Mekanism / Applied Energistics 2** | FE power source to supply the electrification system | When using electrification |
| Create: New Age, etc. | Extra train mechanisms | Optional |
| BSL Shaders, etc. | Visual enhancement | Optional |

> [!NOTE]
> Combined with **SpatialAudioSystem**, departure melodies and in-train announcements play at the same station as your TSU station displays, greatly boosting the station's atmosphere.

## What is it good for?

Good for:

- **Large-scale network operation**: you want to manage many stations / trains from a single list
- **Atmospheric station building**: you want to give stations character with monitors, line symbols, and poster guidance
- **Multiplayer rail operation**: you want to build schedules together while preventing accidental edits
- **Electrified line construction**: you want to reproduce electric train operation with overhead wire and FE power supply
- **Train preset sharing**: you want to save your own trains as JSON and share them with other worlds or players

Not good for:

- **Running just a single freight train**: at that scale the Management Computer is overkill
- **Using without Create**: TSU does nothing on its own

## Architectural pieces (for advanced users)

- **BelugaExperience UI System**: V3 GUI widget framework (controller + json builder + auto-sizing). In the `belugalab.experience.*` package.
- **MCSS Wiki**: in-game markdown wiki + JSON-driven embeds (`embed:screen` / `embed:item` / `embed:items`)
- **GUI capture pipeline**: on login, all layout JSONs are captured via an off-screen FBO → reflected in the wiki immediately as DynamicTextures
- **i18n**: when switching between ja_jp / en_us, run `/tsu-wiki-prebuild` to regenerate the language-specific captures

## Pages to read first

- [Getting Started](getting-started.md) — the shortest route to getting started
- [Management Computer Overview](management-computer/overview.md) — a tour of the central GUI
- [Railway Management Block](railway-management.md) — the station display board
- [Poster Management Block](poster-management.md) — the image board
- [Electrification System](electrification/pantograph.md)

## Development status

> [!IMPORTANT]
> This is a MOD in development. Setting save formats and APIs may change.
> Before running it in production on a large world, we recommend confirming its behavior in a test world.
