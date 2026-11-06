package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Interfaces;
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
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>{@code interactFirst(id, "Drop")} against a live client.</b>
 *
 * <p>Opens the backpack window, drops one backpack item by name, checks it lands on the
 * character's tile, and takes it back, so the backpack ends as it started. The drop is the
 * subject: the name must resolve to the op the client really has under "Drop" (read with
 * {@code get_component_ops}), and only the item leaving the backpack can tell the right
 * op from a wrong one, since a wrong op is still a valid click on something else.</p>
 *
 * <p>The take back sends the ground action directly, with the item id and the world tile,
 * so this test does not depend on how {@link GroundItem#interact(String)} resolves names.
 * A window this test opened is closed again at the end.</p>
 *
 * <p>Moves an item. Opt in with {@code -Dbotwithus.live.inventoryOps=true} on top of
 * {@code :core:harnessTest}; {@code -Dbotwithus.live.dropItemId} pins the item. The agent
 * must have {@code get_component_ops}; an older one skips with that reason.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.inventoryOps", matches = "true")
class LiveInventoryDropByNameTest {

    private static final Logger log = LoggerFactory.getLogger(LiveInventoryDropByNameTest.class);

    private static final String PID_PROPERTY = "botwithus.harness.pid";
    private static final String ITEM_PROPERTY = "botwithus.live.dropItemId";
    private static final String DROP = "Drop";
    /** The ribbon's button grid; each ribbon slot is a sub of it. */
    private static final int RIBBON_IFACE = 1431;
    private static final int RIBBON_COMP = 0;
    /** Ribbon slot of the backpack (enum 13319: slot 9 is window 2, the backpack). */
    private static final int RIBBON_BACKPACK_SLOT = 9;
    private static final int RIBBON_TOGGLE_OP = 1;
    /** The backpack window's own component; hidden while the window is closed. */
    private static final int WINDOW_IFACE = 1477;
    private static final int WINDOW_COMP = 103;
    private static final int HIDDEN = 1;
    /** Ground op 3 is Take. */
    private static final int GROUND_TAKE_OP = 3;
    private static final long WINDOW_TIMEOUT_MS = 5_000;
    private static final long DROP_TIMEOUT_MS = 8_000;
    private static final long TAKE_TIMEOUT_MS = 30_000;
    private static final long POLL_MS = 150;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GameAPI api;
    private boolean isWindowOpenedHere;
    private int itemId = -1;
    private int baselineCount;

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
        if (api != null) {
            restoreBackpack();
            if (isWindowOpenedHere) {
                toggleBackpackWindow();
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
    void dropByName_putsTheItemOnTheGround() {
        openBackpackWindow();
        InventoryItem item = droppableItem();
        itemId = item.itemId();
        baselineCount = api.backpack().count(itemId);
        Assumptions.assumeTrue(ownTileStacks().isEmpty(), "SKIPPED, not passed: the character's tile"
                + " already holds a stack of item " + itemId + "; move to a clear tile");
        log.info("inventory drop live: dropping item {} from slot {} with ops {}", itemId, item.slot(),
                slotOps(item.slot()));

        assertTrue(api.backpack().interactFirst(itemId, DROP), "Drop must resolve on the slot's ops");
        assertTrue(waitFor(() -> api.backpack().count(itemId) < baselineCount, DROP_TIMEOUT_MS),
                "Drop was sent but item " + itemId + " did not leave the backpack");
        assertTrue(waitFor(() -> ownTileStacks().size() == 1, DROP_TIMEOUT_MS),
                "the dropped item never showed up as one ground stack on the character's tile");
        log.info("inventory drop live: item {} is on the ground at {}", itemId, ownTileStacks().getFirst());
    }

    private void openBackpackWindow() {
        if (!isBackpackWindowHidden()) {
            return;
        }
        toggleBackpackWindow();
        isWindowOpenedHere = true;
        assertTrue(waitFor(() -> !isBackpackWindowHidden(), WINDOW_TIMEOUT_MS),
                "the ribbon toggle did not open the backpack window");
    }

    private boolean isBackpackWindowHidden() {
        Component window = api.getComponent(WINDOW_IFACE, WINDOW_COMP);
        return window == null || window.hidden() == HIDDEN;
    }

    private void toggleBackpackWindow() {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, RIBBON_TOGGLE_OP, RIBBON_BACKPACK_SLOT,
                Interfaces.componentHash(RIBBON_IFACE, RIBBON_COMP)));
    }

    /** The pinned item, or the first backpack item whose slot offers Drop. */
    private InventoryItem droppableItem() {
        Optional<Integer> pinned = pinnedItem();
        for (InventoryItem it : api.backpack().getItems()) {
            boolean isPinnedOrAny = pinned.map(p -> p == it.itemId()).orElse(true);
            if (isPinnedOrAny && slotOps(it.slot()).opNamed(DROP).isPresent()) {
                return it;
            }
        }
        ComponentOps firstSlot = api.backpack().getItems().stream().findFirst()
                .map(it -> slotOps(it.slot())).orElse(ComponentOps.NONE);
        Assumptions.assumeTrue(!firstSlot.equals(ComponentOps.NONE), "SKIPPED, not passed: no item"
                + " in the backpack, or the agent does not answer get_component_ops");
        return fail("no backpack item offers Drop; first slot's ops are " + firstSlot);
    }

    private ComponentOps slotOps(int slot) {
        return api.getComponentOps(Backpack.INTERFACE_ID, Backpack.COMPONENT_ID, slot);
    }

    /** Takes the dropped stack back, so the backpack ends with the count it started with. */
    private void restoreBackpack() {
        if (itemId < 0 || api.backpack().count(itemId) >= baselineCount) {
            return;
        }
        List<GroundItem> stacks = ownTileStacks();
        if (stacks.isEmpty()) {
            log.error("inventory drop live: FIXTURE NOT RESTORED, item {} is not on the character's tile",
                    itemId);
            return;
        }
        GroundItem stack = stacks.getFirst();
        api.queueAction(new GameAction(ActionTypes.GROUND_ITEM_OPTIONS[GROUND_TAKE_OP],
                itemId, stack.tileX(), stack.tileY()));
        if (!waitFor(() -> api.backpack().count(itemId) >= baselineCount, TAKE_TIMEOUT_MS)) {
            log.error("inventory drop live: FIXTURE NOT RESTORED, item {} x{} in the backpack, started x{}",
                    itemId, api.backpack().count(itemId), baselineCount);
        }
    }

    /** Stacks of the fixture item on the character's own tile and plane. */
    private List<GroundItem> ownTileStacks() {
        LocalPlayer self = api.snapshot().self();
        return api.groundItems().query().withItemId(itemId).all().stream()
                .filter(g -> g.tileX() == self.tileX() && g.tileY() == self.tileY()
                        && g.plane() == self.plane())
                .toList();
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
