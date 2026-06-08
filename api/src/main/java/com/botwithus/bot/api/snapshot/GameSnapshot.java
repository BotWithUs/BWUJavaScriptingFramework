package com.botwithus.bot.api.snapshot;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tick-scoped read view over the producer's published game snapshot.
 *
 * <p>Obtained from the host's snapshot accessor (slice 2 wires this through
 * {@code Client#snapshot()}). All accessors read from the shared-memory
 * buffer current at the time the snapshot was acquired. The producer flips
 * buffers each tick, so a snapshot is only safe to use within the same
 * logical work unit; do not cache across ticks or across acquisitions.</p>
 *
 * <p>This interface intentionally exposes only what the producer publishes
 * via {@code SharedLayout.h} — nothing here triggers an RPC. Mutations,
 * config-type lookups, and anything that touches game logic live elsewhere.</p>
 */
public interface GameSnapshot {

    /** Monotonically increasing producer tick id. */
    long tickId();

    /**
     * Game client state. {@code 10 = login}, {@code 20 = lobby},
     * {@code 30 = in-game}; only at {@code 30} is {@link #self()} populated.
     */
    int gameState();

    /** Local player's server index, or negative if not in-game. */
    int ownIndex();

    /**
     * The local player, or {@code null} if not currently in-game. Inspect
     * {@link #gameState()} or {@link #ownIndex()} first if you need to
     * distinguish login/lobby states.
     */
    LocalPlayer self();

    /** NPC table accessor. */
    Npcs npcs();

    /** Player table accessor. */
    Players players();

    /** Scene Location table accessor (doors / trees / rocks / scenery). */
    Locations locations();

    /** Inventory table accessor. */
    Inventories inventories();

    /** Ground items table accessor (v15+). */
    GroundItems groundItems();

    /**
     * Producer-side scene-shape version. Bumps whenever the streamed
     * {@code loaded_map_squares} identity changes — region crossings, login,
     * teleport. Use as a cache key on per-region structures derived from
     * snapshot data; a change invalidates anything keyed on the prior scene.
     */
    int sceneVersion();

    /**
     * Whether {@code ifaceId} is currently mounted in
     * {@code jag::InterfaceManager}'s open-subs hashmap — the engine's
     * canonical "this interface is open right now" signal. Backed by the v14
     * SHM snapshot of the open-subs keyset (linear scan over a small
     * keyset, typically &lt;20 entries this tick); no RPC round-trip.
     *
     * <p>Use this for chain gates that need to wait for a dialog to appear
     * after a click that opens it (lodestone map, max-cape menu, dungeoneering
     * cape teleport list). HUD-style interfaces that are always mounted while
     * in-game (the minimap iface 1465 etc.) return {@code true} the whole
     * session; a per-component "is this thing clickable" check is a separate
     * question handled by {@link com.botwithus.bot.api.model.Component#hidden()}.</p>
     *
     * <p>Defaults to {@code false} so test doubles / hand-rolled stubs don't
     * have to implement it; the live {@code GameSnapshotImpl} overrides.</p>
     */
    default boolean isInterfaceOpen(int ifaceId) {
        return false;
    }

    interface Npcs {
        int count();

        Npc at(int index);

        Optional<Npc> byServerIndex(int serverIndex);

        List<Npc> filter(NpcFilter filter);

        Stream<Npc> stream();
    }

    interface Players {
        int count();

        Player at(int index);

        Optional<Player> byServerIndex(int serverIndex);

        List<Player> filter(PlayerFilter filter);

        Stream<Player> stream();
    }

    interface Locations {
        int count();

        Location at(int index);

        List<Location> filter(LocationFilter filter);

        Stream<Location> stream();
    }

    interface Inventories {
        int count();

        Inventory at(int index);

        Optional<Inventory> byInvId(int invId);

        Stream<Inventory> stream();
    }

    interface GroundItems {
        int count();

        GroundItem at(int index);

        List<GroundItem> filter(GroundItemFilter filter);

        Stream<GroundItem> stream();
    }
}
