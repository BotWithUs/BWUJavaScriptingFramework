package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>{@link GroundItem#interact(String) Take} against a live client.</b>
 *
 * <p>Finds a ground stack through {@code api.groundItems()}, calls {@code interact("Take")}
 * on it, and asserts the item arrives in the backpack. That is the whole script-facing path:
 * snapshot row to {@link GroundItem} to {@code queue_action} to the server. A take whose
 * action names the wrong tile is dispatched and then ignored by the server, so only the
 * backpack can tell a working take from a broken one.</p>
 *
 * <p>Two fixtures, chosen in this order:</p>
 * <ol>
 *   <li><b>Own drop.</b> If a backpack item offers both Drop and Take, it is dropped on the
 *       character's tile and taken back. Skips with a named reason when the drop is ignored,
 *       which happens while the backpack window is closed.</li>
 *   <li><b>Existing stack.</b> Otherwise the nearest stack on the character's plane within
 *       {@link #MAX_STACK_DISTANCE} tiles. Teardown drops the item again so the backpack ends
 *       as it started.</li>
 * </ol>
 *
 * <p>Either way the stack must be the only stack of its item on its tile: a take on a tile
 * with two stacks of one item does not pick up, which is tracked separately and is not what
 * this test is about. {@code -Dbotwithus.live.groundItemId} pins the item.</p>
 *
 * <p>Moves an item and may walk the character. Opt in with
 * {@code -Dbotwithus.live.groundItems=true} on top of {@code :core:harnessTest}. The option
 * names come from the cache, opened the way the host opens it
 * ({@link NXTCache#openForHost()}), so no cache flag is needed.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.groundItems", matches = "true")
class LiveGroundItemTakeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveGroundItemTakeTest.class);

    private static final String PID_PROPERTY = "botwithus.harness.pid";
    private static final String ITEM_PROPERTY = "botwithus.live.groundItemId";
    private static final String DROP = "Drop";
    private static final String TAKE = "Take";
    /** The backpack's interface:component; drops are ignored while it is closed. */
    private static final String BACKPACK_WINDOW = "1477:102";
    /** Furthest existing stack the test will walk to, in tiles (Chebyshev). */
    private static final int MAX_STACK_DISTANCE = 40;
    private static final long DROP_TIMEOUT_MS = 6_000;
    /** A take on an existing stack includes the walk to it. */
    private static final long TAKE_TIMEOUT_MS = 90_000;
    private static final long POLL_MS = 150;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GameAPI api;
    private int itemId = -1;
    private int baselineCount;
    private boolean isOwnDrop;

    @BeforeAll
    void connect() throws IOException {
        long pid = targetPid();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(PipeClient.NAME_PREFIX + pid));
        cache = NXTCache.openForHost();
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()));
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(),
                "the character must be in the world");
    }

    @AfterAll
    void restoreAndDisconnect() throws Exception {
        if (api != null && itemId >= 0) {
            restoreBackpack();
        }
        if (rpc != null) {
            rpc.close();
        }
        if (cache != null) {
            cache.close();
        }
        if (region != null) {
            region.close();
        }
    }

    @Test
    void take_putsTheItemInTheBackpack() {
        OptionalInt droppable = droppableItem();
        GroundItem stack = droppable.isPresent() ? dropOwn(droppable.getAsInt()) : existingStack();
        log.info("ground take live: taking {} from {} (backpack x{})", stack, api.snapshot().self(),
                baselineCount);

        assertTrue(stack.interact(TAKE), "Take must be on the stack's menu; options are "
                + stack.getOptions());
        int expected = isOwnDrop ? baselineCount : baselineCount + 1;
        assertTrue(waitFor(() -> api.backpack().count(itemId) >= expected, TAKE_TIMEOUT_MS),
                "Take was queued but item " + itemId + " did not arrive in the backpack");
        log.info("ground take live: item {} now x{} in the backpack", itemId, api.backpack().count(itemId));
    }

    private GroundItem dropOwn(int id) {
        itemId = id;
        isOwnDrop = true;
        baselineCount = api.backpack().count(itemId);
        Assumptions.assumeTrue(ownTileStacks().isEmpty(), "SKIPPED, not passed: the character's tile"
                + " already holds a stack of item " + itemId + "; move to a clear tile");

        assertTrue(api.backpack().interactFirst(itemId, DROP), "Drop must be on the item's menu");
        Assumptions.assumeTrue(waitFor(() -> api.backpack().count(itemId) < baselineCount, DROP_TIMEOUT_MS),
                "SKIPPED, not passed: the drop was ignored; the backpack window (" + BACKPACK_WINDOW
                        + ") is closed on some launches, so open it and rerun");
        assertTrue(waitFor(() -> ownTileStacks().size() == 1, DROP_TIMEOUT_MS),
                "the dropped item never showed up as one ground stack on the character's tile");
        return ownTileStacks().getFirst();
    }

    private GroundItem existingStack() {
        LocalPlayer self = api.snapshot().self();
        List<GroundItem> all = api.groundItems().query().all();
        Optional<GroundItem> nearest = all.stream()
                .filter(g -> g.plane() == self.plane() && distance(g, self) <= MAX_STACK_DISTANCE)
                .filter(g -> pinnedItem().map(p -> p == g.itemId()).orElse(true))
                .filter(g -> isOnlyStackOnItsTile(g, all))
                .min(Comparator.comparingInt(g -> distance(g, self)));
        Assumptions.assumeTrue(nearest.isPresent(), "SKIPPED, not passed: nothing droppable in the"
                + " backpack and no single stack within " + MAX_STACK_DISTANCE + " tiles");
        itemId = nearest.get().itemId();
        baselineCount = api.backpack().count(itemId);
        return nearest.get();
    }

    /** Puts the backpack back to its starting count of the fixture item, best effort. */
    private void restoreBackpack() {
        int now = api.backpack().count(itemId);
        if (isOwnDrop && now < baselineCount) {
            log.warn("ground take live: item {} still on the floor; retrying the take once", itemId);
            ownTileStacks().stream().findFirst().ifPresent(g -> g.interact(TAKE));
        } else if (!isOwnDrop && now > baselineCount) {
            api.backpack().interactFirst(itemId, DROP);
        } else {
            return;
        }
        if (!waitFor(() -> api.backpack().count(itemId) == baselineCount, TAKE_TIMEOUT_MS)) {
            log.error("ground take live: FIXTURE NOT RESTORED, item {} x{} in the backpack, started x{}",
                    itemId, api.backpack().count(itemId), baselineCount);
        }
    }

    private OptionalInt droppableItem() {
        for (InventoryItem it : api.backpack().getItems()) {
            ItemType type = api.getItemType(it.itemId());
            boolean isPinnedOrAny = pinnedItem().map(p -> p == it.itemId()).orElse(true);
            if (isPinnedOrAny && type != null && hasOption(type.inventoryOptions(), DROP)
                    && hasOption(type.groundOptions(), TAKE)) {
                return OptionalInt.of(it.itemId());
            }
        }
        return OptionalInt.empty();
    }

    /** Stacks of the fixture item on the character's own tile and plane. */
    private List<GroundItem> ownTileStacks() {
        LocalPlayer self = api.snapshot().self();
        return api.groundItems().query().withItemId(itemId).all().stream()
                .filter(g -> g.tileX() == self.tileX() && g.tileY() == self.tileY()
                        && g.plane() == self.plane())
                .toList();
    }

    private static boolean isOnlyStackOnItsTile(GroundItem stack, List<GroundItem> all) {
        return all.stream().filter(g -> g.itemId() == stack.itemId() && g.tileX() == stack.tileX()
                && g.tileY() == stack.tileY() && g.plane() == stack.plane()).count() == 1;
    }

    private static int distance(GroundItem g, LocalPlayer self) {
        return Math.max(Math.abs(g.tileX() - self.tileX()), Math.abs(g.tileY() - self.tileY()));
    }

    private static Optional<Integer> pinnedItem() {
        String pinned = System.getProperty(ITEM_PROPERTY);
        if (pinned == null || pinned.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(pinned.trim()));
    }

    private static boolean hasOption(List<String> options, String wanted) {
        return options != null && options.stream().anyMatch(wanted::equalsIgnoreCase);
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static long targetPid() {
        String pid = System.getProperty(PID_PROPERTY);
        if (pid != null && !pid.isBlank()) {
            return Long.parseLong(pid.trim());
        }
        List<Long> pids = SharedRegion.discoverPids();
        if (pids.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible; inject the agent into a running game first");
        }
        return pids.getFirst();
    }
}
