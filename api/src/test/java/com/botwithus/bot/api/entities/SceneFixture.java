package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Orientation;
import com.botwithus.bot.api.snapshot.Player;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link GameAPI} whose snapshot publishes exactly the rows a test hands it, so the entity
 * facades can be exercised through their real query path with no transport behind them.
 * Interfaces only are mocked; every row is a real snapshot record.
 */
final class SceneFixture {

    /** Flag bit the snapshot uses for a hidden loc ({@code LOC_FLAG_HIDDEN}). */
    static final int LOC_FLAG_HIDDEN = 1;
    /** Flag bit the snapshot uses for a moving NPC or player. */
    static final int FLAG_MOVING = 1;

    final GameAPI api = mock(GameAPI.class);
    final GameSnapshot snapshot = mock(GameSnapshot.class);
    private final GameSnapshot.Npcs npcs = mock(GameSnapshot.Npcs.class);
    private final GameSnapshot.Players players = mock(GameSnapshot.Players.class);
    private final GameSnapshot.Locations locations = mock(GameSnapshot.Locations.class);

    SceneFixture() {
        when(api.snapshot()).thenReturn(snapshot);
        when(snapshot.npcs()).thenReturn(npcs);
        when(snapshot.players()).thenReturn(players);
        when(snapshot.locations()).thenReturn(locations);
        withNpcs();
        withPlayers();
        withLocations();
    }

    SceneFixture withNpcs(Npc... rows) {
        List<Npc> list = List.of(rows);
        when(npcs.stream()).thenAnswer(inv -> list.stream());
        return this;
    }

    SceneFixture withPlayers(Player... rows) {
        List<Player> list = List.of(rows);
        when(players.stream()).thenAnswer(inv -> list.stream());
        return this;
    }

    SceneFixture withLocations(Location... rows) {
        List<Location> list = List.of(rows);
        when(locations.stream()).thenAnswer(inv -> list.stream());
        return this;
    }

    /** Puts the local player at a tile; {@code getLocalPlayer()} answers it. */
    SceneFixture withLocalPlayerAt(int x, int y, int plane) {
        when(api.getLocalPlayer()).thenReturn(localPlayerAt(x, y, plane));
        return this;
    }

    /** A real local-player record at a tile, with every other field unknown or empty. */
    static LocalPlayer localPlayerAt(int x, int y, int plane) {
        return new LocalPlayer(0, 0, x, y, plane, 0, -1, -1, -1, -1, -1, false, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(),
                Orientation.unknown(), LocalPlayer.RUN_UNKNOWN, LocalPlayer.RUN_UNKNOWN);
    }

    /** A direct LOCATION row with an interact id, animation and flags. */
    static Location loc(int id, int x, int y, int animationId, int flags) {
        return new Location(id, id, animationId, x, y, 0, 0, 0, flags);
    }

    /** An NPC row; {@code flags} carries {@link #FLAG_MOVING}. */
    static Npc npc(int index, int typeId, int x, int y, int flags, int animationId, int hp, int maxHp) {
        return new Npc(index, typeId, x, y, 0, flags, -1, animationId, -1, hp, maxHp, -1);
    }
}
