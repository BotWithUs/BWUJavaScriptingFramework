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
