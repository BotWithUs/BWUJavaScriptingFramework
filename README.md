# JBotWithUsV2

A modular Java 21 game scripting framework that communicates with a game server via Windows named pipes using MessagePack-encoded JSON-RPC. Scripts are dynamically discovered at runtime via Java's ServiceLoader SPI and execute on virtual threads.

## Requirements

- Java 21+
- Windows (named pipe transport)
- Gradle 8.14+ (included via wrapper)

## Quick Start

```bash
# Build all modules
./gradlew build

# Run the CLI/GUI application
./gradlew :cli:run
```

The GUI provides a command-based interface for connecting to the game server, managing scripts, and viewing logs.

## Module Architecture

Four Gradle subprojects with strict dependency layering:

```
api                 (no deps)        — Public interfaces, models, query builders
  ↑ required by
core                (api + msgpack)  — RPC client, pipe transport, script runtime
  ↑ required by
cli                 (api + core)     — Interactive CLI/GUI, command system
example-script      (api only)       — Example BotScript implementations
```

### api

Pure interface module with zero dependencies. Contains `BotScript` (the SPI), `GameAPI` (100+ methods for game interaction), fluent entity query builders (`Npcs`, `Players`, `SceneObjects`, `GroundItems`), inventory wrappers (`Backpack`, `Bank`, `Equipment`), an event bus, and inter-script communication via `MessageBus`.

Key packages:
- **`constants`** — Game constant registries (`InterfaceIds`, `InventoryIds`, `AnimationIds`)
- **`log`** — Structured logging API (`BotLogger`, `LoggerFactory`, `LogLevel`)
- **`script`** — Task-based scripting framework (`Task`, `TaskScript`)
- **`isc`** — Inter-script communication (`MessageBus` with request/response, `SharedState`)
- **`util`** — Timing helpers (`Timing.gaussianRandom`, `Conditions.waitForAnimation`, `Humanize` for human-like delays)

### core

Runtime and communication layer. Handles Windows named pipe I/O (`PipeClient`), synchronous JSON-RPC with MessagePack serialization (`RpcClient`), script discovery from JAR files (`ScriptLoader`), and script lifecycle management on virtual threads (`ScriptRuntime`, `ScriptRunner`).

Key features:
- **RPC timeouts** — Configurable per-call timeouts with `RpcTimeoutException`
- **Retry & reconnect** — `RetryPolicy` with exponential backoff, `ReconnectablePipeClient` for auto-reconnect
- **Metrics** — `RpcMetrics` tracks call count, latency, and error rate per method
- **Profiling** — `ScriptProfiler` tracks loop timing (avg/min/max/last)
- **Error isolation** — Per-phase error handling in `ScriptRunner` (onStart/onLoop/onStop)
- **Structured logging** — `PrintStreamLogger` implementation of `BotLogger`

### cli

Interactive ImGui-based GUI with ANSI color support and a command system. Commands include `connect`, `disconnect`, `scripts`, `screenshot`, `logs`, `mount`/`unmount`, `reload`, `ping`, `help`, `clear`, and `exit`. Supports multiple simultaneous pipe connections.

Additional commands:
- **`metrics`** — View RPC call statistics (latency, error rates), reset, filter top N
- **`profile`** — View per-script loop timing data, reset
- **`config`** — Persistent CLI configuration (`~/.botwithus/config.properties`), show/set/save
- **`actions`** — Inspect the game action queue, history, and blocked state
- **`events`** — Monitor event bus subscriptions and publish counts
- **`reload --watch`** — Auto-reload scripts when JAR files change in `scripts/`
- **`autostart`** — Manage per-account script auto-start profiles (see [Auto-Start System](#auto-start-system))

### example-script

Reference implementations (`ExampleScript`, `WoodcuttingFletcherScript`). `ExampleScript` demonstrates the custom Script UI system with status display, controls, and an entity summary table. Building this module automatically installs the JAR to the `scripts/` directory.

## Writing a Script

Scripts implement the `BotScript` SPI and are packaged as Java modules.

```java
@ScriptManifest(
    name = "My Script",
    version = "1.0",
    author = "You",
    description = "Does something useful"
)
public class MyScript implements BotScript {

    private ScriptContext ctx;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        // Initialize state, subscribe to events
    }

    @Override
    public int onLoop() {
        GameAPI api = ctx.getGameAPI();
        // Query entities, interact with the game
        Npcs npcs = new Npcs(api);
        // ...
        return 1000; // delay in ms before next loop, or -1 to stop
    }

    @Override
    public void onStop() {
        // Clean up resources
    }
}
```

Your `module-info.java` must declare the service provider:

```java
module my.script {
    requires com.botwithus.bot.api;
    provides com.botwithus.bot.api.BotScript with my.script.MyScript;
}
```

Place the compiled JAR in the `scripts/` directory. The runtime discovers and loads it automatically.

## Script UI

Scripts can provide custom ImGui-based UI that renders in the **Script UI** tab. Override `getUI()` to return a `ScriptUI` implementation:

```java
import imgui.ImGui;
import com.botwithus.bot.api.ui.ScriptUI;

@Override
public ScriptUI getUI() {
    return () -> {
        ImGui.text("Status: running");
        if (ImGui.button("Do something")) {
            // handle click
        }
        ImGui.progressBar(progress, -1, 0, "Progress");
    };
}
```

The full ImGui API is available — collapsing headers, tables, trees, inputs, tabs, etc. Add `requires imgui.binding;` to your `module-info.java` and `compileOnly("io.github.spair:imgui-java-binding:1.90.0")` to your build dependencies. The imgui module is already loaded at runtime by the host application.

```java
module my.script {
    requires com.botwithus.bot.api;
    requires imgui.binding;
    provides com.botwithus.bot.api.BotScript with my.script.MyScript;
}
```

The `render()` method is called every frame on the UI thread. Each script with a UI gets its own tab in the Script UI panel.

## Build Commands

```bash
./gradlew build                    # Build all modules (installs example-script to scripts/)
./gradlew clean build              # Clean and rebuild
./gradlew :cli:run                 # Run the CLI/GUI application
./gradlew :example-script:build    # Build and install example script only
./gradlew test                     # Run all tests
```

## Auto-Start System

The auto-start system remembers which scripts were running on each account and can automatically restart them on reconnect. Profiles are stored as `.properties` files in `~/.botwithus/profiles/`.

### How It Works

1. When you connect to a pipe, the app probes for account info (display name)
2. If a profile exists for that account, the configured scripts are auto-started
3. When scripts are started or stopped, the profile is automatically updated
4. On app shutdown, all running script states are saved

### File Layout

```
~/.botwithus/
├── autostart.properties              # Global settings (autoConnect, pipePrefix, etc.)
├── config.properties                 # CLI config (existing)
└── profiles/
    ├── PlayerOne.properties          # Per-account: scripts=Script1,Script2  autoStart=true
    ├── PlayerTwo.properties
    └── groups/
        └── farm1.properties          # Per-group: scripts=WoodcuttingScript  autoStart=true
```

### Commands

```bash
autostart list                        # Show all account/group profiles
autostart add <script>                # Add script to current account's auto-start
autostart remove <script>             # Remove script from auto-start
autostart enable / disable            # Toggle auto-start for current account
autostart save                        # Save current running scripts as profile
autostart clear [account]             # Clear a profile
autostart group <name> add <script>   # Add script to group auto-start
autostart group <name> remove <script># Remove script from group auto-start
autostart group <name> list           # List scripts in a group
autostart settings                    # Show global settings
autostart on / off                    # Enable/disable background pipe scanning
```

### Background Auto-Connect

When enabled (`autostart on`), the app scans for new pipes in the background and automatically connects, identifies accounts, and starts their configured scripts.

## Communication Flow

```
BotScript → GameAPI → RpcClient → PipeClient → Game Server (named pipe)
```

The pipe transport uses length-prefixed MessagePack frames over `\\.\pipe\BotWithUs`. The RPC client provides synchronous request/response semantics with async event dispatch.

## Testing

```bash
./gradlew test                     # Run all tests
./gradlew :core:test               # Run core module tests only
```

Tests cover MessagePack codec, RPC metrics, event bus, message bus, script runner/runtime, script profiler, script profile persistence, auto-start command, connection groups, and end-to-end transport with a mock game server.