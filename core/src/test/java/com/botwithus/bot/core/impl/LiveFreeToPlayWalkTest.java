package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Free-to-play routing against a live free-to-play account.</b>
 *
 * <p>Walks a real character through {@link GameAPIImpl#walkWorldPathAsync}, the path a
 * script takes, with {@code worldwalker.dll} and an artifact that carries free-to-play
 * land:</p>
 * <ol>
 *   <li>setting on: Lumbridge to Varrock arrives;</li>
 *   <li>setting on: Port Sarim to Brimhaven fails at once, with no click at all, so none on
 *       the boat or charter NPCs ({@link #MEMBERS_NPC_MIN}..{@link #MEMBERS_NPC_MAX}) or the
 *       gangplank loc ({@link #MEMBERS_LOC});</li>
 *   <li>setting off: the same walk plans and starts, as it always did, and is cancelled
 *       before anything but a walk click reaches the game, so no fare is ever paid.</li>
 * </ol>
 *
 * <p>Every action the walker queues goes through {@link GameAPIImpl#queueAction}, which this
 * test overrides to record it. In the setting-off case the override also <b>swallows</b>
 * any action that is not a walk click and cancels the walk, so that case cannot board
 * anything whatever the planner does.</p>
 *
 * <p>Moves the character. Opt in with {@code -Dbotwithus.live.walks=true} on top of
 * {@code :core:harnessTest}, with {@code -Dworldwalker.dll}, {@code -Dworldwalker.artifact}
 * and {@code -Dworldwalker.teleports} pointed at a build that has
 * {@code ww_executor_run_ex}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.walks", matches = "true")
class LiveFreeToPlayWalkTest {

    private static final Logger log = LoggerFactory.getLogger(LiveFreeToPlayWalkTest.class);

    private static final int GAME_STATE_IN_GAME = 30;
    private static final int WALK_ACTION = 23;
    private static final int MEMBERS_NPC_MIN = 4650;
    private static final int MEMBERS_NPC_MAX = 4656;
    private static final int MEMBERS_LOC = 66990;
    /** Loc option actions (options 1..4, 5, 6); param1 is the loc type. */
    private static final Set<Integer> LOC_ACTIONS = Set.of(3, 4, 5, 6, 1001, 1002);
    /** NPC option actions (options 1..5, 6); param1 is the NPC's server index. */
    private static final Set<Integer> NPC_ACTIONS = Set.of(9, 10, 11, 12, 13, 1003);

    private static final int[] LUMBRIDGE = {3222, 3218};
    private static final int[] VARROCK = {3212, 3424};
    private static final int[] PORT_SARIM = {3029, 3217};
    private static final int[] BRIMHAVEN = {2772, 3225};

    private static final long LEG_TIMEOUT_MS = 8 * 60_000L;
    private static final long FAIL_AT_ONCE_MS = 10_000L;
    private static final long OFF_OBSERVE_MS = 15_000L;
    private static final long POLL_MS = 250L;

    private final List<String> actions = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean isSwallowingNonWalk;
    private SharedRegion region;
    private RpcClient rpc;
    private GameAPIImpl api;

    @BeforeAll
    void connect() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(), "SKIPPED, not passed: no agent pipe visible");
        long pid = SharedRegion.parsePid(pipes.getFirst()).orElseThrow();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(pipes.getFirst()));
        api = new RecordingApi(rpc, () -> new GameSnapshotImpl(region.snapshot()));
        GameSnapshot snap = api.snapshot();
        Assumptions.assumeTrue(snap != null && snap.gameState() == GAME_STATE_IN_GAME,
                "SKIPPED, not passed: the client is not in the world");
        Assumptions.assumeFalse(snap.self().isMember(),
                "SKIPPED, not passed: this account reads as a member, so the restriction never applies");
        log.info("connected to pid {}; self at ({},{},{}) isMember={}", pid, snap.self().tileX(),
                snap.self().tileY(), snap.self().plane(), snap.self().isMember());
    }

    @AfterAll
    void disconnect() {
        if (api != null) {
            api.walkCancel();
            api.setRestrictFreeToPlay(false);
        }
        if (rpc != null) {
            rpc.close();
        }
    }

    @Test
    @Order(1)
    void settingOn_lumbridgeToVarrock_arrives() {
        api.setRestrictFreeToPlay(true);
        assertEquals("arrived", walk(LUMBRIDGE), "reaching Lumbridge first");
        actions.clear();

        assertEquals("arrived", walk(VARROCK));
        assertTrue(actions.stream().noneMatch(a -> a.contains("members")), actions.toString());
    }

    @Test
    @Order(2)
    void settingOn_portSarimToBrimhaven_failsAtOnce_withoutAClick() {
        api.setRestrictFreeToPlay(true);
        assertEquals("arrived", walk(PORT_SARIM), "reaching Port Sarim first");
        LocalPlayer before = api.snapshot().self();
        actions.clear();

        long start = System.currentTimeMillis();
        String state = walk(BRIMHAVEN);
        long tookMs = System.currentTimeMillis() - start;
        LocalPlayer after = api.snapshot().self();
        log.info("restricted Port Sarim -> Brimhaven: {} in {} ms, actions {}", state, tookMs, actions);

        assertEquals("failed", state);
        assertTrue(tookMs < FAIL_AT_ONCE_MS, "failed at once, not after walking: " + tookMs + " ms");
        assertEquals(List.of(), actions, "no click of any kind");
        assertEquals(before.tileX(), after.tileX());
        assertEquals(before.tileY(), after.tileY());
        assertTrue(!api.isReachable(BRIMHAVEN[0], BRIMHAVEN[1]), "isReachable agrees with the walk");
    }

    @Test
    @Order(3)
    void settingOff_portSarimToBrimhaven_startsTodaysRoute_andIsCancelledBeforeAnyFare() {
        api.setRestrictFreeToPlay(false);
        assertTrue(api.isReachable(BRIMHAVEN[0], BRIMHAVEN[1]), "unrestricted, a route exists");
        actions.clear();
        isSwallowingNonWalk = true;
        try {
            api.walkWorldPathAsync(BRIMHAVEN[0], BRIMHAVEN[1], 0);
            // The guard cancels the walk itself the moment the walker reaches for anything
            // but a walk click, so "cancelled" is the expected end, and "failed" is the
            // restricted behaviour this case must not show.
            String state = waitFor(s -> !"walking".equals(s) || hasNonWalkAction(), OFF_OBSERVE_MS);
            api.walkCancel();
            log.info("unrestricted Port Sarim -> Brimhaven: state {} at cancel, actions {}", state, actions);
            assertTrue(!"failed".equals(state), "unrestricted, the walk starts rather than failing");
            assertTrue(!actions.isEmpty(), "unrestricted, the walk clicked toward its route");
        } finally {
            api.walkCancel();
            waitFor(s -> !"walking".equals(s), FAIL_AT_ONCE_MS);
            isSwallowingNonWalk = false;
        }
        assertTrue(actions.stream().noneMatch(a -> a.startsWith("sent ") && !a.startsWith("sent walk")),
                "nothing but walk clicks reached the game: " + actions);
    }

    private String walk(int[] goal) {
        api.walkWorldPathAsync(goal[0], goal[1], 0);
        String state = waitFor(s -> !"walking".equals(s), LEG_TIMEOUT_MS);
        LocalPlayer self = api.snapshot().self();
        log.info("walk to ({},{}) ended {} at ({},{})", goal[0], goal[1], state, self.tileX(), self.tileY());
        return state;
    }

    private String waitFor(Predicate<String> isDone, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String state = api.getWalkStatus().state();
        while (!isDone.test(state) && System.currentTimeMillis() < deadline) {
            sleep();
            state = api.getWalkStatus().state();
        }
        return state;
    }

    private boolean hasNonWalkAction() {
        return actions.stream().anyMatch(a -> !a.contains("walk"));
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Records every action the walker queues and, when asked, keeps all but walk clicks back. */
    private final class RecordingApi extends GameAPIImpl {

        RecordingApi(RpcClient rpc, Supplier<GameSnapshot> snapshots) {
            super(rpc, null, snapshots);
        }

        @Override
        public void queueAction(GameAction action) {
            String what = describe(action);
            boolean isWalk = action.actionId() == WALK_ACTION;
            if (!isWalk && isSwallowingNonWalk) {
                actions.add("swallowed " + what);
                walkCancel();
                return;
            }
            actions.add("sent " + what);
            super.queueAction(action);
        }

        private String describe(GameAction action) {
            if (action.actionId() == WALK_ACTION) {
                return "walk " + action.param2() + "," + action.param3();
            }
            if (LOC_ACTIONS.contains(action.actionId())) {
                return "loc " + action.param1() + (action.param1() == MEMBERS_LOC ? " members" : "");
            }
            if (NPC_ACTIONS.contains(action.actionId())) {
                int type = npcType(action.param1());
                boolean isMembers = type >= MEMBERS_NPC_MIN && type <= MEMBERS_NPC_MAX;
                return "npc " + type + (isMembers ? " members" : "");
            }
            return "action " + action;
        }

        private int npcType(int serverIndex) {
            GameSnapshot snap = snapshot();
            if (snap == null) {
                return -1;
            }
            return snap.npcs().stream().filter(n -> n.serverIndex() == serverIndex)
                    .mapToInt(Npc::typeId).findFirst().orElse(-1);
        }
    }
}
