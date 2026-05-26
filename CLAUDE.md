# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JBotWithUsV2 is a modular Java game scripting framework. It binds to an injected C++ DLL through **two parallel transports**: a Windows named pipe (msgpack JSON-RPC, mutations + queries) and a shared-memory mapping (per-tick snapshot + event ring). Scripts are dynamically discovered via Java's `ServiceLoader` and execute on virtual threads.

Group: `com.botwithus` | Gradle 9.5 (Kotlin DSL) | Java 25 toolchain (auto-provisioned by Gradle) | JUnit 5

## Producer-side coupling

This repo is the **consumer** half of a tightly-coupled pair. The producer-side DLL lives at `E:\BotWithUsv2.5\NXTLibrary` (C++, freestanding, injected into the game). When changing anything that crosses the process boundary, grep both repos and update matching call sites in the same logical change:

- **Pipe name**: producer publishes `\\.\pipe\BotWithUs_<pid>`. Java side: `core/.../pipe/PipeClient.java` (`NAME_PREFIX`, `firstAvailableOrThrow`), all `new PipeClient(...)` callers.
- **SHM mapping**: producer publishes `Local\nxt_snapshot_<pid>`. Java side: `core/.../shm/Layout.java` (`MAPPING_NAME_PREFIX`), `SharedRegion`.
- **Wire protocol version**: `Layout.PROTOCOL_VERSION` (currently `13`) must equal `kProtocolVersion` in `NXTLibrary/src/ipc/SharedLayout.h`. `SharedRegion.open()` validates and refuses mismatched versions.
- **Event-type discriminators**: `Layout.EVT_*` mirrors `kEvent*` enum in `NXTLibrary/src/ipc/Events.h`. Decoder switch arms in `EventRingReader` must cover every type the producer emits.
- **Wire body shapes**: each `api/.../event/*Event.java` constructor mirrors a POD struct in `NXTLibrary/src/ipc/Events.h`. Field order is load-bearing for the byte-offset decoders.
- **Snapshot field offsets**: `Layout.SNAP_*` / `LP_*` / `NPC_*` / `PLAYER_*` / `LOC_*` mirror `NXTLibrary/src/ipc/SharedLayout.h`. Static_asserts on the C++ side will catch divergent strides at compile time, but the Java side is untyped — keep the offset constants in lockstep.
- **RPC method names + param shapes**: each `rpc.callSync(<name>, ...)` in `GameAPIImpl` (and its `domain/*API` mixin partials) matches a handler in `NXTLibrary/src/rpc/Handlers.cpp`. New RPCs land in both files together.

## Build Commands

```bash
./gradlew build                    # Build all modules (installs example-script JARs to scripts/)
./gradlew clean build              # Clean and rebuild
./gradlew :cli:run                 # Run the CLI/GUI application
./gradlew :example-script:build    # Build and auto-install example scripts to scripts/
./gradlew test                     # Run tests
./gradlew test --tests "com.botwithus.SomeTest.methodName"  # Run a single test
```

### Machine-specific paths (`local.properties`)

Absolute paths that differ per developer must **not** be committed. They live in `local.properties` at the project root (git-ignored); copy `local.properties.example` to start. The `Project.localProperty(key, envVar?)` helper in `buildSrc` resolves each key in order: Gradle project property (`-Pkey=` / `gradle.properties`) → `local.properties` → environment variable. Supported keys: `bwu.loaderDll` (source for the bundled `/native/bwu.dll`), `nxtcache.dll`, `nxtcache.path`, `jlink.javaHome`, `navDataDir`. Use forward slashes in `.properties` files — a backslash is an escape char.

## Module Architecture

Five Gradle subprojects with strict dependency layering:

```
api                 (slf4j-api)                — Public interfaces, models, snapshot view, query filters
  ↑ required by
core                (api + msgpack + logback   — Pipe + SHM transport, RPC client, script runtime,
                     + bouncycastle + panama)    Maven resolver, loader DLL bridge
  ↑ required by                                ↑
cli                 (api + core + imgui)       test-support  (api)
                                               — Mocks for scripts: MockGameAPI, MockScriptContext,
                                                 CannedSnapshot, InMemoryEventBus
example-script      (api only)                 — Reference BotScripts; auto-installs to scripts/
```

### api (`com.botwithus.bot.api`)

Pure interface module (sole runtime dependency: `slf4j-api`, exposed transitively).

- **`BotScript`** — SPI scripts implement: `onStart` / `onLoop` / `onStop`, plus default `getConfigFields` / `onConfigUpdate` / `getUI` hooks.
- **`Client` / `ClientProvider`** — One `Client` per connected game process; gives the script a `GameAPI`, an `EventBus`, and a per-tick `GameSnapshot`. Scripts can see all connected clients via `ScriptContext.getClientProvider()`.
- **`GameAPI`** — Slim RPC surface (post-rewrite, mid-2026). Composed of three domain mixins in `api/domain/`:
  - `SystemAPI` — pipe ping / introspection
  - `ActionAPI` — action queue, behavior modifiers
  - `NavigationAPI` — walker, pathfinder, nav-graph CRUD, teleport registry

  Plus mutations, login/break controls, client-script execution, interface tree walks, and slice-5 cache-type stubs. **Reads of live game state (local player, NPCs, players, locations, inventories, components) are NOT here** — they go through `GameSnapshot` (obtained from `Client.snapshot()` or `GameAPI.snapshot()`) or via the `EventBus`.
- **`snapshot/GameSnapshot`** — Tick-scoped read view backed by the SHM mapping. Exposes `self()`, `npcs()`, `players()`, `locations()`, `inventories()`, `tickId()`, `gameState()`, `sceneVersion()`. Not safe to cache across ticks.
- **`entities/`** — Fluent query facades (`Npcs`, `Players`, `SceneObjects`, `GroundItems`, `WorldMapElements`) that wrap the snapshot tables with chainable filters.
- **`inventory/`** — `Backpack`, `Bank`, `Equipment` facades.
- **`event/`** — `EventBus` (game-event push from the producer) and event types.
- **`isc/`** — Inter-Script Communication: `MessageBus` (request/response) + `SharedState` (thread-safe KV).
- **`script/`** — `ManagementScript` SPI (cross-client orchestration), `ScriptManager`, `ScriptScheduler`, `TaskScript`, `ClientOrchestrator`.
- **`launcher/GameLauncher`** — Abstraction for the loader DLL (login, accounts, launch+inject); concrete impl in `core/loader/`.
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
- **`loader/`** — Panama FFI bridge to `bwu.dll` (the BotWithUs-Loader: auth, account management, agent download, DLL injection). `BwuClient` (high-level wrapper), `BwuNative` (downcall handles), `BwuLayouts` (struct layouts), `NativeCache` (resolves the `~/.botwithus/native/` path; cache is populated externally by the loader DLL).
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

## Key Patterns

**Two transports, one binding**: the producer DLL exposes both a pipe and a SHM mapping under the same `<pid>` suffix. `Client` owns both: `getGameAPI()` returns the RPC-shaped surface, `snapshot()` returns the SHM-shaped surface. If you're adding a read that fires every tick, it belongs in SHM, not on the pipe.

**Script SPI**: scripts must be Java modules that declare `provides com.botwithus.bot.api.BotScript with <ClassName>` in their `module-info.java`. Each script JAR is loaded in its own child `ModuleLayer` so module-info `requires` are isolated per script. `onLoop()` returns the next delay in ms, or `-1` to stop.

**Script installation**: drop JARs in `scripts/` at the project root, **or** install by Maven coordinate via the `scripts install` CLI command (see the README's "Script Repositories" section for the resolver pipeline).

**Scripts directory discovery**: `LocalScriptLoader.resolveScriptsDir()` checks `-Dbotwithus.scripts.dir` first, then walks up to three parents looking for an existing `scripts/`, then falls back to creating `./scripts` in the CWD. If a script JAR isn't being picked up, the loader writes the resolved path to the log — check there before guessing.

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
