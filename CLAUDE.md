# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JBotWithUsV2 is a modular Java game scripting framework. It binds to a library-loaded C++ DLL through **two parallel transports**: a Windows named pipe (msgpack JSON-RPC, mutations + queries) and a shared-memory mapping (per-tick snapshot + event ring). Scripts are dynamically discovered via Java's `ServiceLoader` and execute on virtual threads.

Group: `com.botwithus` | Gradle 9.5 (Kotlin DSL) | Java 25 toolchain (auto-provisioned by Gradle) | JUnit 5

## This repository is public

`BotWithUs/BWUJavaScriptingFramework` is a **public** GitHub repo, and it is the
only public repo in the workspace — every sibling project (`NXTLibrary`,
`BotWithUs-Launcher`, `BotWithUs-Heartbeat`, the runtime forks) is private.
Anything committed here is world-readable and stays reachable in history even
after deletion. Before adding a file, check it is none of the following:

- **Security audits, threat models, or vulnerability writeups.** A finding with
  a `file:line` and a repro is an exploitation roadmap. This includes
  regression-test javadoc that explains *how* a fixed flaw worked.
- **Protocol specifications for anything but the game wire.** The pipe + SHM
  contract below is a deliberate public API and belongs here. The heartbeat
  auth framing, the SDN envelope/bundle layout, and the licensing path do not —
  document those on the private side and keep public javadoc to the *contract*
  (what a method promises), never the *format* (byte layouts, opcodes).
- **Absolute paths** (`E:\...`), which expose the workspace layout, and
  **internal tracker URLs** (`trello.com/c/...`).
- **Planning docs and scratch notes.** These belong in the private
  `JBotWithUsV2-internal/` tree, which `.gitignore` covers alongside
  `claudedocs/`.

Naming a private sibling project is fine — that's how the cross-repo lockstep
below is documented. Describing its internals is not.

## Producer-side coupling

This repo is the **consumer** half of a tightly-coupled pair. The producer-side DLL is the `NXTLibrary` project, checked out as a sibling of this one in the same workspace (C++, freestanding, loaded into the game via our LoadLibrary). When changing anything that crosses the process boundary, grep both repos and update matching call sites in the same logical change:

- **Pipe name**: producer publishes `\\.\pipe\BotWithUs_<pid>`. Java side: `core/.../pipe/PipeClient.java` (`NAME_PREFIX`, `firstAvailableOrThrow`), all `new PipeClient(...)` callers.
- **SHM mapping**: producer publishes `Local\nxt_snapshot_<pid>`. Java side: `core/.../shm/Layout.java` (`MAPPING_NAME_PREFIX`), `SharedRegion`.
- **Wire protocol version**: `Layout.PROTOCOL_VERSION` (currently `19`) must equal `kProtocolVersion` in `NXTLibrary/src/ipc/SharedLayout.h` (and the `static_assert(nxt::ipc::kProtocolVersion == 19, ...)` in `NXTDebugger/src/attach/Session.h`). `SharedRegion.open()` validates and refuses mismatched versions. `core/.../shm/LayoutWireOffsetsTest` pins the version, the dynamic-region block offsets and `SNAPSHOT_SIZE` as hardcoded literals so a cap edit on either side of the wire makes the two disagree loudly.
- **Dynamic regions** (v19): `GameSnapshot.dynamicRegion()` publishes the client's instance chunk-descriptor grid — the table that maps an instance tile back to the static tile it was copied from, so a static-baked navigation layer can path inside a player-owned house or a Dungeoneering floor. Byte offsets live in `Layout` (`DYNREGION_*`, `SNAP_DYN*`); the **bit** layout of a descriptor lives on `api/.../snapshot/DynamicRegion` because scripts decode it and `api` cannot depend on `core` — one home, not two copies. The snapshot-backed region is a flyweight over shared memory, so anything retaining it past the tick must copy it — prefer `DynamicRegion.copyOfStable(snapshot)`, which re-checks `publishSeq()` around the copy, over bare `copyOf(...)`: the grid is up to 64 KB read out of a live double-buffered mapping with no seqlock, and a half-stale grid resolves to plausible wrong tiles rather than failing. Two traps the javadoc is loud about and reviewers should stay loud about: origin/max are **mapsquares** while `gridW`/`gridH` are **chunks**, and `maxMapX`/`maxMapY` bound the loaded window rather than the resolvable grid.
- **The snapshot's three clocks** (v18): `GameSnapshot.serverTick()` is the 600ms server tick and the one scripts should pace against; `gameCycle()` is the client's ~20ms counter and the unit `Projectile.startCycle()`/`endCycle()` use; `publishSeq()` is the producer's republish counter — same cadence as `gameCycle()` but a different number space, so never compare them. `publishSeq()` was called `tickId()` through v17, and pacing off it ran ~30x fast.
- **Event-type discriminators**: `Layout.EVT_*` mirrors `kEvent*` enum in `NXTLibrary/src/ipc/Events.h`. Decoder switch arms in `EventRingReader` must cover every type the producer emits.
- **Wire body shapes**: each `api/.../event/*Event.java` constructor mirrors a POD struct in `NXTLibrary/src/ipc/Events.h`. Field order is load-bearing for the byte-offset decoders.
- **Snapshot field offsets**: `Layout.SNAP_*` / `LP_*` / `NPC_*` / `PLAYER_*` / `LOC_*` mirror `NXTLibrary/src/ipc/SharedLayout.h`. Static_asserts on the C++ side will catch divergent strides at compile time, but the Java side is untyped — keep the offset constants in lockstep.
- **RPC method names + param shapes**: each `rpc.callSync(<name>, ...)` in `GameAPIImpl` (and its `domain/*API` mixin partials) matches a handler in `NXTLibrary/src/rpc/Handlers.cpp`. New RPCs land in both files together. Note the component query surface: `get_component` + `get_interface_tree` decode through the shared `GameAPIImpl.decodeComponent`, both surfaced via the `api.components()` facade (`api/.../component/`). The component map carries a stable `category` code (producer's `WireCategory` → `ComponentType`); it is **additive over the RPC pipe**, so it does **not** bump `PROTOCOL_VERSION` (that gates only the SHM snapshot layout). A producer predating `category` decodes to `0` → `ComponentType.UNKNOWN`.
- **Per-item obj vars**: `get_obj_vars` (RPC, `GameAPIImpl.getObjVars` → `GameAPI` / `InventoryContainer.getSlotVars`/`getAllSlotVars`) returns `{slots:[{slot, vars:[{id,value}]}]}` for a container's items — the per-instance `ObjVarDomain` state (augmentation XP, charges). `EVT_OBJ_VAR_CHANGE` (13, `ObjVarChangeEvent`, body `{invId,slot,varId,oldValue,newValue}`) reports per-slot value changes. Producer: `Handle_GetObjVars` + `EmitObjVarChanges` in `NXTLibrary`. Both **additive** — no `PROTOCOL_VERSION` bump (new event type + new RPC; the SHM snapshot layout is unchanged).
- **Debug drawing** (`api/draw/`, `api.draw()`): the nine `debug_draw_*` / `highlight_component` handlers in `NXTLibrary/src/rpc/Handlers.cpp`, bound by `core/impl/DrawCodec.java` (encode/decode) and `GameAPIImpl`'s `DrawAPI` methods. **Additive over the RPC pipe — no `PROTOCOL_VERSION` bump**; the overlay store is not in shared memory. Caps are mirrored into `api/draw/DrawLimits.java` from `NXTLibrary/src/overlay/DrawTypes.h` (`kMaxDrawCmds`, `kMaxKeyChars`, `kMaxTextSlots`, `kPolySlotPoints`, `kMaxDrawCoord`, `kMaxDrawExtent`, `kMaxResolveTargets`) plus `kDrawBatchMax` in `Handlers.cpp` — change a cap on the producer and change it here in the same logical change. Four wire behaviours the API is deliberately loud about rather than smoothing over, because each one lets a naive wrapper lie to a scripter:
  - **`debug_draw_set_batch` reports per-item refusals inside a *successful* reply** (`{count, dropped, error}`, first error only). `DrawFrame.flush()` returns that as `DrawBatchResult` and `close()` logs a WARN when anything was dropped. It never throws **for a refused command** — a debug overlay must not be able to take a script's tick down — but a *transport* failure does propagate, because that is a dead pipe rather than a failed drawing, and swallowing it would hide it. See the bullet below for what that leaves behind. Note the batch *does* hard-error on an over-size `items` array and on a malformed item, which `NXTDebugger/wire/PROTOCOL.md` ("does not fail") does not say.
  - **The batch cap is 256.** `DrawFrame` splits at `DrawLimits.MAX_BATCH_ITEMS` rather than truncating or refusing, because the producer rejects an over-size batch whole and would lose the part that fit. `GameAPIImpl.drawSetBatch` refuses an over-cap list outright — the split belongs to the frame.
  - **An envelope error from `debug_draw_set_batch` does NOT mean nothing drew.** `ApplyBatchItem` calls `overlay::Set` per item *as it walks the array*, so the two error paths are not alike: `items > 256` returns before the loop (store clean), but a structurally malformed item at index N aborts the call with items `0..N-1` already stored and no count in the reply. Splitting sharpens it — a failure in sub-batch 2 means sub-batch 1 is entirely drawn. `DrawFrame.flush()` therefore **keeps** its pending commands on failure (keys stay in hand; re-flushing is safe because a key replaces) and logs a WARN naming how far it got; `draw.list()` reports what is retained and `draw.clearAll()` removes it without needing keys. **The malformed-item path is unreachable from this encoder** and `DrawCodecTest` proves it rather than defending against it: `ForEachParam` (`MsgPack.h`) fails only on a non-map item, a non-string key, or an unskippable value — an unknown key is *skipped* and every value-type mismatch is a soft `dropped` — and a sealed `DrawCommand` through `DrawCodec.encode` cannot produce any of the three. Do not add defensive handling here; extend that test instead.
  - **`debug_draw_list`'s `ttl_ms` is *remaining*, not what you sent.** Named `DrawEntry.remainingTtlMs()` so the asymmetry is in the signature. Its `text` field carries a **component's label** as well as a text command's words, and a fixed-point caption arrives already formatted — the producer does the formatting because the wire cannot carry a float back.
  - **`z` is the one limit the producer does not enforce, which is why the host does.** `Handlers.cpp:2651` is `req.z = static_cast<int16_t>(ClampToI32(p.z))` and there is no range check, so `z(100000)` is *silently truncated* to `-31072` and repaints in a different order — no error, no `dropped`, nothing in `debug_draw_list` to show for it. `DrawLimits.MIN_Z`/`MAX_Z` are validated in `DrawStyle`'s compact constructor: unlike every other check here it has no producer-side refusal to agree with, it is the only thing standing between a caller and a silently wrong result. If the producer later grows an explicit `z` error, this degrades into relocating that error to the call site rather than becoming redundant.
  - **Two different counters are called `dropped`.** `DrawStats.dropped()` is the store's process-lifetime counter and an over-long key, an out-of-range coordinate or a malformed command never reach it (`DrawStore.cpp` returns before `g_dropped`). `DrawBatchResult.dropped()` is per-call and *does* count all of those, because `ApplyBatchItem` tallies every non-OK status whatever the reason. Same name, different scope, different membership — say which one you mean.
  - **Captions are three spellings of one payload, and the host makes two of them unrepresentable rather than validated.** `text` (text commands), `label` (component highlights) and `value`+`decimals` (fixed point, because the wire has no float) are mutually exclusive, and a caption or a `font` on a shape is refused outright — `only text and component commands take text, label or value` / `take a font`. So `font` lives on `DrawCaption`, **not** on `DrawStyle`, and a caption lives only on the two captioned variants: `draw.rect(...).font(...)` does not compile, and there is no second caption slot to fill. The producer spec warns callers who build from a shared style object to strip `font` for the shapes; this host has nothing to strip, and `DrawCodecTest.shapes_encodeNoCaptionAndNoFont` is the guard on that placement. `DrawFont` is a style **name** (`normal`/`small`/`large`/`heading`), never a size — the producer owns the size table.
  - **`highlight_component`'s auto key is `comp:<iface>:<comp>`.** `DrawCommand.ComponentTarget.autoKey` computes it host-side so `submit()` can answer the key before the round-trip; `LiveDebugDrawSmokeTest` asserts it against the producer rather than assuming it.

  **`DrawSpace.WORLD` works as of the phase-3 producer**, and the signatures shipped before it did without needing to change — which is what putting the field on the wire early bought. The live test that pinned the old rejection is what noticed: it was an `assertThrows`, so the feature landing made it fail. It has been repointed at `poly`, which world space still refuses (its points live in a side slot with no room for a projected copy), so the "an unsupported space fails with its *own* message" property keeps a subject; the positive case is asserted separately. **Two coordinate bounds now, not one** — `DrawLimits.MAX_SCREEN_COORDINATE` (2^20) gates what a screen command sends *and* what a world command projects to a tick later, while `MAX_WORLD_COORDINATE` (2^23) gates what a world command sends. The world bound is the whole `int16` tile space at `SUBTILE_SCALE`, so a tile read from a snapshot can never exceed it; one bound for both would have refused everything past tile 4096 for failing a limit about pixels. **Known gap**: `draw.npc/player/tile(...)` mark a *position*, not an entity, and send no `plane` — so a box stays put when the NPC walks and an upstairs NPC is marked on plane 0. The producer's semantic world highlights are the fix, and binding them changes what those helpers send rather than how they are called. `drawClearAll()` is pinned to `scope: "mine"` and the producer's `"all"` is deliberately unreachable from a script.
- **`script.context` broker publish (Phase 4 — `JBotWithUsV2` is now a *producer* on the broker)**: `core/impl/ScriptContextChannel.java` posts `_debug.publish({topic: "script.context", data: {script, connection?, t_us, kind, ...}})` over the existing `RpcClient`. `kind` is one of `"state"` / `"trace"` / `"annotation"`; the lifecycle state alphabet (`STARTING`/`RUNNING`/`STOPPED`/`CRASHED`, plus the stop-escalation states `STALLED`/`REVOKED`/`ABANDONED`) is enforced by `core/runtime/ScriptRunner.java`'s constants. Adding a state is additive — the debugger falls through to a default colour for anything it doesn't know — but give it a colour in `NXTDebugger/src/panels/ScriptContextPanel.cpp`'s `StateColor()` in the same change. Wire-mirrored by `NXTDebugger/src/panels/ScriptContextPanel.cpp` (decode) and `JBotWithUsV2/api/debug/ScriptContextPublisher.java` (producer interface). Additive over the RPC pipe; does **not** bump `PROTOCOL_VERSION`. JBotWithUsV2 doesn't subscribe to anything on the broker — only publishes — so the existing reader-loop drains `_debug.publish` replies without further demux.

## Build Commands

```bash
./gradlew build                    # Build all modules (installs example-script JARs to scripts/)
./gradlew clean build              # Clean and rebuild
./gradlew :cli:run                 # Run the CLI/GUI application
./gradlew :example-script:build    # Build and auto-install example scripts to scripts/
./gradlew test                     # Run tests
./gradlew test --tests "com.botwithus.SomeTest.methodName"  # Run a single test
```

### Publishing `bot-api`

`api` is the only published module. It goes to **`BotWithUs/maven`** — a static Maven tree served over GitHub Pages at `https://botwithus.github.io/maven` — not to GitHub Packages, which demands a token even to *read* a public artifact and would put a PAT setup step in front of every third-party script author.

Tagging `vX.Y.Z` fans out to three jobs: `publish-api.yml` writes the artifact to the Maven repo, `javadoc.yml` redeploys the docs so they match the tag, and `publish-api.yml` cuts a GitHub release carrying the jar, sources and javadoc.

Three things that will bite if changed carelessly:
- **The workflow checks out `BotWithUs/maven` *before* running Gradle and publishes into that working copy.** Gradle merges `maven-metadata.xml` against the versions already on disk; publish to a fresh staging dir and copy afterwards and the metadata collapses to a single version, making every prior release unresolvable by version range.
- **Version comes from `-PreleaseVersion`** (root `build.gradle.kts`), defaulting to `1.0-SNAPSHOT`. The default is never published; a static repo has no SNAPSHOT republish story.
- **`:api:javadoc` sets `modularity.inferModulePath = false`.** `module-info.java` is excluded from the javadoc source set, so with module path inference on, every `org.slf4j` import fails to resolve and javadoc emits *nothing*. That combination silently shipped an empty javadoc jar and an empty Pages site until `isFailOnError` was turned on. Leave it on.

### CI and branch protection

`master` is governed by two rulesets, both with an empty bypass list — they apply to org admins too.

- **`master protection`** — PRs only, one approving review (GitHub forbids self-approval, so this genuinely needs a second person), stale reviews dismissed on push, the `build` check green, and no force-push or deletion.
- **`release tags are immutable`** — `v*` tags cannot be deleted, moved, or force-updated, which is what makes "a published version is never replaced" true at the git level and not just by workflow convention.

`ci.yml` provides the required `build` check. **Its job must stay named `build`** — the required-check context is matched by job name, so renaming the job blocks every merge to `master` until the ruleset is updated to match.

**CI does not run `./gradlew build`.** It builds `:api :core :test-support :quest-core :skilling-core`, the modules that compile from a bare clone. `cli` needs `NXTCache.dll`, `worldwalker.dll`, an Atlas database and a jlink JDK home from `local.properties`; `example-script` and `sdn-test-script` reference quest constants that `quest-core` only generates when `quest.research.dir` points at the research data, and emits as a stub otherwise. Adding a module to CI means making it build without `local.properties` first.

### Machine-specific paths (`local.properties`)

Absolute paths that differ per developer must **not** be committed. They live in `local.properties` at the project root (git-ignored); copy `local.properties.example` to start. The `Project.localProperty(key, envVar?)` helper in `buildSrc` resolves each key in order: Gradle project property (`-Pkey=` / `gradle.properties`) → `local.properties` → environment variable. Supported keys: `nxtcache.dll`, `nxtcache.path`, `worldwalker.dll`, `worldwalker.artifact`, `jlink.javaHome`, `navDataDir`. Use forward slashes in `.properties` files — a backslash is an escape char.

## Module Architecture

Five Gradle subprojects with strict dependency layering:

```
api                 (slf4j-api)                — Public interfaces, models, snapshot view, query filters
  ↑ required by
core                (api + msgpack + logback   — Pipe + SHM transport, RPC client, script runtime,
                     + bouncycastle + panama)    Maven resolver, cache + pathfinder bridges
  ↑ required by                                ↑
cli                 (api + core + imgui)       test-support  (api)
                                               — Mocks for scripts: MockGameAPI, MockScriptContext,
                                                 CannedSnapshot, InMemoryEventBus
example-script      (api only)                 — Reference BotScripts; auto-installs to scripts/
```

### api (`com.botwithus.bot.api`)

Pure interface module (sole runtime dependency: `slf4j-api`, exposed transitively).

- **`BotScript`** — SPI scripts implement: `onStart` / `onLoop` / `onStop`, plus default `getConfigFields` / `onConfigUpdate` / `getUI` hooks.
- **`Client` / `ClientProvider`** — One `Client` per connected game process; gives the script a `GameAPI`, an `EventBus`, and a per-tick `GameSnapshot`. A `BotScript` sees only its own bound `Client` (through `ScriptContext.getGameAPI()` / `getEventBus()`); cross-client visibility is reserved for `ManagementScript` via `ManagementContext.getClientProvider()` and `ClientOrchestrator`.
- **`GameAPI`** — Slim RPC surface (post-rewrite, mid-2026). Composed of three domain mixins in `api/domain/`:
  - `SystemAPI` — pipe ping / introspection
  - `ActionAPI` — action queue, behavior modifiers
  - `NavigationAPI` — walker, pathfinder, nav-graph CRUD, teleport registry

  Plus mutations, login/break controls, client-script execution, interface tree walks, and slice-5 cache-type stubs. **Reads of live game state (local player, NPCs, players, locations, inventories, components) are NOT here** — they go through `GameSnapshot` (obtained from `Client.snapshot()` or `GameAPI.snapshot()`) or via the `EventBus`.
- **`snapshot/GameSnapshot`** — Tick-scoped read view backed by the SHM mapping. Exposes `self()`, `npcs()`, `players()`, `locations()`, `inventories()`, `serverTick()`, `gameCycle()`, `publishSeq()`, `gameState()`, `sceneVersion()`. Not safe to cache across ticks.
- **`entities/`** — Fluent query facades (`Npcs`, `Players`, `SceneObjects`, `GroundItems`, `WorldMapElements`) that wrap the snapshot tables with chainable filters.
- **`inventory/`** — `Backpack`, `Bank`, `Equipment` facades.
- **`gameval/`** — Contract for gameval name → id lookup (`GamevalIndex`, `GamevalType`, `GamevalEntry`). See *Gameval names* below.
- **`draw/`** — Debug drawing over the client (`api.draw()`). `Draw` is the facade, `DrawFrame` is the batching one a script should reach for, `DrawCommand` is a sealed hierarchy so the encoder dispatches exhaustively, and `DrawLimits` mirrors the producer's caps. See the *Debug drawing* coupling bullet above before changing any of it.
- **`event/`** — `EventBus` (game-event push from the producer) and event types.
- **`isc/`** — Inter-Script Communication: `MessageBus` (request/response) + `SharedState` (thread-safe KV).
- **`script/`** — `ManagementScript` SPI (cross-client orchestration), `ScriptManager`, `ScriptScheduler`, `TaskScript`, `ClientOrchestrator`.
- **`ui/ScriptUI`** — Hook for scripts to render their own ImGui tab.
- **`config/`** — `ConfigField` + `ScriptConfig` for runtime-editable, persisted script parameters.

### core (`com.botwithus.bot.core`)

Runtime, transports, RPC, script discovery, native bridges:

- **`pipe/PipeClient`** — Windows named-pipe client (`\\.\pipe\BotWithUs_<pid>`), length-prefixed framing. **Single-threaded access** (concurrent reads/writes deadlock the kernel handle).
- **`shm/`** — Reader for the shared-memory snapshot + event ring published by the DLL (`SharedRegion`, `SnapshotView`, `EventRingReader`, typed entry views).
- **`rpc/RpcClient`** — Synchronous msgpack JSON-RPC over the pipe. Configurable per-call timeouts, retry/backoff policy, per-method `RpcMetrics`.
- **`msgpack/MessagePackCodec`** — Serialization via `org.msgpack:msgpack-core:0.9.8`.
- **`impl/`** — Concrete implementations: `GameAPIImpl`, `EventBusImpl`, `ClientImpl`, `ClientProviderImpl`, `ScriptContextImpl`, `ScriptManagerImpl`, `ScriptSchedulerImpl`, `MessageBusImpl`, `SharedStateImpl`, `Walker`, plus `impl/snapshot/` for the snapshot view backed by `SharedRegion`.
- **`runtime/`** — Script lifecycle: `LocalScriptLoader` (filesystem JAR discovery), `SDNScriptLoader` (signed-network distribution), `ManagementScriptLoader`, `ScriptRunner` (per-script virtual thread; sets MDC `script.name` and `connection.name`), `ScriptRuntime`, `ScriptProfiler`, `ConnectionContext`.

  **A loaded script JAR can never be released, so nothing is loaded from `scripts/` directly.** `defineModulesWithOneLoader` makes the layer's internal `jdk.internal.loader.Loader` open a module reader — a `JarFile` — on the first class it loads, and that reader has no public close; closing the `URLClassLoader` passed alongside it does nothing, because that is only the layer's *parent* and never opened the JAR. The handle goes away solely when GC collects the layer, its loader, its classes **and every instance of them** — so one lingering reference (a quarantined runner, a pinned loader) keeps it open for the life of the process, and on Windows an open handle denies the delete that Gradle's `Copy` performs to install a rebuilt JAR. `ScriptJarStaging` therefore copies every JAR into `~/.botwithus/staged-scripts/<label>-<pid>-<generation>/` and the layer is defined over the copy, leaving `scripts/` writable at all times. Consequences to keep in mind when editing this package: paths reported to the user must be mapped back through `Staged.sourceOf` (a staged path means nothing to a scripter); staged generations are swept on a later run by dead pid, not deterministically; and `registerScript` must keep ignoring quarantined runners, or a zombie shadows the rebuilt script of the same name forever.
- **`util/NativeCache`** — Path-only resolver for the `~/.botwithus/native/` directory where the NXTCache and WorldWalker DLLs and the gameval index live. Populated by a separate loader (out-of-band from the host); dev work can bypass with `-Dnxtcache.dll=…` / `-Dworldwalker.dll=…` / `-Dbotwithus.gameval=…` overrides.
- **`gameval/`** — `SqliteGamevalIndex`, the read-only reader over `gameval.sqlite` behind the api's `GamevalIndex`. See *Gameval names* below.
- **`resolver/`** — Maven-coordinate script installer: discovers versions via `maven-metadata.xml`, fetches JAR + `.sha1`/`.sha256`/`.asc` sidecars, optional PGP verification, writes to `scripts/`, tracks installs in `~/.botwithus/installed-scripts.json`. Repository drivers are plugged in via `RepositoryDriver` ServiceLoader SPI.
- **`crypto/`** — `SdnLoader` reflects into a JVM-injected `jdk.internal.sdn.SdnClassLoader` for signed scripts (see *Java rules exceptions* below).
- **`cache/`** — Lazy NXT cache reader for cache-resident asset queries.

### cli (`com.botwithus.bot.cli`)

ImGui-based GUI + interactive command system (`JBotApplication`, `gui/ImGuiApp`). The full command list, repository management, auto-start, blueprint editor, streaming, and script UI features are documented in the [README](README.md).

The CLI run task places the API JAR on the **module path** (not classpath) so `LocalScriptLoader` can build child `ModuleLayer`s that reference the API module.

### example-script (`com.botwithus.bot.scripts.example`)

Reference scripts: `ExampleScript` (Script UI demo), `WoodcuttingFletcherScript`, `LocationProbeScript`, `WalkToFlagScript`, `DivinationScript`. Build auto-copies the JAR into `scripts/`.

### test-support (`com.botwithus.bot.test`, artifact `bot-test-support`)

Published mocks for downstream script projects: `MockGameAPI`, `MockScriptContext`, `CannedSnapshot`, `InMemoryEventBus`.

## Gameval names

A **gameval** is the game's own stable symbolic name for an entity — `YEW_LOGS`,
`BANK__BANK_INV_BUTTON`, `ZAROS_SPELLBOOK`. Unlike a display name it is unique
within its namespace, is not localised, and exists for things that have no
display name at all (interface components, varbits, params). Unlike a raw id it
survives a game update.

`GameAPI.gamevals()` returns a `GamevalIndex` — never `null`, so a script that
names something can always ask. The script-facing surface:

```java
api.npcs().allByGameval("HANS");
api.objects().query().withGameval("MCANNONCAVE").withinDistance(10).nearest();
api.groundItems().nearestByGameval("COINS");
api.components().get("BANK__BANK_INV_BUTTON").interact(1);            // detached, 1 RPC
api.components().in("BANK").withGameval("BANK__BANK_INV_BUTTON")      // tree-attached,
        .visible().first();                                           //   composes with filters
api.components().isOpen("BANK");
api.backpack().containsGameval("YEW_LOGS");
api.getVarbit("ZAROS_SPELLBOOK");
api.gamevals().id(GamevalType.ENUM, "CRAFTING_PLATINUM_TABLE");
```

Three things to keep straight when extending it:

- **Gameval methods never overload a display-name method.** `contains(String)` /
  `named(String)` / `nearest(String)` all mean *localised display name, matched
  by case-insensitive substring*; the gameval siblings are separately named
  (`containsGameval`, `withGameval`, `nearestByGameval`) because same-signature
  opposite-semantics overloads would be a silent trap. `Components` is the one
  exception, and only because its int-taking methods differ in arity or type.
- **An unresolved name matches nothing, loudly.** `EntityQuery.withGamevalOf` and
  `ComponentQuery.withGameval` resolve once at query-build time; a stale name
  drops to `filter(… -> false)`. It must never quietly widen a query to every
  entity in the scene. Passing *no* names warns too — an empty varargs array is
  a filter that silently matches nothing otherwise.
- **The `WARN` for an unknown name is emitted once per name, by the index.**
  `SqliteGamevalIndex` logs when it first memoises a miss; the query classes log
  at `DEBUG`. Warning from the query would fire every tick for a script that
  polls a typo in `onLoop`. Keep new lookup sites on that side of the line.
- **Everything degrades; nothing throws for a missing name.** Including
  `getVarp`/`getVarbit`/`getVarcInt(String)`, which return
  `GameAPI.UNRESOLVED_VARIABLE` (`-1`, the same sentinel the `int` overloads
  already use for an unset variable). "No index deployed" is the *default* state
  today, so a throw there would kill scripts on an otherwise healthy host. Use
  `gamevals().require(...)` explicitly to opt into fail-fast.
- **A closed index degrades too.** `close()` sets a flag; already-memoised
  answers keep working and cold lookups return empty. Shutdown can race a script
  the non-force `disconnectAll()` deliberately left running, and that script's
  next lookup must not throw out of its loop.
- **`ComponentQuery.withGameval` matches the whole `(interfaceId, componentId)`
  pair**, not just the component half — a materialized tree is cross-mount aware
  (`ComponentTreeNode`'s javadoc), so a mounted sub-interface can contribute a
  node with the same component id. `withId(int)` is component-id only and *does*
  see both; that asymmetry is deliberate and tested.
- **Backing data is out-of-band and optional.** `core.gameval.SqliteGamevalIndex`
  reads `~/.botwithus/native/gameval.sqlite` (override `-Dbotwithus.gameval`),
  a ~70 MB read-only index of ~814k names baked offline from the game's index-67
  tables. Absent file → `GamevalIndex.empty()`, every lookup empty,
  `isAvailable()` false. Lookups are lazy and memoised (hits *and* misses);
  eagerly loading the whole table would retain ~200 MB. One process-wide index
  is opened at the composition root (`CliContext.getOrInitGamevals()`,
  `JBotApplication.openGamevalIndex()`) and shared by every connection — the
  same treatment `NXTCache` gets.

`meta.schema_version` gates the file: the reader refuses anything but `1`, so a
format change fails loud rather than misreading rows. `GamevalTypeTest` pins the
41 `etype` strings, and `GamevalIndexLiveTest` checks a deployed index for drift
(it skips when none is present).

This is separate from `skilling-core`'s `Atlas` (`resolved.sqlite`), which still
owns recipes, gather spots and closures and has its own gameval methods against
that heavier file.

## Key Patterns

**Two transports, one binding**: the producer DLL exposes both a pipe and a SHM mapping under the same `<pid>` suffix. `Client` owns both: `getGameAPI()` returns the RPC-shaped surface, `snapshot()` returns the SHM-shaped surface. If you're adding a read that fires every tick, it belongs in SHM, not on the pipe.

**Script SPI**: scripts must be Java modules that declare `provides com.botwithus.bot.api.BotScript with <ClassName>` in their `module-info.java`. Each script JAR is loaded in its own child `ModuleLayer` so module-info `requires` are isolated per script. `onLoop()` returns the next delay in ms, or `-1` to stop.

**Script installation**: drop JARs in `scripts/` at the project root, **or** install by Maven coordinate via the `scripts install` CLI command (see the README's "Script Repositories" section for the resolver pipeline).

**Scripts directory discovery**: `LocalScriptLoader.resolveScriptsDir()` checks `-Dbotwithus.scripts.dir` first, then `scripts/` in the working directory, then falls back to `~/.botwithus/scripts`. If a script JAR isn't being picked up, the loader writes the resolved path to the log — check there before guessing. It deliberately does **not** search parent directories: every JAR found is loaded as fully-trusted code with no signature check, so a parent-walk let anyone able to create a directory in an ancestor of the CWD replace the entire script set. Running from a subdirectory means passing `-Dbotwithus.scripts.dir` explicitly.

**Logging**: use `private static final Logger log = LoggerFactory.getLogger(ClassName.class);` (from `org.slf4j`). Never use `System.out/err.println` for logging. Scripts get SLF4J transitively from the API module. The MDC keys `script.name` and `connection.name` are set automatically by `ScriptRunner` / `RpcClient`.

## Java rules exceptions

The user's `java-rules` skill defines a banned set; the project follows it everywhere **except** the deliberate carve-outs documented here. Every violation site carries a `// rule-exception:` comment pointing back at this section so future audits see the explicit waiver.

### `core/.../crypto/SdnLoader.java` and `core/.../runtime/SDNScriptLoader.java`

These two files bridge into `jdk.internal.sdn.SdnClassLoader` — a class injected into a custom-built JDK that ships with the loader. The class is **not on the module path**, **not on the classpath**, and **not in any artifact the build sees** — it only exists inside the running JVM. None of the prescribed `java-rules` fixes (ServiceLoader, sealed `switch`, constructor injection) can reach a target that the build doesn't know about, so this is the one boundary where the framework must reflect.

Intentionally violated rules:

- **§Banned 1 (Reflection)** — `Class.forName("jdk.internal.sdn.SdnClassLoader")`, `MethodHandles.privateLookupIn(...)`, `getDeclaredConstructor(...).setAccessible(true)`, `MethodHandle.invoke(...)`. The class is identified by name only; there is no compile-time symbol to import. The reflection is wrapped in three named methods (`getSdnClass`, `getPubkey0Handle`, `getLockdown0Handle`) so the bridge is small and inspectable.
- **§Banned 5 (Mutable static)** — the `pubkey0Handle`, `lockdown0Handle`, and `sdnClass` static fields in `SdnLoader`, and the `lockdownCalled` flag in `SDNScriptLoader`, hold cached lookups into the same JVM-injected class. The cached lookups are process-global because the JVM-injected class is process-global; instance-scoping them would buy nothing but per-call reflection cost. `lockdownCalled` is one-way (false → true) and gates a one-shot native call that must not run twice.

Everywhere else in the project, both rules are enforced as written.

### `core/.../runtime/ConnectionContext.java`

This class wraps an `InheritableThreadLocal<String>` carrying the *connection name* tag for the current thread (and any virtual threads it spawns). The CLI's stdout/stderr interception (`cli/.../log/LogCapture.java`) reads it from arbitrary threads that the CLI does not own — code that prints with `System.out.println` from inside a script's virtual thread must still be tagged with the originating connection so the log buffer can filter by it. This is the same shape as SLF4J's `MDC`, which is also a static-thread-local request-context API and which this project uses freely.

Intentionally violated rule:

- **§Banned 5 (Mutable static behind a getter)** — `ConnectionContext` exposes static `set/get/clear` over a process-global `InheritableThreadLocal`. Removing the static read API would require modifying the CLI to consume the context through an injected supplier; the producing side (`ScriptRunner`, `ManagementScriptRunner`, `RpcClient`) takes the tagger / cleaner as constructor-injected `Consumer<String>` / `Runnable` so the runners themselves no longer reach for the global state directly. The static class remains as the explicit, named cross-cutting context seam.

The pattern is *request context*, not *singleton service*: the value is per-thread, not shared, and the static API is the standard way to expose thread-local context to code (such as a custom `PrintStream`) that cannot accept an injected handle. Future-Java alternative: `ScopedValue` (preview in Java 21, stable in 25). When `ScopedValue` becomes baseline, revisit.

### Script runner threads are **platform** threads, not virtual

`ScriptRunner.start()` and `ManagementScriptRunner.start()` use `Thread.ofPlatform()`, and `RpcClient.start()` does the same for `rpc-reader`. Each site carries a `// rule-exception:` comment pointing here.

Intentionally violated rule:

- **§Modern Java (virtual threads)** — `java-rules` lists virtual threads as the default for concurrent work. Script runners are the documented exception.

**Why.** Virtual threads are never preempted. A virtual thread that performs no blocking operation never unmounts from its carrier, and the default scheduler's parallelism is `availableProcessors()`. Script code is untrusted third-party code that may spin in `onLoop()`, so `availableProcessors()` runaway scripts can occupy every carrier — at which point *every* virtual thread in the JVM stops being scheduled, including `rpc-reader`. That wedges RPC for every connected client, not just the offending one. (`jdk.virtualThreadScheduler.maxPoolSize` does not help: FJP compensation applies to threads blocked in a `ManagedBlocker`, not to CPU spin.)

Script runners are also the wrong *shape* for virtual threads: virtual threads pay off for many short-lived, mostly-blocked tasks, whereas there are few script runners, they live for the whole session, and they are CPU-active every loop. On a platform thread the OS preempts a runaway script, so it costs CPU share and nothing else — and `Thread.interrupt()` / `InheritableThreadLocal` semantics are unchanged.

Threads created *by* a script's thread (notably `ww-executor-<nanos>` in `GameAPIImpl.walkWorldPathAsync`) inherit its `InheritableThreadLocal`s, which is what lets the runtime attribute their RPC calls back to the owning script. Don't convert these back to virtual threads without re-checking that attribution.

Everything else in the host — `ScriptContextChannel`'s worker, `ReconnectController`, `MessageBusImpl` dispatch, the CLI's scan loops — stays virtual. Only the untrusted-code threads and the one RPC thread they could starve are platform.
