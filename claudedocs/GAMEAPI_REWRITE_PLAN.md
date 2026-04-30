# GameAPI Rewrite Plan (Path B — Wire-Surface-First)

Cross-session plan for replacing the legacy RPC-shaped Java `GameAPI` with a SHM-snapshot-first read surface, fleshing out the wire-side mutation contract on the C++ producer, and dropping dead surface.

## Architectural framing

**The wire surface is the API.** Java is one reference consumer. The contract is:

- `NXTLibrary/src/ipc/SharedLayout.h` — snapshot field offsets and caps
- `NXTLibrary/src/ipc/Events.h` — event-type discriminators and body POD layouts
- `NXTLibrary/src/rpc/Handlers.cpp` — RPC method names, param shapes, response shapes

These three files are the spec. Future hosts (Python, C#, etc.) consume the same surfaces. Anything pushed into a Java-only path that other hosts can't reach fragments the platform.

**Where logic lives**: C++ owns smart logic (auto-login, break scheduling, pathfinder strategy, humanization, var caches). Hosts get parameter knobs and events, not reimplementation duties.

## Repos involved

- `E:\BotWithUs V2\JBotWithUsV2` — Java consumer
- `E:\BotWithUsv2.5\NXTLibrary` — C++ producer (injected DLL)

Branch: create `gameapi-rewrite` off current `slice2-mmap-shared-memory` head before starting slice 1.

## Decisions already made

| Module | Where | Wire exposure |
|---|---|---|
| Auto-login | C++ | `set_auto_login` / `get_auto_login` RPCs; reliability across host crashes is the reason |
| Break enforcement | C++ | action queue gate (already exists) |
| Break scheduling (fatigue/risk model) | C++ | `schedule_break(ms)`, `interrupt_break()`, `set_break_policy(...)` RPCs; `break_started` / `break_ended` events (wired) |
| Pathfinder execution + strategy | C++ | `walk_to`, `walk_world_path`, `walk_cancel`, `walk_status` RPCs; `walk_arrived` / `walk_cancelled` / `walk_failed` events (wired) |
| Variables (varp/varbit/varc current values) | C++ | `get_varp`, `get_varbit`, `get_varc_int`, `get_varc_string`, `query_varbits` RPCs OR snapshot extension |
| Humanizer / Personality | C++ | already there; tied to action execution |
| Action queue | C++ | already there |
| Snapshot writer + event ring | C++ | already there |
| Config-type lookups (slice 5) | C++ | stub for now per user direction |

## Snapshot fields available today (`SharedLayout.h` v8)

- `tickId`, `gameState`, `ownIndex`
- `LocalPlayer self`: serverIndex, combatLevel, tile/plane, flags, followingIndex, animationId, stanceId, targetIndex/Type, isMember, skills[32] (typeId/xp/actualLevel/boostedLevel)
- `NpcEntry npcs[1024]`: serverIndex, typeId, tile/plane, flags, followingIndex, animationId, stanceId, hp, maxHp
- `PlayerEntry players[2048]`: serverIndex, tile/plane, flags, followingIndex, animationId, stanceId, combatLevel
- `InventoryHeader inventories[32]` + `InventoryItem invItems[2048]`: invId/slotCount/firstItemIdx → itemId/quantity

Producer is already writing all of this each tick (`State.cpp:FillSnapshot` → `PublishSnapshot()`), validated against injected DLL in earlier sessions.

## Events working today

`TickEvent`, `LoginStateChangeEvent`, `VarChangeEvent`, `VarbitChangeEvent`, `VarcChangeEvent`, `ChatMessageEvent`. Pump runs at 50ms in `SharedRegionEventPump`.

Wired but unused producer-side: walk_*, break_*, key_input, action_executed, hitmark, headbar, spot_anim. Java decoder arms exist for some.

---

## Slice 1 — `GameSnapshot` skeleton (Java only, ~1 day)

**Goal**: tick-scoped read API backed by `SnapshotView`. Pure addition; nothing wired yet.

**Files to add** (`api` module unless noted):
- `api/.../snapshot/GameSnapshot.java` — interface (full sketch below)
- `api/.../snapshot/Npc.java` — record (mirrors `NpcEntry` fields)
- `api/.../snapshot/Player.java` — record (mirrors `PlayerEntry` fields)
- `api/.../snapshot/LocalPlayer.java` — already exists at `api/.../model/LocalPlayer.java`; rename or relocate, drop fields not in the snapshot (hitmarks, headbars, overhead text, target-related extras), add `skills` field
- `api/.../snapshot/Skill.java` — record (typeId, experience, actualLevel, boostedLevel)
- `api/.../snapshot/Inventory.java`, `InventoryItem.java` (existing `model/InventoryItem.java` works; check field shape)
- `api/.../query/NpcFilter.java`, `PlayerFilter.java` — records with predicate-style match methods
- `core/.../impl/snapshot/GameSnapshotImpl.java` — wraps `SnapshotView`

**Files to test**:
- `core/src/test/.../snapshot/GameSnapshotImplTest.java` — synthetic in-memory buffer, no DLL needed. Cover: empty snapshot, single NPC, full NPC array, inventory with sparse slots, filters.

**Interface sketch**:

```java
public interface GameSnapshot {
    long tickId();
    int  gameState();
    int  ownIndex();
    LocalPlayer self();   // null if not in-game
    Npcs    npcs();
    Players players();
    Inventories inventories();

    interface Npcs {
        int count();
        Npc at(int i);
        Npc byServerIndex(int serverIndex);
        List<Npc> filter(NpcFilter f);
        Stream<Npc> stream();
    }
    // Players, Inventories follow the same pattern
}
```

**Done when**: tests pass against synthetic buffer; not yet exposed via `Client` / `ScriptContext`.

---

## Slice 2 — Wire `GameSnapshot` through `ScriptContext` (~½ day)

**Goal**: scripts can call `client.snapshot()` and get a `GameSnapshot` backed by the live SHM region.

**Files to modify**:
- `api/.../Client.java` — add `GameSnapshot snapshot();`
- `core/.../impl/ClientImpl.java` — implement; takes `SharedRegion` (or pump's region) in constructor and returns `new GameSnapshotImpl(sharedRegion.snapshot())`
- `core/.../JBotApplication.java` — pass `SharedRegion` into `ClientImpl` (currently the pump owns it; either share or hand the region to both)
- `cli/.../CliContext.java` — same wiring
- `core/.../shm/SnapshotProbe.java` — port to use the new public API end-to-end (validates the bridge against the injected DLL)
- `core/build.gradle.kts` — add a `gameSnapshotProbe` JavaExec task if useful

**Done when**: probe prints local player + NPC count + inventory totals via `client.snapshot()` against an injected DLL.

---

## Slice 3 — Trim `GameAPI` read surface (~1 day)

**Goal**: delete every Java method that today RPCs a query the C++ side doesn't handle. The mutation surface and stub config-type lookups stay.

**Files to modify** (delete methods, not whole files):
- `api/.../GameAPI.java` — strip read methods listed below
- `core/.../impl/GameAPIImpl.java` — strip matching impls
- `api/.../domain/EntityQueryAPI.java`, `ComponentAPI.java`, `VariableAPI.java`, `InventoryAPI.java`, `GameStateAPI.java` — most collapse; `NavigationAPI` keeps mutations only; delete the dead interfaces
- `api/.../query/EntityFilter.java`, `ComponentFilter.java`, `WorldMapElementFilter.java`, `WorldMapIconFilter.java` — delete
- Tests in `core/src/test/...` referencing deleted methods — delete or rewrite

**Methods to delete from `GameAPI` / `GameAPIImpl`** (RPC names that have no C++ handler):
- Entity query: `queryEntities`, `getEntityInfo`, `getEntityName`, `getEntityHealth`, `getEntityPosition`, `isEntityValid`, `getEntityHitmarks`, `getEntityHeadbars`, `getEntitySpotAnims`, `getEntityAnimation`, `getEntityOverheadText`, `getAnimationLength`
- Ground / scene: `queryGroundItems`, `getObjStackItems`, `queryObjStacks`, `queryProjectiles`, `querySpotAnims`, `queryHintArrows`
- Worlds: `queryWorlds`, `getCurrentWorld`
- World map: `queryWorldMapElements`, `getWorldMapElement`, `getWorldMapElementCount`, `queryWorldMapIcons`
- Components: `queryComponents`, `isComponentValid`, `getComponentText`, `getComponentItem`, `getComponentPosition`, `getComponentOptions`, `getComponentSpriteId`, `getComponentType`, `getComponentChildren`, `getComponentByHash`, `getOpenInterfaces`, `isInterfaceOpen`
- Variables (current-value getters): `getVarp`, `getVarbit`, `getVarcInt`, `getVarcString`, `queryVarbits` — **decide in slice 6** whether to add via SHM or RPC; delete from API for now
- Game state reads now in snapshot: `getLocalPlayer`, `getAccountInfo`, `getMiniMenu`, `getGrandExchangeOffers`
- Screen math: `getWorldToScreen`, `batchWorldToScreen`, `getViewportInfo`, `getEntityScreenPositions`, `getGameWindowRect`
- Player stats (in snapshot now): `getPlayerStats`, `getPlayerStat`, `getPlayerStatCount`
- Chat: `queryChatHistory`, `getChatMessageText`, `getChatMessagePlayer`, `getChatMessageType`, `getChatHistorySize`
- Streaming/screenshot/humanizer-getters: `startStream`, `stopStream`, `takeScreenshot`, `getHumanizationEnabled`, `setHumanizationEnabled`, `getPersonality`
- Misc: `computeNameHash`, `updateQueryContext`, `invalidateQueryContext`, `getCacheFile`, `getCacheFileCount`, `getNavigationArchive`

**Methods that stay** (or move to a trimmed `GameAPI`): see slice 4 list.

**Done when**: project compiles with the trimmed API; remaining `GameAPI` surface is mutations + cache lookups + `snapshot()` + the two working RPCs (`getGameCycle`, `getLoginState`, `ping`).

---

## Slice 4 — Mutation RPC handlers, C++ side (~3-5 days)

**Goal**: implement the wire-side public contract for state-mutating operations. Each handler is a versioned API surface — names and shapes get pinned now.

**Files to modify** (`NXTLibrary/src/`):
- `rpc/Handlers.cpp` — add `Handle_*` functions and entries in `g_methods[]`
- New `.cpp` files per logical group if Handlers.cpp gets too long (linker-pulled, see existing comment in `Handlers.cpp:90-96`)
- Existing C++ subsystems likely need audit/repair: action queue, walker, nav graph, login control, break manager, humanizer

**Handler list** (each is `bool Handle_X(Reader&, Writer&)` + entry in `g_methods[]`):

Action queue:
- `queue_action` (action_id, param1, param2, param3) → {}
- `queue_actions` (actions: [...]) → {queued: int}
- `clear_action_queue` () → {}
- `get_action_queue_size` () → {size: int}
- `get_action_history` (max_results, action_id_filter) → {entries: [...]}
- `get_last_action_time` () → {timestamp: long}
- `set_actions_blocked` (blocked: bool) → {}
- `are_actions_blocked` () → {blocked: bool}
- `set_behavior_mod` (mod_id, value) → {}
- `clear_behavior_mod` (mod_id) → {}
- `get_behavior_mod` (mod_id) → {value: float}

Walker / pathfinder:
- `walk_to` (x, y) → {}
- `walk_world_path` (x, y, plane?, exact_dest_tile?, config?) → {}
- `walk_cancel` () → {}
- `walk_status` () → {state, target_x, target_y, current_step, total_steps, nav_step, total_nav_steps, is_walking, is_done, hpa_ready}
- `is_reachable` (x, y, max_iterations?) → {reachable: bool}
- `find_path` (from_x?, from_y?, to_x, to_y) → {found, path_length, path: [...]}
- `find_world_path` (from_x?, from_y?, to_x, to_y) → same
- `region_cache_info` () → {cache_size: int}
- `region_cache_clear` () → {}

Login / world / breaks:
- `set_world` (world_id) → {}
- `change_login_state` (old_state?, new_state) → {}
- `login_to_lobby` () → {}
- `set_auto_login` (enabled: bool) → {}
- `get_auto_login` () → {enabled: bool}
- `schedule_break` (duration: int_ms) → {}
- `interrupt_break` () → {}
- `set_break_policy` (policy params...) → {} *(shape TBD when auditing C++ break manager)*

Script execution:
- `get_script_handle` (script_id) → {handle: long}
- `execute_script` (handle, int_args?, string_args?, returns?) → {returns: [...]}
- `destroy_script_handle` (handle) → {}
- `fire_key_trigger` (interface_id, component_id, input) → {}

Nav graph CRUD:
- `nav.add_transport`, `nav.remove_transport`, `nav.list_transports`
- `nav.add_door`, `nav.remove_door`, `nav.list_doors`
- `nav.add_shortcut`, `nav.remove_shortcut`
- `nav.add_plane_transition`, `nav.remove_plane_transition`
- `nav.add_climbover`, `nav.remove_climbover`
- `nav.load_json` (links: [...]) → {added: int}
- `nav.save_links` (path?) → {}
- `nav.load_links` (path?) → {loaded: int}
- `nav.stats` () → {regions, doors, shortcuts, plane_transitions, climbovers, transports, teleports, teleports_builtin, teleports_script}
- `nav.register_teleports` (json, format?) → {added: int}
- `nav.clear_script_teleports` () → {removed: int}
- `nav.list_teleports` (script_only?) → {teleports: [...]}

Meta / dispatcher utility:
- `rpc.ping` () → {pong: bool}
- `rpc.list_methods` () → [list of strings]
- `rpc.client_count` () → {count: int}

**Pattern for each handler**: see `Handle_GetGameCycle` / `Handle_GetLoginState` in `Handlers.cpp` for the existing template — `DrainParams` if no input, otherwise `ReadMapHeader` + per-key `ReadString` matches, then call into the relevant subsystem and `WriteMapHeader` / `WriteCStr` / `WriteInt` etc. for the response.

**Subsystem audits** likely needed before handlers can be implemented (each may be partial or rotted in the legacy module):
- Action queue executor — confirm it exists, runs on game thread, supports the action-id taxonomy implied by Java's `GameAction` record
- Walker — confirm HPA*/A* implementation status, walk-event emission, `walk_status` shape
- Nav graph — confirm storage, persistence (binary save/load), teleport registry
- Login manager — confirm `change_login_state`, `login_to_lobby` script execution, auto-login loop
- Break manager — confirm fatigue/risk model wiring, event emission, parameterization

**Done when**: a Java script can queue an action, walk somewhere, and observe `walk_arrived` against the injected DLL.

---

## Slice 5 — Config-type lookups (STUB, ~½ day)

**User direction**: stub for now. Real implementation deferred.

**C++ side**: register handlers in `Handlers.cpp` that return empty/sentinel responses so any host gets a graceful "method exists, no data yet" response rather than `method not found`.

- `get_item_type` (id) → {id, name: "", members: false, stackable: false, ...zero-fields...}
- `get_npc_type` (id) → {id, name: "", combat_level: 0, ...}
- `get_location_type` (id) → {id, name: "", ...}
- `get_enum_type` (id) → {id, entry_count: 0, entries: {}}
- `get_struct_type` (id) → {id, params: {}}
- `get_sequence_type` (id) → {id, frame_count: 0, ...}
- `get_quest_type` (id) → {id, name: "", ...}

**Java side**: keep `GameAPI` method signatures and the model classes; `GameAPIImpl` continues to call the RPCs and decode whatever comes back. Add javadoc `@apiNote stub — see issue #N` on each.

**Done when**: handlers are registered, return sentinel responses, no NPE on the Java side.

---

## Slice 6 — Decision pass on dropped surface (~½ day discussion)

For each method deleted in slice 3, decide: stay dropped, snapshot extension, RPC handler, or event-cache. Apply the rule: "drop only if no future host plausibly needs it."

**Pre-staged recommendations** (for the discussion):

| Surface | Recommendation | Reason |
|---|---|---|
| `getVarp` / `getVarbit` / `getVarc*` / `queryVarbits` | RPC handlers, C++ owns cache | Every host wants current values without rebuilding from event stream |
| Components / UI state | Snapshot extension OR drop | Most bots react to events + queue actions; live UI reads are rare. Defer until a concrete use case |
| Chat history | Drop, hosts maintain ring from `ChatMessageEvent` | Per-host concern; events already carry it |
| Ground items / projectiles / spotanims / hint arrows | Snapshot extension if scripts need it | Small structs, fits existing pattern; defer until needed |
| World list / current world | RPC, low-frequency | Cache-driven, fits one-shot RPC |
| World map elements | RPC, low-frequency | Same |
| Hitmarks / headbars / per-entity spotanims | Stay event-only (already wired producer-side, mostly) | Transient; events are the right shape |
| Streaming / screenshot | Drop unless concrete use | Speculative |
| Humanizer config getters / setters | RPC if scripts need to tune | Current `Personality` is RPC-shaped; keep that path |
| Viewport / world-to-screen / window rect | Drop unless overlay use case | Speculative |
| GE offers / mini menu | Snapshot extension if needed | Defer until concrete use |
| `getAccountInfo` (login_progress, login_status, jx_*, session_id) | RPC, infrequent | Fits one-shot RPC, sensitive fields anyway |
| Cache file reads (`getCacheFile`, `getNavigationArchive`) | RPC, byte-array response | Already RPC-shaped; preserve |

**Open question for the user**: are there other language hosts in flight (Python, C#) right now, or is this purely about preserving optionality? Affects how aggressively to "drop unless concrete use."

**Done when**: each item has a verdict; verdicts get queued as either snapshot-extension tasks (slice 6a) or RPC-handler tasks (slice 6b) for follow-up work.

---

## Slice 7 — Cleanup + spec docs (~½ day)

- Delete dead model classes (whatever fell in "drop" in slice 6): candidates include `HintArrow`, `Projectile`, `SpotAnim` (live), `WorldMapElement`, `WorldMapIconResult`, `ResourceSection`, `ResourceItem`, `SkillRequirement`, `WorldMapPlacement`, `Component`, `OpenInterface`, `ComponentPosition`, `ComponentTypeInfo`, `MiniMenuEntry`, `GrandExchangeOffer`, `ScreenPosition`, `ViewportInfo`, `EntityScreenPosition`, `GameWindowRect`, `StreamInfo`, `Personality`, `ChatMessage`, `EntityInfo`, `EntityHealth`, `EntityPosition`, `Hitmark`, `Headbar`, `Entity`, `GroundItem`, `GroundItemStack`, `World`, `VarbitValue`
- Update `package-info.java` files to reflect the slimmed surface
- Spec documentation pass on `SharedLayout.h`, `Events.h`, `Handlers.cpp` — verify the comments fully describe the wire contract for future host implementers
- Bump `kProtocolVersion` and `Layout.PROTOCOL_VERSION` if any snapshot extension landed
- Single commit per slice on `gameapi-rewrite`; merge plan TBD

---

## Resumption checklist

When picking this up in a future session:

1. `git status` and `git branch` in both repos
2. Read this plan top to bottom
3. Identify which slice is in progress (look for the most recent commit message on the `gameapi-rewrite` branch)
4. Re-read `Handlers.cpp` and `SharedLayout.h` to confirm wire surface didn't drift
5. Re-read `GameAPI.java` and `GameAPIImpl.java` to confirm Java surface state
6. If injecting to test: rebuild `NXTLibrary.dll`, inject, confirm pipe is `BotWithUs_<pid>`, verify pump events flow with `EventPumpProbe`

## Cross-cutting reminders

- Java rule: imports at top, never inline FQNs (`feedback_java_no_full_package_names.md`)
- Commits: omit `Co-Authored-By` trailer in this project (`feedback_commits_no_coauthor.md`)
- Cross-process audit: any change to pipe name, mapping name, protocol version, event discriminators, body shapes, snapshot offsets, or RPC method names/shapes requires updating both sides in the same logical change (`feedback_audit_java_consumer.md` + `NXTLibrary/CLAUDE.md`)
- Tick cadence: TickEvent fires on server ticks (~600ms), not 50Hz frames (`project_tick_cadence.md`)
- Login state: `LoggedIn()` only valid at gameState == 30 (`nxt_game_state_values.md`)
- VarDomain: varp and varc are separate domains with separate id namespaces (`nxt_var_domain_architecture.md`)
