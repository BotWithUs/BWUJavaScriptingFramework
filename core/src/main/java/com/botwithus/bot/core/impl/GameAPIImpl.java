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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Lease owner for a thread carrying no script tag: host threads, and
     * management-script threads, which are deliberately untagged. Angle
     * brackets keep it out of the namespace of real script names.
     */
    private static final String HOST_OWNER = "<host>";

    /**
     * How long a cancel waits for the walk executor to drain before taking the
     * lease away from it anyway. The executor can be parked in an in-flight RPC
     * or a tick sleep, so the cancel flag alone is not a stop; without a bound
     * here a stuck executor would wedge every later walk on the connection.
     */
    private static final long WALK_JOIN_TIMEOUT_MS = 2000L;

    /**
     * How many times an acquire will re-try after the lease changes hands
     * underneath it. Bounded rather than unbounded so a pair of scripts
     * preempting each other cannot spin here; running out is reported as a
     * refusal like any other.
     */
    private static final int LEASE_ACQUIRE_ATTEMPTS = 3;

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

    /**
     * The connection's single movement lease, or {@code null} when nobody is
     * walking. One {@code GameAPIImpl} serves every script on a connection and
     * they all drive the same character, so the lease — not the script — is
     * what a walk is attributed to. See {@link WalkLease} for why a second
     * concurrent walk is refused instead of interleaved.
     */
    private final AtomicReference<WalkLease> activeWalk = new AtomicReference<>();

    /**
     * Each owner's most recent terminal walk state, so {@link #getWalkStatus()}
     * answers "what happened to <em>my</em> walk" rather than "what happened to
     * whoever walked last". Holds one small entry per script that has ever
     * walked on this connection.
     */
    private final Map<String, WalkOutcome> lastOutcome = new ConcurrentHashMap<>();

    /**
     * Issue order for walk requests on this connection. Stamped on every lease
     * and every refusal so out-of-order reports can be ranked — see
     * {@link #recordOutcome}.
     */
    private final AtomicLong walkSeq = new AtomicLong();

    /**
     * How many requests each owner has had refused, backing
     * {@link #walkRefusalCount()}. Monotonic per owner and deliberately never
     * cleared, not even on teardown: a caller detects its own refusal by
     * comparing the count across a request, so a reset landing mid-request
     * would leave the count lower afterwards than before and the refusal would
     * be <em>missed</em> — sending the caller off to wait out its full timeout
     * for a walk that never started.
     */
    private final Map<String, AtomicLong> walkRefusals = new ConcurrentHashMap<>();

    private volatile ScriptGate scriptGate;

    /**
     * Installs the per-connection gate used to attribute walks to the script
     * that started them. Unset in tests, where every caller is treated as the
     * host and so owns every walk (the pre-existing behaviour).
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
        // Resolve the owner here, on the caller's own thread. The executor runs
        // on a thread of our making and only inherits a copy of the tag, so
        // asking the gate from inside the worker would answer a question about
        // the worker rather than about who asked for the walk.
        String owner = callerOwner();
        WalkLease lease = acquireWalkLease(owner, x, y);
        if (lease == null) {
            return;
        }
        WorldWalker w;
        try {
            w = lazyWorldWalker();
        } catch (RuntimeException e) {
            // Nothing was started, so hand the lease straight back rather than
            // leaving the character unwalkable for the rest of the session.
            releaseWalkLease(lease, WalkState.FAILED);
            throw e;
        }
        WwGoal goal = new WwGoal(x, y, plane, exactDestTile ? 0 : 1);
        Consumer<? super GameEvent> publisher = eventPublisher;
        Supplier<GameSnapshot> snapSrc = snapshotSource != null ? snapshotSource : () -> null;
        WorldWalkerCallbackBridge bridge = new WorldWalkerCallbackBridge(
                this, snapSrc, lease.cancel(), e -> log.info("ww-event: {}", e), goal);
        // rule-exception: platform thread rather than virtual — see CLAUDE.md,
        // "Script runner threads are platform threads". This executor is
        // spawned from a script's own thread and inherits its gate tag, which
        // is what keeps its RPC calls attributable to the script that started
        // it.
        Thread worker = Thread.ofPlatform()
                .name("ww-executor-" + System.nanoTime())
                .daemon(true)
                .unstarted(() -> runWalk(w, goal, bridge, lease, publisher, x, y));
        startWalkWorker(lease, worker);
    }

    /**
     * Attaches {@code worker} to {@code lease} and starts it — unless the lease
     * was cancelled while the caller was still getting ready.
     *
     * <p>Handing the character over takes two steps that cannot be collapsed
     * into one. The lease goes up as soon as the contention is decided, but the
     * worker cannot exist until the pathfinder is open, and on the first walk of
     * a session that means mapping the artifact and building a search-context
     * pool. A cancel arriving in that gap finds no thread to join, so on its own
     * it would withdraw the lease and let a second script start walking while
     * this one went on to start its executor anyway — two of them clicking into
     * one action queue, which is precisely what the lease exists to stop.</p>
     *
     * <p>The two sides therefore interlock on the cancel flag: this method
     * writes the worker and then reads the flag, while a canceller writes the
     * flag and then reads the worker. Whichever writes second sees the other's
     * write, so the walk is either joined there or never started here.</p>
     *
     * <p>Package-private so the race is testable without the native library.</p>
     *
     * @return {@code true} if the worker was started
     */
    boolean startWalkWorker(WalkLease lease, Thread worker) {
        lease.worker().set(worker);
        if (lease.cancel().get()) {
            // The canceller owns the teardown from here: it withdraws the lease
            // and records the outcome. All that is left is not to start.
            log.debug("Walk for {} was cancelled before its executor started", lease.owner());
            return false;
        }
        worker.start();
        return true;
    }

    /**
     * Takes the connection's movement lease for {@code owner}, or refuses.
     *
     * <p>Re-targeting your own walk is normal and stays legal: an owner
     * preempting itself cancels and joins its own executor first. A host
     * caller may take the lease from anyone, and so may anyone when the
     * incumbent has been revoked — a revoked script's executor is already
     * dying and will never release the lease itself, so without that branch the
     * character would stay unwalkable for the rest of the session.</p>
     *
     * <p>Everyone else is refused. The incumbent is left running and no thread
     * is started; the refusal is recorded against the caller so its own
     * {@link #getWalkStatus()} reports it.</p>
     *
     * <p>Deliberately runs before {@link #lazyWorldWalker()}: the whole
     * contention decision is made here, with no native library involved, which
     * is both cheaper on the refusal path and what makes the contract testable
     * headlessly. Package-private for that test.</p>
     *
     * @return the acquired lease, or {@code null} if the request was refused
     */
    WalkLease acquireWalkLease(String owner, int x, int y) {
        for (int attempt = 0; attempt < LEASE_ACQUIRE_ATTEMPTS; attempt++) {
            WalkLease incumbent = activeWalk.get();
            if (incumbent == null) {
                WalkLease lease = WalkLease.forOwner(owner, walkSeq.incrementAndGet(), x, y);
                if (activeWalk.compareAndSet(null, lease)) {
                    return lease;
                }
                continue;
            }
            if (!mayTakeWalk(incumbent, owner)) {
                log.warn("Refusing walk to ({},{}) for {}: {} is already walking"
                                + " this character to ({},{})",
                        x, y, owner, incumbent.owner(), incumbent.targetX(), incumbent.targetY());
                return refuseWalk(owner, x, y);
            }
            cancelAndRelease(incumbent);
        }
        log.warn("Refusing walk to ({},{}) for {}: the movement lease changed hands"
                        + " {} times while acquiring it",
                x, y, owner, LEASE_ACQUIRE_ATTEMPTS);
        return refuseWalk(owner, x, y);
    }

    /**
     * Records a refusal against {@code owner} and bumps its refusal count;
     * always returns {@code null}.
     *
     * <p>The count is the edge a caller watches, and the recorded outcome is
     * the level {@code getWalkStatus} reports. Both are written here so the two
     * can never disagree about whether a refusal happened.</p>
     */
    private WalkLease refuseWalk(String owner, int x, int y) {
        recordOutcome(owner, new WalkOutcome(WalkState.REFUSED_BUSY, x, y, walkSeq.incrementAndGet()));
        walkRefusals.computeIfAbsent(owner, k -> new AtomicLong()).incrementAndGet();
        return null;
    }

    @Override
    public long walkRefusalCount() {
        AtomicLong refusals = walkRefusals.get(callerOwner());
        return refusals == null ? 0L : refusals.get();
    }

    private void runWalk(WorldWalker w, WwGoal goal, WorldWalkerCallbackBridge bridge,
                         WalkLease lease, Consumer<? super GameEvent> publisher, int x, int y) {
        WwStatus status = WwStatus.FAILED;
        try {
            status = w.runExecutor(goal, bridge);
            log.info("WorldWalker walk to ({},{}) finished: {}", x, y, status);
        } catch (Throwable t) {
            log.warn("WorldWalker executor threw", t);
        } finally {
            // Releases only if we are still the holder — a worker that outlives
            // its cancel-join window must not clobber the state of whoever took
            // the lease next.
            releaseWalkLease(lease, terminalState(status));
            switch (status) {
                case ARRIVED   -> publisher.accept(new WalkArrivedEvent(x, y));
                case CANCELLED -> publisher.accept(new WalkCancelledEvent(x, y));
                default        -> publisher.accept(new WalkFailedEvent(x, y));
            }
        }
    }

    private static WalkState terminalState(WwStatus status) {
        return switch (status) {
            case ARRIVED   -> WalkState.ARRIVED;
            case CANCELLED -> WalkState.CANCELLED;
            default        -> WalkState.FAILED;
        };
    }

    @Override
    public void walkCancel() {
        WalkLease lease = activeWalk.get();
        if (lease == null) {
            return;
        }
        if (!callerOwnsWalk(lease, callerOwner())) {
            log.debug("walkCancel ignored: the in-flight walk belongs to {}", lease.owner());
            return;
        }
        cancelAndRelease(lease);
    }

    /**
     * The lease owner for the calling thread.
     *
     * <p>A thread with no script tag is the host, and the host has authority
     * over every script's walk. Management-script threads are deliberately
     * untagged — a management script is cross-client by design, so there is no
     * per-connection gate to tag it with — which means orchestrator code walks
     * with host authority on every client it drives. That is intended: an
     * orchestrator that cannot move a character it is managing would be unable
     * to do its job.</p>
     *
     * <p>With no gate wired at all (tests) every caller is the host, which
     * preserves the pre-existing "every caller owns every walk" behaviour.</p>
     *
     * <p>Package-private so the lease test can pair it with
     * {@link #acquireWalkLease} exactly as the production path does.</p>
     */
    String callerOwner() {
        ScriptGate gate = this.scriptGate;
        String caller = gate != null ? gate.current() : null;
        return caller != null ? caller : HOST_OWNER;
    }

    /**
     * Whether {@code owner} may cancel {@code lease}: its own walk, or any walk
     * if it has host authority.
     */
    private boolean callerOwnsWalk(WalkLease lease, String owner) {
        return HOST_OWNER.equals(owner) || owner.equals(lease.owner());
    }

    /** Whether {@code owner} may take the lease away from {@code incumbent}. */
    private boolean mayTakeWalk(WalkLease incumbent, String owner) {
        if (callerOwnsWalk(incumbent, owner)) {
            return true;
        }
        ScriptGate gate = this.scriptGate;
        return gate != null && gate.isRevoked(incumbent.owner());
    }

    @Override
    public WalkStatus getWalkStatus() {
        String owner = callerOwner();
        WalkLease lease = activeWalk.get();
        boolean ready = worldWalker != null;
        if (lease != null && callerOwnsWalk(lease, owner)) {
            return walkStatus(WalkState.WALKING, lease.targetX(), lease.targetY(), ready);
        }
        WalkOutcome outcome = lastOutcome.getOrDefault(owner, WalkOutcome.NONE);
        return walkStatus(outcome.state(), outcome.targetX(), outcome.targetY(), ready);
    }

    private static WalkStatus walkStatus(WalkState state, int targetX, int targetY, boolean ready) {
        return new WalkStatus(
                state.wireName(),
                targetX, targetY,
                0, 0, 0, 0,
                state == WalkState.WALKING, state.isDone(), ready);
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
     * Cancel a lease, wait briefly for its executor to drain, then hand the
     * lease back.
     *
     * <p>Cancel <em>and</em> join. The flag alone only asks the executor to
     * stop at its next poll, and it can be parked in a tick sleep or an
     * in-flight RPC for seconds — during which it keeps queueing actions. Since
     * this is what a stopping script reaches through {@code Navigation.cleanup},
     * returning before the executor has quiesced is what let a "stopped" bot
     * carry on walking and interacting.</p>
     */
    private void cancelAndRelease(WalkLease lease) {
        lease.cancel().set(true);
        Thread worker = lease.worker().get();
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(WALK_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseWalkLease(lease, WalkState.CANCELLED);
    }

    /**
     * Hand {@code lease} back: publish its owner's outcome first, then withdraw
     * the lease.
     *
     * <p>The order is the point. {@link #getWalkStatus()} reads the active
     * lease and the recorded outcome as two separate reads, so withdrawing
     * first leaves a hole in which a poll sees neither — a walk that has both
     * ended and never started, which reads back as {@code idle}. A
     * {@code while (!getWalkStatus().isDone())} spins an extra round on that,
     * and a {@code if (!isWalking()) restart()} re-issues the walk. Publishing
     * first closes it: a caller that sees the lease gone is guaranteed by the
     * release's own ordering to see the outcome that went with it.</p>
     *
     * <p>Publishing unconditionally is what makes that safe to do before the
     * hand-back, and it is also what opens the stale-stamp risk the old
     * CAS-guarded write closed by accident: a worker that outlived its
     * cancel-join window reports <em>after</em> the walk that replaced it.
     * {@link #recordOutcome} settles that by sequence rather than by arrival
     * order, so the late report loses.</p>
     *
     * <p>Package-private: this is what the executor calls on its way out, and
     * the late-report ordering is testable here without one.</p>
     */
    void releaseWalkLease(WalkLease lease, WalkState outcome) {
        recordOutcome(lease.owner(),
                new WalkOutcome(outcome, lease.targetX(), lease.targetY(), lease.seq()));
        activeWalk.compareAndSet(lease, null);
    }

    /**
     * Record {@code candidate} as {@code owner}'s outcome, keeping whichever
     * record describes the <em>newer</em> request rather than whichever is
     * written last — and, for two reports of the <em>same</em> request, the
     * first one rather than the last.
     *
     * <p>Both halves matter, and they close different holes. Ranking by
     * sequence stops a worker that lost its lease from stamping over the walk
     * that replaced it. Letting the first writer win a tie stops that same
     * worker from stamping over <em>its own</em> release: when a cancel's join
     * times out, the cancel records {@code cancelled} and hands the lease back,
     * and the executor reports its own terminal status seconds later carrying
     * the identical sequence. Whoever gave up on it already said what happened;
     * the straggler does not get to revise it to {@code arrived}.</p>
     *
     * @see #releaseWalkLease
     */
    private void recordOutcome(String owner, WalkOutcome candidate) {
        lastOutcome.merge(owner, candidate,
                (existing, fresh) -> fresh.seq() > existing.seq() ? fresh : existing);
    }

    /**
     * Release the WorldWalker handle. Idempotent; called from the CLI / app
     * shutdown hook. Cancels any in-flight walk first and drops every owner's
     * recorded outcome, so a reconnect starts from a clean slate.
     *
     * <p>The refusal counters are the one thing that deliberately survives —
     * see {@link #walkRefusals} for why resetting them is worse than letting
     * them run on.</p>
     */
    public void closeWorldWalker() {
        WalkLease lease = activeWalk.get();
        if (lease != null) {
            // cancelAndRelease withdraws exactly this lease. Forcing the slot to
            // null afterwards would silently discard a lease taken in between,
            // orphaning an executor nobody has cancelled.
            cancelAndRelease(lease);
        }
        lastOutcome.clear();
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
