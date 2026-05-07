package com.botwithus.bot.api;

import com.botwithus.bot.api.domain.ActionAPI;
import com.botwithus.bot.api.domain.NavigationAPI;
import com.botwithus.bot.api.domain.SystemAPI;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.Players;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.LoginState;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import java.util.List;

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
 *   <li>{@link NavigationAPI} — walker, pathfinder queries, nav graph CRUD,
 *       teleport registry</li>
 * </ul>
 *
 * <p>Reads of game state (local player, NPCs, players, inventories) and
 * of any per-tick observation (varps, components, chat history) are NOT
 * here — go through {@link Client#snapshot()} or subscribe to events on
 * the {@link com.botwithus.bot.api.event.EventBus}.</p>
 *
 * @see ScriptContext#getGameAPI()
 */
public interface GameAPI extends SystemAPI, ActionAPI, NavigationAPI {

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

    // ---------------------------------------------------------------- Local player & skills

    /**
     * Convenience accessor — equivalent to {@code snapshot().self()} but
     * named for ergonomics in scripts that don't otherwise touch the
     * snapshot. Returns {@code null} when not in-game (matches snapshot
     * semantics).
     */
    LocalPlayer getLocalPlayer();

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

    /** Fires a key-input trigger on an interface component. */
    void fireKeyTrigger(int interfaceId, int componentId, String input);

    // ---------------------------------------------------------------- Interface tree walk

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
