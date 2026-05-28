package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.entities.GroundItems;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.Players;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.model.ActionEntry;
import com.botwithus.bot.api.model.GroundItemInfo;
import com.botwithus.bot.api.model.ResourceItem;
import com.botwithus.bot.api.model.ResourceSection;
import com.botwithus.bot.api.model.SceneObjectInfo;
import com.botwithus.bot.api.model.SkillRequirement;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.model.WorldMapPlacement;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.LoginState;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.Layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getInt;
import static com.botwithus.bot.core.impl.MapHelper.getIntList;
import static com.botwithus.bot.core.impl.MapHelper.getList;
import static com.botwithus.bot.core.impl.MapHelper.getLong;
import static com.botwithus.bot.core.impl.MapHelper.getMapList;
import static com.botwithus.bot.core.impl.MapHelper.getObjectMap;
import static com.botwithus.bot.core.impl.MapHelper.getString;
import static com.botwithus.bot.core.impl.MapHelper.getStringList;

public class GameAPIImpl implements GameAPI {

    private final RpcClient rpc;
    private final NXTCache cache;

    /**
     * Source of per-iface invalidation tokens for the {@code getComponent}
     * cache. Production wiring is {@code iface -> region.snapshot().ifaceVersion(iface)};
     * tests pass a stub. {@code null} disables caching entirely — the legacy
     * 1-/2-arg constructors take this path so existing callers and tests
     * keep their RPC-every-call semantics.
     */
    private final IntUnaryOperator ifaceVersionSource;

    /**
     * Cache of {@code getComponent} results keyed on {@code (ifaceId, compId)}
     * packed into a long. Each entry remembers the {@code ifaceVersion} the
     * cached {@link Component} was fetched at; on lookup we re-read the
     * current version and treat any mismatch as eviction. {@code null} when
     * caching is disabled (no version source). Sized via the natural growth
     * pattern — scripts touch only a handful of unique components, so we
     * don't bound this and let the JVM's hash map auto-resize.
     */
    private final ConcurrentHashMap<Long, ComponentCacheEntry> componentCache;

    /**
     * Snapshot supplier used by the entity facades and the local-player
     * helpers. Production wiring is {@code () -> new GameSnapshotImpl(region.snapshot())};
     * tests pass a stub. {@code null} means no SHM bound — entity queries
     * yield empty, getLocalPlayer returns null, getPlayerStat returns null.
     */
    private final Supplier<GameSnapshot> snapshotSource;

    /**
     * One-shot WARN-once channel for stub producer calls. Instance-scoped so
     * each {@link GameAPIImpl} throttles independently — tests that want a
     * fresh slate just build a fresh impl.
     */
    private final StubGuard stubGuard;

    private final Npcs npcsFacade = new Npcs(this);
    private final Players playersFacade = new Players(this);
    private final Backpack backpackFacade = new Backpack(this);
    private final Bank bankFacade = new Bank(this);
    private final Equipment equipmentFacade = new Equipment(this);
    private final SceneObjects objectsFacade = new SceneObjects(this);
    private final GroundItems groundItemsFacade = new GroundItems(this);
    private final WorldMapElements mapElementsFacade = new WorldMapElements(this);
    private final Components componentsFacade = new Components(this);

    /** Legacy constructor used by tests; config-type lookups will throw. */
    public GameAPIImpl(RpcClient rpc) {
        this(rpc, null, null, null);
    }

    public GameAPIImpl(RpcClient rpc, NXTCache cache) {
        this(rpc, cache, null, null);
    }

    /**
     * Slice-17 ctor: cache enabled, no snapshot source. Kept for the existing
     * cache test which doesn't need entity queries; new production callers
     * should use the 4-arg form.
     */
    public GameAPIImpl(RpcClient rpc, NXTCache cache, IntUnaryOperator ifaceVersionSource) {
        this(rpc, cache, ifaceVersionSource, null);
    }

    /**
     * Full ctor — wires the component cache and the entity-facade snapshot
     * source. {@code ifaceVersionSource} can be {@code null} to disable the
     * cache only; {@code snapshotSource} can be {@code null} to disable
     * snapshot-derived helpers (entity queries and {@code getLocalPlayer}).
     * Production callers pass both:
     * <pre>{@code
     *   new GameAPIImpl(rpc, nxtCache,
     *       iface -> region.snapshot().ifaceVersion(iface),
     *       () -> new GameSnapshotImpl(region.snapshot()));
     * }</pre>
     */
    public GameAPIImpl(RpcClient rpc, NXTCache cache,
                       IntUnaryOperator ifaceVersionSource,
                       Supplier<GameSnapshot> snapshotSource) {
        this(rpc, cache, ifaceVersionSource, snapshotSource, new StubGuard());
    }

    /**
     * Full ctor accepting a {@link StubGuard} for instrumented WARN-once
     * reporting of producer stubs ({@link #queryLocations}, {@link #queryGroundItems},
     * {@link #queryWorldMapElements}). Production wiring constructs one
     * {@code StubGuard} per session in {@code CliContext} and passes it
     * through here; the 4-arg ctor chains to a default {@code StubGuard}
     * for tests and legacy callers that don't care about the warn channel.
     */
    public GameAPIImpl(RpcClient rpc, NXTCache cache,
                       IntUnaryOperator ifaceVersionSource,
                       Supplier<GameSnapshot> snapshotSource,
                       StubGuard stubGuard) {
        if (stubGuard == null) {
            throw new IllegalArgumentException("stubGuard");
        }
        this.rpc = rpc;
        this.cache = cache;
        this.ifaceVersionSource = ifaceVersionSource;
        this.componentCache = ifaceVersionSource != null ? new ConcurrentHashMap<>() : null;
        this.snapshotSource = snapshotSource;
        this.stubGuard = stubGuard;
    }

    private record ComponentCacheEntry(int version, Component component) {}

    // ---------------------------------------------------------------- Snapshot + entities

    @Override
    public GameSnapshot snapshot() {
        return snapshotSource != null ? snapshotSource.get() : null;
    }

    @Override
    public Npcs npcs() { return npcsFacade; }

    @Override
    public Players players() { return playersFacade; }

    @Override
    public Backpack backpack() { return backpackFacade; }

    @Override
    public Bank bank() { return bankFacade; }

    @Override
    public Equipment equipment() { return equipmentFacade; }

    @Override
    public SceneObjects objects() { return objectsFacade; }

    @Override
    public GroundItems groundItems() { return groundItemsFacade; }

    @Override
    public List<SceneObjectInfo> queryLocations(int centerX, int centerY, int radius, int plane, int max) {
        stubGuard.warnOnce("queryLocations");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tile_x", centerX);
        params.put("tile_y", centerY);
        params.put("radius", radius);
        params.put("plane", plane);
        params.put("max", max);
        return rpc.callSyncList("query_locations", params).stream()
                .map(m -> new SceneObjectInfo(
                        getInt(m, "handle"),
                        getInt(m, "type_id"),
                        getInt(m, "tile_x"),
                        getInt(m, "tile_y"),
                        getInt(m, "plane"),
                        getString(m, "name"),
                        getStringList(m, "options")))
                .toList();
    }

    @Override
    public WorldMapElements mapElements() { return mapElementsFacade; }

    @Override
    public Components components() { return componentsFacade; }

    @Override
    @SuppressWarnings("unchecked")
    public List<WorldMapElement> queryWorldMapElements(Map<String, Object> filter) {
        stubGuard.warnOnce("queryWorldMapElements");
        return rpc.callSyncList("query_world_map_elements", filter).stream()
                .map(m -> new WorldMapElement(
                        getInt(m, "id"),
                        getInt(m, "tile_x"),
                        getInt(m, "tile_y"),
                        getInt(m, "plane"),
                        getInt(m, "category"),
                        getInt(m, "sprite_id"),
                        getInt(m, "element_id"),
                        getString(m, "name"),
                        getString(m, "tooltip"),
                        getString(m, "description"),
                        getInt(m, "min_level"),
                        getInt(m, "level_tier1"),
                        getInt(m, "level_tier2"),
                        getInt(m, "level_tier3"),
                        getMapList(m, "skill_requirements").stream()
                                .map(sr -> new SkillRequirement(
                                        getInt(sr, "skill_id"),
                                        getInt(sr, "level"),
                                        getString(sr, "skill_name")))
                                .toList(),
                        getMapList(m, "resources").stream()
                                .map(rs -> new ResourceSection(
                                        getString(rs, "title"),
                                        getMapList(rs, "items").stream()
                                                .map(ri -> new ResourceItem(
                                                        getInt(ri, "item_id"),
                                                        getInt(ri, "level"),
                                                        getInt(ri, "quantity")))
                                                .toList()))
                                .toList(),
                        getMapList(m, "placements").stream()
                                .map(pl -> new WorldMapPlacement(
                                        getInt(pl, "plane"),
                                        getInt(pl, "tile_x"),
                                        getInt(pl, "tile_y"),
                                        getBool(pl, "members_only")))
                                .toList()))
                .toList();
    }

    @Override
    public List<GroundItemInfo> queryGroundItems(int centerX, int centerY, int radius, int plane, int max) {
        stubGuard.warnOnce("queryGroundItems");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tile_x", centerX);
        params.put("tile_y", centerY);
        params.put("radius", radius);
        params.put("plane", plane);
        params.put("max", max);
        return rpc.callSyncList("query_ground_items", params).stream()
                .map(m -> new GroundItemInfo(
                        getInt(m, "handle"),
                        getInt(m, "item_id"),
                        getInt(m, "quantity"),
                        getInt(m, "tile_x"),
                        getInt(m, "tile_y"),
                        getInt(m, "plane")))
                .toList();
    }

    @Override
    public LocalPlayer getLocalPlayer() {
        GameSnapshot snap = snapshot();
        return snap == null ? null : snap.self();
    }

    @Override
    public PlayerStat getPlayerStat(int skillId) {
        LocalPlayer self = getLocalPlayer();
        if (self == null) {
            return null;
        }
        for (Skill s : self.skills()) {
            if (s.typeId() == skillId) {
                return new PlayerStat(s.typeId(), s.actualLevel(), s.boostedLevel(), s.experience());
            }
        }
        return null;
    }

    private NXTCache requireCache() {
        if (cache == null) {
            throw new IllegalStateException(
                    "Config-type lookup requires NXTCache. Pass -Dnxtcache.path=<cache dir> "
                            + "and -Dnxtcache.dll=<NXTCache.dll path> when launching.");
        }
        return cache;
    }

    // ---------------------------------------------------------------- System

    @Override
    public boolean ping() {
        Map<String, Object> r = rpc.callSync("rpc.ping", Map.of());
        return getBool(r, "pong");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listMethods() {
        Object raw = rpc.callSyncRaw("rpc.list_methods", Map.of());
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @Override
    public int getClientCount() {
        Map<String, Object> r = rpc.callSync("rpc.client_count", Map.of());
        return getInt(r, "count");
    }

    // ---------------------------------------------------------------- Actions

    @Override
    public void queueAction(GameAction action) {
        rpc.callSync("queue_action", Map.of(
                "action_id", action.actionId(),
                "param1", action.param1(),
                "param2", action.param2(),
                "param3", action.param3()
        ));
    }

    @Override
    public int queueActions(List<GameAction> actions) {
        List<Map<String, Object>> actionList = actions.stream()
                .map(a -> Map.<String, Object>of(
                        "action_id", a.actionId(), "param1", a.param1(),
                        "param2", a.param2(), "param3", a.param3()))
                .toList();
        Map<String, Object> r = rpc.callSync("queue_actions", Map.of("actions", actionList));
        return getInt(r, "queued");
    }

    @Override
    public int getActionQueueSize() {
        Map<String, Object> r = rpc.callSync("get_action_queue_size", Map.of());
        return getInt(r, "size");
    }

    @Override
    public void clearActionQueue() {
        rpc.callSync("clear_action_queue", Map.of());
    }

    @Override
    public List<ActionEntry> getActionHistory(int maxResults, int actionIdFilter) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("max_results", maxResults);
        params.put("action_id_filter", actionIdFilter);
        return rpc.callSyncList("get_action_history", params).stream()
                .map(m -> new ActionEntry(
                        getInt(m, "action_id"), getInt(m, "param1"), getInt(m, "param2"), getInt(m, "param3"),
                        getLong(m, "timestamp"), getLong(m, "delta")))
                .toList();
    }

    @Override
    public long getLastActionTime() {
        Map<String, Object> r = rpc.callSync("get_last_action_time", Map.of());
        return getLong(r, "timestamp");
    }

    @Override
    public boolean areActionsBlocked() {
        Map<String, Object> r = rpc.callSync("are_actions_blocked", Map.of());
        return getBool(r, "blocked");
    }

    @Override
    public void setActionsBlocked(boolean blocked) {
        rpc.callSync("set_actions_blocked", Map.of("blocked", blocked));
    }

    // ---------------------------------------------------------------- Script execution

    @Override
    public long getScriptHandle(int scriptId) {
        Map<String, Object> r = rpc.callSync("get_script_handle", Map.of("script_id", scriptId));
        return getLong(r, "handle");
    }

    @Override
    public ScriptResult executeScript(long handle, int[] intArgs, String[] stringArgs, String[] returns) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("handle", handle);
        if (intArgs != null && intArgs.length > 0) {
            List<Integer> intList = new ArrayList<>();
            for (int a : intArgs) intList.add(a);
            params.put("int_args", intList);
        }
        if (stringArgs != null && stringArgs.length > 0) {
            params.put("string_args", List.of(stringArgs));
        }
        if (returns != null && returns.length > 0) {
            params.put("returns", List.of(returns));
        }
        Map<String, Object> r = rpc.callSync("execute_script", params);
        @SuppressWarnings("unchecked")
        List<Object> returnValues = r.containsKey("returns") ? (List<Object>) r.get("returns") : List.of();
        return new ScriptResult(returnValues);
    }

    @Override
    public void destroyScriptHandle(long handle) {
        rpc.callSync("destroy_script_handle", Map.of("handle", handle));
    }

    @Override
    public void fireKeyTrigger(int interfaceId, int componentId, String input) {
        rpc.callSync("fire_key_trigger", Map.of(
                "interface_id", interfaceId, "component_id", componentId, "input", input));
    }

    // ---------------------------------------------------------------- Interface tree walk

    @Override
    public Component getComponent(int interfaceId, int componentId) {
        // Bypass the cache when (a) it's disabled, (b) the iface id is outside
        // the version-token array, or (c) compId is negative — none of these
        // fit our (iface, comp, version) keying scheme. Out-of-range ids fall
        // through to the RPC, which is responsible for "not found" responses.
        if (componentCache == null
                || interfaceId < 0
                || interfaceId >= Layout.IFACE_VERSION_CAP
                || componentId < 0) {
            return rpcGetComponent(interfaceId, componentId);
        }
        long key = ((long) interfaceId << 32) | (componentId & 0xFFFFFFFFL);
        // Read the version BEFORE the RPC. If the producer bumps mid-RPC the
        // cached entry is stored under the older version, which the next
        // lookup will detect as a mismatch and refresh. Caching under the
        // older version is cheaper-but-correct vs reading post-RPC and
        // potentially hiding a bump that happened during the call.
        int version = ifaceVersionSource.applyAsInt(interfaceId);
        ComponentCacheEntry cached = componentCache.get(key);
        if (cached != null && cached.version == version) {
            return cached.component;
        }
        Component fetched = rpcGetComponent(interfaceId, componentId);
        if (fetched != null) {
            componentCache.put(key, new ComponentCacheEntry(version, fetched));
        } else {
            // RPC said "not found" — drop any stale entry rather than caching
            // a null sentinel. Future calls will RPC again, which is cheap
            // for the not-found path and avoids carrying negative state.
            componentCache.remove(key);
        }
        return fetched;
    }

    private Component rpcGetComponent(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_component",
                Map.of("iface", interfaceId, "comp", componentId));
        return decodeComponent(r);
    }

    @Override
    public List<ComponentTreeNode> getInterfaceTree(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_interface_tree",
                Map.of("iface", interfaceId, "comp", componentId));
        // Nodes are breadth-first; "parent" indexes an earlier slot in this same
        // list. The producer only emits resolved components, so decodeComponent
        // is non-null here — keeping every node preserves parent-index alignment.
        List<Map<String, Object>> rawNodes = getMapList(r, "nodes");
        List<ComponentTreeNode> out = new ArrayList<>(rawNodes.size());
        for (Map<String, Object> node : rawNodes) {
            out.add(new ComponentTreeNode(decodeComponent(node), getInt(node, "parent")));
        }
        return out;
    }

    /**
     * Decode one component map — from {@code get_component} or a
     * {@code get_interface_tree} node — into a {@link Component}. Returns
     * {@code null} when the producer signals "not found" via {@code iface == -1}
     * (the single-component path; tree nodes are always resolved).
     */
    private static Component decodeComponent(Map<String, Object> r) {
        int iface = getInt(r, "iface");
        if (iface < 0) {
            return null;
        }
        return new Component(
                iface,
                getInt(r, "comp"),
                getInt(r, "sub"),
                getInt(r, "type"),
                getInt(r, "category"),
                getInt(r, "x"),
                getInt(r, "y"),
                getInt(r, "w"),
                getInt(r, "h"),
                getInt(r, "raw_x"),
                getInt(r, "raw_y"),
                getInt(r, "raw_w"),
                getInt(r, "raw_h"),
                getInt(r, "x_pos_mode"),
                getInt(r, "y_pos_mode"),
                getInt(r, "x_size_mode"),
                getInt(r, "y_size_mode"),
                getInt(r, "abs_screen_pos"),
                getString(r, "text"),
                getInt(r, "hidden"),
                getInt(r, "sprite_id"),
                getInt(r, "item_id"),
                getInt(r, "item_amount"));
    }

    /** Diagnostics: number of cached (iface, comp) entries; 0 when caching is disabled. */
    public int componentCacheSize() {
        return componentCache == null ? 0 : componentCache.size();
    }

    /** Diagnostics/test hook: drop all cached components. */
    public void clearComponentCache() {
        if (componentCache != null) componentCache.clear();
    }

    @Override
    public List<Integer> getStaticChildren(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_static_children",
                Map.of("iface", interfaceId, "comp", componentId));
        return getIntList(r, "children");
    }

    @Override
    public List<Integer> getDynamicChildren(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_dynamic_children",
                Map.of("iface", interfaceId, "comp", componentId));
        return getIntList(r, "children");
    }

    // ---------------------------------------------------------------- State probes

    @Override
    public int getGameCycle() {
        Map<String, Object> r = rpc.callSync("get_game_cycle", Map.of());
        return getInt(r, "cycle");
    }

    @Override
    public LoginState getLoginState() {
        Map<String, Object> r = rpc.callSync("get_login_state", Map.of());
        return new LoginState(getInt(r, "state"), getInt(r, "login_progress"), getInt(r, "login_status"));
    }

    // ---------------------------------------------------------------- Login / breaks (mutations)

    @Override
    public void setWorld(int worldId) {
        rpc.callSync("set_world", Map.of("world_id", worldId));
    }

    @Override
    public void changeLoginState(int oldState, int newState) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (oldState > 0) params.put("old_state", oldState);
        params.put("new_state", newState);
        rpc.callSync("change_login_state", params);
    }

    @Override
    public void loginToLobby() {
        rpc.callSync("login_to_lobby", Map.of());
    }

    @Override
    public void scheduleBreak(int durationMs) {
        rpc.callSync("schedule_break", Map.of("duration", durationMs));
    }

    @Override
    public void interruptBreak() {
        rpc.callSync("interrupt_break", Map.of());
    }

    @Override
    public boolean getAutoLogin() {
        Map<String, Object> r = rpc.callSync("get_auto_login", Map.of());
        return getBool(r, "enabled");
    }

    @Override
    public void setAutoLogin(boolean enabled) {
        rpc.callSync("set_auto_login", Map.of("enabled", enabled));
    }

    // ---------------------------------------------------------------- Walker / pathfinder

    @Override
    public void walkToAsync(int x, int y) {
        rpc.callSync("walk_to", Map.of("x", x, "y", y));
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane) {
        walkWorldPathAsync(x, y, plane, false, null);
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("x", x);
        params.put("y", y);
        if (plane != 0) params.put("plane", plane);
        if (exactDestTile) params.put("exact_dest_tile", true);
        if (config != null && config != WorldPathConfig.DEFAULT) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            WorldPathConfig defaults = WorldPathConfig.DEFAULT;
            if (config.agilityLevel() > 1) {
                cfg.put("agility_level", config.agilityLevel());
            }
            if (config.maxIterations() != defaults.maxIterations()) {
                cfg.put("max_iterations", config.maxIterations());
            }
            if (!config.allowDoors()) {
                cfg.put("allow_doors", false);
            }
            if (!config.allowShortcuts()) {
                cfg.put("allow_shortcuts", false);
            }
            if (!config.allowPlaneTransitions()) {
                cfg.put("allow_plane_transitions", false);
            }
            if (!config.allowClimbovers()) {
                cfg.put("allow_climbovers", false);
            }
            if (!config.allowTransports()) {
                cfg.put("allow_transports", false);
            }
            if (!config.allowTeleports()) {
                cfg.put("allow_teleports", false);
            }
            if (config.doorCost() != defaults.doorCost()) {
                cfg.put("door_cost", config.doorCost());
            }
            if (config.transitionCost() != defaults.transitionCost()) {
                cfg.put("transition_cost", config.transitionCost());
            }
            if (config.shortcutCost() != defaults.shortcutCost()) {
                cfg.put("shortcut_cost", config.shortcutCost());
            }
            if (config.climboverCost() != defaults.climboverCost()) {
                cfg.put("climbover_cost", config.climboverCost());
            }
            if (config.transportCost() != defaults.transportCost()) {
                cfg.put("transport_cost", config.transportCost());
            }
            if (config.globalTeleportMinHeuristic() != defaults.globalTeleportMinHeuristic()) {
                cfg.put("global_teleport_min_heuristic", config.globalTeleportMinHeuristic());
            }
            if (config.heuristicWeight() != defaults.heuristicWeight()) {
                cfg.put("heuristic_weight", config.heuristicWeight());
            }
            if (!cfg.isEmpty()) {
                params.put("config", cfg);
            }
        }
        rpc.callSync("walk_world_path", params);
    }

    @Override
    public void walkCancel() {
        rpc.callSync("walk_cancel", Map.of());
    }

    @Override
    public WalkStatus getWalkStatus() {
        Map<String, Object> r = rpc.callSync("walk_status", Map.of());
        return new WalkStatus(
                getString(r, "state"),
                getInt(r, "target_x"), getInt(r, "target_y"),
                getInt(r, "current_step"), getInt(r, "total_steps"),
                getInt(r, "nav_step"), getInt(r, "total_nav_steps"),
                getBool(r, "is_walking"), getBool(r, "is_done"),
                getBool(r, "hpa_ready")
        );
    }

    @Override
    public boolean isReachable(int x, int y) {
        Map<String, Object> r = rpc.callSync("is_reachable", Map.of("x", x, "y", y));
        return getBool(r, "reachable");
    }

    @Override
    public boolean isReachable(int x, int y, int maxIterations) {
        Map<String, Object> r = rpc.callSync("is_reachable",
                Map.of("x", x, "y", y, "max_iterations", maxIterations));
        return getBool(r, "reachable");
    }

    @Override
    public PathResult findPath(int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_path", Map.of("to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public PathResult findPath(int fromX, int fromY, int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_path",
                Map.of("from_x", fromX, "from_y", fromY, "to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public PathResult findWorldPath(int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_world_path", Map.of("to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public PathResult findWorldPath(int fromX, int fromY, int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_world_path",
                Map.of("from_x", fromX, "from_y", fromY, "to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public int getRegionCacheSize() {
        Map<String, Object> r = rpc.callSync("region_cache_info", Map.of());
        return getInt(r, "cache_size");
    }

    @Override
    public void clearRegionCache() {
        rpc.callSync("region_cache_clear", Map.of());
    }

    // ---------------------------------------------------------------- Config-type lookups (NXTCache-backed)
    //
    // Each lookup goes through the embedded NXTCache.dll (sqlite + live JS5
    // fallback) instead of round-tripping over the pipe. The producer-side
    // get_*_type RPC handlers were removed alongside the nav graph CRUD —
    // these methods are the sole config-type entry point now.

    @Override
    public ItemType getItemType(int id) {
        return requireCache().getItem(id);
    }

    @Override
    public NpcType getNpcType(int id) {
        return requireCache().getNpc(id);
    }

    @Override
    public LocationType getLocationType(int id) {
        return requireCache().getLocation(id);
    }

    @Override
    public EnumType getEnumType(int id) {
        return requireCache().getEnum(id);
    }

    @Override
    public StructType getStructType(int id) {
        return requireCache().getStruct(id);
    }

    @Override
    public SequenceType getSequenceType(int id) {
        return requireCache().getSequence(id);
    }

    @Override
    public QuestType getQuestType(int id) {
        return requireCache().getQuest(id);
    }

    // ---------------------------------------------------------------- Game variables (varp / varc / varbit)
    //
    // varp / varc reads are raw producer round-trips (it walks the live variable
    // hashmap on the game thread). Varbit decoding is composed here: read the
    // varbit's base variable, then shift/mask with the bit range from the cache
    // type config — the producer never needs to know about varbits.

    @Override
    public int getVarp(int varId) {
        Map<String, Object> r = rpc.callSync("get_varp", Map.of("var_id", varId));
        return getInt(r, "value");
    }

    @Override
    public int getVarcInt(int varcId) {
        Map<String, Object> r = rpc.callSync("get_varc_int", Map.of("var_id", varcId));
        return getInt(r, "value");
    }

    @Override
    public String getVarcString(int varcId) {
        Map<String, Object> r = rpc.callSync("get_varc_string", Map.of("var_id", varcId));
        return getString(r, "value");
    }

    @Override
    public int getVarbit(int varbitId) {
        VarbitType def = requireCache().getVarbit(varbitId);
        if (def == null) {
            return -1;
        }
        // domainType 0 = player varp; a client-domain varbit reads the base varc.
        int base = def.domainType() == 0 ? getVarp(def.varId()) : getVarcInt(def.varId());
        int width = def.msb() - def.lsb() + 1;
        if (width <= 0 || width > 32) {
            return -1;
        }
        // width == 32 would make (1 << 32) wrap to 1 in Java; treat it as all bits.
        int mask = width == 32 ? -1 : (1 << width) - 1;
        return (base >>> def.lsb()) & mask;
    }

    @Override
    public List<VarbitValue> queryVarbits(List<Integer> varbitIds) {
        List<VarbitValue> out = new ArrayList<>(varbitIds.size());
        for (int id : varbitIds) {
            out.add(new VarbitValue(id, getVarbit(id)));
        }
        return out;
    }

    // ---------------------------------------------------------------- Helpers

    @SuppressWarnings("unchecked")
    private PathResult mapPathResult(Map<String, Object> r) {
        boolean found = getBool(r, "found");
        int pathLength = getInt(r, "path_length");
        List<Map<String, Object>> rawPath = getList(r, "path");
        List<int[]> path = rawPath.stream()
                .map(p -> new int[]{getInt(p, "x"), getInt(p, "y")})
                .toList();
        return new PathResult(found, pathLength, path);
    }
}
