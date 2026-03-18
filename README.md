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

# Run the GUI application
./gradlew :cli:run
```

The GUI provides a tabbed interface for connecting to the game server, managing scripts, viewing logs, and rendering custom script UIs.

## Module Architecture

Four Gradle subprojects with strict dependency layering:

```
api                 (slf4j-api)      — Public interfaces, models, query builders
  ↑ required by
core                (api + msgpack + logback) — RPC client, pipe transport, script runtime
  ↑ required by
cli                 (api + core)     — Interactive GUI, command system
example-script      (api only)       — Example BotScript implementations
```

### api

Pure interface module whose only dependency is `slf4j-api` (exposed transitively so scripts get SLF4J for free). Contains `BotScript` (the SPI), `GameAPI` (100+ methods for game interaction), fluent entity query builders (`Npcs`, `Players`, `SceneObjects`, `GroundItems`), inventory wrappers (`Backpack`, `Bank`, `Equipment`), an event bus, and inter-script communication via `MessageBus`.

Key packages:
- **`blueprint`** — Visual graph workflow model (`BlueprintGraph`, `NodeInstance`, `Link`, `PinDefinition`)
- **`config`** — Script configuration fields (`ConfigField`, `ScriptConfig`)
- **`constants`** — Game constant registries (`InterfaceIds`, `InventoryIds`, `AnimationIds`)
- **`entities`** — Fluent query builders and `EntityContext` wrapper with lazy-cached info, distance calculations, health/animation/combat state
- **`isc`** — Inter-script communication (`MessageBus` with request/response, `SharedState` thread-safe key-value store)
- **`log`** — Structured logging API (`BotLogger` → SLF4J delegation, `LoggerFactory`, `LogLevel`)
- **`model`** — Domain models including `Personality` (humanizer profile with live session stats)
- **`script`** — `ManagementScript` SPI, `ScriptScheduler`, `TaskScript`, `ClientOrchestrator`
- **`ui`** — `ScriptUI` interface for custom ImGui-based script UIs
- **`util`** — Timing helpers (`Timing.gaussianRandom`, `Conditions.waitForAnimation`, `Humanize`)

### core

Runtime and communication layer. Handles Windows named pipe I/O (`PipeClient`), synchronous JSON-RPC with MessagePack serialization (`RpcClient`), script discovery from JAR files (`ScriptLoader`), and script lifecycle management on virtual threads (`ScriptRuntime`, `ScriptRunner`).

Key features:
- **RPC timeouts** — Configurable per-call timeouts with `RpcTimeoutException`
- **Retry & reconnect** — `RetryPolicy` with exponential backoff, `ReconnectablePipeClient` for auto-reconnect
- **Metrics** — `RpcMetrics` tracks call count, latency, and error rate per method
- **Profiling** — `ScriptProfiler` tracks loop timing (avg/min/max/last)
- **Error isolation** — Per-phase error handling in `ScriptRunner` (onStart/onLoop/onStop)
- **Structured logging** — SLF4J + Logback with MDC-based context tagging (`script.name`, `connection.name`)
- **GUI log bridge** — Custom `LogBufferAppender` feeds Logback events into the in-memory `LogBuffer` for the GUI log panel

### cli

ImGui-based GUI with ANSI color support and a command system. Supports multiple simultaneous pipe connections, custom script UI rendering, and management script orchestration.

Commands:

| Command | Aliases | Description |
|---------|---------|-------------|
| `connect` | | Connect to a game server pipe |
| `disconnect` | | Disconnect from a pipe |
| `scripts` | | List / start / stop scripts |
| `mgmt` | `management`, `m` | Manage management scripts (list, start, stop, restart, reload, info) |
| `client` | `cm`, `clients` | Manage clients, groups, and cross-client script operations |
| `stream` | `sv` | Start/stop live game video streaming with quality/fps/resolution options |
| `screenshot` | | Capture a screenshot |
| `logs` | | View log output |
| `metrics` | | View RPC call statistics (latency, error rates) |
| `profile` | `prof` | View per-script loop timing data |
| `config` | | Persistent CLI configuration (`~/.botwithus/config.properties`) |
| `actions` | | Inspect the game action queue, history, and blocked state |
| `events` | | Monitor event bus subscriptions and publish counts |
| `autostart` | | Manage per-account script auto-start profiles |
| `reload` | | Reload scripts (supports `--watch` for auto-reload on JAR change) |
| `mount` / `unmount` | | Mount/unmount script directories |
| `ping` | | Ping the game server |
| `help` | | Show available commands |
| `clear` | | Clear the console |
| `exit` | | Exit the application |

GUI panels:
- **Console** — Command input and output with copy-to-clipboard
- **Logs** — In-memory log capture with copy-to-clipboard
- **Scripts** — Script management (alphabetically sorted)
- **Management Scripts** — Load, start/stop/restart management scripts and their configs
- **Script UI** — Custom per-script ImGui UI rendered in tabs
- **Blueprint Editor** — Visual node-graph workflow editor with drag-and-drop, linking, and save/load

### example-script

Reference implementations (`ExampleScript`, `WoodcuttingFletcherScript`). `ExampleScript` demonstrates the custom Script UI system with status display, controls, and an entity summary table. Building this module automatically installs the JAR to the `scripts/` directory.

## Writing a Script

Scripts implement the `BotScript` SPI and are packaged as Java modules.

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ScriptManifest(
    name = "My Script",
    version = "1.0",
    author = "You",
    description = "Does something useful"
)
public class MyScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(MyScript.class);
    private ScriptContext ctx;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        log.info("Started!");
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
        log.info("Stopped.");
    }
}
```

SLF4J is available transitively from the API module — no extra dependency needed. Log output from scripts is automatically tagged with the script name and connection via MDC.

Your `module-info.java` must declare the service provider:

```java
module my.script {
    requires com.botwithus.bot.api;
    provides com.botwithus.bot.api.BotScript with my.script.MyScript;
}
```

Place the compiled JAR in the `scripts/` directory. The runtime discovers and loads it automatically.

## Management Scripts

Management scripts run independently of any single client and can coordinate across all connected clients. Use these for multi-account orchestration, group rotation, cross-client monitoring, and scheduled workflows.

```java
@ScriptManifest(name = "GroupRotator", version = "1.0",
        description = "Rotates scripts across client groups")
public class GroupRotator implements ManagementScript {

    private ClientOrchestrator orchestrator;

    @Override
    public void onStart(ManagementContext ctx) {
        orchestrator = ctx.getOrchestrator();
        orchestrator.createGroup("skillers", "Skilling accounts");
    }

    @Override
    public int onLoop() {
        orchestrator.startScriptOnGroup("skillers", "Woodcutter");
        return 60_000; // check every minute
    }

    @Override
    public void onStop() {
        orchestrator.stopAllScriptsOnAll();
    }
}
```

Declare the SPI in `module-info.java`:

```java
provides com.botwithus.bot.api.script.ManagementScript with my.script.GroupRotator;
```

### Script Scheduling

The `ScriptScheduler` enables deferred and recurring script execution:

```java
ScriptScheduler scheduler = ctx.getScriptManager().getScheduler();

scheduler.runAfter("Woodcutter", Duration.ofMinutes(10));       // one-shot after delay
scheduler.runAt("Fisher", Instant.parse("2026-03-09T14:00:00Z")); // at specific time
scheduler.runEvery("Miner", Duration.ofHours(2));               // recurring
scheduler.runEvery("Crafter", Duration.ofMinutes(30), Duration.ofMinutes(5)); // recurring with auto-stop
```

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

## Personality & Humanization

The `Personality` profile provides per-user behavioral characteristics and live session stats. Scripts can use this to adapt timing, click precision, and break scheduling for more human-like behavior.

```java
Personality p = api.getPersonality();
double reactionMultiplier = p.timing().reactionSpeed(); // 0.7–1.5
double fatigue = p.session().fatigueLevel();            // 0.0–1.0
String risk = p.session().riskLevel();                  // "low", "moderate", "high", "critical"
```

Personality traits include speed, path curvature, precision, tremor, timing, fatigue resistance, and camera movement characteristics.

## Build Commands

```bash
./gradlew build                    # Build all modules (installs example-script to scripts/)
./gradlew clean build              # Clean and rebuild
./gradlew :cli:run                 # Run the GUI application
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
├── config.properties                 # CLI config
├── groups.json                       # Persisted connection groups
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
autostart group <name> list           # List scripts in a group
autostart settings                    # Show global settings
autostart on / off                    # Enable/disable background pipe scanning
```

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

## API Documentation

Javadoc is generated for the API module and published to GitHub Pages. Build locally with:

```bash
./gradlew :api:javadoc
```
