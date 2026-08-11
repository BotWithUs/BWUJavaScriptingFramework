package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.entities.Projectile;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.LocationFilter;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Projectile query facade tests (v17+). Projectiles are SHM-backed like the
 * other scene entities, so each test seeds {@code snap.projectiles} and
 * exercises the {@link com.botwithus.bot.api.entities.Projectiles} wrapper.
 *
 * <p>The load-bearing behaviour here is that {@link Projectile} anchors
 * {@code EntityContext} on the <b>impact</b> tile, not the launch tile — every
 * distance-based query depends on it, and the two tiles are deliberately far
 * apart in these fixtures so a regression to launch-anchoring fails loudly.</p>
 *
 * <p>rule-exception: {@code {rule:no-fqn}} — the stub snapshot references
 * {@code api.snapshot.Projectile} etc. fully qualified to avoid a name clash
 * with the entity-package types. Same convention as the production wrappers in
 * {@code api/.../entities/} and as {@code GameAPIImplSceneObjectsTest}.
 */
class GameAPIImplProjectilesTest {

    private StubSnapshot snap;
    private GameAPIImpl api;

    private GameAPIImpl build() {
        RpcClient rpc = mock(RpcClient.class);
        snap = new StubSnapshot();
        api = new GameAPIImpl(rpc, null, () -> snap);
        return api;
    }

    // ---------------- Facade wiring ----------------

    @Test
    void projectilesFacadeIsSingleton() {
        build();
        assertSame(api.projectiles(), api.projectiles());
    }

    @Test
    void queryEmptyWhenSnapshotHasNoProjectiles() {
        build();
        assertNull(api.projectiles().query().nearest());
        assertEquals(0, api.projectiles().query().count());
        assertTrue(api.projectiles().all().isEmpty());
    }

    // ---------------- Position semantics ----------------

    /**
     * The anchoring test. Both projectiles launch from right beside the player;
     * only their impact tiles differ. If {@code tileX/tileY} ever regress to the
     * launch point, {@code nearest()} becomes a coin flip and this fails.
     */
    @Test
    void nearestAnchorsOnImpactTileNotLaunchTile() {
        build();
        snap.self = makeSelf(100, 100, 0);
        // Both launch adjacent to the player; impacts are 30 and 3 tiles away.
        snap.projectiles.add(proj(10, 101, 100, 130, 100, -1, -1, 0));
        snap.projectiles.add(proj(20, 101, 100, 103, 100, -1, -1, 0));

        Projectile nearest = api.projectiles().query().nearest();
        assertNotNull(nearest);
        assertEquals(20, nearest.projectileId(), "should pick the closer IMPACT, not launch");
        assertEquals(103, nearest.tileX(), "tileX is the impact tile");
        assertEquals(101, nearest.startTileX(), "launch tile still reachable");
    }

    @Test
    void withinDistanceMeasuresFromImpactTile() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(proj(10, 100, 100, 105, 100, -1, -1, 0)); // impact 5 away
        snap.projectiles.add(proj(20, 100, 100, 140, 100, -1, -1, 0)); // impact 40 away

        List<Projectile> near = api.projectiles().query().withinDistance(10).all();
        assertEquals(1, near.size());
        assertEquals(10, near.getFirst().projectileId());
    }

    // ---------------- Filters ----------------

    @Test
    void withIdFiltersOnGraphicId() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(proj(55, 100, 100, 101, 100, -1, -1, 0));
        snap.projectiles.add(proj(66, 100, 100, 102, 100, -1, -1, 0));
        snap.projectiles.add(proj(55, 100, 100, 103, 100, -1, -1, 0));

        assertEquals(2, api.projectiles().query().withId(55).count());
        assertEquals(1, api.projectiles().query().withId(66).count());
    }

    /**
     * Projectiles have no cache definition (no {@code SpotAnimType}, no decoder
     * in NXTCacheLibrary), so {@code nameOf} returns null and name filters can
     * never match. Pinned so a future definition source is a deliberate change.
     */
    @Test
    void namedNeverMatchesBecauseThereIsNoDefinitionSource() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(proj(55, 100, 100, 101, 100, -1, -1, 0));

        assertEquals(0, api.projectiles().query().named("anything").count());
        assertEquals(1, api.projectiles().query().count(), "unfiltered still sees it");
    }

    // ---------------- Endpoints ----------------

    @Test
    void tileAnchoredEndpointsReportAsTiles() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(proj(10, 100, 100, 105, 100, -1, -1, 0));

        Projectile p = api.projectiles().query().first();
        assertTrue(p.isSourceTile());
        assertTrue(p.isTargetTile());
        assertNull(p.sourceNpc(), "no entity to resolve");
        assertNull(p.targetNpc());
        assertNull(p.sourcePlayer());
        assertNull(p.targetPlayer());
        assertFalse(p.targetsLocalPlayer(), "a tile target is never the local player");
    }

    @Test
    void entityEndpointsHydrateToLiveNpcs() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.npcs.add(npc(42, 1500, 120, 100));
        snap.projectiles.add(proj(10, 100, 100, 120, 100, 7, 42, 0));

        Projectile p = api.projectiles().query().first();
        assertFalse(p.isSourceTile());
        assertFalse(p.isTargetTile());
        assertEquals(7, p.sourceIndex());
        assertEquals(42, p.targetIndex());

        assertNotNull(p.targetNpc(), "target index 42 matches a live NPC");
        assertEquals(1500, p.targetNpc().typeId());
        assertNull(p.sourceNpc(), "index 7 is not among the live NPCs");
    }

    @Test
    void targetsLocalPlayerMatchesOwnIndex() {
        build();
        snap.ownIndex = 3;
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(proj(10, 130, 100, 100, 100, 42, 3, 0));  // at us
        snap.projectiles.add(proj(20, 130, 100, 100, 100, 42, 9, 0));  // at someone else

        List<Projectile> incoming = api.projectiles().incoming();
        assertEquals(1, incoming.size());
        assertEquals(10, incoming.getFirst().projectileId());

        Projectile nearest = api.projectiles().nearestIncoming();
        assertNotNull(nearest);
        assertEquals(10, nearest.projectileId());
    }

    @Test
    void nearestIncomingNullWhenNothingInbound() {
        build();
        snap.ownIndex = 3;
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(proj(20, 130, 100, 100, 100, 42, 9, 0));

        assertNull(api.projectiles().nearestIncoming());
        assertTrue(api.projectiles().incoming().isEmpty());
    }

    // ---------------- Flight ----------------

    @Test
    void flightProgressInterpolatesAndClamps() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(projCycles(10, 1000, 1100));

        Projectile p = api.projectiles().query().first();
        assertEquals(0.0d, p.flightProgress(1000), 1e-9, "at launch");
        assertEquals(0.5d, p.flightProgress(1050), 1e-9, "midway");
        assertEquals(1.0d, p.flightProgress(1100), 1e-9, "at impact");
        assertEquals(0.0d, p.flightProgress(900), 1e-9, "clamped below");
        assertEquals(1.0d, p.flightProgress(9999), 1e-9, "clamped above");
    }

    @Test
    void flightProgressHandlesZeroLengthFlight() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(projCycles(10, 500, 500));

        assertEquals(1.0d, api.projectiles().query().first().flightProgress(500), 1e-9);
    }

    @Test
    void cyclesRemainingFloorsAtZero() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.projectiles.add(projCycles(10, 1000, 1100));

        Projectile p = api.projectiles().query().first();
        assertEquals(100, p.cyclesRemaining(1000));
        assertEquals(40, p.cyclesRemaining(1060));
        assertEquals(0, p.cyclesRemaining(1100));
        assertEquals(0, p.cyclesRemaining(5000), "never negative");
    }

    // ---------------- Fixtures ----------------

    private static LocalPlayer makeSelf(int x, int y, int plane) {
        return new LocalPlayer(0, 100, x, y, plane, 0, -1, -1, 0, -1, 0, true, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of());
    }

    private static com.botwithus.bot.api.snapshot.Npc npc(int serverIndex, int typeId,
                                                          int tileX, int tileY) {
        return new com.botwithus.bot.api.snapshot.Npc(
                serverIndex, typeId, tileX, tileY, 0, 0, -1, -1, 0, 100, 100, -1);
    }

    /** Projectile with explicit launch/impact tiles and endpoint indices. */
    private static com.botwithus.bot.api.snapshot.Projectile proj(
            int id, int startX, int startY, int endX, int endY,
            int sourceIndex, int targetIndex, int plane) {
        return new com.botwithus.bot.api.snapshot.Projectile(
                id, 0, 10, sourceIndex, 0, targetIndex, 0,
                startX, startY, endX, endY, plane);
    }

    /** Projectile that only cares about its cycle window. */
    private static com.botwithus.bot.api.snapshot.Projectile projCycles(
            int id, int startCycle, int endCycle) {
        return new com.botwithus.bot.api.snapshot.Projectile(
                id, startCycle, endCycle, -1, 0, -1, 0, 100, 100, 100, 100, 0);
    }

    private static final class StubSnapshot implements GameSnapshot {
        LocalPlayer self;
        int ownIndex = 0;
        final List<com.botwithus.bot.api.snapshot.Projectile> projectiles = new ArrayList<>();
        final List<com.botwithus.bot.api.snapshot.Npc> npcs = new ArrayList<>();
        final List<com.botwithus.bot.api.snapshot.Player> players = new ArrayList<>();

        @Override public int serverTick() { return 0; }
        @Override public int gameCycle() { return 0; }
        @Override public long publishSeq() { return 0; }
        @Override public int gameState() { return 30; }
        @Override public int ownIndex() { return ownIndex; }
        @Override public LocalPlayer self() { return self; }

        @Override public Npcs npcs() {
            return new Npcs() {
                @Override public int count() { return npcs.size(); }
                @Override public com.botwithus.bot.api.snapshot.Npc at(int i) { return npcs.get(i); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Npc> byServerIndex(int s) {
                    return npcs.stream().filter(n -> n.serverIndex() == s).findFirst();
                }
                @Override public List<com.botwithus.bot.api.snapshot.Npc> filter(com.botwithus.bot.api.snapshot.NpcFilter f) {
                    return npcs.stream().filter(f).toList();
                }
                @Override public Stream<com.botwithus.bot.api.snapshot.Npc> stream() { return npcs.stream(); }
            };
        }

        @Override public Players players() {
            return new Players() {
                @Override public int count() { return players.size(); }
                @Override public com.botwithus.bot.api.snapshot.Player at(int i) { return players.get(i); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Player> byServerIndex(int s) {
                    return players.stream().filter(p -> p.serverIndex() == s).findFirst();
                }
                @Override public List<com.botwithus.bot.api.snapshot.Player> filter(com.botwithus.bot.api.snapshot.PlayerFilter f) {
                    return players.stream().filter(f).toList();
                }
                @Override public Stream<com.botwithus.bot.api.snapshot.Player> stream() { return players.stream(); }
            };
        }

        @Override public Locations locations() {
            return new Locations() {
                @Override public int count() { return 0; }
                @Override public Location at(int i) { throw new IndexOutOfBoundsException(i); }
                @Override public List<Location> filter(LocationFilter f) { return List.of(); }
                @Override public Stream<Location> stream() { return Stream.empty(); }
            };
        }

        @Override public Inventories inventories() {
            return new Inventories() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Inventory at(int i) { throw new IndexOutOfBoundsException(i); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Inventory> byInvId(int id) { return Optional.empty(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Inventory> stream() { return Stream.empty(); }
            };
        }

        @Override public GroundItems groundItems() {
            return new GroundItems() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.GroundItem at(int i) { throw new IndexOutOfBoundsException(i); }
                @Override public List<com.botwithus.bot.api.snapshot.GroundItem> filter(com.botwithus.bot.api.snapshot.GroundItemFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.GroundItem> stream() { return Stream.empty(); }
            };
        }

        @Override public Projectiles projectiles() {
            return new Projectiles() {
                @Override public int count() { return projectiles.size(); }
                @Override public com.botwithus.bot.api.snapshot.Projectile at(int i) { return projectiles.get(i); }
                @Override public List<com.botwithus.bot.api.snapshot.Projectile> filter(com.botwithus.bot.api.snapshot.ProjectileFilter f) {
                    return projectiles.stream().filter(f).toList();
                }
                @Override public Stream<com.botwithus.bot.api.snapshot.Projectile> stream() { return projectiles.stream(); }
            };
        }

        @Override public int sceneVersion() { return 0; }
    }
}
