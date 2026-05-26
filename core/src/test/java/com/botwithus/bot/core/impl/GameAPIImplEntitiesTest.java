package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.Player;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice-18 entity query surface. Exercises the Npcs/Players facades
 * against a stub {@link GameSnapshot} so we don't need a live SHM
 * binding. Real producer integration is covered by the probes.
 */
class GameAPIImplEntitiesTest {

    private RpcClient rpc;
    private StubSnapshot snap;
    private Map<Integer, NpcType> npcTypes;
    private int npcTypeCalls;
    private GameAPIImpl api;

    private GameAPIImpl build() {
        rpc = mock(RpcClient.class);
        snap = new StubSnapshot();
        npcTypes = new java.util.HashMap<>();
        npcTypeCalls = 0;
        // Override getNpcType so tests don't need a real NXTCache binding —
        // the facade's caching is what we want to verify, not NXTCache.
        api = new GameAPIImpl(rpc, null, () -> snap) {
            @Override public NpcType getNpcType(int id) {
                npcTypeCalls++;
                return npcTypes.get(id);
            }
        };
        return api;
    }

    // ---------------------------------------------------------------- snapshot()

    @Test
    void snapshotPassesThroughToSupplier() {
        build();
        assertSame(snap, api.snapshot());
    }

    @Test
    void snapshotIsNullWithoutSupplier() {
        rpc = mock(RpcClient.class);
        api = new GameAPIImpl(rpc);
        assertNull(api.snapshot());
        assertNull(api.getLocalPlayer());
        assertNull(api.getPlayerStat(26));
        assertEquals(0, api.npcs().query().count());
    }

    // ---------------------------------------------------------------- getLocalPlayer / getPlayerStat

    @Test
    void getLocalPlayerReadsFromSnapshot() {
        build();
        snap.self = makeSelf(3, 5, 1, List.of(new Skill(26, 1_000_000, 75, 80)));
        LocalPlayer lp = api.getLocalPlayer();
        assertNotNull(lp);
        assertEquals(3, lp.tileX());
        assertEquals(5, lp.tileY());
    }

    @Test
    void getLocalPlayerNullWhenNotInGame() {
        build();
        snap.self = null;
        assertNull(api.getLocalPlayer());
    }

    @Test
    void getPlayerStatLooksUpBySkillId() {
        build();
        snap.self = makeSelf(0, 0, 0, List.of(
                new Skill(6,  500_000, 70, 75),   // Magic
                new Skill(26, 1_000_000, 80, 80)  // Divination
        ));
        PlayerStat divination = api.getPlayerStat(26);
        assertNotNull(divination);
        assertEquals(26, divination.skillId());
        assertEquals(80, divination.actualLevel());
        assertEquals(80, divination.boostedLevel());
        assertEquals(80, divination.level()); // alias
    }

    @Test
    void getPlayerStatNullForUnknownSkillId() {
        build();
        snap.self = makeSelf(0, 0, 0, List.of(new Skill(6, 0, 1, 1)));
        assertNull(api.getPlayerStat(99999));
    }

    // ---------------------------------------------------------------- npcs()

    @Test
    void npcsFacadeIsSingleton() {
        build();
        assertSame(api.npcs(), api.npcs());
    }

    @Test
    void npcsQueryReturnsEmptyWhenNoNpcs() {
        build();
        snap.npcs = List.of();
        assertEquals(0, api.npcs().query().count());
        assertNull(api.npcs().query().nearest());
        assertFalse(api.npcs().query().exists());
    }

    @Test
    void npcsQueryFiltersByDistance() {
        build();
        snap.self = makeSelf(100, 100, 0, List.of());
        snap.npcs = List.of(
                makeNpc(1, 50, 105, 100), // dist 5
                makeNpc(2, 50, 130, 100), // dist 30
                makeNpc(3, 50, 102, 100)  // dist 2
        );
        npcTypes.put(50, makeType(50, "Goblin", "Attack"));

        List<Npc> within10 = api.npcs().query().withinDistance(10).all();
        assertEquals(2, within10.size(), "expected 2 NPCs within 10 tiles");
    }

    @Test
    void npcsQueryNearestSortsByDistance() {
        build();
        snap.self = makeSelf(0, 0, 0, List.of());
        snap.npcs = List.of(
                makeNpc(1, 50, 30, 0),
                makeNpc(2, 50, 5, 0),
                makeNpc(3, 50, 10, 0)
        );
        npcTypes.put(50, makeType(50, "Goblin", "Attack"));

        Npc nearest = api.npcs().query().withId(50).nearest();
        assertNotNull(nearest);
        assertEquals(2, nearest.serverIndex(), "nearest should be the one 5 tiles away");
    }

    @Test
    void npcsQueryFilterPredicate() {
        build();
        snap.npcs = List.of(
                makeNpc(1, 50, 0, 0),
                makeNpc(2, 51, 0, 0),
                makeNpc(3, 50, 0, 0)
        );
        npcTypes.put(50, makeType(50, "Goblin", "Attack"));
        npcTypes.put(51, makeType(51, "Cow", "Milk"));

        List<Npc> goblins = api.npcs().query()
                .filter((Predicate<Npc>) n -> n.typeId() == 50)
                .all();
        assertEquals(2, goblins.size());
    }

    @Test
    void npcDefinitionLookupCachesByTypeId() {
        build();
        snap.npcs = List.of(makeNpc(1, 50, 0, 0), makeNpc(2, 50, 0, 0));
        npcTypes.put(50, makeType(50, "Goblin", "Attack"));

        // Two NPCs share typeId 50; calling name() on each should hit the
        // cache the second time (only one underlying lookup).
        List<Npc> all = api.npcs().query().all();
        all.forEach(Npc::name); // triggers lookup

        assertEquals(1, npcTypeCalls,
                "expected exactly one getNpcType lookup for two NPCs of the same typeId");
        assertEquals(1, api.npcs().definitionCacheSize());
    }

    @Test
    void npcInteractByOptionTextResolvesIndex() {
        build();
        snap.npcs = List.of(makeNpc(7, 50, 0, 0));
        npcTypes.put(50, makeType(50, "Goblin", "Attack", "Examine"));

        Npc npc = api.npcs().query().first();
        assertTrue(npc.interact("Examine"));
        // Examine is option 2 → action id NPC2 = 10, target = serverIndex
        verify(rpc).callSync(eq("queue_action"),
                eq(Map.of("action_id", 10, "param1", 7, "param2", 0, "param3", 0)));
    }

    @Test
    void npcInteractByMissingOptionReturnsFalse() {
        build();
        snap.npcs = List.of(makeNpc(7, 50, 0, 0));
        npcTypes.put(50, makeType(50, "Goblin", "Attack"));

        Npc npc = api.npcs().query().first();
        assertFalse(npc.interact("Talk-to"));
        verify(rpc, times(0)).callSync(eq("queue_action"), anyMap());
    }

    @Test
    void npcInteractIndexOutOfRangeThrows() {
        build();
        snap.npcs = List.of(makeNpc(7, 50, 0, 0));
        Npc npc = api.npcs().query().first();
        assertThrows(IllegalArgumentException.class, () -> npc.interact(0));
        assertThrows(IllegalArgumentException.class, () -> npc.interact(7));
    }

    // ---------------------------------------------------------------- players()

    @Test
    void playersQueryFiltersAndSorts() {
        build();
        snap.self = makeSelf(0, 0, 0, List.of());
        snap.players = List.of(
                makePlayer(1, 20, 0),
                makePlayer(2, 5, 0)
        );
        Player nearest = api.players().query().nearest();
        assertNotNull(nearest);
        assertEquals(2, nearest.serverIndex());
    }

    // ---------------------------------------------------------------- helpers

    private static LocalPlayer makeSelf(int x, int y, int plane, List<Skill> skills) {
        return new LocalPlayer(0, 100, x, y, plane, 0, -1, -1, 0, -1, 0, true, skills);
    }

    private static com.botwithus.bot.api.snapshot.Npc makeNpc(int idx, int typeId, int x, int y) {
        return new com.botwithus.bot.api.snapshot.Npc(idx, typeId, x, y, 0, 0, -1, -1, 0, 100, 100);
    }

    private static com.botwithus.bot.api.snapshot.Player makePlayer(int idx, int x, int y) {
        return new com.botwithus.bot.api.snapshot.Player(idx, x, y, 0, 0, -1, -1, 0, 138);
    }

    private static NpcType makeType(int id, String name, String... options) {
        return new NpcType(
                id, name, 1, true, true,
                List.of(options),
                -1, -1, List.of(), Map.of());
    }

    /** Mutable stub snapshot so individual tests can populate just the fields they need. */
    private static final class StubSnapshot implements GameSnapshot {
        long tickId = 0;
        int gameState = 30;
        int ownIndex = 0;
        LocalPlayer self;
        List<com.botwithus.bot.api.snapshot.Npc> npcs = List.of();
        List<com.botwithus.bot.api.snapshot.Player> players = List.of();

        @Override public long tickId() { return tickId; }
        @Override public int gameState() { return gameState; }
        @Override public int ownIndex() { return ownIndex; }
        @Override public LocalPlayer self() { return self; }

        @Override public Npcs npcs() {
            return new Npcs() {
                @Override public int count() { return npcs.size(); }
                @Override public com.botwithus.bot.api.snapshot.Npc at(int i) { return npcs.get(i); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Npc> byServerIndex(int s) {
                    return npcs.stream().filter(n -> n.serverIndex() == s).findFirst();
                }
                @Override public List<com.botwithus.bot.api.snapshot.Npc> filter(
                        com.botwithus.bot.api.snapshot.NpcFilter f) {
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
                @Override public List<com.botwithus.bot.api.snapshot.Player> filter(
                        com.botwithus.bot.api.snapshot.PlayerFilter f) {
                    return players.stream().filter(f).toList();
                }
                @Override public Stream<com.botwithus.bot.api.snapshot.Player> stream() { return players.stream(); }
            };
        }

        @Override public Locations locations() {
            return new Locations() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Location at(int i) { throw new IndexOutOfBoundsException(i); }
                @Override public List<com.botwithus.bot.api.snapshot.Location> filter(
                        com.botwithus.bot.api.snapshot.LocationFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Location> stream() { return Stream.empty(); }
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

        @Override public int sceneVersion() { return 0; }
    }
}
