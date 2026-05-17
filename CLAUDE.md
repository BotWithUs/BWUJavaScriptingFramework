1
0# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JBotWithUsV2 is a Java 21 modular game scripting framework. It communicates with a game server via Windows named pipes using MessagePack-encoded JSON-RPC. Scripts are dynamically discovered at runtime via Java's ServiceLoader SPI and execute on virtual threads.

Group: `com.botwithus` | Java 21 | Gradle 8.14 (Kotlin DSL) | JUnit 5

## Build Commands

```bash
./gradlew build                    # Build all modules (also installs example-script JAR to scripts/)
./gradlew clean build              # Clean and rebuild
./gradlew :cli:run                 # Run the CLI/GUI application
./gradlew :example-script:build    # Build and auto-install example script to scripts/
./gradlew test                     # Run tests
./gradlew test --tests "com.botwithus.SomeTest.methodName"  # Run a single test
```

## Module Architecture

Four Gradle subprojects with strict dependency layering:

```
api                 (slf4j-api)      — Public interfaces, models, query builders
  ↑ required by
core                (api + msgpack + logback) — RPC client, pipe transport, script runtime
  ↑ required by
cli                 (api + core)     — Interactive CLI/GUI, command system
example-script      (api only)       — Example BotScript implementations
```

### api (`com.botwithus.bot.api`)
Pure interface module (sole dependency: `slf4j-api`, exposed transitively). Contains:
- **`BotScript`** — SPI interface scripts implement (`onStart`/`onLoop`/`onStop`)
- **`GameAPI`** — 100+ methods for game interaction (entities, inventories, actions, UI, vars, cache)
- **`ScriptContext`** — Provides scripts access to GameAPI, EventBus, MessageBus
- **`ScriptManifest`** — Annotation for script metadata
- **`entities/`** — Fluent query builders: `Npcs`, `Players`, `SceneObjects`, `GroundItems`
- **`inventory/`** — `Backpack`, `Bank`, `Equipment` wrappers
- **`event/`** — `EventBus` and game events
- **`isc/`** — Inter-Script Communication via `MessageBus`
- **`query/`** — Filter interfaces for entity/component/inventory queries

### core (`com.botwithus.bot.core`)
Runtime and communication layer:
- **`pipe/PipeClient`** — Windows named pipe client (`\\.\pipe\BotWithUs`), length-prefixed messages
- **`rpc/RpcClient`** — Synchronous JSON-RPC over pipe with MessagePack serialization
- **`msgpack/MessagePackCodec`** — Serialization using `org.msgpack:msgpack-core:0.9.8`
- **`runtime/ScriptLoader`** — Discovers script JARs in `scripts/` dir, creates child `ModuleLayer` per script
- **`runtime/ScriptRuntime`** — Manages script lifecycle across multiple scripts
- **`runtime/ScriptRunner`** — Runs individual script on a virtual thread; sets MDC keys `script.name` and `connection.name`
- **`impl/`** — Concrete implementations of API interfaces (`GameAPIImpl`, `EventBusImpl`, etc.)

Logging: All modules use SLF4J (`org.slf4j.Logger`/`LoggerFactory`). Logback Classic is the runtime backend (configured in `cli/src/main/resources/logback.xml`). The `BotLogger` API in `api/log/` delegates to SLF4J. Script log output is auto-tagged via MDC.

### cli (`com.botwithus.bot.cli`)
Interactive application with command system:
- **Main class**: `com.botwithus.bot.cli.gui.ImGuiApp`
- **Commands**: `connect`, `scripts`, `screenshot`, `logs`, `reload`, `ping`, `help`, `clear`, `exit`
- **`gui/`** — ImGui-based GUI with ANSI color support
- **`command/`** — Command registry, parser, and implementations
- **`log/`** — `LogBuffer` ring buffer, `LogCapture` for stdout/stderr, `LogBufferAppender` (Logback → LogBuffer bridge)

### example-script (`com.botwithus.bot.scripts.example`)
Reference implementations: `ExampleScript`, `WoodcuttingFletcherScript`. Build auto-copies JAR to `scripts/`.

## Key Patterns

**Script SPI**: Scripts must be Java modules that `provides com.botwithus.bot.api.BotScript with <ClassName>` in their `module-info.java`. Scripts return delay (ms) from `onLoop()`, or `-1` to stop.

**Communication flow**: `BotScript → GameAPI → RpcClient → PipeClient → Game Server`

**Module path**: The CLI's run task places the API JAR on the module path (not classpath) so `ScriptLoader` can build child module layers that reference the API module.

**Script installation**: Script JARs go in the `scripts/` directory at project root. The `example-script` build task does this automatically via `installScript`.

**Logging**: Use `private static final Logger log = LoggerFactory.getLogger(ClassName.class);` (from `org.slf4j`). Never use `System.out/err.println` for logging — all output goes through SLF4J. Scripts get SLF4J transitively from the API module. MDC keys `script.name` and `connection.name` are set automatically by `ScriptRunner`.

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
