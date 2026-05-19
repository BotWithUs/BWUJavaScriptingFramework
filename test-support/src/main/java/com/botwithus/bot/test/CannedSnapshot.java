package com.botwithus.bot.test;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.LocationFilter;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.NpcFilter;
import com.botwithus.bot.api.snapshot.Player;
import com.botwithus.bot.api.snapshot.PlayerFilter;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Hand-built {@link GameSnapshot} for dry-run script tests.
 *
 * <p>Tests build a snapshot via {@link #empty()} (no in-game player) or
 * {@link #withSelf(LocalPlayer)} (a single local player, empty
 * tables for npcs / players / inventories) and hand it to
 * {@link MockScriptContext} via {@code withSnapshot}. The nested table
 * implementations are empty by design — tests that need populated NPCs
 * or players are expected to either subclass this record or supply
 * their own snapshot.</p>
 *
 * <p>All record components are non-null by construction; the empty
 * Npcs/Players/Inventories table records are reusable singletons.</p>
 */
public record CannedSnapshot(
        long tickId,
        int gameState,
        int ownIndex,
        LocalPlayer self,
        Npcs npcs,
        Players players,
        Locations locations,
        Inventories inventories,
        int sceneVersion
) implements GameSnapshot {

    /** Game state code returned by the producer when the client is on the login screen. */
    public static final int GAME_STATE_LOGIN = 10;

    /** Game state code returned when the client is at the lobby. */
    public static final int GAME_STATE_LOBBY = 20;

    /** Game state code returned when the client is in-game. */
    public static final int GAME_STATE_IN_GAME = 30;

    private static final long DEFAULT_TICK_ID = 0L;
    private static final int NO_LOCAL_PLAYER_INDEX = -1;
    private static final int DEFAULT_SCENE_VERSION = 0;

    private static final Npcs EMPTY_NPCS = new Npcs(List.of());
    private static final Players EMPTY_PLAYERS = new Players(List.of());
    private static final Locations EMPTY_LOCATIONS = new Locations(List.of());
    private static final Inventories EMPTY_INVENTORIES = new Inventories(List.of());

    /** Snapshot with no in-game player, no NPCs, no other players, no inventories. */
    public static CannedSnapshot empty() {
        return new CannedSnapshot(
                DEFAULT_TICK_ID,
                GAME_STATE_LOGIN,
                NO_LOCAL_PLAYER_INDEX,
                null,
                EMPTY_NPCS,
                EMPTY_PLAYERS,
                EMPTY_LOCATIONS,
                EMPTY_INVENTORIES,
                DEFAULT_SCENE_VERSION);
    }

    /** Snapshot in-game with the supplied local player and empty entity tables. */
    public static CannedSnapshot withSelf(LocalPlayer self) {
        if (self == null) {
            throw new IllegalArgumentException("self");
        }
        return new CannedSnapshot(
                DEFAULT_TICK_ID,
                GAME_STATE_IN_GAME,
                self.serverIndex(),
                self,
                EMPTY_NPCS,
                EMPTY_PLAYERS,
                EMPTY_LOCATIONS,
                EMPTY_INVENTORIES,
                DEFAULT_SCENE_VERSION);
    }

    /** Returns a copy with a different {@link #tickId()} for advancing time in tests. */
    public CannedSnapshot withTickId(long newTickId) {
        return new CannedSnapshot(newTickId, gameState, ownIndex, self,
                                  npcs, players, locations, inventories, sceneVersion);
    }

    public record Npcs(List<Npc> all) implements GameSnapshot.Npcs {
        public Npcs {
            all = List.copyOf(all);
        }

        @Override
        public int count() {
            return all.size();
        }

        @Override
        public Npc at(int index) {
            return index >= 0 && index < all.size() ? all.get(index) : null;
        }

        @Override
        public Optional<Npc> byServerIndex(int serverIndex) {
            return all.stream().filter(n -> n.serverIndex() == serverIndex).findFirst();
        }

        @Override
        public List<Npc> filter(NpcFilter filter) {
            return all.stream().filter(filter::test).toList();
        }

        @Override
        public Stream<Npc> stream() {
            return all.stream();
        }
    }

    public record Players(List<Player> all) implements GameSnapshot.Players {
        public Players {
            all = List.copyOf(all);
        }

        @Override
        public int count() {
            return all.size();
        }

        @Override
        public Player at(int index) {
            return index >= 0 && index < all.size() ? all.get(index) : null;
        }

        @Override
        public Optional<Player> byServerIndex(int serverIndex) {
            return all.stream().filter(p -> p.serverIndex() == serverIndex).findFirst();
        }

        @Override
        public List<Player> filter(PlayerFilter filter) {
            return all.stream().filter(filter::test).toList();
        }

        @Override
        public Stream<Player> stream() {
            return all.stream();
        }
    }

    public record Locations(List<Location> all) implements GameSnapshot.Locations {
        public Locations {
            all = List.copyOf(all);
        }

        @Override
        public int count() {
            return all.size();
        }

        @Override
        public Location at(int index) {
            return index >= 0 && index < all.size() ? all.get(index) : null;
        }

        @Override
        public List<Location> filter(LocationFilter filter) {
            return all.stream().filter(filter::test).toList();
        }

        @Override
        public Stream<Location> stream() {
            return all.stream();
        }
    }

    public record Inventories(List<Inventory> all) implements GameSnapshot.Inventories {
        public Inventories {
            all = List.copyOf(all);
        }

        @Override
        public int count() {
            return all.size();
        }

        @Override
        public Inventory at(int index) {
            return index >= 0 && index < all.size() ? all.get(index) : null;
        }

        @Override
        public Optional<Inventory> byInvId(int invId) {
            return all.stream().filter(inv -> inv.invId() == invId).findFirst();
        }

        @Override
        public Stream<Inventory> stream() {
            return all.stream();
        }
    }
}
