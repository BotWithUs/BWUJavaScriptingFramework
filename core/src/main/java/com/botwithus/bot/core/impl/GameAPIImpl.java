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
import com.botwithus.bot.api.entities.Projectiles;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.model.ActionEntry;
import com.botwithus.bot.api.model.ResourceItem;
import com.botwithus.bot.api.model.ResourceSection;
import com.botwithus.bot.api.model.SkillRequirement;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.model.WorldMapPlacement;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentRef;
import com.botwithus.bot.api.model.ComponentTrigger;
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
import com.botwithus.bot.api.snapshot.DynamicRegion;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.util.NativeCache;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptGate;
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
import java.util.LinkedHashSet;
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
    private final Projectiles projectilesFacade = new Projectiles(this);
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

    /**
     * Gameval name → id resolver. Process-wide: the composition root opens one
     * index over {@code ~/.botwithus/native/gameval.sqlite} and passes the same
     * instance to every connection. Never {@code null} — callers that have no
     * index pass {@link GamevalIndex#empty()}, whose lookups all come back empty.
     */
    private final GamevalIndex gamevals;

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
    /**
     * Script that started the in-flight walk, or {@code null} when it was
     * started by a host thread. Lets {@link #walkCancel()} cancel only the
     * caller's own walk, so one script stopping doesn't strand another mid-path.
     */
    private volatile String currentWalkOwner;
    private volatile ScriptGate scriptGate;

    /**
     * Installs the per-connection gate used to attribute walks to the script
     * that started them. Unset in tests, where every caller owns every walk
     * (the pre-existing behaviour).
     */
    public void setScriptGate(ScriptGate scriptGate) {
        this.scriptGate = scriptGate;
    }

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
     * reporting of producer stubs ({@link #queryWorldMapElements}) and an
     * {@code eventPublisher} for
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
        this(rpc, cache, snapshotSource, stubGuard, eventPublisher, GamevalIndex.empty());
    }

    /**
     * Full ctor, additionally taking the shared {@link GamevalIndex} that
     * resolves gameval symbolic names. Production wiring opens one index per
     * process and hands the same instance to every connection; the shorter ctors
     * chain to {@link GamevalIndex#empty()} so tests and legacy callers keep
     * compiling and simply resolve no names.
     */
    public GameAPIImpl(RpcClient rpc, NXTCache cache,
                       Supplier<GameSnapshot> snapshotSource,
                       StubGuard stubGuard,
                       Consumer<? super GameEvent> eventPublisher,
                       GamevalIndex gamevals) {
        if (stubGuard == null) {
            throw new IllegalArgumentException("stubGuard");
        }
        if (eventPublisher == null) {
            throw new IllegalArgumentException("eventPublisher");
        }
        if (gamevals == null) {
            throw new IllegalArgumentException("gamevals");
        }
        this.rpc = rpc;
        this.cache = cache;
        this.snapshotSource = snapshotSource;
        this.stubGuard = stubGuard;
        this.eventPublisher = eventPublisher;
        this.gamevals = gamevals;
    }

    // ---------------------------------------------------------------- Snapshot + entities

    @Override
    public GameSnapshot snapshot() {
        return snapshotSource != null ? snapshotSource.get() : null;
    }

    @Override
    public GamevalIndex gamevals() { return gamevals; }

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
    public Projectiles projectiles() { return projectilesFacade; }

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

    // Builds a ComponentTrigger GameAction with the packed param convention the
    // producer's DispatchComponentTrigger expects:
    //   param1 = compHash = (iface << 16) | comp
    //   param2 = (triggerType << 16) | (sub & 0xFFFF)
    //   param3 = arg (key code, or packed (x << 16) | y press coords, or 0)
    private static GameAction triggerAction(int interfaceId, int componentId,
                                            int subId, int triggerType, int arg) {
        int compHash = (interfaceId << 16) | (componentId & 0xFFFF);
        int param2 = (triggerType << 16) | (subId & 0xFFFF);
        return new GameAction(ActionTypes.COMPONENT_TRIGGER, compHash, param2, arg);
    }

    @Override
    public void fireComponentTrigger(int interfaceId, int componentId, int subId,
                                     int triggerType, int arg) {
        queueAction(triggerAction(interfaceId, componentId, subId, triggerType, arg));
    }

    @Override
    public void fireKeyTrigger(int interfaceId, int componentId, String input) {
        if (input == null || input.isEmpty()) {
            return;
        }
        // One key (type-10) trigger per character, submitted as a single batched
        // request so a multi-char string costs one RPC round-trip, not N. The
        // producer drains the queue one action per tick (human-like cadence).
        List<GameAction> batch = new ArrayList<>(input.length());
        for (int i = 0; i < input.length(); i++) {
            batch.add(triggerAction(interfaceId, componentId, -1,
                    ActionTypes.TRIGGER_TYPE_KEY, input.charAt(i)));
        }
        queueActions(batch);
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
    public Component findComponentAt(int screenX, int screenY) {
        Map<String, Object> r = rpc.callSync("find_component_at",
                Map.of("screen_x", screenX, "screen_y", screenY));
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
                getInt(r, "item_amount"),
                getMapList(r, "triggers").stream()
                        .map(t -> new ComponentTrigger(getInt(t, "type"), getInt(t, "script_id")))
                        .toList());
    }

    @Override
    public List<Component> getComponents(List<ComponentRef> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> targets = new ArrayList<>(refs.size());
        for (ComponentRef ref : refs) {
            targets.add(List.of(ref.interfaceId(), ref.componentId()));
        }
        Map<String, Object> r = rpc.callSync("get_components", Map.of("targets", targets));
        List<Map<String, Object>> rawNodes = getMapList(r, "components");
        List<Component> out = new ArrayList<>(rawNodes.size());
        for (Map<String, Object> node : rawNodes) {
            out.add(decodeComponent(node));
        }
        return out;
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
    // (worldwalker.dll + baked artifact). The agent has no pathfinder and
    // exposes no walker RPCs — its whole contribution to movement is a single
    // queue_action WALK click per tile, which the WorldWalker executor drives
    // through WorldWalkerCallbackBridge. So the public Navigation /
    // NavigationAPI surface only does useful work when an artifact is loaded.
    //
    // The walk_arrived / walk_cancelled / walk_failed events these methods
    // publish are produced HERE, locally, from the executor's terminal status —
    // the agent never emits those ring event types.

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
        ScriptGate gate = this.scriptGate;
        String caller = gate != null ? gate.current() : null;
        String previousOwner = currentWalkOwner;
        if (previousOwner != null && caller != null && !previousOwner.equals(caller)
                && "walking".equals(currentWalkState)) {
            // Known limitation: walk state is per-connection, so there is only
            // one in-flight walk per client and starting a new one preempts
            // whoever was walking. Logged rather than silently stolen; the fix
            // is per-script walk state (tracked separately on the board).
            log.warn("Script {} is starting a walk while {} is still walking; preempting it",
                    caller, previousOwner);
        }
        cancelInFlightWalk();
        WorldWalker w = lazyWorldWalker();
        WwGoal goal = new WwGoal(x, y, plane, exactDestTile ? 0 : 1);
        AtomicBoolean cancel = new AtomicBoolean(false);
        currentWalkCancel = cancel;
        currentWalkOwner = caller;
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
                currentWalkOwner = null;
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
        if (!callerOwnsWalk()) {
            log.debug("walkCancel ignored: in-flight walk belongs to {}", currentWalkOwner);
            return;
        }
        // Cancel *and* join. The flag alone only asks the executor to stop at
        // its next poll, and it can be parked in sleepTicks or an in-flight RPC
        // for seconds — during which it keeps queueing actions. Since this is
        // what a stopping script calls via Navigation.cleanup(), returning
        // before the executor has quiesced is what let a "stopped" bot carry on
        // walking and interacting.
        cancelInFlightWalk();
    }

    /**
     * Whether the calling thread may cancel the in-flight walk. A script may
     * only cancel a walk it started; a host thread (no script tag) has
     * authority over any walk, as does any caller when no gate is wired.
     */
    private boolean callerOwnsWalk() {
        ScriptGate gate = this.scriptGate;
        if (gate == null) {
            return true;
        }
        String caller = gate.current();
        if (caller == null) {
            return true;
        }
        String owner = currentWalkOwner;
        return owner == null || owner.equals(caller);
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
            return lazyWorldWalker().query(start, goal, null, currentInstance()) != null;
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
            WwPathResult result = lazyWorldWalker().query(start, goal, null, currentInstance());
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

    /**
     * The current scene's dynamic-region grid for a one-shot query, or
     * {@code null} in an ordinary scene.
     *
     * <p>Uses {@code copyOfStable} for the same reason the executor's per-plan
     * pull does. An earlier version skipped it on the theory that a torn read
     * here only costs a wrong answer to one reachability question — but
     * {@code findPath} hands its step list straight back to callers, so a script
     * that walks those tiles turns a torn read into exactly the mis-walked route
     * the stable copy exists to prevent. The copy costs microseconds against a
     * graph search.</p>
     *
     * <p>Empty means the grid tore on every attempt; it explicitly does not mean
     * "static", so the query is answered as unreachable rather than being
     * silently planned against the overworld collision that shares this
     * instance's coordinates.</p>
     */
    private DynamicRegion currentInstance() {
        GameSnapshot snap = snapshot();
        if (snap == null) {
            return null;
        }
        DynamicRegion region = snap.dynamicRegion();
        if (region == null || !region.isInstance()) {
            return null;
        }
        if (region.isTruncated()) {
            log.warn("query in an instance whose {}x{} chunk grid the producer dropped"
                            + " (needed {} descriptors); answering unreachable",
                    region.gridW(), region.gridH(), region.requiredChunks());
            throw new WorldWalkerException("dynamic-region grid was truncated by the producer");
        }
        return DynamicRegion.copyOfStable(snap).orElseThrow(() ->
                new WorldWalkerException("dynamic-region grid tore on all "
                        + DynamicRegion.STABLE_COPY_ATTEMPTS + " copy attempts"));
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

    // VarbitType.domainType discriminator: 0 means the base variable lives in
    // the player-varp hashmap; any other value means it lives in the client-varc
    // hashmap. Wire constant defined by the cache type config, not invented here.
    private static final int VARBIT_DOMAIN_PLAYER = 0;

    // A varbit packs into [lsb, msb] of a 32-bit int; any width > 32 is malformed.
    private static final int VARBIT_MAX_WIDTH = 32;

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
        int base = def.domainType() == VARBIT_DOMAIN_PLAYER
                ? getVarp(def.varId())
                : getVarcInt(def.varId());
        return decodeVarbitBits(def, base);
    }

    @Override
    public List<Integer> getVarps(List<Integer> varIds) {
        return readVarBatch("get_varps", varIds);
    }

    @Override
    public List<Integer> getVarcInts(List<Integer> varcIds) {
        return readVarBatch("get_varcs_int", varcIds);
    }

    @Override
    public List<String> getVarcStrings(List<Integer> varcIds) {
        if (varcIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> r = rpc.callSync("get_varcs_string", Map.of("ids", varcIds));
        return getStringList(r, "values");
    }

    private List<Integer> readVarBatch(String method, List<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Object> r = rpc.callSync(method, Map.of("ids", ids));
        return getIntList(r, "values");
    }

    @Override
    public List<VarbitValue> queryVarbits(List<Integer> varbitIds) {
        if (varbitIds.isEmpty()) {
            return List.of();
        }
        // Resolve every def up front; partition into the two base-variable
        // domains so each domain takes exactly one batched round-trip
        // regardless of how many varbits share a base.
        NXTCache cache = requireCache();
        int n = varbitIds.size();
        VarbitType[] defs = new VarbitType[n];
        LinkedHashSet<Integer> varpBases = new LinkedHashSet<>();
        LinkedHashSet<Integer> varcBases = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            VarbitType def = cache.getVarbit(varbitIds.get(i));
            defs[i] = def;
            if (def == null) {
                continue;
            }
            (def.domainType() == VARBIT_DOMAIN_PLAYER ? varpBases : varcBases).add(def.varId());
        }

        Map<Integer, Integer> varpValueByBase = readBatchAsMap("get_varps", varpBases);
        Map<Integer, Integer> varcValueByBase = readBatchAsMap("get_varcs_int", varcBases);

        List<VarbitValue> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            VarbitType def = defs[i];
            int value = -1;
            if (def != null) {
                Map<Integer, Integer> values = def.domainType() == VARBIT_DOMAIN_PLAYER
                        ? varpValueByBase
                        : varcValueByBase;
                Integer base = values.get(def.varId());
                // Missing base → producer truncated the batch past its cap, or the
                // base var isn't set. -1 matches getVarbit's sentinel for "unknown".
                if (base != null) {
                    value = decodeVarbitBits(def, base);
                }
            }
            out.add(new VarbitValue(varbitIds.get(i), value));
        }
        return out;
    }

    private Map<Integer, Integer> readBatchAsMap(String method, LinkedHashSet<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Integer> keys = List.copyOf(ids);
        List<Integer> values = readVarBatch(method, keys);
        // Pair by index up to min(keys, values) so a producer-side truncation
        // leaves the dropped keys absent rather than mispaired with a wrong value.
        int paired = Math.min(keys.size(), values.size());
        Map<Integer, Integer> out = new LinkedHashMap<>(paired);
        for (int i = 0; i < paired; i++) {
            out.put(keys.get(i), values.get(i));
        }
        return out;
    }

    private static int decodeVarbitBits(VarbitType def, int base) {
        int width = def.msb() - def.lsb() + 1;
        if (width <= 0 || width > VARBIT_MAX_WIDTH) {
            return -1;
        }
        // width == 32 would make (1 << 32) wrap to 1 in Java; treat as all bits.
        int mask = width == VARBIT_MAX_WIDTH ? -1 : (1 << width) - 1;
        return (base >>> def.lsb()) & mask;
    }

    // ---------------------------------------------------------------- Obj vars

    @Override
    public Map<Integer, Map<Integer, Integer>> getObjVars(int invId) {
        Map<String, Object> r = rpc.callSync("get_obj_vars", Map.of("inv_id", invId));
        return parseObjVarSlots(r);
    }

    @Override
    public Map<Integer, Integer> getObjVars(int invId, int slot) {
        Map<String, Object> r = rpc.callSync("get_obj_vars",
                Map.of("inv_id", invId, "slot", slot));
        return parseObjVarSlots(r).getOrDefault(slot, Map.of());
    }

    // Decodes { slots:[ { slot, vars:[ { id, value } ] } ] } into
    // slot -> (varId -> value). Insertion-ordered for stable iteration.
    private static Map<Integer, Map<Integer, Integer>> parseObjVarSlots(Map<String, Object> r) {
        List<Map<String, Object>> slots = getMapList(r, "slots");
        Map<Integer, Map<Integer, Integer>> out = new LinkedHashMap<>(slots.size());
        for (Map<String, Object> s : slots) {
            List<Map<String, Object>> vars = getMapList(s, "vars");
            Map<Integer, Integer> byId = new LinkedHashMap<>(vars.size());
            for (Map<String, Object> v : vars) {
                byId.put(getInt(v, "id"), getInt(v, "value"));
            }
            out.put(getInt(s, "slot"), byId);
        }
        return out;
    }

}
