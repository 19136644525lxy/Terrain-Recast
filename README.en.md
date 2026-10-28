# Terrain Recast

> A Fabric mod that rebuilds overworld terrain from a plate-tectonics driven causal pipeline.
> 用「板块构造 → 大地貌 → 气候 → 高度」的因果链重写主世界地形。

[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62b47a?style=flat-square)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric_Loader-0.20.1+-dbb69b?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25+-b07219?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)

---

## What it does

Terrain Recast takes over overworld generation with custom density functions. Terrain is no
longer "Perlin noise stacked into a height" but a causal chain:

```
plates (soft Worley Voronoi)
  → continentalness / plate-boundary proximity
  → orogenic belts & mountain walls / trenches / plateaus / basins / rifts
  → erosion envelope (sharp young ranges vs. rounded old ones)
  → analytic height field → density function → real block terrain
```

Everything derives from a single **continuous field**: plate identity is never read directly,
so straight Voronoi edges never turn into hundred-block vertical cliffs. Peaks exceed
**Y 1500** in favorable seeds, and the full vanilla world height (2544 blocks) is supported.

## Features

- **Plate-driven terrain** — soft Voronoi plates plus an F2−F1 boundary distance field: mountains
  rise along boundaries, trenches cut into the ocean side
- **Two range archetypes** — sharp ranges (`edge⁶ × ridge³`, alpine aretes) and broad massifs
  (low-frequency blocks, plateau-like bodies)
- **Himalayan belts** — convergent boundaries are stitched into one continuous wall by
  low-frequency ridge lines instead of a row of isolated cones
- **Six landform classes** — plains / hills / plateaus (with escarpments) / basins (flat floors) /
  rifts / mountain walls, distributed by plate setting and latitude
- **Snowline model** — altitude cooling is two-staged: gentle below the local snowline, steep
  above it. The snowline itself shifts with latitude and aridity (≈980 ± 480 blocks), so arid
  high mountains stay bare rock (`stony_peaks`) while humid ones get snow caps
- **Flat-topped massifs** — large-scale noise carves out mesa zones; inside them, peaks above
  relative height 900 are cut to a near-level tabletop at 82% of their height (5% residual
  slope), producing mesas and tepuis with sheer shoulders. Mountains outside the zones keep
  their sharp summits
- **Spawn protection** — terrain within 900 blocks of spawn is flattened into plains, so you
  never "spawn on a mountain"
- **Real surface** — custom `material_rule` places surface blocks by altitude, temperature and
  slope
- **Commands & presets** — locate peaks, inspect the terrain under your feet, export/import
  tuning presets
- **Open API** — data modifiers, feature injection, ore registry, event bus
  (see the [API guide](api-guide/README.md))

## Requirements

| Component | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | ≥ 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Java | 25+ |

## Installation

1. Install a matching Fabric Loader
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and this mod's jar into `mods/`
3. **Create a new world** — the terrain generator is fixed at world creation; existing worlds
   are unaffected

## Commands

| Command | Description | Permission |
|---|---|---|
| `/terrainrecast info` | Print key config values; players also get terrain fields under their feet | everyone |
| `/terrainrecast peak [radius] [minHeight]` | Find the tallest peaks within a radius (default 2000 / 800) | everyone |
| `/terrainrecast preset list` | List built-in presets | everyone |
| `/terrainrecast preset apply <id>` | Apply a preset (writes config) | OP level 2 |
| `/terrainrecast preset export` | Export the current config as a share code | everyone |
| `/terrainrecast preset import <code>` | Import a share code (writes config) | OP level 2 |

`peak` samples the analytic height field directly and **generates no chunks**; a 20000-block
radius takes roughly 12 ms:

```
Top 5 peaks within 10000 blocks:
1. (9654, 4352) height 1528 · surface Y≈1591 · distance 10482
...
```

Heights are **relative to sea level**; surface Y ≈ height + 63.

## Configuration

Config lives in `config/terrain_recast.json` (per-world, applied when the world is reloaded).

| Key | Default | Range | Description |
|---|---|---|---|
| `terrain.mountain_amp` | 1100 | 0–3000 | Mountain amplitude |
| `terrain.vertical_scale` | 1.0 | 0.3–3.0 | Global vertical scale |
| `terrain.interior_amp` | 42 | 0–600 | Inland relief |
| `terrain.abyss_amp` | 16 | 0–120 | Deep-ocean relief |
| `terrain.trench_depth` | 60 | 0–300 | Trench depth |
| `terrain.coast_span` | 30 | 5–150 | Coast span |
| `terrain.erosion_round` | 0.55 | 0–1 | Erosion roundness (higher = rounder) |
| `terrain.min_continent_height` | 8 | 0–80 | Minimum continental height |
| `climate.latitude_scale` | 6000 | 1000–30000 | Latitude band scale |
| `general.spawn_land_radius` | 1400 | 0–20000 | Spawn continent radius |

## Development

### Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
.\gradlew build --no-configuration-cache
```

Artifacts land in `build/libs/`. Add `--rerun-tasks` to force a fresh jar.

### Verification

| Command | Purpose |
|---|---|
| `.\gradlew runServer --no-configuration-cache` | Smoke test: registry / feature / chunk-generation errors |
| `.\gradlew dumpPreview --no-configuration-cache` | Offline PNG dumps (height / slope / biome / landform) plus percentile stats |

**A green `build` is not "it works"**: registry and feature conflicts only show up in
`runServer`.

When tuning terrain parameters you **must pin the world seed** (`level-seed` in
`run/server.properties`); otherwise every run picks a new seed and coverage numbers swing by
±50%.

### Layout

```
src/main/java/yifei/terrain/recast/
├── worldgen/cell/     analytic field core (RealCellLookup, Worley, Cell)
├── worldgen/densityfunction/  custom density functions
├── worldgen/feature/  vegetation, ore deposits
├── commands/          command implementations
├── config/            config and presets
├── core/              registration and bootstrap
├── api/               public API (modifiers, events, injection, ores)
└── mixin/             required injections

src/main/resources/data/
├── minecraft/         overrides: dimension / noise_settings / density_function / material_rule
└── terrain_recast/    custom density functions, tags, recipes

api-guide/             add-on API documentation (public contract)
```

### Documentation

- [Add-on API guide](api-guide/README.md) — full API usage, runnable examples, pitfall list
- `docs/` holds internal project documents (design vision, handover notes); kept in the
  development workspace only and not shipped with the repository

## License

Released under the [Apache License 2.0](LICENSE).
Third-party reference projects are credited in [NOTICE](NOTICE).

Minecraft is a trademark of Mojang Studios. This project is not affiliated with Mojang or
Microsoft.
