package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.test.LiveAgent;
import com.botwithus.bot.core.test.LiveAgentSelection;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@link GroundItem#interact(String) Take} against a live client.</b>
 *
 * <p>Finds a ground stack through {@code api.groundItems()}, calls {@code interact("Take")}
 * on it, and asserts the item arrives in the backpack. That is the whole script-facing path:
 * snapshot row to {@link GroundItem} to {@code queue_action} to the server. A take whose
 * action names the wrong tile is dispatched and then ignored by the server, so only the
 * backpack can tell a working take from a broken one.</p>
 *
 * <p>The fixture is the nearest existing stack on the character's plane within
 * {@link #MAX_STACK_DISTANCE} tiles. It must be the only stack of its item on its tile: a
 * take on a tile with two stacks of one item does not pick up, which is tracked separately
 * and is not what this test is about. {@code -Dbotwithus.live.groundItemId} pins the item.</p>
 *
 * <p><b>This leaves the item in the backpack.</b> Nothing here drops it again, because a
 * by-name drop does not yet send the backpack's real Drop op; dropping it is left to the
 * inventory-op work. On the harness character the stack is a respawning Lumbridge spawn (a
 * knife or a bronze arrow), so what is left behind is one such item in the backpack.</p>
 *
 * <p>Takes an item and may walk the character. Opt in with
 * {@code -Dbotwithus.live.groundItems=true} on top of {@code :core:harnessTest}. The option
 * names come from the cache, opened the way the host opens it
 * ({@link NXTCache#openForHost()}), so no cache flag is needed.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.groundItems", matches = "true")
class LiveGroundItemTakeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveGroundItemTakeTest.class);

    private static final String ITEM_PROPERTY = "botwithus.live.groundItemId";
    private static final String TAKE = "Take";
    /** Furthest existing stack the test will walk to, in tiles (Chebyshev). */
    private static final int MAX_STACK_DISTANCE = 40;
    /** A take on an existing stack includes the walk to it. */
    private static final long TAKE_TIMEOUT_MS = 90_000;
    private static final long POLL_MS = 150;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GameAPI api;
    private int itemId = -1;
    private int baselineCount;

    @BeforeAll
    void connect() throws IOException {
        LiveAgentSelection.Attach agent = LiveAgent.attachOrFail();
        long pid = agent.pid();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        cache = NXTCache.openForHost();
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()));
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(),
                "the character must be in the world");
    }

    @AfterAll
    void disconnect() throws Exception {
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
        GroundItem stack = existingStack();
        log.info("ground take live: taking {} from {} (backpack x{})", stack, api.snapshot().self(),
                baselineCount);

        assertTrue(stack.interact(TAKE), "Take must be on the stack's menu; options are "
                + stack.getOptions());
        assertTrue(waitFor(() -> api.backpack().count(itemId) > baselineCount, TAKE_TIMEOUT_MS),
                "Take was queued but item " + itemId + " did not arrive in the backpack");
        log.info("ground take live: item {} now x{} in the backpack; left there, see the class javadoc",
                itemId, api.backpack().count(itemId));
    }

    private GroundItem existingStack() {
        LocalPlayer self = api.snapshot().self();
        List<GroundItem> all = api.groundItems().query().all();
        Optional<GroundItem> nearest = all.stream()
                .filter(g -> g.plane() == self.plane() && distance(g, self) <= MAX_STACK_DISTANCE)
                .filter(g -> pinnedItem().map(p -> p == g.itemId()).orElse(true))
                .filter(g -> isOnlyStackOnItsTile(g, all))
                .min(Comparator.comparingInt(g -> distance(g, self)));
        Assumptions.assumeTrue(nearest.isPresent(), "SKIPPED, not passed: no single stack within "
                + MAX_STACK_DISTANCE + " tiles");
        itemId = nearest.get().itemId();
        baselineCount = api.backpack().count(itemId);
        return nearest.get();
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
}
