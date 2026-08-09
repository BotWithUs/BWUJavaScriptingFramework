package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.LocationFilter;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Scene-object / ground-item query facade tests. The facades read from the
 * SHM snapshot (v15+) — no RPC for entity queries — so each test seeds
 * {@code snap.locs} / {@code snap.grounds} and exercises the
 * {@link com.botwithus.bot.api.entities.SceneObjects} /
 * {@link com.botwithus.bot.api.entities.GroundItems} wrappers, distance
 * sort, definition cache, and interaction params.
 *
 * <p>rule-exception: {@code {rule:no-fqn}} — the stub snapshot references
 * {@code api.snapshot.Npc} / {@code api.snapshot.Location} etc. fully
 * qualified to avoid a name clash with the entity-package types. Same
 * convention as the production wrappers in {@code api/.../entities/}.
 */
class GameAPIImplSceneObjectsTest {

    private RpcClient rpc;
    private StubSnapshot snap;
    private Map<Integer, LocationType> locTypes;
    private Map<Integer, com.botwithus.bot.api.model.ItemType> itemTypes;
    private GameAPIImpl api;

    private GameAPIImpl build() {
        return build(GamevalIndex.empty());
    }

    private GameAPIImpl build(GamevalIndex gamevals) {
        rpc = mock(RpcClient.class);
        snap = new StubSnapshot();
        locTypes = new HashMap<>();
        itemTypes = new HashMap<>();
        api = new GameAPIImpl(rpc, null, () -> snap, new StubGuard(), event -> {}, gamevals) {
            @Override public LocationType getLocationType(int id) { return locTypes.get(id); }
            @Override public com.botwithus.bot.api.model.ItemType getItemType(int id) { return itemTypes.get(id); }
        };
        return api;
    }

    @Test
    void objectsFacadeIsSingleton() {
        build();
        assertSame(api.objects(), api.objects());
        assertSame(api.groundItems(), api.groundItems());
    }

    @Test
    void objectsQueryEmptyWhenSnapshotHasNoLocations() {
        build();
        // snap.locs default empty
        assertNull(api.objects().query().nearest());
        assertEquals(0, api.objects().query().count());
    }

    @Test
    void objectsQueryReturnsRichWrappers() {
        build();
        snap.self = makeSelf(100, 100, 0);
        snap.locs.add(directLoc(50, 105, 100, 0));
        snap.locs.add(directLoc(50, 130, 100, 0));
        snap.locs.add(directLoc(50, 102, 100, 0));
        locTypes.put(50, makeLoc(50, "Tree", List.of("Chop down")));

        List<SceneObject> trees = api.objects().query().named("Tree").all();
        assertEquals(3, trees.size());
        assertEquals("Tree", trees.get(0).name());
        assertEquals(50, trees.get(0).typeId());
    }

    @Test
    void objectsNearestSortsByDistance() {
        build();
        snap.self = makeSelf(0, 0, 0);
        snap.locs.add(directLoc(50, 30, 0, 0));   // far
        snap.locs.add(directLoc(50,  5, 0, 0));   // nearest
        snap.locs.add(directLoc(50, 10, 0, 0));   // middle
        locTypes.put(50, makeLoc(50, "Rift", List.of("Convert")));

        SceneObject nearest = api.objects().query().named("Rift").nearest();
        assertNotNull(nearest);
        assertEquals(5, nearest.tileX(), "expected the (5,0) tile (closest to origin)");
    }

    @Test
    void objectsSurfacesBothDirectAndCombinedSections() {
        build();
        // Direct LOCATION: loc id rides in interactId.
        snap.locs.add(directLoc(50, 5, 5, 0));
        // Combined section: loc id rides in typeId (interactId is -1 by
        // construction). Most trees / rocks / posts appear here.
        snap.locs.add(combinedSectionLoc(38782, 6, 6, 0));
        locTypes.put(50, makeLoc(50, "Door", List.of("Open")));
        locTypes.put(38782, makeLoc(38782, "Tree", List.of("Chop down")));

        List<SceneObject> all = api.objects().query().all();
        assertEquals(2, all.size());
        // Each row exposes the right loc id regardless of which field the
        // producer stored it in.
        assertTrue(all.stream().anyMatch(o -> o.typeId() == 50 && "Door".equals(o.name())));
        assertTrue(all.stream().anyMatch(o -> o.typeId() == 38782 && "Tree".equals(o.name())));
    }

    @Test
    void objectsDropsRowsWithNoUsableLocId() {
        build();
        // Direct LOC with interactId=-1 (malformed) and section with
        // typeId=-1 (malformed): both must be dropped.
        snap.locs.add(new com.botwithus.bot.api.snapshot.Location(
                5, -1, -1, 0, 0, 0, 10, 0, 0));
        snap.locs.add(new com.botwithus.bot.api.snapshot.Location(
                -1, -1, -1, 0, 0, 0, 10, 0,
                com.botwithus.bot.core.shm.Layout.LOC_FLAG_COMBINED_SECTION));

        assertEquals(0, api.objects().query().count());
    }

    @Test
    void objectsHasOptionUsesLocationType() {
        build();
        snap.locs.add(directLoc(50, 0, 0, 0));
        locTypes.put(50, makeLoc(50, "Rift", List.of("Convert", "Examine")));

        SceneObject obj = api.objects().query().first();
        assertTrue(obj.hasOption("Convert"));
        assertTrue(obj.hasOption("convert"));
        assertFalse(obj.hasOption("Attack"));
    }

    @Test
    void objectsInteractByOptionResolvesIndex() {
        build();
        snap.locs.add(directLoc(7, 0, 0, 0));
        locTypes.put(7, makeLoc(7, "Rift", List.of("Convert", "Examine")));

        SceneObject obj = api.objects().query().first();
        assertTrue(obj.interact("Examine"));
        // Examine = option 2 → ActionTypes.OBJECT2 = 4, target = handle (== interactId == 7)
        verify(rpc).callSync(eq("queue_action"),
                eq(Map.of("action_id", 4, "param1", 7, "param2", 0, "param3", 0)));
    }

    @Test
    void objectsInteractByMissingOptionReturnsFalse() {
        build();
        snap.locs.add(directLoc(7, 0, 0, 0));
        locTypes.put(7, makeLoc(7, "Rift", List.of("Convert")));

        SceneObject obj = api.objects().query().first();
        assertFalse(obj.interact("Talk-to"));
        verify(rpc, times(0)).callSync(eq("queue_action"), anyMap());
    }

    @Test
    void objectsLocationTypeCachedByTypeId() {
        build();
        // Two rows with the same loc id (== interactId) — one type cache fetch.
        snap.locs.add(directLoc(50, 0, 0, 0));
        snap.locs.add(directLoc(50, 1, 0, 0));
        locTypes.put(50, makeLoc(50, "Rift", List.of("Convert")));

        api.objects().query().all().forEach(SceneObject::name);
        assertEquals(1, api.objects().definitionCacheSize());
    }

    // ---------------------------------------------------------------- Ground items

    @Test
    void groundItemsQueryReturnsRichWrappers() {
        build();
        snap.self = makeSelf(0, 0, 0);
        snap.grounds.add(new com.botwithus.bot.api.snapshot.GroundItem(995, 1000, 5, 0, 0));
        itemTypes.put(995, makeItem(995, "Coins", List.of("Take")));

        GroundItem coins = api.groundItems().query().nearest();
        assertNotNull(coins);
        assertEquals("Coins", coins.name());
        assertEquals(1000, coins.quantity());
    }

    @Test
    void groundItemsInteractByOption() {
        build();
        // handle == itemId mirrors the producer's existing GROUND_ITEM_N action convention.
        snap.grounds.add(new com.botwithus.bot.api.snapshot.GroundItem(7, 1, 0, 0, 0));
        itemTypes.put(7, makeItem(7, "Coins", List.of("Take", "Examine")));

        GroundItem g = api.groundItems().query().first();
        assertTrue(g.interact("Take"));
        verify(rpc).callSync(eq("queue_action"),
                eq(Map.of("action_id", 18, "param1", 7, "param2", 0, "param3", 0)));
    }

    // ---------------------------------------------------------------- Gameval names

    @Test
    void objectsQueryFiltersByGamevalName() {
        build(stubIndex(Map.of("YEW", 50, "MAPLE", 51)));
        snap.self = makeSelf(0, 0, 0);
        snap.locs.add(directLoc(50, 5, 0, 0));
        snap.locs.add(directLoc(51, 1, 0, 0));

        List<SceneObject> yews = api.objects().query().withGameval("YEW").all();
        assertEquals(1, yews.size());
        assertEquals(50, yews.getFirst().typeId());

        // Several names match any of them, and lookups are case-insensitive
        // only because the index says so — the query passes the name through.
        assertEquals(2, api.objects().query().withGameval("YEW", "MAPLE").count());
        assertEquals(50, api.objects().nearestByGameval("YEW").typeId());
        assertEquals(1, api.objects().allByGameval("MAPLE").size());
    }

    @Test
    void unresolvedGamevalMatchesNothingRatherThanEverything() {
        // The failure mode this guards: a filter that silently drops out and
        // leaves the query matching every entity in the scene.
        build(stubIndex(Map.of("YEW", 50)));
        snap.locs.add(directLoc(50, 5, 0, 0));
        snap.locs.add(directLoc(51, 1, 0, 0));

        assertEquals(0, api.objects().query().withGameval("NOT_A_REAL_NAME").count());
        assertNull(api.objects().nearestByGameval("NOT_A_REAL_NAME"));
        // A partially-resolvable set keeps the names that did resolve.
        assertEquals(1, api.objects().query().withGameval("NOT_A_REAL_NAME", "YEW").count());
    }

    @Test
    void gamevalQueriesResolveNothingWithoutAnIndex() {
        build();
        snap.locs.add(directLoc(50, 5, 0, 0));
        assertEquals(0, api.objects().query().withGameval("YEW").count());
        assertEquals(0, api.groundItems().query().withGameval("COINS").count());
    }

    @Test
    void groundItemsQueryFiltersByGamevalName() {
        build(stubIndex(Map.of("COINS", 995)));
        snap.grounds.add(groundStack(995, 1000));
        snap.grounds.add(groundStack(1515, 1));
        itemTypes.put(995, makeItem(995, "Coins", List.of("Take")));

        assertEquals(1, api.groundItems().query().withGameval("COINS").count());
        assertEquals(1000, api.groundItems().nearestByGameval("COINS").quantity());
    }

    @Test
    void emptyGamevalListMatchesNothing() {
        // Not the same as "every name failed": building a filter from an empty
        // list is silent unless the query says so.
        build(stubIndex(Map.of("YEW", 50)));
        snap.locs.add(directLoc(50, 5, 0, 0));
        snap.locs.add(directLoc(51, 1, 0, 0));

        assertEquals(0, api.objects().query().withGameval().count());
        assertEquals(0, api.objects().query().withGameval(new String[0]).count());
    }

    @Test
    void componentsByGamevalSpendNoRpcWhenTheNameIsUnknown() {
        build(stubIndex(Map.of()));
        assertNull(api.components().get("BANK__BANK_INV_BUTTON"));
        assertFalse(api.components().isOpen("BANK"));
        assertEquals(0, api.components().in("BANK").count());
        assertNull(api.components().under("BANK__CONTENT").root());
        verify(rpc, times(0)).callSync(eq("get_component"), anyMap());
        verify(rpc, times(0)).callSync(eq("get_interface_tree"), anyMap());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A gameval index over a fixed name→id map. Every name is looked up in the
     * one namespace, which is enough here: each facade only ever consults its
     * own type, so a shared map cannot cross-talk within a single assertion.
     */
    private static GamevalIndex stubIndex(Map<String, Integer> byName) {
        return new GamevalIndex() {
            @Override public OptionalInt id(GamevalType type, String gameval) {
                Integer found = byName.get(gameval);
                return found == null ? OptionalInt.empty() : OptionalInt.of(found);
            }

            @Override public Optional<String> gameval(GamevalType type, int id) {
                return byName.entrySet().stream()
                        .filter(e -> e.getValue() == id)
                        .map(Map.Entry::getKey)
                        .findFirst();
            }

            @Override public List<GamevalEntry> startingWith(GamevalType t, String p, int n) {
                return List.of();
            }

            @Override public boolean isAvailable() { return true; }

            @Override public Optional<String> meta(String key) { return Optional.empty(); }
        };
    }

    private static LocalPlayer makeSelf(int x, int y, int plane) {
        return new LocalPlayer(0, 100, x, y, plane, 0, -1, -1, 0, -1, 0, true, -1, List.of());
    }

    /** Direct LOCATION row — typeId is irrelevant (entity classifier); the
     *  loc id rides in {@code interactId}. The resolveLocHandle filter keeps it. */
    private static Location directLoc(int locId, int tileX, int tileY, int plane) {
        return new Location(locId, locId, -1, tileX, tileY, plane, 10, 0, 0);
    }

    /** Combined-section row — interactId always -1, flag bit set. Filter drops it. */
    private static Location combinedSectionLoc(int modelTypeId, int tileX, int tileY, int plane) {
        return new Location(modelTypeId, -1, -1, tileX, tileY, plane, 10, 0,
                com.botwithus.bot.core.shm.Layout.LOC_FLAG_COMBINED_SECTION);
    }

    private static LocationType makeLoc(int id, String name, List<String> options) {
        return new LocationType(id, name, 1, 1, 0, 0, false, options, -1, -1, List.of(), 0, Map.of());
    }

    /**
     * Snapshot ground-item row. A named factory rather than an inline
     * constructor call because the snapshot type collides with the entity
     * wrapper {@code GroundItem} this file imports.
     */
    private static com.botwithus.bot.api.snapshot.GroundItem groundStack(int itemId, int quantity) {
        return new com.botwithus.bot.api.snapshot.GroundItem(itemId, quantity, 0, 0, 0);
    }

    private static com.botwithus.bot.api.model.ItemType makeItem(int id, String name, List<String> ground) {
        return new com.botwithus.bot.api.model.ItemType(
                id, name, false, false, 0, 0, 0, -1, -1, false,
                ground, List.of(), Map.of());
    }

    private static final class StubSnapshot implements GameSnapshot {
        LocalPlayer self;
        final List<Location> locs = new ArrayList<>();
        final List<com.botwithus.bot.api.snapshot.GroundItem> grounds = new ArrayList<>();

        @Override public int serverTick() { return 0; }
        @Override public int gameCycle() { return 0; }
        @Override public long publishSeq() { return 0; }
        @Override public int gameState() { return 30; }
        @Override public int ownIndex() { return 0; }
        @Override public LocalPlayer self() { return self; }

        @Override public Npcs npcs() {
            return new Npcs() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Npc at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Npc> byServerIndex(int s) { return Optional.empty(); }
                @Override public List<com.botwithus.bot.api.snapshot.Npc> filter(com.botwithus.bot.api.snapshot.NpcFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Npc> stream() { return Stream.empty(); }
            };
        }
        @Override public Players players() {
            return new Players() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Player at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Player> byServerIndex(int s) { return Optional.empty(); }
                @Override public List<com.botwithus.bot.api.snapshot.Player> filter(com.botwithus.bot.api.snapshot.PlayerFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Player> stream() { return Stream.empty(); }
            };
        }
        @Override public Locations locations() {
            return new Locations() {
                @Override public int count() { return locs.size(); }
                @Override public Location at(int i) { return locs.get(i); }
                @Override public List<Location> filter(LocationFilter f) {
                    return locs.stream().filter(f).toList();
                }
                @Override public Stream<Location> stream() { return locs.stream(); }
            };
        }
        @Override public Inventories inventories() {
            return new Inventories() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Inventory at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Inventory> byInvId(int id) { return Optional.empty(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Inventory> stream() { return Stream.empty(); }
            };
        }
        @Override public GroundItems groundItems() {
            return new GroundItems() {
                @Override public int count() { return grounds.size(); }
                @Override public com.botwithus.bot.api.snapshot.GroundItem at(int i) { return grounds.get(i); }
                @Override public List<com.botwithus.bot.api.snapshot.GroundItem> filter(com.botwithus.bot.api.snapshot.GroundItemFilter f) {
                    return grounds.stream().filter(f).toList();
                }
                @Override public Stream<com.botwithus.bot.api.snapshot.GroundItem> stream() { return grounds.stream(); }
            };
        }
        @Override public Projectiles projectiles() {
            return new Projectiles() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Projectile at(int i) { throw new IndexOutOfBoundsException(i); }
                @Override public List<com.botwithus.bot.api.snapshot.Projectile> filter(com.botwithus.bot.api.snapshot.ProjectileFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Projectile> stream() { return Stream.empty(); }
            };
        }
        @Override public int sceneVersion() { return 0; }
    }
}
