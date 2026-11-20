package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.InventoryChangeEvent;
import com.botwithus.bot.api.event.SkillChangeEvent;
import com.botwithus.bot.api.snapshot.InventoryItem;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Derives {@link InventoryChangeEvent} and {@link SkillChangeEvent} by comparing the snapshot
 * once per server tick with the copy taken on the previous tick. The agent sends neither
 * event; this is the only producer of both.
 *
 * <p>Cost: {@link #observe(SnapshotView)} is called on every event-pump poll, but reads one
 * integer unless {@code serverTick} has advanced, so the copy and the compare run once per
 * 600&nbsp;ms. An inventory is compared slot by slot only when its slot list differs as a
 * whole, so an idle tick allocates nothing beyond the copy.</p>
 *
 * <p>Not thread-safe: the event pump's thread is its only caller.</p>
 */
public final class SnapshotChangeDetector {

    /** {@link InventoryItem#isEmpty()}'s item id, used for a slot past an inventory's end. */
    private static final int EMPTY_ITEM_ID = -1;

    private final Consumer<GameEvent> sink;
    private TickState previous;

    public SnapshotChangeDetector(Consumer<GameEvent> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Compares {@code view} with the previous tick's copy and publishes what changed. Does
     * nothing when the tick has not advanced or when the copy was torn by a republish (the
     * next poll retries).
     */
    public void observe(SnapshotView view) {
        if (previous != null && view.serverTick() == previous.serverTick()) {
            return;
        }
        TickState.capture(view).ifPresent(this::observe);
    }

    /** Test seam: compares an already-captured state. */
    void observe(TickState current) {
        if (!current.isInGame()) {
            previous = null;
            return;
        }
        if (previous != null && current.serverTick() != previous.serverTick()) {
            publishInventoryChanges(previous, current);
            publishSkillChanges(previous, current);
        }
        previous = current;
    }

    private void publishInventoryChanges(TickState before, TickState after) {
        for (Map.Entry<Integer, List<InventoryItem>> entry : after.inventories().entrySet()) {
            List<InventoryItem> old = before.inventories().get(entry.getKey());
            if (old == null || old.equals(entry.getValue())) {
                continue;
            }
            publishSlotChanges(entry.getKey(), old, entry.getValue(), after.serverTick());
        }
    }

    private void publishSlotChanges(int invId, List<InventoryItem> old, List<InventoryItem> now,
                                    int serverTick) {
        int slots = Math.max(old.size(), now.size());
        for (int slot = 0; slot < slots; slot++) {
            InventoryItem was = slotOrEmpty(old, slot);
            InventoryItem is = slotOrEmpty(now, slot);
            if (!was.equals(is)) {
                sink.accept(new InventoryChangeEvent(invId, slot, was, is, serverTick));
            }
        }
    }

    private static InventoryItem slotOrEmpty(List<InventoryItem> items, int slot) {
        return slot < items.size() ? items.get(slot) : new InventoryItem(slot, EMPTY_ITEM_ID, 0);
    }

    private void publishSkillChanges(TickState before, TickState after) {
        for (SkillEntry now : after.skills().values()) {
            SkillEntry was = before.skills().get(now.typeId());
            if (was == null || was.equals(now)) {
                continue;
            }
            sink.accept(new SkillChangeEvent(now.typeId(), was.experience(), now.experience(),
                    was.actualLevel(), now.actualLevel(), was.boostedLevel(),
                    now.boostedLevel(), after.serverTick()));
        }
    }
}
