package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.WalkArrivedEvent;
import com.botwithus.bot.api.event.WalkCancelledEvent;
import com.botwithus.bot.api.event.WalkFailedEvent;
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
import com.botwithus.bot.core.loader.NativeCache;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.worldwalker.WorldWalker;
import com.botwithus.bot.core.worldwalker.WorldWalkerException;
import com.botwithus.bot.core.worldwalker.WwGoal;
import com.botwithus.bot.core.worldwalker.WwPathResult;
import com.botwithus.bot.core.worldwalker.WwStatus;
import com.botwithus.bot.core.worldwalker.WwStep;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getInt;
import static com.botwithus.bot.core.impl.MapHelper.getIntList;
import static com.botwithus.bot.core.impl.MapHelper.getLong;
import static com.botwithus.bot.core.impl.MapHelper.getMapList;
import static com.botwithus.bot.core.impl.MapHelper.getObjectMap;
import static com.botwithus.bot.core.impl.MapHelper.getString;
import static com.botwithus.bot.core.impl.MapHelper.getStringList;

public class GameAPIImpl implements GameAPI {

    private static final Logger log = LoggerFactory.getLogger(GameAPIImpl.class);

    private final RpcClient rpc;
    private final NXTCache cache;

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

    /**
     * Where terminal walk events ({@link WalkArrivedEvent},
     * {@link WalkCancelledEvent}, {@link WalkFailedEvent}) are published from
     * the WorldWalker executor worker. Production wiring threads
     * {@code eventBus::publish}; tests default to a no-op so existing fixtures
     * keep compiling.
     */
    private final Consumer<? super GameEvent> eventPublisher;

    private volatile WorldWalker worldWalker;
    private final Object worldWalkerLock = new Object();

    /** Worker thread running the current {@code ww_executor_run} call, or {@code null} when idle. */
    private volatile Thread walkThread;
    /** Cancel flag for the current walk. The worker's bridge polls it; null when idle. */
    private volatile AtomicBoolean currentWalkCancel;
    /** Live walk-state machine used by {@link #getWalkStatus}; one of idle / walking / arrived / failed / cancelled. */
    private volatile String currentWalkState = "idle";
    private volatile int currentWalkTargetX;
    private volatile int currentWalkTargetY;

    /** Legacy constructor used by tests; config-type lookups will throw. */
    public GameAPIImpl(RpcClient rpc) {
        this(rpc, null, null);
    }

    public GameAPIImpl(RpcClient rpc, NXTCache cache) {
        this(rpc, cache, null);
    }

    /**
     * Wires the entity-facade snapshot source. {@code snapshotSource} can be
     * {@code null} to disable snapshot-derived helpers (entity queries and
     * {@code getLocalPlayer}). Production callers pass:
     * <pre>{@code
     *   new GameAPIImpl(rpc, nxtCache,
     *       () -> new GameSnapshotImpl(region.snapshot()));
     * }</pre>
     */
    public GameAPIImpl(RpcClient rpc, NXTCache cache,
                       Supplier<GameSnapshot> snapshotSource) {
        this(rpc, cache, snapshotSource, new StubGuard(), o -> {});
    }

    public GameAPIImpl(RpcClient rpc, NXTCache cache,
                       Supplier<GameSnapshot> snapshotSource,
                       StubGuard stubGuard) {
        this(rpc, cache, snapshotSource, stubGuard, o -> {});
    }

    /**
     * Full ctor accepting a {@link StubGuard} for instrumented WARN-once
     * reporting of producer stubs ({@link #queryLocations}, {@link #queryGroundItems},
     * {@link #queryWorldMapElements}) and an {@code eventPublisher} for
     * surfacing terminal walk events from the WorldWalker executor. Production
     * wiring constructs one {@code StubGuard} per session in {@code CliContext}
     * and passes {@code eventBus::publish} as the publisher; shorter ctors
     * chain to a default {@code StubGuard} and a no-op publisher for tests
     * and legacy callers.
     */
    public GameAPIImpl(RpcClient rpc, NXTCache cache,
                       Supplier<GameSnapshot> snapshotSource,
                       StubGuard stubGuard,
                       Consumer<? super GameEvent> eventPublisher) {
        if (stubGuard == null) {
            throw new IllegalArgumentException("stubGuard");
        }
        if (eventPublisher == null) {
            throw new IllegalArgumentException("eventPublisher");
        }
        this.rpc = rpc;
        this.cache = cache;
        this.snapshotSource = snapshotSource;
        this.stubGuard = stubGuard;
        this.eventPublisher = eventPublisher;
    }

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
        // Every call reads live state: the RPC handler marshals the component
        // read onto the game thread (where the structures are quiescent) and
        // returns a fresh snapshot. There is no consumer-side cache — the
        // producer no longer publishes per-iface invalidation tokens (v13), so
        // a cache could only ever serve stale geometry/text/visibility.
        return rpcGetComponent(interfaceId, componentId);
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
    //
    // Every walker/pathfinder method routes through the in-process WorldWalker
    // (worldwalker.dll + baked artifact). The producer-side handlers
    // (walk_to, walk_world_path, walk_cancel, is_reachable, find_path,
    // find_world_path, region_cache_*) are no-op stubs — see
    // NXTLibrary/src/rpc/Handlers.cpp:299-305 — so the public Navigation /
    // NavigationAPI surface only does useful work when an artifact is loaded.

    @Override
    public void walkToAsync(int x, int y) {
        int plane = currentPlane();
        walkWorldPathAsync(x, y, plane, false, null);
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane) {
        walkWorldPathAsync(x, y, plane, false, null);
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config) {
        if (config != null && config != WorldPathConfig.DEFAULT) {
            log.debug("walkWorldPathAsync: WorldPathConfig overrides are ignored — artifact is pre-baked");
        }
        cancelInFlightWalk();
        WorldWalker w = lazyWorldWalker();
        WwGoal goal = new WwGoal(x, y, plane, exactDestTile ? 0 : 1);
        AtomicBoolean cancel = new AtomicBoolean(false);
        currentWalkCancel = cancel;
        currentWalkTargetX = x;
        currentWalkTargetY = y;
        currentWalkState = "walking";
        Consumer<? super GameEvent> publisher = eventPublisher;
        Supplier<GameSnapshot> snapSrc = snapshotSource != null ? snapshotSource : () -> null;
        WorldWalkerCallbackBridge bridge = new WorldWalkerCallbackBridge(
                this, snapSrc, cancel, e -> log.info("ww-event: {}", e), goal);
        Thread worker = Thread.ofPlatform()
                .name("ww-executor-" + System.nanoTime())
                .daemon(true)
                .unstarted(() -> runWalk(w, goal, bridge, cancel, publisher, x, y));
        walkThread = worker;
        worker.start();
    }

    private void runWalk(WorldWalker w, WwGoal goal, WorldWalkerCallbackBridge bridge,
                         AtomicBoolean cancel, Consumer<? super GameEvent> publisher, int x, int y) {
        WwStatus status = WwStatus.FAILED;
        try {
            status = w.runExecutor(goal, bridge);
            log.info("WorldWalker walk to ({},{}) finished: {}", x, y, status);
        } catch (Throwable t) {
            log.warn("WorldWalker executor threw", t);
        } finally {
            // Only update shared state if a fresh walkWorldPathAsync hasn't
            // already replaced us — a worker that outlives its cancel-join
            // window must not clobber the successor walk's "walking" state or
            // its cancel signal.
            if (currentWalkCancel == cancel) {
                switch (status) {
                    case ARRIVED   -> currentWalkState = "arrived";
                    case CANCELLED -> currentWalkState = "cancelled";
                    default        -> currentWalkState = "failed";
                }
                currentWalkCancel = null;
            }
            if (walkThread == Thread.currentThread()) {
                walkThread = null;
            }
            switch (status) {
                case ARRIVED   -> publisher.accept(new WalkArrivedEvent(x, y));
                case CANCELLED -> publisher.accept(new WalkCancelledEvent(x, y));
                default        -> publisher.accept(new WalkFailedEvent(x, y));
            }
        }
    }

    @Override
    public void walkCancel() {
        AtomicBoolean c = currentWalkCancel;
        if (c != null) {
            c.set(true);
        }
    }

    @Override
    public WalkStatus getWalkStatus() {
        String state = currentWalkState;
        boolean walking = "walking".equals(state);
        boolean done = "arrived".equals(state) || "failed".equals(state) || "cancelled".equals(state);
        boolean ready = worldWalker != null;
        return new WalkStatus(
                state,
                currentWalkTargetX, currentWalkTargetY,
                0, 0, 0, 0,
                walking, done, ready);
    }

    @Override
    public boolean isReachable(int x, int y) {
        return isReachable(x, y, 0);
    }

    @Override
    public boolean isReachable(int x, int y, int maxIterations) {
        if (maxIterations > 0) {
            log.debug("isReachable: maxIterations ignored — WorldWalker HPA* doesn't expose an iteration cap");
        }
        LocalPlayer lp = currentLocalPlayer();
        if (lp == null) {
            return false;
        }
        WwTile start = new WwTile(lp.tileX(), lp.tileY(), lp.plane());
        WwGoal goal = new WwGoal(x, y, lp.plane(), 0);
        try {
            return lazyWorldWalker().query(start, goal, null) != null;
        } catch (RuntimeException e) {
            log.debug("isReachable query failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public PathResult findPath(int toX, int toY) {
        LocalPlayer lp = currentLocalPlayer();
        if (lp == null) {
            return notFoundPath();
        }
        return runQuery(lp.tileX(), lp.tileY(), toX, toY, lp.plane());
    }

    @Override
    public PathResult findPath(int fromX, int fromY, int toX, int toY) {
        return runQuery(fromX, fromY, toX, toY, currentPlane());
    }

    @Override
    public PathResult findWorldPath(int toX, int toY) {
        return findPath(toX, toY);
    }

    @Override
    public PathResult findWorldPath(int fromX, int fromY, int toX, int toY) {
        return findPath(fromX, fromY, toX, toY);
    }

    private PathResult runQuery(int fromX, int fromY, int toX, int toY, int plane) {
        WwTile start = new WwTile(fromX, fromY, plane);
        WwGoal goal = new WwGoal(toX, toY, plane, 0);
        try {
            WwPathResult result = lazyWorldWalker().query(start, goal, null);
            if (result == null) {
                return notFoundPath();
            }
            List<int[]> tiles = new ArrayList<>(result.steps().size());
            for (WwStep step : result.steps()) {
                tiles.add(new int[]{step.targetX(), step.targetY()});
            }
            return new PathResult(true, tiles.size(), tiles);
        } catch (RuntimeException e) {
            log.debug("findPath query failed: {}", e.toString());
            return notFoundPath();
        }
    }

    private static PathResult notFoundPath() {
        return new PathResult(false, 0, List.of());
    }

    @Override
    public int getRegionCacheSize() {
        return 0;
    }

    @Override
    public void clearRegionCache() {
        // WorldWalker's artifact is a single memory-mapped file with no
        // per-region eviction; nothing to clear.
    }

    // ---------------------------------------------------------------- WorldWalker lifecycle

    private WorldWalker lazyWorldWalker() {
        WorldWalker w = worldWalker;
        if (w != null) {
            return w;
        }
        synchronized (worldWalkerLock) {
            if (worldWalker != null) {
                return worldWalker;
            }
            Path artifact = NativeCache.locateWorldWalkerArtifact()
                    .orElseThrow(() -> new IllegalStateException(
                            "WorldWalker artifact not found — set "
                                    + "-Dworldwalker.artifact=<path> or place "
                                    + NativeCache.WORLDWALKER_ARTIFACT_NAME
                                    + " under ~/.botwithus/native/"));
            try {
                worldWalker = WorldWalker.open(artifact, Runtime.getRuntime().availableProcessors());
            } catch (IOException e) {
                throw new IllegalStateException("WorldWalker artifact open failed: " + artifact, e);
            } catch (WorldWalkerException e) {
                throw new IllegalStateException("WorldWalker context pool failed", e);
            }
            return worldWalker;
        }
    }

    /**
     * Cancel any walk in flight and wait briefly for the worker to drain. Called
     * by {@link #walkWorldPathAsync} before kicking off a fresh walk, and by
     * {@link #closeWorldWalker} during shutdown.
     */
    private void cancelInFlightWalk() {
        AtomicBoolean prev = currentWalkCancel;
        if (prev != null) {
            prev.set(true);
        }
        Thread t = walkThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Release the WorldWalker handle. Idempotent; called from the CLI / app
     * shutdown hook. Cancels any in-flight walk first.
     */
    public void closeWorldWalker() {
        cancelInFlightWalk();
        synchronized (worldWalkerLock) {
            WorldWalker w = worldWalker;
            worldWalker = null;
            if (w != null) {
                w.close();
            }
        }
    }

    private int currentPlane() {
        LocalPlayer lp = currentLocalPlayer();
        return lp != null ? lp.plane() : 0;
    }

    private LocalPlayer currentLocalPlayer() {
        if (snapshotSource == null) {
            return null;
        }
        GameSnapshot snap = snapshotSource.get();
        return snap == null ? null : snap.self();
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

}
