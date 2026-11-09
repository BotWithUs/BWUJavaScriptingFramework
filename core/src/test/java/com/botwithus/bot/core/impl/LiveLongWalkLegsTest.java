package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.ActionRefusal;
import com.botwithus.bot.core.shm.ActionRefusalFeed;
import com.botwithus.bot.core.shm.EventRingReader;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.test.LiveAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Every walk leg within the minimap's reach, against a live agent that clicks every action
 * (nxt-library #70).</b>
 *
 * <p>Walks a real character more than 200 tiles, Lumbridge to Varrock, through
 * {@link GameAPIImpl#walkWorldPathAsync}. Every walk click the walker queues is measured from the
 * player's tile at that moment, and must be within {@link #MAX_LEG_TILES} in a straight line: the
 * minimap reaches about 27.5 tiles at default zoom, and #70 refuses an off-screen walk it cannot
 * click there. The agent's {@code click_stats} must count no new {@code refused_anchor_timeout},
 * and the event ring must carry no refused walk.</p>
 *
 * <p>Moves the character. Opt in with {@code -Dbotwithus.live.walks=true} on top of
 * {@code :core:harnessTest}, with {@code -Dworldwalker.dll}, {@code -Dworldwalker.artifact} and
 * {@code -Dworldwalker.teleports} pointed at a WorldWalker build with the capped legs.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.walks", matches = "true")
class LiveLongWalkLegsTest {

    private static final Logger log = LoggerFactory.getLogger(LiveLongWalkLegsTest.class);

    private static final int GAME_STATE_IN_GAME = 30;
    /** WorldWalker's leg cap (kLegMaxTiles), mirrored rather than derived. */
    private static final int MAX_LEG_TILES = 22;
    private static final String REFUSED_ANCHOR_TIMEOUT = "refused_anchor_timeout";

    private static final int[] LUMBRIDGE = {3222, 3218};
    private static final int[] VARROCK = {3212, 3424};
    private static final int MIN_ROUTE_TILES = 100;

    private static final long LEG_TIMEOUT_MS = 8 * 60_000L;
    private static final long POLL_MS = 250L;
    private static final long RING_POLL_MS = 50L;

    /** Each walk click and the tile the player stood on when it was queued. */
    private record Click(int fromX, int fromY, int toX, int toY) {
        double reach() {
            return Math.hypot(toX - fromX, toY - fromY);
        }
    }

    private final List<Click> clicks = Collections.synchronizedList(new ArrayList<>());
    private final List<ActionRefusal> refusals = Collections.synchronizedList(new ArrayList<>());
    private final ActionRefusalFeed feed = new ActionRefusalFeed();
    private SharedRegion region;
    private RpcClient rpc;
    private GameAPIImpl api;
    private Thread ringPump;
    private volatile boolean isPumping = true;

    @BeforeAll
    void connect() {
        AgentSelection.Attach agent = LiveAgent.attachOrSkip();
        long pid = agent.pid();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        api = new RecordingApi(rpc, () -> new GameSnapshotImpl(region.snapshot()), feed);
        GameSnapshot snap = api.snapshot();
        Assumptions.assumeTrue(snap != null && snap.gameState() == GAME_STATE_IN_GAME,
                "SKIPPED, not passed: the client is not in the world");
        feed.subscribe(refusals::add);
        EventRingReader reader = new EventRingReader(region, true, feed::publish);
        ringPump = Thread.ofPlatform().daemon(true).name("live-legs-ring").start(() -> pumpRing(reader));
        log.info("connected to pid {}; self at ({},{},{})", pid, snap.self().tileX(),
                snap.self().tileY(), snap.self().plane());
    }

    @AfterAll
    void disconnect() {
        isPumping = false;
        if (ringPump != null) {
            ringPump.interrupt();
        }
        if (api != null) {
            api.walkCancel();
        }
        if (rpc != null) {
            rpc.close();
        }
    }

    @Test
    void longWalkKeepsEveryLegInMinimapReach() {
        assertEquals("arrived", walk(LUMBRIDGE), "reaching Lumbridge first");
        clicks.clear();
        refusals.clear();
        long refusedBefore = refusedAnchorTimeouts();
        LocalPlayer start = api.snapshot().self();

        String state = walk(VARROCK);

        long refusedAfter = refusedAnchorTimeouts();
        LocalPlayer end = api.snapshot().self();
        int route = Math.max(Math.abs(end.tileX() - start.tileX()), Math.abs(end.tileY() - start.tileY()));
        double farthest = clicks.stream().mapToDouble(Click::reach).max().orElse(0);
        List<Click> overCap = clicks.stream().filter(c -> c.reach() > MAX_LEG_TILES).toList();
        log.info("Lumbridge -> Varrock: {} over {} tiles, {} walk clicks, farthest {} tiles, over cap {},"
                        + " click_stats refused_anchor_timeout {} -> {}, refusals heard {}",
                state, route, clicks.size(), String.format("%.1f", farthest), overCap, refusedBefore,
                refusedAfter, refusals);

        assertEquals("arrived", state);
        assertTrue(route >= MIN_ROUTE_TILES, "the route was long: " + route + " tiles");
        assertEquals(List.of(), overCap, "every walk click within " + MAX_LEG_TILES + " tiles");
        assertEquals(refusedBefore, refusedAfter, "no walk refused for want of a click");
        assertEquals(List.of(), refusals, "no action refused on the ring");
    }

    private long refusedAnchorTimeouts() {
        Map<String, Object> stats = rpc.callSync("click_stats", Map.of());
        Object value = stats.get(REFUSED_ANCHOR_TIMEOUT);
        Assumptions.assumeTrue(value != null,
                "SKIPPED, not passed: this agent's click_stats has no " + REFUSED_ANCHOR_TIMEOUT
                        + " (not an nxt-library #70 build)");
        return Long.parseLong(String.valueOf(value));
    }

    private String walk(int[] goal) {
        api.walkWorldPathAsync(goal[0], goal[1], 0);
        long deadline = System.currentTimeMillis() + LEG_TIMEOUT_MS;
        String state = api.getWalkStatus().state();
        while ("walking".equals(state) && System.currentTimeMillis() < deadline) {
            sleep(POLL_MS);
            state = api.getWalkStatus().state();
        }
        LocalPlayer self = api.snapshot().self();
        log.info("walk to ({},{}) ended {} at ({},{})", goal[0], goal[1], state, self.tileX(), self.tileY());
        return state;
    }

    private void pumpRing(EventRingReader reader) {
        while (isPumping) {
            reader.poll(event -> { });
            try {
                Thread.sleep(RING_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Records every walk click with the tile the player stood on when it was queued. */
    private final class RecordingApi extends GameAPIImpl {

        RecordingApi(RpcClient rpc, Supplier<GameSnapshot> snapshots, ActionRefusalFeed refusalFeed) {
            super(rpc, null, snapshots, new StubGuard(), event -> { }, GamevalIndex.empty(), refusalFeed);
        }

        @Override
        public void queueAction(GameAction action) {
            GameSnapshot snap = snapshot();
            if (action.actionId() == ActionTypes.WALK && snap != null && snap.self() != null) {
                clicks.add(new Click(snap.self().tileX(), snap.self().tileY(), action.param2(),
                        action.param3()));
            }
            super.queueAction(action);
        }
    }
}
