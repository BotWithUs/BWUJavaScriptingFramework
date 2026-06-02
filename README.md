# JBotWithUsV2

A modular Java 21 game scripting framework that communicates with a game server via Windows named pipes using MessagePack-encoded JSON-RPC. Scripts are dynamically discovered at runtime via Java's ServiceLoader SPI and execute on virtual threads.

## Requirements

- Java 25 (auto-provisioned by Gradle's toolchain — no manual install needed if Gradle has network access)
- Windows (named pipe + shared-memory transports)
- Gradle 9.5+ (included via wrapper)

## Quick Start

```bash
# Build all modules
./gradlew build

# Run the GUI application
./gradlew :cli:run
```

The GUI provides a tabbed interface for connecting to the game server, managing scripts, viewing logs, and rendering custom script UIs.

## Module Architecture

Five Gradle subprojects with strict dependency layering:

```
api                 (slf4j-api)                — Public interfaces, models, snapshot view, query builders
  ↑ required by
core                (api + msgpack + logback   — Pipe + SHM transport, RPC client, script runtime,
                     + bouncycastle + panama)    Maven resolver, loader DLL bridge
  ↑ required by                                ↑
cli                 (api + core + imgui)       test-support  (api)
                                               — Mocks for downstream script projects
example-script      (api only)                 — Example BotScript implementations
```

### api

Pure interface module whose only dependency is `slf4j-api` (exposed transitively so scripts get SLF4J for free). Contains `BotScript` (the SPI), `Client` / `ClientProvider`, `GameAPI` (slim RPC surface composed of `SystemAPI` / `ActionAPI` / `NavigationAPI` for mutations and state probes), `GameSnapshot` (the tick-scoped read view backed by shared memory), fluent entity query builders (`Npcs`, `Players`, `SceneObjects`, `GroundItems`, `WorldMapElements`), inventory wrappers (`Backpack`, `Bank`, `Equipment`), an event bus, and inter-script communication via `MessageBus`.

> **Reads vs writes.** Live state (local player, NPCs, players, locations, inventories) is read from `Client.snapshot()` — a per-tick shared-memory view, no RPC round-trip. `GameAPI` is for mutations, login/break controls, client-script execution, and cache-type lookups.

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

Runtime and communication layer. Owns both transports: Windows named pipe I/O (`pipe/PipeClient`, `\\.\pipe\BotWithUs_<pid>`) for synchronous msgpack JSON-RPC (`rpc/RpcClient`), and the shared-memory mapping (`shm/SharedRegion`, `Local\nxt_snapshot_<pid>`) that exposes the producer's per-tick snapshot + event ring. Discovers script JARs from the `scripts/` directory (`runtime/LocalScriptLoader`) and runs each script on a virtual thread (`runtime/ScriptRuntime`, `runtime/ScriptRunner`).

Key features:
- **RPC timeouts** — Configurable per-call timeouts with `RpcTimeoutException`
- **Retry** — `RetryPolicy` with exponential backoff for transient RPC failures
- **Metrics** — `RpcMetrics` tracks call count, latency, and error rate per method
- **Profiling** — `ScriptProfiler` tracks loop timing (avg/min/max/last)
- **Error isolation** — Per-phase error handling in `ScriptRunner` (onStart/onLoop/onStop)
- **Structured logging** — SLF4J + Logback with MDC-based context tagging (`script.name`, `connection.name`)
- **GUI log bridge** — Custom `LogBufferAppender` feeds Logback events into the in-memory `LogBuffer` for the GUI log panel
- **Loader bridge** — Project Panama FFI binding to `bwu.dll` (`loader/BwuClient`) for auth, accounts, and agent injection; native artifacts auto-download to `~/.botwithus/native`
- **Maven resolver** — `resolver/` pipeline (drivers via `RepositoryDriver` SPI, BouncyCastle PGP verification, SHA-1/SHA-256 sidecar validation) for `scripts install` (see [Script Repositories](#script-repositories))

### cli

ImGui-based GUI with ANSI color support and a command system. Supports multiple simultaneous pipe connections, custom script UI rendering, and management script orchestration.

Commands:

| Command | Aliases | Description |
|---------|---------|-------------|
| `connect` | | Connect to a game server pipe |
| `disconnect` | | Disconnect from a pipe |
| `scripts` | | List / start / stop scripts, plus `install` / `update` / `uninstall` / `search` / `adopt` / `repo` / `trust` subcommands (see [Script Repositories](#script-repositories)) |
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
| `player` | `self`, `pos` | Print local player position and state from the snapshot (`player skills` for the skills table) |
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

Reference implementations: `ExampleScript` (Script UI demo with status display, controls, and entity summary table), `WoodcuttingFletcherScript`, `LocationProbeScript` (smoke test against the live producer's scene Locations table), `WalkToFlagScript`, and `DivinationScript`. Building this module automatically installs the JAR to the `scripts/` directory.

### test-support

Published as `bot-test-support`. Mocks for downstream script projects to unit-test against the API: `MockGameAPI`, `MockScriptContext`, `CannedSnapshot`, `InMemoryEventBus`.

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
        GameSnapshot snap = api.snapshot();         // tick-scoped read view (SHM-backed)
        // Read live state from the snapshot, mutate via api
        if (snap != null && snap.self() != null) {
            // ...
        }
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

## Script Repositories

Beyond manually dropping JARs into `scripts/`, the CLI can install scripts from any Maven-layout repository — Maven Central, Sonatype Nexus, JFrog Artifactory, JitPack, or a local file repo. Artifacts are fetched by Maven coordinate, validated against `.sha1` / `.sha256` sidecars, optionally verified against a PGP trust ring, and dropped into the same `scripts/` directory the runtime watches.

All state lives under `~/.botwithus/`:

```
~/.botwithus/
├── repositories.json          # configured repos (id, url, driver, snapshots, requireSignature, ...)
├── credentials.json           # per-repo Basic-auth credentials, keyed by repo id
├── installed-scripts.json     # resolver-managed install index (coord → jar/version/repo)
└── trusted-keys/              # imported PGP public keys
```

### Adding a repository

Repository URLs are the **content root** of a specific hosted repo. For Nexus, that's `https://<host>/repository/<repo-name>/`, not the Nexus root URL.

```text
# public Maven repo
scripts repo add central https://repo1.maven.org/maven2/

# Nexus release repo
scripts repo add my-nexus https://nexus.example.com/repository/scripts-releases/

# Nexus snapshots
scripts repo add my-nexus-snap https://nexus.example.com/repository/scripts-snapshots/ --type=snapshot

# require every artifact to carry a valid PGP signature
scripts repo add my-nexus https://nexus.example.com/repository/scripts-releases/ --require-signature
```

The default driver is `maven` — the standard `groupId/artifactId/version/artifactId-version.jar` layout. Other drivers can be plugged in via the `RepositoryDriver` ServiceLoader SPI; pass `--driver=<id>` to select one.

| Flag | Meaning |
|------|---------|
| `--type=snapshot` | Repo serves `-SNAPSHOT` versions (release-only repos refuse them) |
| `--require-signature` | Refuse any artifact without a valid `.jar.asc` signed by a trusted key |
| `--driver=<id>` | Override the default `maven` driver |

`scripts repo list` shows everything configured; `scripts repo remove <id>` drops one.

### Private repositories (credentials)

```text
scripts repo login my-nexus --user deploy-reader --password <token>
scripts repo logout my-nexus
```

Credentials are persisted in `~/.botwithus/credentials.json`, keyed by repo id, and sent as HTTP Basic auth by the HTTP transport. Plain-HTTP URLs trigger a yellow warning at `repo add` time — credentials and artifacts ride in the clear.

### PGP trust (signed repositories)

When a repo is added with `--require-signature`, the installer fetches the `.jar.asc` sidecar alongside each artifact and refuses to install unless the signature verifies against an imported public key. Export the publisher's key with `gpg --export --armor <keyid> > publisher.asc`, then:

```text
scripts trust add publisher.asc      # imports every public key in the file
scripts trust list                   # show trusted keys (id, user id, added timestamp)
scripts trust remove <keyId>         # revoke trust
```

Verification is handled by `BouncyCastlePgpVerifier`. Unsigned artifacts, signatures from unknown keys, and tampered JARs all fail with `SignatureInvalid` and abort the install.

### Installing and managing scripts

```text
# install the latest release
scripts install com.example:woodcutter

# install a pinned version
scripts install com.example:woodcutter:1.4.2

# restrict resolution to one repo
scripts install com.example:woodcutter --repo=my-nexus

# list resolver-managed installs
scripts list --installed
scripts list --installed --outdated

# update one or all
scripts update com.example:woodcutter
scripts update --all

# remove
scripts uninstall com.example:woodcutter

# take ownership of a JAR you copied in manually
scripts adopt some-old-script.jar
```

The install pipeline reads `maven-metadata.xml` to discover versions, downloads the JAR plus `.sha1` / `.sha256` (and `.asc` if signing is required), verifies, writes the JAR to `scripts/`, and records the install in `installed-scripts.json`. The runtime picks up the new module on next `reload` (or automatically if `reload --watch` is active).

### Searching

```text
scripts search woodcutter
scripts search woodcutter --repo=my-nexus --limit=20
```

Search returns hits per repo using each repo's native dialect — Solr for Maven Central (`search.maven.org`), the REST v1 API for Nexus. **Search requires the repo entry to carry a `searchEndpoint` URL**, and `scripts repo add` does not yet expose a flag for it. To enable search against a Nexus instance, hand-edit `~/.botwithus/repositories.json` and add:

```json
"searchEndpoint": "https://nexus.example.com/service/rest/v1/search"
```

to the repo entry. Without it, the CLI reports `search not supported` for that repo. Browsing and direct installs still work — only the search dialect is gated.

### End-to-end example

```text
scripts repo add bwu https://nexus.botwithus.dev/repository/scripts/
scripts repo login bwu --user me --password <token>
scripts trust add publisher.asc            # only if --require-signature was used
scripts install com.botwithus.scripts:woodcutter
reload
scripts                                    # the new script is now listed and runnable
```

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

ManagementScripts schedule scripts through `ClientOrchestrator`. The orchestrator owns per-client targeting, so each call states *which* client(s) the schedule applies to. Single-client, group, and all-client variants exist for one-shot (`scheduleScript` / `scheduleScriptAt`) and recurring (`scheduleScriptEvery`) operations, each with optional `Map<String, Object>` config for the started script:

```java
// One-shot in 10 minutes on a specific client
orchestrator.scheduleScript("Account1", "Woodcutter", Duration.ofMinutes(10));

// Scheduled start at a specific instant on every client in a group
orchestrator.scheduleScriptOnGroupAt("Skillers", "Fisher", Instant.parse("2026-03-09T14:00:00Z"));

// Recurring every 2h across the whole fleet
orchestrator.scheduleScriptOnAllEvery("Miner", Duration.ofHours(2));

// Recurring with auto-stop after 5 min per cycle, group-wide
orchestrator.scheduleScriptOnGroupEvery("Skillers", "Crafter",
        Duration.ofMinutes(30), Duration.ofMinutes(5));

// Cancel by id, or wipe everything
orchestrator.cancelSchedule("Account1", scheduleId);
orchestrator.cancelAllSchedules();

// Observe scheduled state
orchestrator.listScheduled().forEach(e ->
        log.info("{}: {} next at {}", e.clientName(), e.scriptName(), e.nextRun()));
```

`ScriptScheduler` itself remains a framework-internal type — each instance is bound to one Connection's runtime, and the orchestrator routes calls to the right one.

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

## Live Config

Scripts expose runtime-editable parameters by overriding two methods on `BotScript`:

```java
@Override
public List<ConfigField> getConfigFields() {
    return List.of(
            ConfigField.intField("loopDelay", "Loop Delay (ms)", 5000),
            ConfigField.boolField("verbose", "Verbose Logging", true),
            ConfigField.choiceField("mode", "Operating Mode",
                    List.of("Passive", "Active", "Aggressive"), "Passive"),
            ConfigField.itemIdField("axeId", "Axe Item ID", 1351),
            ConfigField.stringField("greeting", "Greeting Text", "hello"));
}

@Override
public void onConfigUpdate(ScriptConfig config) {
    this.loopDelay = config.getInt("loopDelay", 5000);
    this.verbose = config.getBoolean("verbose", true);
    this.mode = config.getString("mode", "Passive");
}
```

`ConfigField` supports five kinds: `INT`, `STRING`, `BOOLEAN`, `CHOICE`, and `ITEM_ID`. The framework renders a typed widget per field — number spinner for `INT`/`ITEM_ID`, text input for `STRING`, checkbox for `BOOLEAN`, dropdown for `CHOICE`. Open the panel from the Scripts list (Configure button on each script card).

`onConfigUpdate(ScriptConfig)` fires twice:

1. **At startup** — once the saved config is loaded from disk (or the defaults if nothing is persisted yet). This happens before `onLoop` begins.
2. **At runtime** — every time the user clicks "Apply" in the config panel. The script keeps running; treat this as a hot reload of your tuning knobs.

Persistence: configs are written to `~/.botwithus/config/<scriptName>.json` after every "Apply". The same file is read at script start. Editing the JSON by hand works — the change picks up on next start. Delete the file to reset to declared defaults.

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

The producer (an injected C++ DLL) exposes two transports under the same `<pid>` suffix; both bind together via `Client`:

```
                          ┌──────────────────────────────┐
                          │  Injected NXTLibrary DLL     │
                          │  (game process, per-pid)     │
                          └─────────┬────────────────┬───┘
                                    │                │
              \\.\pipe\BotWithUs_<pid>      Local\nxt_snapshot_<pid>
                  (msgpack JSON-RPC)        (per-tick snapshot + event ring)
                                    │                │
       ┌──── mutations / probes ────┘                └──── live reads ─────┐
       ▼                                                                   ▼
  BotScript → GameAPI → RpcClient → PipeClient                  Client.snapshot() → GameSnapshot
                                                                                    (via SharedRegion)
```

- **Pipe (RPC)**: length-prefixed MessagePack frames, synchronous request/response. Used for mutations, login/break control, action queueing, navigation, client-script execution, and cache-type lookups.
- **SHM (snapshot + events)**: `core/shm/SharedRegion` maps the producer's double-buffered snapshot region; readers honour acquire-load on `frontIdx` and per-slot `seq` so reads tear-free. The event ring carries push-style notifications consumed by `EventBus`. `Layout.PROTOCOL_VERSION` must match the producer's `kProtocolVersion`.

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

## Troubleshooting

**Pipe not found.** Connect fails with "no pipe matching `\\.\pipe\BotWithUs_*` found". The agent DLL (`BotWithUsDll.dll`) hasn't injected, or it injected into a different client PID. Check that the game client is running, that the loader actually injected (loader console will show the injection result), and that the PID matches. `PipeClient.firstAvailableOrThrow` walks `\\.\pipe\` and picks the first match — if multiple game clients are running, pass an explicit pipe name to `connect`.

**Agent not injected.** The game client launched but no `\\.\pipe\BotWithUs_<pid>` ever appears. Two common causes: (1) the loader's `phantom_load` was blocked by an antivirus / EDR; (2) the agent DLL panicked during DllMain (look for `bwu_agent.log` next to the loader). The agent writes a startup line on success — its absence is the signal.

**Protocol-version mismatch.** Connect succeeds but reads fail immediately with "shared region protocol version X, expected Y" — the consumer (`Layout.PROTOCOL_VERSION`) and the producer (`kProtocolVersion` in `NXTLibrary/src/ipc/SharedLayout.h`) drifted. Rebuild both sides from matching commits; `SharedRegion.open()` refuses to map a region whose version byte doesn't match.

**Missing `provides` clause.** A JAR is placed in `scripts/` but doesn't show up in the Scripts panel. The most common cause is forgetting `provides com.botwithus.bot.api.BotScript with my.script.MyScript;` in the script's `module-info.java`. `LocalScriptLoader` emits a WARN-level log line when a module-bearing JAR contains no `BotScript` provider — check the log to confirm.

**Scripts folder discovery order.** `LocalScriptLoader.resolveScriptsDir()` checks the `botwithus.scripts.dir` system property first; if unset, it walks up from the current working directory looking for an existing `scripts/` subdirectory (up to three parents — handles running from a sub-module's working dir). If nothing is found, it falls back to creating `./scripts` in the current working directory. If your script JAR isn't being picked up, the most common cause is running the CLI from a working directory the loader can't see — set `-Dbotwithus.scripts.dir=/absolute/path/to/scripts` or check the log for the resolved path.
