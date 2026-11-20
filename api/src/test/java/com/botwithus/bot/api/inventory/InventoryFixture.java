package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.Orientation;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A {@link GameAPI} whose snapshot publishes the inventories, item definitions and scene a
 * test hands it, with real {@link Backpack}, {@link Bank}, {@link SceneObjects} and
 * {@link Npcs} facades over it. No gameval index is deployed, so every bank ref resolves to
 * its literal fallback id.
 */
final class InventoryFixture {

    /** A slot with nothing in it, as the snapshot publishes one. */
    static final int EMPTY_ITEM = -1;
    private static final int PACK_SLOTS = 28;
    private static final int BANK_SLOTS = 800;

    final GameAPI api = mock(GameAPI.class);
    private final GameSnapshot snapshot = mock(GameSnapshot.class);
    private final GameSnapshot.Inventories inventories = mock(GameSnapshot.Inventories.class);
    private final GameSnapshot.Locations locations = mock(GameSnapshot.Locations.class);
    private final GameSnapshot.Npcs npcs = mock(GameSnapshot.Npcs.class);
    final Backpack backpack = new Backpack(api);
    final Bank bank = new Bank(api);

    InventoryFixture() {
        when(api.snapshot()).thenReturn(snapshot);
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.backpack()).thenReturn(backpack);
        when(api.bank()).thenReturn(bank);
        when(api.objects()).thenReturn(new SceneObjects(api));
        when(api.npcs()).thenReturn(new Npcs(api));
        when(snapshot.inventories()).thenReturn(inventories);
        when(snapshot.locations()).thenReturn(locations);
        when(snapshot.npcs()).thenReturn(npcs);
        when(locations.stream()).thenAnswer(inv -> List.<Location>of().stream());
        when(npcs.stream()).thenAnswer(inv -> List.<Npc>of().stream());
        when(api.getLocalPlayer()).thenReturn(new LocalPlayer(0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1,
                false, -1, LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(),
                Orientation.unknown(), LocalPlayer.RUN_UNKNOWN, LocalPlayer.RUN_UNKNOWN));
    }

    /** Defines an item: id, name, category and cache inventory options. */
    InventoryFixture item(int id, String name, int category, String... inventoryOptions) {
        when(api.getItemType(id)).thenReturn(new ItemType(id, name, false, false, 0, 0, category,
                -1, -1, false, List.of(), List.of(inventoryOptions), Map.of()));
        return this;
    }

    /** Fills the backpack: one entry per slot, item ids in slot order, quantity 1. */
    InventoryFixture backpack(int... itemIds) {
        return inventory(Backpack.INVENTORY_ID, PACK_SLOTS, itemIds);
    }

    /** Fills the bank: one entry per slot, item ids in slot order, quantity 1. */
    InventoryFixture bank(int... itemIds) {
        return inventory(Bank.INVENTORY_ID, BANK_SLOTS, itemIds);
    }

    InventoryFixture bankOpen() {
        when(snapshot.isInterfaceOpen(Bank.INTERFACE_ID)).thenReturn(true);
        return this;
    }

    /** One scene object at the local player's tile offering {@code options}. */
    InventoryFixture object(int id, String name, String... options) {
        when(locations.stream()).thenAnswer(inv -> List.of(new Location(id, id, -1, 1, 0, 0, 0, 0, 0)).stream());
        when(api.getLocationType(id)).thenReturn(new LocationType(id, name, 1, 1, 0, 0, false,
                List.of(options), -1, -1, List.of(), -1, Map.of()));
        return this;
    }

    /** One NPC next to the local player offering {@code options}. */
    InventoryFixture npc(int index, int typeId, String name, String... options) {
        when(npcs.stream()).thenAnswer(inv -> List.of(
                new Npc(index, typeId, 1, 0, 0, 0, -1, -1, -1, 1, 1, -1)).stream());
        when(api.getNpcType(typeId)).thenReturn(new NpcType(typeId, name, 0, true, true,
                List.of(options), -1, -1, List.of(), Map.of()));
        return this;
    }

    /** Every action queued so far, in order; empty when none. */
    List<GameAction> queued() {
        ArgumentCaptor<GameAction> captor = ArgumentCaptor.forClass(GameAction.class);
        verify(api, atLeast(0)).queueAction(captor.capture());
        return captor.getAllValues();
    }

    InventoryFixture inventory(int invId, int slots, int... itemIds) {
        List<InventoryItem> items = new ArrayList<>();
        for (int slot = 0; slot < itemIds.length; slot++) {
            int id = itemIds[slot];
            items.add(new InventoryItem(slot, id, id == EMPTY_ITEM ? 0 : 1));
        }
        when(inventories.byInvId(invId)).thenReturn(Optional.of(new Inventory(invId, slots, items)));
        return this;
    }
}
