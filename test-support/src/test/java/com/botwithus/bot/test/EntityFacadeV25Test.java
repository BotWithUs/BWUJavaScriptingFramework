package com.botwithus.bot.test;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.Player;
import com.botwithus.bot.api.entities.Players;
import com.botwithus.bot.api.model.EntityKind;
import com.botwithus.bot.api.model.EntityRead;
import com.botwithus.bot.api.model.EntityReadStatus;
import com.botwithus.bot.api.model.Headbar;
import com.botwithus.bot.api.model.OverheadText;
import com.botwithus.bot.api.snapshot.Orientation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wire-v25 members of the script-facing {@code Npc} and {@code Player}, reached the way a script
 * reaches them: through the query facades over a {@link CannedSnapshot}.
 *
 * <p>Only the matching {@link EntityKind} and index are stubbed. A facade that asked the wrong table
 * gets {@code null} from the mock and fails here, where against the agent it would read another
 * entity or be refused, and a refusal collapses to empty and looks like silence.</p>
 *
 * <p>rule-exception: {@code {rule:no-fqn}}: the snapshot records share their simple names with the
 * wrappers under test, the same collision the wrapper classes themselves document.</p>
 */
class EntityFacadeV25Test {

    private static final int INDEX = 42;
    private static final int VISUAL_X = 3222;
    private static final int VISUAL_Y = 3235;
    private static final int SERVER_X = 3225;
    private static final int SERVER_Y = 3236;
    private static final int SERVER_PLANE = 2;
    private static final int UNKNOWN = com.botwithus.bot.api.snapshot.Player.SERVER_TILE_UNKNOWN;
    private static final String SPEECH = "Cheep!";
    private static final OverheadText OVERHEAD = new OverheadText(SPEECH, 1500L);
    private static final List<Headbar> BARS = List.of(new Headbar(Headbar.HEALTH_DEFAULT, 200, 100, 7, 30));

    private GameAPI api;

    @BeforeEach
    void stubSnapshot() {
        api = mock(GameAPI.class);
    }

    @Test
    void player_forwardsAllFourServerTileGetters() {
        Player player = playerWithServerTile(SERVER_X, SERVER_Y, SERVER_PLANE);

        assertEquals(SERVER_X, player.serverTileX());
        assertEquals(SERVER_Y, player.serverTileY());
        assertEquals(SERVER_PLANE, player.serverPlane());
        assertTrue(player.hasServerTile());
        assertEquals(VISUAL_X, player.tileX(), "tileX stays the visual tile");
        assertEquals(VISUAL_Y, player.tileY(), "tileY stays the visual tile");
    }

    @Test
    void player_withAnUnknownServerTile_saysSo() {
        Player player = playerWithServerTile(UNKNOWN, UNKNOWN, UNKNOWN);

        assertFalse(player.hasServerTile());
        assertEquals(UNKNOWN, player.serverTileX());
        assertEquals(UNKNOWN, player.serverTileY());
        assertEquals(UNKNOWN, player.serverPlane());
    }

    /** One unknown field is enough: the three fields are one reading. */
    @Test
    void player_withOneUnknownField_hasNoServerTile() {
        assertFalse(playerWithServerTile(SERVER_X, SERVER_Y, UNKNOWN).hasServerTile());
        assertFalse(playerWithServerTile(SERVER_X, UNKNOWN, SERVER_PLANE).hasServerTile());
        assertFalse(playerWithServerTile(UNKNOWN, SERVER_Y, SERVER_PLANE).hasServerTile());
    }

    @Test
    void player_getOverheadText_asksThePlayerTableAtItsIndex() {
        Player player = playerWithServerTile(SERVER_X, SERVER_Y, SERVER_PLANE);
        when(api.getOverheadText(EntityKind.PLAYER, INDEX)).thenReturn(EntityRead.ok(Optional.of(OVERHEAD), false));

        assertEquals(Optional.of(SPEECH), player.getOverheadText());
    }

    @Test
    void player_getHeadbars_asksThePlayerTableAtItsIndex() {
        Player player = playerWithServerTile(SERVER_X, SERVER_Y, SERVER_PLANE);
        when(api.getHeadbars(EntityKind.PLAYER, INDEX)).thenReturn(EntityRead.ok(BARS, false));

        assertEquals(BARS, player.getHeadbars());
    }

    @Test
    void npc_getOverheadText_asksTheNpcTableAtItsIndex() {
        Npc npc = npc();
        when(api.getOverheadText(EntityKind.NPC, INDEX)).thenReturn(EntityRead.ok(Optional.of(OVERHEAD), false));

        assertEquals(Optional.of(SPEECH), npc.getOverheadText());
    }

    @Test
    void npc_getHeadbars_asksTheNpcTableAtItsIndex() {
        Npc npc = npc();
        when(api.getHeadbars(EntityKind.NPC, INDEX)).thenReturn(EntityRead.ok(BARS, false));

        assertEquals(BARS, npc.getHeadbars());
    }

    /** The documented collapse, on beta as anywhere: empty, never a throw. */
    @Test
    void refusedReads_collapseToEmpty() {
        Npc npc = npc();
        Player player = playerWithServerTile(UNKNOWN, UNKNOWN, UNKNOWN);
        EntityRead<Optional<OverheadText>> noText =
                EntityRead.refused(EntityReadStatus.CHANNEL_UNSUPPORTED, Optional.empty(), "channel_unsupported");
        EntityRead<List<Headbar>> noBars =
                EntityRead.refused(EntityReadStatus.CHANNEL_UNSUPPORTED, List.of(), "channel_unsupported");
        when(api.getOverheadText(EntityKind.NPC, INDEX)).thenReturn(noText);
        when(api.getOverheadText(EntityKind.PLAYER, INDEX)).thenReturn(noText);
        when(api.getHeadbars(EntityKind.NPC, INDEX)).thenReturn(noBars);
        when(api.getHeadbars(EntityKind.PLAYER, INDEX)).thenReturn(noBars);

        assertTrue(npc.getOverheadText().isEmpty());
        assertTrue(player.getOverheadText().isEmpty());
        assertTrue(npc.getHeadbars().isEmpty());
        assertTrue(player.getHeadbars().isEmpty());
    }

    private Player playerWithServerTile(int x, int y, int plane) {
        com.botwithus.bot.api.snapshot.Player raw = new com.botwithus.bot.api.snapshot.Player(
                INDEX, VISUAL_X, VISUAL_Y, 0, 0, -1, -1, 0, 3, -1, Orientation.unknown(), "Zezima", x, y, plane);
        when(api.snapshot()).thenReturn(CannedSnapshot.empty().withPlayers(List.of(raw)));
        return new Players(api).query().first();
    }

    private Npc npc() {
        com.botwithus.bot.api.snapshot.Npc raw = new com.botwithus.bot.api.snapshot.Npc(
                INDEX, 30870, VISUAL_X, VISUAL_Y, 0, 0, -1, -1, 0, 0, 0, -1);
        when(api.snapshot()).thenReturn(CannedSnapshot.empty().withNpcs(List.of(raw)));
        return new Npcs(api).query().first();
    }
}
