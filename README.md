<p align="center">
  <img src="src/main/resources/trainsystemutilitieslogo.png" alt="Train System Utilities" width="220">
</p>

<h1 align="center">Train System Utilities</h1>

<p align="center">
  A BelugaLab railway systems mod for Minecraft, built on <a href="https://github.com/hololocheck/Manta">Manta</a> and <a href="https://github.com/Creators-of-Create/Create">Create</a>.
</p>

---

Train System Utilities (TSU) turns Create's trains into a managed railway. It adds
operation tools, train control interfaces, scheduling and network management, and an
in-game wiki — all rendered through the BelugaExperience design workflow on top of the
Manta UI runtime.

## Features

- **Railway management console** — monitor your track network, lines, and stations from a
  single in-game screen.
- **Coupling / decoupling tools** — connect and split trains in the field.
- **Scheduling & electronic timetables** — author Create schedules, export them, and share
  them between trains.
- **FE electrification** — power Create trains with Forge Energy: substations, overhead
  lines, pantograph pickup, consumption, and automatic stop-on-empty.
- **Transit terminal** — an in-world route/transfer guide for passengers.
- **Station groups, monitors, platform doors, and signage** — the operational furniture of
  a real network.
- **Train Preset Tool** — save train consists as presets and place them back into the world.
- **Built-in wiki** — every screen and tool is documented in-game, in Japanese and English.

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.220+ |
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) | 6.0.10+ |
| [Manta](https://github.com/hololocheck/Manta) | 1.0.0+ |
| [GeckoLib](https://www.curseforge.com/minecraft/mc-mods/geckolib) | 4.7+ |

## Open core & Preset Place

TSU is **open core**. The source code in this repository — railway management, wiring,
electrification, routing, timetables, monitors, the wiki, and local preset save/placement —
is published under the [MIT license](LICENSE).

The mod also ships **Preset Place**, an optional online service for sharing train presets
with other players. Its client, authentication flow, and online UI are proprietary and are
bundled with the released binary in obfuscated form only; that part is **not** in this
repository. Everything else works fully offline without it.

## Building from source

```bash
./gradlew build
```

The build produces a working mod jar from the open-core sources. The Preset Place online
components are not part of the public sources, so a build from this repository has its
online sharing features disabled — every local feature builds and runs normally.

Manta must be available as a dependency (see [`build.gradle`](build.gradle) for the expected
path).

## License

MIT for the open core. See [LICENSE](LICENSE) for the full text and the notice covering the
proprietary Preset Place components.
