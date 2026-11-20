package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.InventoryItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An owned copy of the parts of one snapshot that {@link SnapshotChangeDetector} compares
 * tick to tick: every published inventory and the local player's skills.
 *
 * <p>Owned rather than a flyweight because the detector keeps the previous tick's copy, and
 * the mapping it came from has been rewritten by then.</p>
 *
 * @param serverTick  the server tick the copy was taken on
 * @param isInGame    whether the client was in game ({@link GameSnapshot#GAME_STATE_IN_GAME})
 * @param inventories inventory id to its slots, in slot order
 * @param skills      skill id to its experience and levels
 */
record TickState(int serverTick, boolean isInGame, Map<Integer, List<InventoryItem>> inventories,
                 Map<Integer, SkillEntry> skills) {

    TickState {
        inventories = Map.copyOf(inventories);
        skills = Map.copyOf(skills);
    }

    /**
     * Copies {@code view}, or returns empty when the producer republished while the copy was
     * being taken. The mapping is double-buffered with no seqlock, so a copy that straddles a
     * republish can mix two ticks; re-reading {@code publishSeq} around the copy is the only
     * way to tell, and a torn copy would surface as phantom change events.
     */
    static Optional<TickState> capture(SnapshotView view) {
        long seqBefore = view.publishSeq();
        TickState state = new TickState(view.serverTick(),
                view.gameState() == GameSnapshot.GAME_STATE_IN_GAME,
                copyInventories(view), copySkills(view.self()));
        if (view.publishSeq() != seqBefore) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    private static Map<Integer, List<InventoryItem>> copyInventories(SnapshotView view) {
        Map<Integer, List<InventoryItem>> inventories = new HashMap<>();
        int itemCount = view.invItemCount();
        for (int i = 0; i < view.inventoryCount(); i++) {
            int first = view.firstItemIdx(i);
            int slots = view.slotCount(i);
            if (first + slots > itemCount) {
                // A header pointing past the item buffer is a torn or broken publish; leave
                // the inventory out rather than diffing garbage against the last tick.
                continue;
            }
            InventoryItem[] items = new InventoryItem[slots];
            for (int slot = 0; slot < slots; slot++) {
                items[slot] = new InventoryItem(slot, view.itemIdAt(first + slot),
                        view.itemQtyAt(first + slot));
            }
            inventories.put(view.invId(i), List.of(items));
        }
        return inventories;
    }

    private static Map<Integer, SkillEntry> copySkills(LocalPlayerView self) {
        Map<Integer, SkillEntry> skills = new HashMap<>();
        for (int i = 0; i < self.skillCount(); i++) {
            SkillEntry skill = self.skill(i);
            skills.put(skill.typeId(), skill);
        }
        return skills;
    }
}
