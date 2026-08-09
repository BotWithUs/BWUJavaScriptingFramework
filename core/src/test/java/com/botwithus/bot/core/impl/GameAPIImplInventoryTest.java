package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Slice-19 inventory facade tests. Snapshot-backed reads + cached
 * ItemType lookup for name resolution + slot click via queue_action.
 *
 * <p>rule-exception: {@code {rule:no-fqn}} — the stub snapshot references
 * {@code api.snapshot.Npc} / {@code api.snapshot.Player} fully qualified
 * because the wrappers are the API under test. Same convention as the
 * production wrapper classes in {@code api/.../entities/}.
 */
class GameAPIImplInventoryTest {

    private RpcClient rpc;
    private StubSnapshot snap;
    private Map<Integer, ItemType> itemTypes;
    private GameAPIImpl api;

    private GameAPIImpl build() {
        return build(GamevalIndex.empty());
    }

    private GameAPIImpl build(GamevalIndex gamevals) {
        rpc = mock(RpcClient.class);
        snap = new StubSnapshot();
        itemTypes = new HashMap<>();
        api = new GameAPIImpl(rpc, null, () -> snap, new StubGuard(), event -> {}, gamevals) {
            @Override public ItemType getItemType(int id) { return itemTypes.get(id); }
        };
        return api;
    }

    /** A gameval index resolving {@code YEW_LOGS} to 1515 in the item namespace. */
    private static GamevalIndex yewLogsIndex() {
        return new GamevalIndex() {
            @Override public OptionalInt id(GamevalType type, String gameval) {
                return type == GamevalType.ITEM && "YEW_LOGS".equals(gameval)
                        ? OptionalInt.of(1515) : OptionalInt.empty();
            }

            @Override public Optional<String> gameval(GamevalType type, int id) {
                return type == GamevalType.ITEM && id == 1515
                        ? Optional.of("YEW_LOGS") : Optional.empty();
            }

            @Override public List<GamevalEntry> startingWith(GamevalType t, String p, int n) {
                return List.of();
            }

            @Override public boolean isAvailable() { return true; }

            @Override public Optional<String> meta(String key) { return Optional.empty(); }
        };
    }

    @Test
    void containsAndCountByGamevalName() {
        build(yewLogsIndex());
        snap.setInv(Backpack.INVENTORY_ID, 4,
                items(slot(0, 1515, 10), slot(1, 1515, 5), empty(2), slot(3, 1517, 1)));
        Backpack bp = api.backpack();

        assertTrue(bp.containsGameval("YEW_LOGS"));
        assertEquals(15, bp.countGameval("YEW_LOGS"));
        assertEquals(0, bp.getFirstGameval("YEW_LOGS").slot());
        assertTrue(bp.findFirstGameval("YEW_LOGS").isPresent());

        // An unknown name must read as "not held", never as a match.
        assertFalse(bp.containsGameval("MAPLE_LOGS"));
        assertEquals(0, bp.countGameval("MAPLE_LOGS"));
        assertNull(bp.getFirstGameval("MAPLE_LOGS"));
        assertFalse(bp.findFirstGameval("MAPLE_LOGS").isPresent());
    }

    @Test
    void interactByGamevalNameClicksTheHoldingSlot() {
        build(yewLogsIndex());
        snap.setInv(Backpack.INVENTORY_ID, 4, items(empty(0), slot(1, 1515, 3)));

        assertTrue(api.backpack().interactFirstGameval("YEW_LOGS", 1));
        verify(rpc).callSync(eq("queue_action"), eq(Map.of(
                "action_id", 57, "param1", 1, "param2", 1,
                "param3", Interfaces.componentHash(Interfaces.BACKPACK, Backpack.COMPONENT_ID))));
    }

    @Test
    void gamevalInventoryLookupsResolveNothingWithoutAnIndex() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4, items(slot(0, 1515, 10)));
        assertFalse(api.backpack().containsGameval("YEW_LOGS"));
        assertEquals(0, api.backpack().countGameval("YEW_LOGS"));
        assertFalse(api.backpack().interactFirstGameval("YEW_LOGS", 1));
        verify(rpc, times(0)).callSync(eq("queue_action"), anyMap());
    }

    @Test
    void backpackFacadeIsSingleton() {
        build();
        assertSame(api.backpack(), api.backpack());
    }

    @Test
    void backpackEmptyWhenNoSnapshotInventory() {
        build();
        Backpack bp = api.backpack();
        assertEquals(0, bp.slotCount());
        assertEquals(0, bp.occupiedSlots());
        assertEquals(0, bp.freeSlots());
        assertTrue(bp.isEmpty());
        assertFalse(bp.isFull());
    }

    @Test
    void backpackReadsCountAndOccupancy() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 28, items(slot(0, 1511, 5), empty(1), slot(2, 1521, 1)));
        Backpack bp = api.backpack();
        assertEquals(28, bp.slotCount());
        assertEquals(2, bp.occupiedSlots());
        assertEquals(26, bp.freeSlots());
        assertFalse(bp.isFull());
        assertFalse(bp.isEmpty());
    }

    @Test
    void backpackIsFullWhenAllSlotsOccupied() {
        build();
        InventoryItem[] full = new InventoryItem[28];
        for (int i = 0; i < 28; ++i) {
            full[i] = slot(i, 1511, 1);
        }
        snap.setInv(Backpack.INVENTORY_ID, 28, items(full));
        assertTrue(api.backpack().isFull());
    }

    @Test
    void containsAndCountById() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4,
                items(slot(0, 555, 100), slot(1, 555, 50), empty(2), slot(3, 999, 1)));
        Backpack bp = api.backpack();
        assertTrue(bp.contains(555));
        assertTrue(bp.contains(555, 150));
        assertFalse(bp.contains(555, 151));
        assertEquals(150, bp.count(555));
        assertEquals(0, bp.count(123));
        assertTrue(bp.containsAny(123, 999));
        assertTrue(bp.containsAll(555, 999));
        assertFalse(bp.containsAll(555, 123));
    }

    @Test
    void containsByNameUsesItemTypeCache() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 2,
                items(slot(0, 1511, 1), slot(1, 1521, 1)));
        itemTypes.put(1511, makeItem(1511, "Logs"));
        itemTypes.put(1521, makeItem(1521, "Oak logs"));

        Backpack bp = api.backpack();
        assertTrue(bp.contains("logs"));
        assertEquals(2, bp.count("logs"));
        assertNotNull(bp.getFirst("oak"));
        assertEquals(1521, bp.getFirst("oak").itemId());
    }

    @Test
    void definitionLookupCachesByItemId() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 3,
                items(slot(0, 1511, 1), slot(1, 1511, 1), slot(2, 1511, 1)));
        itemTypes.put(1511, makeItem(1511, "Logs"));

        // Multiple name lookups against the same itemId should hit the cache —
        // we observe that by counting interactions; but since the lookup goes
        // through getItemType (which is overridden), we can't easily count
        // hits without instrumenting the override. Instead, exercise the
        // public diagnostic.
        Backpack bp = api.backpack();
        assertEquals(3, bp.count("logs"));
        assertTrue(bp.definitionCacheSize() >= 1);
    }

    @Test
    void getSlotReturnsItemOrNull() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4,
                items(slot(0, 555, 1), empty(1), empty(2), empty(3)));
        Backpack bp = api.backpack();
        assertEquals(555, bp.getSlot(0).itemId());
        assertTrue(bp.getSlot(1).isEmpty());
        assertNull(bp.getSlot(99));
        assertNull(bp.getSlot(-1));
    }

    @Test
    void interactBuildsCorrectGameAction() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4,
                items(slot(0, 555, 1), empty(1), empty(2), empty(3)));

        Backpack bp = api.backpack();
        assertTrue(bp.interact(0, 1));

        int packed = Interfaces.componentHash(Backpack.INTERFACE_ID, Backpack.COMPONENT_ID);
        verify(rpc).callSync(eq("queue_action"),
                eq(Map.of("action_id", 57, "param1", 1, "param2", 0, "param3", packed)));
    }

    @Test
    void interactReturnsFalseForOutOfRangeSlot() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4, items(slot(0, 555, 1), empty(1), empty(2), empty(3)));
        assertFalse(api.backpack().interact(99, 1));
        verify(rpc, times(0)).callSync(eq("queue_action"), anyMap());
    }

    @Test
    void interactFirstByOptionResolvesViaItemType() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4,
                items(slot(0, 1511, 1), empty(1), empty(2), empty(3)));
        itemTypes.put(1511, new ItemType(
                1511, "Logs", false, false, 0, 0, 0, -1, -1, false,
                List.of(),
                List.of("Use", "Drop", "Examine"),
                Map.of()));

        Backpack bp = api.backpack();
        assertTrue(bp.interactFirst(1511, "Drop"));

        // Drop is option 2 (index 2 in 1-based); param2 is the slot index (0),
        // param3 packs iface/comp.
        int packed = Interfaces.componentHash(Backpack.INTERFACE_ID, Backpack.COMPONENT_ID);
        verify(rpc).callSync(eq("queue_action"),
                eq(Map.of("action_id", 57, "param1", 2, "param2", 0, "param3", packed)));
    }

    @Test
    void interactFirstByMissingOptionReturnsFalse() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4, items(slot(0, 1511, 1), empty(1), empty(2), empty(3)));
        itemTypes.put(1511, new ItemType(
                1511, "Logs", false, false, 0, 0, 0, -1, -1, false,
                List.of(), List.of("Use"), Map.of()));

        assertFalse(api.backpack().interactFirst(1511, "Drop"));
        verify(rpc, times(0)).callSync(eq("queue_action"), anyMap());
    }

    @Test
    void interactFirstByMissingItemReturnsFalse() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 4, items(empty(0), empty(1), empty(2), empty(3)));
        assertFalse(api.backpack().interactFirst(1511, "Drop"));
        verify(rpc, times(0)).callSync(eq("queue_action"), anyMap());
    }

    @Test
    void clearDefinitionCacheResetsState() {
        build();
        snap.setInv(Backpack.INVENTORY_ID, 1, items(slot(0, 1511, 1)));
        itemTypes.put(1511, makeItem(1511, "Logs"));
        api.backpack().contains("logs");
        assertTrue(api.backpack().definitionCacheSize() >= 1);
        api.backpack().clearDefinitionCache();
        assertEquals(0, api.backpack().definitionCacheSize());
    }

    // ---------------- helpers ----------------

    private static InventoryItem slot(int slot, int itemId, int qty) {
        return new InventoryItem(slot, itemId, qty);
    }

    private static InventoryItem empty(int slot) {
        return new InventoryItem(slot, -1, 0);
    }

    private static List<InventoryItem> items(InventoryItem... is) {
        List<InventoryItem> out = new ArrayList<>(is.length);
        for (InventoryItem i : is) {
            out.add(i);
        }
        return out;
    }

    private static ItemType makeItem(int id, String name) {
        return new ItemType(id, name, false, false, 0, 0, 0, -1, -1, false,
                List.of(), List.of(), Map.of());
    }

    private static final class StubSnapshot implements GameSnapshot {
        private final Map<Integer, Inventory> invMap = new HashMap<>();

        void setInv(int invId, int slotCount, List<InventoryItem> items) {
            invMap.put(invId, new Inventory(invId, slotCount, items));
        }

        @Override public int serverTick() { return 0; }
        @Override public int gameCycle() { return 0; }
        @Override public long publishSeq() { return 0; }
        @Override public int gameState() { return 30; }
        @Override public int ownIndex() { return 0; }
        @Override public com.botwithus.bot.api.snapshot.LocalPlayer self() { return null; }

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
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Location at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public List<com.botwithus.bot.api.snapshot.Location> filter(com.botwithus.bot.api.snapshot.LocationFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Location> stream() { return Stream.empty(); }
            };
        }

        @Override public Inventories inventories() {
            return new Inventories() {
                @Override public int count() { return invMap.size(); }
                @Override public Inventory at(int i) {
                    return invMap.values().stream().toList().get(i);
                }
                @Override public Optional<Inventory> byInvId(int id) {
                    return Optional.ofNullable(invMap.get(id));
                }
                @Override public Stream<Inventory> stream() { return invMap.values().stream(); }
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
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Projectile at(int i) { throw new IndexOutOfBoundsException(i); }
                @Override public List<com.botwithus.bot.api.snapshot.Projectile> filter(com.botwithus.bot.api.snapshot.ProjectileFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Projectile> stream() { return Stream.empty(); }
            };
        }

        @Override public int sceneVersion() { return 0; }
    }
}
