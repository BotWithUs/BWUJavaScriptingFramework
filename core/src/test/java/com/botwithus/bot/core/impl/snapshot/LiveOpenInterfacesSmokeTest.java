package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live smoke test for the v22 open-interface block: binds to a running agent and
 * checks that the published open-interface list is complete, which is what makes
 * {@link GameSnapshot#isInterfaceOpen(int)} returning {@code false} trustworthy.
 *
 * <p>In-game, the client's open-interface table is always readable and a default
 * HUD alone holds several dozen entries, so a total of {@code 0} or a count short
 * of the total is a failure, not a skip. Before v22 the list was cut at 64 entries
 * with nothing to say so, and an open bank read as closed.</p>
 *
 * <p>{@code -Dbotwithus.live.expectOpenIfaces=<id>[,<id>...]} additionally asserts
 * that each listed interface is open. Set it only when the caller has opened those
 * interfaces first (the harness opens the bank through the agent).</p>
 *
 * <p>Disabled by default; opt in with {@code -Dbotwithus.smoke.live=true}.
 * {@code -Dbotwithus.harness.pid=<pid>} pins the client; without it the first
 * visible agent is used.</p>
 */
class LiveOpenInterfacesSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveOpenInterfacesSmokeTest.class);

    private static final String PID_PROPERTY = "botwithus.harness.pid";
    private static final String EXPECT_PROPERTY = "botwithus.live.expectOpenIfaces";

    /** Game state code published by the producer when the client is in-game. */
    private static final int GAME_STATE_IN_GAME = 30;

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void openInterfaceListIsCompleteInGame() {
        try (SharedRegion region = openTarget()) {
            GameSnapshot snap = new GameSnapshotImpl(region.snapshot());
            int count = snap.openInterfaceCount();
            int total = snap.openInterfaceTotal();
            log.info("live open interfaces: count={} total={} cap={} complete={}",
                    count, total, Layout.OPEN_IFACE_CAP, snap.isOpenInterfaceListComplete());

            assumeTrue(snap.gameState() == GAME_STATE_IN_GAME,
                    "client not in-game (gameState=" + snap.gameState() + ")");
            assertTrue(total > 0, "in-game, the agent must be able to read the open-interface table");
            assertTrue(snap.isOpenInterfaceListComplete(),
                    "open-interface list is incomplete: count=" + count + " total=" + total
                            + " cap=" + Layout.OPEN_IFACE_CAP);
            assertExpectedOpen(snap);
        }
    }

    private static void assertExpectedOpen(GameSnapshot snap) {
        String expected = System.getProperty(EXPECT_PROPERTY, "").trim();
        if (expected.isEmpty()) {
            return;
        }
        int[] ids = Arrays.stream(expected.split(","))
                .map(String::trim)
                .mapToInt(Integer::parseInt)
                .toArray();
        for (int id : ids) {
            log.info("expected open interface {}: open={}", id, snap.isInterfaceOpen(id));
            assertTrue(snap.isInterfaceOpen(id), "interface " + id + " was expected to be open");
        }
    }

    private static SharedRegion openTarget() {
        String pid = System.getProperty(PID_PROPERTY);
        if (pid != null && !pid.isBlank()) {
            return SharedRegion.open(Long.parseLong(pid.trim()));
        }
        Optional<SharedRegion> first = SharedRegion.openFirstAvailable();
        if (first.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible; inject the agent into a running game first");
        }
        return first.orElseThrow();
    }
}
