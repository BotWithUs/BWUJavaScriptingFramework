package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.inventory.Backpack;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>{@link GroundItem#interact(String) Take} against a live client.</b>
 *
 * <p>Drops one backpack item on the character's own tile, finds the stack it made through
 * {@code api.groundItems()}, calls {@code interact("Take")} on it, and asserts the backpack
 * count comes back to where it started. That is the whole script-facing path: snapshot row
 * to {@link GroundItem} to {@code queue_action} to the server. A take whose action names
 * the wrong tile is dispatched and then ignored by the server, so only the backpack can
 * tell a working take from a broken one.</p>
 *
 * <p>The item is the one named by {@code -Dbotwithus.live.groundItemId}, or else the first
 * backpack item whose menus offer both Drop and Take. If the take fails, the teardown tries
 * once more so the fixture is not left on the floor, and logs loudly if that fails too.</p>
 *
 * <p>Drops and picks up an item. Opt in with {@code -Dbotwithus.live.groundItems=true} on
 * top of {@code :core:harnessTest}, with {@code -Dnxtcache.dll} / {@code -Dnxtcache.path}
 * set, because the option names come from the cache.</p>
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
    private static final long DROP_TIMEOUT_MS = 6_000;
    private static final long TAKE_TIMEOUT_MS = 12_000;
    private static final long POLL_MS = 150;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GameAPI api;
    private int itemId = -1;
    private int baselineCount;

    @BeforeAll
    void connect() throws IOException {
        long pid = targetPid();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(PipeClient.NAME_PREFIX + pid));
        cache = NXTCache.tryOpenFromSystemProperty();
        assertNotNull(cache, "ground options need NXTCache: set -Dnxtcache.dll / -Dnxtcache.path");
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()));
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(),
                "the character must be in the world");
    }

    @AfterAll
    void restoreAndDisconnect() throws Exception {
        if (api != null && itemId >= 0 && api.backpack().count(itemId) < baselineCount) {
            log.warn("ground take live: item {} still on the floor; retrying the take once", itemId);
            findOwnStack().ifPresent(g -> g.interact(TAKE));
            if (!waitFor(() -> api.backpack().count(itemId) >= baselineCount, TAKE_TIMEOUT_MS)) {
                log.error("ground take live: FIXTURE LOST, item {} left on the floor", itemId);
            }
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
    void take_putsTheDroppedItemBackInTheBackpack() {
        itemId = chooseItem();
        baselineCount = api.backpack().count(itemId);
        log.info("ground take live: item {} x{} at {}", itemId, baselineCount, api.snapshot().self());

        assertTrue(api.backpack().interactFirst(itemId, DROP), "Drop must be on the item's menu");
        assertTrue(waitFor(() -> findOwnStack().isPresent(), DROP_TIMEOUT_MS),
                "the dropped item never showed up as a ground stack on the character's tile");

        GroundItem stack = findOwnStack().orElseThrow();
        log.info("ground take live: taking {}", stack);
        assertTrue(stack.interact(TAKE), "Take must be on the stack's menu");
        assertTrue(waitFor(() -> api.backpack().count(itemId) >= baselineCount, TAKE_TIMEOUT_MS),
                "Take was queued but the item did not come back to the backpack");
    }

    private int chooseItem() {
        String pinned = System.getProperty(ITEM_PROPERTY);
        if (pinned != null && !pinned.isBlank()) {
            return Integer.parseInt(pinned.trim());
        }
        Backpack bag = api.backpack();
        for (InventoryItem it : bag.getItems()) {
            ItemType type = api.getItemType(it.itemId());
            if (type != null && hasOption(type.inventoryOptions(), DROP)
                    && hasOption(type.groundOptions(), TAKE)) {
                return it.itemId();
            }
        }
        return fail("no backpack item offers both Drop and Take; give the character one");
    }

    /** The stack of the fixture item on the character's own tile and plane. */
    private Optional<GroundItem> findOwnStack() {
        LocalPlayer self = api.snapshot().self();
        return api.groundItems().query().withItemId(itemId).all().stream()
                .filter(g -> g.tileX() == self.tileX() && g.tileY() == self.tileY()
                        && g.plane() == self.plane())
                .findFirst();
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
