package com.botwithus.bot.api;

import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.domain.ActionAPI;
import com.botwithus.bot.api.domain.NavigationAPI;
import com.botwithus.bot.api.domain.SystemAPI;
import com.botwithus.bot.api.domain.VariableAPI;
import com.botwithus.bot.api.entities.GroundItems;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.Players;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.LoginState;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentRef;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import java.util.List;
import java.util.Map;

/**
 * Slim RPC-shaped surface for talking to the game producer. After the
 * GameAPI rewrite (Path B) most legacy reads moved to
 * {@link com.botwithus.bot.api.snapshot.GameSnapshot} (obtained via
 * {@link Client#snapshot()}); only mutations, the two state probes that
 * the producer always handles, and the cache-type lookups (slice 5
 * stubs) live here.
 *
 * <p>Domain split:</p>
 * <ul>
 *   <li>{@link SystemAPI} — pipe ping / introspection</li>
 *   <li>{@link ActionAPI} — action queue + behavior modifiers</li>
 *   <li>{@link NavigationAPI} — walker, pathfinder queries, region cache</li>
 *   <li>{@link VariableAPI} — on-demand varp / varbit / varc reads</li>
 * </ul>
 *
 * <p>Per-tick reads of game state (local player, NPCs, players, inventories)
 * go through {@link Client#snapshot()}; transient changes (varp/varbit deltas,
 * chat) arrive as events on the {@link com.botwithus.bot.api.event.EventBus}.
 * On-demand variable reads (current value of an arbitrary varp/varbit/varc)
 * live on {@link VariableAPI} below.</p>
 *
 * @see ScriptContext#getGameAPI()
 */
public interface GameAPI extends SystemAPI, ActionAPI, NavigationAPI, VariableAPI {

    // ---------------------------------------------------------------- Snapshot

    /**
     * Returns a tick-scoped read view over the producer's published snapshot,
     * or {@code null} if no shared-memory region is bound (e.g. tests using
     * the legacy 1-/2-arg {@code GameAPIImpl} constructor). Same instance
     * shape as {@link Client#snapshot()}; surfaced here so the entity query
     * facades ({@link #npcs()}, {@link #players()}) and skill helpers
     * ({@link #getLocalPlayer()}, {@link #getPlayerStat(int)}) can read
     * snapshot fields without forcing scripts to thread {@code Client}
     * through every helper.
     *
     * <p>The returned view is bounded to the current tick — don't cache it.
     * Each call resolves the published front buffer.</p>
     */
    GameSnapshot snapshot();

    // ---------------------------------------------------------------- Entity queries

    /**
     * NPC query facade. Singleton per {@link GameAPI}; returns the same
     * instance every call so the underlying {@code NpcType} cache is shared.
     */
    Npcs npcs();

    /**
     * Player query facade. Singleton per {@link GameAPI}.
     */
    Players players();

    // ---------------------------------------------------------------- Inventory facades

    /** Backpack facade. Singleton per {@link GameAPI}. */
    Backpack backpack();

    /** Bank facade. Singleton per {@link GameAPI}. */
    Bank bank();

    /** Worn equipment facade. Singleton per {@link GameAPI}. */
    Equipment equipment();

    /**
     * Per-item obj vars for every filled slot of a container — the per-instance
     * variables an item carries in its slot's {@code ObjVarDomain}
     * (augmentation XP, charges, etc.), distinct from the global player/client
     * var domains. Read on demand over the RPC pipe (the obj-var hashmaps are
     * walked on the game thread). Returns {@code slot -> (varId -> value)}; an
     * empty map when nothing in the container carries obj vars.
     *
     * @param invId container id (e.g. 93 = backpack, 94 = equipment)
     */
    Map<Integer, Map<Integer, Integer>> getObjVars(int invId);

    /**
     * Per-item obj vars for a single container slot. Returns {@code varId ->
     * value}, or an empty map when the slot is empty or carries no obj vars.
     *
     * @param invId container id
     * @param slot  0-based slot index
     */
    Map<Integer, Integer> getObjVars(int invId, int slot);

    // ---------------------------------------------------------------- Scene queries

    /** Scene-object query facade. Singleton per {@link GameAPI}. */
    SceneObjects objects();

    /** Ground-item query facade. Singleton per {@link GameAPI}. */
    GroundItems groundItems();

    // Scene entity queries are SHM-backed as of v15 — read them via the
    // {@link #objects()} / {@link #groundItems()} facades, which in turn
    // pull from {@link #snapshot()}. The old query_locations /
    // query_ground_items RPCs were retired.

    /** World-map element query facade. Singleton per {@link GameAPI}. */
    WorldMapElements mapElements();

    /**
     * Low-level RPC: ask the producer for cache-resident world map elements
     * matching {@code filter}. The map shape is the same one
     * {@link WorldMapElements.Query} accumulates. Most scripts go through
     * {@link #mapElements()} instead. Stub-empty until producer iteration
     * lands.
     */
    List<WorldMapElement> queryWorldMapElements(Map<String, Object> filter);

    /**
     * Single world-map element by {@link WorldMapElement#id() id}, or
     * {@code null} when not found. Default implementation scans the
     * {@link #queryWorldMapElements unfiltered query} result client-side, so
     * it inherits the producer-side stub-empty behaviour — once cache
     * iteration lands, this returns real data without a code change.
     *
     * <p>Convenience for callers that already know the id (e.g. resolving a
     * named-location catalog entry). For map searches with filters use
     * {@link #mapElements()}.</p>
     */
    default WorldMapElement getWorldMapElement(int id) {
        for (WorldMapElement e : queryWorldMapElements(Map.of())) {
            if (e.id() == id) return e;
        }
        return null;
    }

    // ---------------------------------------------------------------- Local player & skills

    /**
     * Convenience accessor — equivalent to {@code snapshot().self()} but
     * named for ergonomics in scripts that don't otherwise touch the
     * snapshot. Returns {@code null} when not in-game (matches snapshot
     * semantics).
     */
    LocalPlayer getLocalPlayer();

    /**
     * No-plane convenience for {@link NavigationAPI#walkWorldPathAsync(int, int, int)}
     * — uses the local player's current plane, falling back to plane 0 when
     * not in-game. Lives here (not on {@code NavigationAPI}) because it
     * needs {@link #getLocalPlayer()}.
     */
    default void walkWorldPath(int x, int y) {
        LocalPlayer lp = getLocalPlayer();
        walkWorldPathAsync(x, y, lp == null ? 0 : lp.plane());
    }

    /**
     * Returns one of the local player's skill stats by skill type id.
     * {@code null} when not in-game or the skill isn't in the published
     * skills array. Ids match the in-game {@code StatType.id} (e.g. 26
     * for Divination, 6 for Magic).
     *
     * <p>Read out of the snapshot — no RPC round-trip.</p>
     */
    PlayerStat getPlayerStat(int skillId);

    // ---------------------------------------------------------------- State probes

    /**
     * Returns the producer's monotonically increasing game cycle counter.
     * Backed by the {@code get_game_cycle} RPC.
     */
    int getGameCycle();

    /**
     * Returns the producer's current login state. Backed by the
     * {@code get_login_state} RPC.
     */
    LoginState getLoginState();

    // ---------------------------------------------------------------- Login / breaks (mutations)

    /** Initiates a world hop to the given world id. */
    void setWorld(int worldId);

    /**
     * Requests a login state transition.
     *
     * @param oldState the expected current state, or {@code <= 0} to skip the check
     * @param newState the desired new state
     */
    void changeLoginState(int oldState, int newState);

    /**
     * Triggers a login from the login screen to the lobby.
     *
     * @throws RuntimeException if the client is not on the login screen
     */
    void loginToLobby();

    /** Schedules a break (action-queue gate) for the given duration. */
    void scheduleBreak(int durationMs);

    /** Interrupts any currently active break. */
    void interruptBreak();

    /** Returns whether the producer's auto-login state machine is enabled. */
    boolean getAutoLogin();

    /** Enables or disables the producer's auto-login state machine. */
    void setAutoLogin(boolean enabled);

    // ---------------------------------------------------------------- Client script execution

    /**
     * Obtains a producer-side handle to a client script for repeated execution.
     * Release with {@link #destroyScriptHandle(long)} when done.
     */
    long getScriptHandle(int scriptId);

    /**
     * Executes a previously-obtained client script handle.
     *
     * @param handle     handle from {@link #getScriptHandle(int)}
     * @param intArgs    integer args, or {@code null}
     * @param stringArgs string args, or {@code null}
     * @param returns    expected return-type descriptors, or {@code null}
     */
    ScriptResult executeScript(long handle, int[] intArgs, String[] stringArgs, String[] returns);

    /** Releases a script handle. */
    void destroyScriptHandle(long handle);

    /**
     * Fires a component's CS2 event trigger (click / key / ...) by event type,
     * reproducing the engine's own component-event path. Enqueued on the action
     * queue and run on the game thread; a component that carries no trigger of
     * the requested type is a silent no-op.
     *
     * @param interfaceId owning interface id
     * @param componentId component id
     * @param subId       sub-component id, or {@code -1} for the top-level component
     * @param triggerType event type — {@code 9} = click, {@code 10} = key
     *                    (see {@code ActionTypes.TRIGGER_TYPE_*})
     * @param arg         type-dependent argument: key code for key triggers;
     *                    packed {@code (x << 16) | y} component-relative press
     *                    coordinates for click triggers; {@code 0} if unused
     */
    void fireComponentTrigger(int interfaceId, int componentId, int subId, int triggerType, int arg);

    /**
     * Fires a key (type-10) CS2 trigger on a component once per character of
     * {@code input}, submitted as a single batched action-queue request (one RPC
     * round-trip). Characters dispatch one per game tick. Convenience over
     * {@link #fireComponentTrigger} for the common "type into a component" case.
     */
    void fireKeyTrigger(int interfaceId, int componentId, String input);

    // ---------------------------------------------------------------- Interface components

    /**
     * Interface-component query facade — the high-level, fluent entry point for
     * inspecting interface components (parallel to {@link #npcs()} /
     * {@link #mapElements()}). Singleton per {@link GameAPI}. Most scripts use
     * this rather than the low-level {@link #getComponent} /
     * {@link #getInterfaceTree} primitives below.
     */
    Components components();

    // ---------------------------------------------------------------- Interface tree walk (low-level)

    /**
     * Returns the component at {@code (interfaceId, componentId)} or
     * {@code null} when no such component is loaded. Each call is a pipe
     * round-trip — Phase 1 has no consumer-side cache.
     *
     * <p>Phase 1 starter set covers identity + post-layout geometry only;
     * text, item, sprite, hidden, color, font and other per-type fields
     * are not yet surfaced and arrive in later slices.</p>
     */
    Component getComponent(int interfaceId, int componentId);

    /**
     * Batch counterpart of {@link #getComponent(int, int)}. The producer
     * walks every target on a single game-thread visit, collapsing the
     * latency of N independent {@code getComponent} calls (one tick each)
     * into one tick total.
     *
     * <p>The producer hard-caps the per-call batch (currently 64). Oversize
     * inputs are silently truncated by the producer, so the returned list
     * may be shorter than {@code refs}; callers that need more than the cap
     * should split into chunks themselves.</p>
     *
     * @param refs components to resolve, in the desired result order
     * @return one element per resolved ref, in input order; {@code null}
     *         entries signal "not found" (matches the single-component
     *         contract). Length may be less than {@code refs.size()} when
     *         the producer cap is exceeded.
     */
    List<Component> getComponents(List<ComponentRef> refs);

    /**
     * Returns the static (cache-defined) child component ids of
     * {@code (interfaceId, componentId)}. Each call is a pipe round-trip — Phase 1
     * has no consumer-side cache, so don't loop this in tight code yet.
     *
     * <p>The list contains each child's own {@code componentId} within the same
     * interface; entries equal to {@code -1} indicate a child whose packed
     * address slot is unset (the producer surfaces the game's {@code 0xFFFF}
     * sentinel as -1). Returns an empty list when the parent is unresolvable
     * (interface not loaded, component slot empty, or no static children).</p>
     */
    List<Integer> getStaticChildren(int interfaceId, int componentId);

    /**
     * Returns the dynamic (script-spawned) child component ids of
     * {@code (interfaceId, componentId)}. Same shape and caveats as
     * {@link #getStaticChildren(int, int)}; this is the path embedded
     * dropdowns and other transient sub-trees travel through.
     */
    List<Integer> getDynamicChildren(int interfaceId, int componentId);

    /**
     * Returns the component at {@code (interfaceId, componentId)} and its entire
     * descendant subtree, flattened breadth-first, in a single pipe round-trip.
     * Empty list when the root doesn't resolve.
     *
     * <p>Each {@link ComponentTreeNode} carries its component plus a
     * {@code parentIndex} into the same list ({@code -1} for the root). The
     * producer follows the live {@code Component*} children vectors, so the walk
     * spans mounted sub-interface boundaries — a child's own interface id rides
     * along in {@code component.ifaceId()}, which per-node
     * {@link #getStaticChildren} cannot follow.</p>
     *
     * <p>Most scripts use {@link #components()} instead; this is the seam the
     * {@link Components} facade drives.</p>
     */
    List<ComponentTreeNode> getInterfaceTree(int interfaceId, int componentId);

    /**
     * Locate the deepest visible component under a screen-space coordinate, or
     * {@code null} when nothing is there. Coordinates are raw screen pixels
     * (the value of Win32 {@code GetCursorPos}); the producer runs inside the
     * game process and converts to client-window space itself.
     *
     * <p>The producer walks every open sub-interface on the game thread and
     * picks the component with the greatest tree depth whose post-layout AABB
     * contains the point — ties broken by the smaller AABB. Hidden components
     * are excluded. Useful for debugger "pick on screen" tools and for
     * scripters who know a feature's location but not its component id.</p>
     */
    Component findComponentAt(int screenX, int screenY);

    // ---------------------------------------------------------------- Config-type lookups (slice 5: stubs)

    /**
     * @apiNote slice 5 stub — producer returns a sentinel response until
     *          the real cache-backed lookup lands.
     */
    ItemType getItemType(int id);

    /** @apiNote slice 5 stub — see {@link #getItemType(int)}. */
    NpcType getNpcType(int id);

    /** @apiNote slice 5 stub — see {@link #getItemType(int)}. */
    LocationType getLocationType(int id);

    /** @apiNote slice 5 stub — see {@link #getItemType(int)}. */
    EnumType getEnumType(int id);

    /** @apiNote slice 5 stub — see {@link #getItemType(int)}. */
    StructType getStructType(int id);

    /** @apiNote slice 5 stub — see {@link #getItemType(int)}. */
    SequenceType getSequenceType(int id);

    /** @apiNote slice 5 stub — see {@link #getItemType(int)}. */
    QuestType getQuestType(int id);
}
