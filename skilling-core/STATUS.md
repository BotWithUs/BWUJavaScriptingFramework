# skilling-core — status

The host-side **skilling SDK + Atlas data-layer reader**. Per-skill scripts live
in the separate **`../../BotWithUsScripts`** project (composite build) and depend
only on the published `bot-api` + `bot-skilling` artifacts.

## Shipped (2026-06-24)

- **Atlas** (`atlas/`) — read-only `resolved.sqlite` reader (sqlite-jdbc).
  - `recipe(id)`, `gatherSpots(id)`, `gatherSpotsByCategory`, `banks()`,
    `nearestBank`, `nearestGatherSpot`, `meta`.
  - `closure(item, qty, stopSkills)` — host port of the analyzer's
    `_compute_closure` (colored DFS → topo order → quantity rollup → raw
    materials with gather spots → xp/skill). **Parity verified** by
    `AtlasClosureTest` (100 bronze bars → 100 copper + 100 tin ore).
  - Located via `~/.botwithus/native/resolved.sqlite` or `-Dbotwithus.atlas`
    (self-contained in `AtlasPaths`; no dependency on `core`).
- **SDK** (`script/`) — `SkillScript` (wires api/nav/atlas/backpack/banking +
  idle/level gates), `GatherScript` (data-driven gather loop), `Capability`.
- **Banking** (`banking/Banking.java`) — greenfield: walk to nearest Atlas bank
  via the pathfinder, open a booth, deposit carried items.

## Skilling SDK additions (2026-06-25)

Built to back the fuller Woodcutting script; all reusable for Mining/Fishing:

- **`script/Disposal.java`** — `enum {BANK, DROP, BOX}`. Replaces `GatherScript`'s
  old `bankWhenFull()` boolean.
- **`GatherScript`** — `disposalMode()` + `resourceBox()` hooks drive a cross-tick
  full-handling state machine: DROP (power-gather), BANK (existing), and **BOX** —
  fill a resource box to extend the trip, banking only when the box is full
  (deposit → empty box → deposit, sequenced so it completes even after the pack
  frees up). `refreshTargets()` re-resolves spots so `onConfigUpdate` edits take
  effect live; `requestStop()`/`isStopRequested()` for a UI stop button.
- **`inventory/ResourceBox.java`** — generic wood-box/ore-box wrapper
  (`presentTier`/`isPresent`/`looseCount`/`fill`/`emptyAtBank`), parameterised by
  tier item ids + storable ids. Box-full is detected behaviourally (a `fill()`
  that frees no slots), since live fill count isn't in the snapshot. Exported via
  module-info.
- **`atlas/Atlas`** — generic gameval resolvers `gamevalId(etype, name)`,
  `itemId(name)`, `varpId(name)`, and `struct(name)` → `atlas/Struct` (param map +
  `paramInt`/`paramText`). Locked by `AtlasClosureTest` (wood-box item ids,
  `WOODCUTTING_WOODBOX_LASTUSED_TIER` varp 10903, capacity struct param 2212).
- **Planner** (`plan/`) — `Goal` (Production/Training), `Planner` (wraps
  `Atlas.closure`), `Orchestrator` (interface only — see below).
- Wired into the host boot layer: `core` `requires com.botwithus.bot.skilling`
  (boot-layer trick, like quest-core) + `cli` run `--enable-native-access`.

## First script

`BotWithUsScripts/woodcutting` → `WoodcuttingScript extends GatherScript`. Builds
to a modular JAR installed into `JBotWithUsV2/scripts/`.

**v2.0 (2026-06-25) — fully featured + configured.** Configuration is the host's
persisted config surface (`getConfigFields()`/`onConfigUpdate()`, not sysprops):
**Tree** (species), **Location** (curated `ChopPreset` named groves, snapped to
real Atlas spots + grove-constrained; or *Auto-nearest*), **Disposal** (Bank /
Drop / **Wood box**), and **Stop at** (Never / Level / Logs / Time). Wood-box tier
ids resolved by gameval (`WOODCUTTING_WOODBOX_*`); capacity from the
`SKILLGUIDE_WOODCUTTING_WOOD_BOX_CAPACITY_*` structs (param 2212). A custom
`getUI()` status tab (`WoodcuttingUI`) shows live tree/disposal/level/XP-hr/~logs/
box/runtime + a Stop button and live disposal switch. Box "Fill"/"Empty" option
indices are a **live-verify** (item defs expose no options — CS2-driven); the
string path is tried first, with `-Dbotwithus.wc.box.fillOp`/`emptyOp` raw-index
overrides, and BOX gracefully degrades to BANK if neither resolves.

**Data edge case (fixed at the source):** oak/willow/maple/yew/magic are item-keyed
in the gather table from world-map icons, but **normal trees were not** — the
skilling table resolves no product item for level-1 trees. Fixed by
`analyzer/tree_clusters.py`: map-dumps every normal/oak tree placement (index-5
locspawn), density-clusters per plane, and bakes generalised grove waypoints into
`gather` (item 1511/1521, `kind='tree_cluster'` — 216 normal + 89 oak groves).
So `atlas.gatherSpots(1511)` now returns cluster waypoints. `WoodcuttingScript`
still keeps a `gatherSpotsByLocName("Tree")` fallback as a safety net. Locked by
`AtlasClosureTest#normalTreesAreClusterKeyed`.

## Deferred / TODO

- **`Orchestrator` impl** — the cross-skill dispatch (make 100 bronze bars →
  run Mining for copper+tin → hand back to Smithing). Lands with the first
  **Smithing** script; the `Planner`/closure it builds on is already here.
- **Metal bank** deposit/withdraw — needed by Smithing.
- ✅ **Bank deposit button resolved from gamevals** — `BANK__BANK_INV_BUTTON` =
  interface 517, component 39 (siblings: WORN 42, BOB 45, POUCH 48). `Banking`
  looks it up by name from the Atlas `component` nodes at runtime (so a future
  interface renumber is fixed by rebuilding the Atlas), falling back to (517, 39)
  with no Atlas; `-Dbotwithus.bank.depositInventoryComponent=<id>` still overrides.
  Locked by `AtlasClosureTest#resolvesBankDepositButtonFromGamevals`.
- **jlink/jpackage packaging** of the host now pulls in sqlite-jdbc (an
  *automatic* module) — `:cli:run`/`build`/`test` are fine, but a packaged image
  needs sqlite-jdbc converted to a proper module (gradlex `module{}`).

## Build / verify

```
# host SDK + closure test (against a real Atlas)
JBotWithUsV2>  gradlew :skilling-core:build -Dbotwithus.atlas=<...>\resolved.sqlite

# the woodcutting script (separate project, composite build)
BotWithUsScripts>  gradlew :woodcutting:build      # installs JAR to ../JBotWithUsV2/scripts
```
