# Litematica Printer 4th

[简体中文](README_zh.md)

[![GitHub release](https://img.shields.io/github/v/release/water2004/litematica-printer?include_prereleases)](https://github.com/water2004/litematica-printer/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2-blue)](#download)
[![License](https://img.shields.io/badge/license-AGPL--3.0-green)](LICENSE.md)

> [!IMPORTANT]
> This is the fourth-revision fork of [BiliXWhite/litematica-printer](https://github.com/BiliXWhite/litematica-printer). This repository has its own releases, compatibility range, issue tracker, and documentation. Download this fork only from the [water2004/litematica-printer Releases page](https://github.com/water2004/litematica-printer/releases).

Litematica Printer 4th is a client-side Fabric extension for [Litematica](https://modrinth.com/mod/litematica). It continuously discovers unfinished schematic positions and processes them through a bounded, multithreaded producer-consumer scheduler. Printing, filling, fluid removal, and bedrock breaking are independent modes.

## Download

Download the jar matching your Minecraft version from [GitHub Releases](https://github.com/water2004/litematica-printer/releases):

| Minecraft | Release artifact |
| --- | --- |
| 26.1.2 | `litematica-printer-<version>+26.1.2.jar` |
| 26.2 | `litematica-printer-<version>+26.2.jar` |

Only these two artifacts are built and tested. The upstream project and this fork use the same mod id, `litematica-printer`, so do not install both at the same time.

Back up important worlds and inventories before using automated placement or breaking features.

## Requirements

Install these client-side dependencies for the same Minecraft version:

- [Fabric Loader](https://fabricmc.net/use/installer/)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [MaLiLib](https://modrinth.com/mod/malilib)
- [Litematica](https://modrinth.com/mod/litematica)

The tested baselines are MaLiLib `0.28.8` with Litematica `0.27.9` on Minecraft 26.1.2, and MaLiLib `0.29.2` with Litematica `0.28.3` on Minecraft 26.2. Compatible newer Litematica releases may also work within the declared Minecraft range.

## Optional integrations

The base placement, filling, and fluid-removal features do not require the integrations below.

| Integration | What it enables |
| --- | --- |
| [ChainVeinFabric](https://github.com/water2004/ChainVeinFabric) | Break wrong or extra blocks and perform ice-to-water jobs. A client installation is enough for basic breaking; server-only ChainVein features require server support. |
| [Quick Shulker](https://github.com/water2004/quickshulker) | Retrieve required materials from carried shulker boxes. The direct screen-independent protocol is used when supported; the legacy screen path remains isolated for compatible older releases. Use matching Quick Shulker client and server versions. |
| AxShulkers or TakeItOut | Alternative server-backed shulker material sources selectable in the printer settings. |
| [Servux](https://modrinth.com/mod/servux) | Litematica Easy Place protocol support and optional server-authoritative held-item confirmation. |
| [Fabric-Bedrock-Miner](https://github.com/bunnyi116/fabric-bedrock-miner) or [Block-Miner](https://github.com/z7087/blockminer) | Executes jobs produced by the bedrock-breaking mode. |

All integrations are capability-detected. Features that depend on a missing integration stay disabled or are skipped; normal printing remains available.

## What changed in the fourth revision

### Continuous producer-consumer scheduling

- A producer continuously scans immutable world and schematic snapshots instead of alternating between a full search and a print pass.
- Search work is divided into small tasks and processed by a configurable worker pool without reading the live world from worker threads.
- A bounded job pool groups placement, state-adjustment, use, and breaking jobs by transaction kind.
- The consumer traverses job buckets fairly and batches compatible jobs, reducing item switching and preventing one skipped position from blocking unrelated work.
- Processed, failed, skipped, stale, and unavailable-material jobs are all consumed. If a position still needs work, a later producer scan discovers it again.
- Consumer actions validate the live world before execution, so an old snapshot cannot force an obsolete action.

### Lower scanning overhead

- Work-range shapes and schematic/selection workspaces are compiled into reusable spatial masks.
- GUI statistics and printer discovery reuse compatible search plans and shared snapshot pages.
- Search cursors read packed page data directly and create `BlockPos` objects only for positions that become jobs.
- The HUD publishes completed statistics rather than displaying a producer's partial in-flight pass.

In a fresh Minecraft 26.2 A/B benchmark using the same 531,441-position fixture, four search threads, two warm-up rounds, and seven measured rounds, median complete GUI + printer scan time fell from `457.45 ms` in 1.0.0 to `45.59 ms` in 1.1.0—about `10.0x` faster on the development system. See the [1.1.0 release notes](https://github.com/water2004/litematica-printer/releases/tag/v1.1.0) for methodology, correctness checks, and caveats.

### Predictable item and action handling

- Every inventory or hotbar item change waits for its configured confirmation path before printing continues.
- Remote shulker retrieval has explicit waiting states instead of allowing the consumer to race ahead of material arrival.
- Same-kind jobs can be processed in one tick up to the configured placement or breaking limits.
- Disabling the global work switch pauses work without clearing producer or consumer state.
- Cycling modes changes the selected mode but never turns on the global work switch automatically.

## Features

### Work modes

- **Print:** place missing blocks, adjust supported block states, and optionally break wrong or extra blocks.
- **Fill:** fill the active Litematica selection using configurable block and direction filters.
- **Fluid removal:** remove configured still or flowing fluids inside the selected work area.
- **Bedrock breaking:** discover bedrock jobs and submit them to a supported bedrock-mining mod.

The legacy general-purpose mining mode and the built-in remote warehouse were removed.

### Special print actions

Existing block-operation behavior remains available, including:

- place a log and then strip it when stripped logs are required;
- place ice and break it to produce water;
- direct water placement and waterlogged-block handling;
- directional and state-sensitive placement for observers, pistons, stairs, doors, trapdoors, signs, heads, banners, redstone components, and other supported blocks;
- note-block tuning, safe observer placement, coral replacement, crop bonemealing, and composter filling;
- configurable skip and replace lists;
- normal interaction placement and the alternate packet placement path.

### Feedback and controls

- Stable completion progress and missing-material HUD.
- Current consumer activity, wait reason, job position, and job-pool length.
- Producer scan progress for the current round.
- Configurable placement highlights for place, adjust, break, and failed actions.
- Work-range shape, traversal order, axis direction, search-thread count, and per-tick action limits.
- Litematica visible-layer filtering is respected by both discovery and execution.

## Getting started

1. Install the correct printer jar and required dependencies.
2. Load and place a schematic with Litematica.
3. Press `Z`, then `Y` by default to open the printer settings.
4. Enable the **Print** module, or another work mode you want to use.
5. Move within interaction range of the target blocks.
6. Press `Caps Lock` to enable the global **Work Switch**.

Both the global Work Switch and a specific work-mode switch must be enabled. The default work range of `0` automatically uses the available interaction distance.

## Important settings

- **Blocks per tick / work interval:** controls throughput. Servers with rate limits or anti-cheat may require lower values or a longer interval.
- **Place in air:** allows placement without an existing adjacent support block where the server accepts it.
- **Packet placement:** uses the alternate packet-sending placement path. It does not override server validation or network loss.
- **Servux held-item confirmation:** waits for the server-authoritative main-hand item after a switch. If enabled without a compatible Servux server, the printer intentionally pauses and reports that wait state.
- **Break wrong/extra blocks:** requires ChainVeinFabric. Without it, destructive print jobs are not enabled.
- **Quick Shulker source:** select Quick Shulker, AxShulkers, or TakeItOut to match the environment actually installed.

## Troubleshooting

### The printer is not doing anything

Check that:

- the jar matches the exact Minecraft version;
- Fabric API, MaLiLib, and Litematica are loaded;
- a schematic is placed and its relevant layer is visible;
- both the chosen mode and the global Work Switch are enabled;
- the player is within the configured work range;
- the required item is available and the HUD is not reporting a hand or shulker wait;
- server anti-cheat or placement limits are not rejecting the configured speed.

### Breaking jobs never run

Wrong-block, extra-block, and ice-breaking jobs require ChainVeinFabric. Bedrock jobs additionally require one of the supported bedrock-mining mods.

### The HUD says it is waiting for Servux

Server-authoritative held-item confirmation was enabled, but the current server did not expose a compatible Servux channel. Install/configure Servux on the server or disable that confirmation option.

### Quick Shulker retrieval is waiting or unavailable

Confirm that the selected shulker source matches the installed integration. For the direct Quick Shulker protocol, keep the client and server on the same compatible release. The HUD wait state identifies whether the printer is waiting for a container operation or an item switch.

For reproducible bugs, open an issue in [this repository](https://github.com/water2004/litematica-printer/issues) and attach both client and server logs, Minecraft/mod versions, relevant settings, and a minimal schematic when possible.

## Building from source

The project requires JDK 25 and includes the Gradle wrapper.

```bash
git clone https://github.com/water2004/litematica-printer.git
cd litematica-printer
./gradlew build
```

On Windows, use `gradlew.bat build`. Version-specific jars are written to:

- `versions/26.1.2/build/libs/`
- `versions/26.2/build/libs/`

Minecraft-independent scheduling code lives in `core/`; each directory under `versions/` contains its own Minecraft adapter. Tagged commits on `main` run GameTests, build both jars, and publish the corresponding GitHub Release.

## License

This project is distributed under the [GNU Affero General Public License v3.0](LICENSE.md).
