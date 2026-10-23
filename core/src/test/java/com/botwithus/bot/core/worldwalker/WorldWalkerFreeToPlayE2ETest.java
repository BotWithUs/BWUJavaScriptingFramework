package com.botwithus.bot.core.worldwalker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The free-to-play restriction through the real downcalls ({@code ww_query_moves}
 * and {@code ww_executor_run_ex}) against a real {@code worldwalker.dll} and an
 * artifact that carries free-to-play land.
 *
 * <p>Gated on {@code -Dworldwalker.dll} and {@code -Dworldwalker.f2pArtifact}. The
 * second is separate from the other E2E test's artifact on purpose: these
 * assertions only hold for an artifact baked with the free-to-play zones section,
 * and an older one would fail them for a reason that is not a host bug. Runs under
 * {@code :core:worldwalkerE2ETest}.</p>
 */
@EnabledIfSystemProperty(named = "worldwalker.dll",         matches = ".+")
@EnabledIfSystemProperty(named = "worldwalker.f2pArtifact", matches = ".+")
class WorldWalkerFreeToPlayE2ETest {

    private static final WwTile LUMBRIDGE = new WwTile(3222, 3218, 0);
    private static final WwGoal VARROCK = new WwGoal(3212, 3424, 0, 0);
    private static final WwTile PORT_SARIM = new WwTile(3029, 3217, 0);
    private static final WwGoal BRIMHAVEN = new WwGoal(2772, 3225, 0, 0);
    private static final WwGoal LUMBRIDGE_NEARBY = new WwGoal(3216, 3214, 0, 0);

    @Test
    void aFreeToPlayRoute_isPlannedEitherWay() throws Exception {
        try (WorldWalker walker = open()) {
            assertNotNull(walker.query(LUMBRIDGE, VARROCK, null, null, DisabledMoves.NONE));
            assertNotNull(walker.query(LUMBRIDGE, VARROCK, null, null, DisabledMoves.FREE_TO_PLAY));
        }
    }

    @Test
    void aMembersGoal_hasNoRoute_onlyWhenRestricted() throws Exception {
        try (WorldWalker walker = open()) {
            assumeTrue(walker.query(PORT_SARIM, BRIMHAVEN, null, null, DisabledMoves.NONE) != null,
                    "this artifact has no unrestricted route to the members goal");
            assertNull(walker.query(PORT_SARIM, BRIMHAVEN, null, null, DisabledMoves.FREE_TO_PLAY));
        }
    }

    @Test
    void theExecutor_failsAMembersGoalAtOnce_withoutAClick() throws Exception {
        try (WorldWalker walker = open()) {
            StandStill stub = new StandStill(PORT_SARIM);

            WwStatus status = walker.runExecutor(BRIMHAVEN, stub, DisabledMoves.FREE_TO_PLAY);

            assertEquals(WwStatus.FAILED, status);
            assertEquals(0, stub.walkToCalls.get(), "no walk click");
            assertEquals(0, stub.interactCalls.get(), "no loc or NPC click");
            assertEquals(0, stub.chainStepCalls.get(), "no chain step");
        }
    }

    /**
     * A free-to-play walk still arrives with the restriction requested. Against a
     * library without {@code ww_executor_run_ex} this is the fallback: the walk runs
     * unrestricted rather than not at all, which is why this case also passes
     * against an old {@code worldwalker.dll} that the others fail on.
     */
    @Test
    void aFreeToPlayWalk_arrivesWithTheRestrictionRequested() throws Exception {
        try (WorldWalker walker = open()) {
            WwPathResult plan = walker.query(LUMBRIDGE, LUMBRIDGE_NEARBY, null, null, DisabledMoves.NONE);
            assumeTrue(plan != null && plan.steps().stream().allMatch(s -> s.kind() == StepKind.WALK),
                    "this artifact has no plain walk between the two tiles");
            StandStill stub = new StandStill(LUMBRIDGE);
            stub.isJumping = true;

            WwStatus status = walker.runExecutor(LUMBRIDGE_NEARBY, stub, DisabledMoves.FREE_TO_PLAY);

            assertEquals(WwStatus.ARRIVED, status);
            assertTrue(stub.walkToCalls.get() >= 1, "the walk clicked");
        }
    }

    private static WorldWalker open() throws Exception {
        Path artifact = Path.of(System.getProperty("worldwalker.f2pArtifact"));
        assertTrue(Files.exists(artifact), "artifact missing at " + artifact);
        return WorldWalker.open(artifact, 1);
    }

    /**
     * A player who never moves, counting every click the executor asks for; or, with
     * {@code isJumping}, one who arrives wherever a walk click sends them.
     */
    private static final class StandStill implements WwCallbacks {

        final AtomicInteger walkToCalls = new AtomicInteger();
        final AtomicInteger interactCalls = new AtomicInteger();
        final AtomicInteger chainStepCalls = new AtomicInteger();
        volatile boolean isJumping;
        private volatile WwTile position;

        StandStill(WwTile position) {
            this.position = position;
        }

        @Override
        public WwTile readPosition() {
            return position;
        }

        @Override
        public CapabilitySnapshot readCapability() {
            return CapabilitySnapshot.empty();
        }

        @Override
        public int readVarbit(int id) {
            return 0;
        }

        @Override
        public int readItemCount(int itemId) {
            return 0;
        }

        @Override
        public boolean isItemWorn(int itemId) {
            return false;
        }

        @Override
        public boolean isInterfaceOpen(int interfaceId) {
            return false;
        }

        @Override
        public void walkTo(WwTile target) {
            walkToCalls.incrementAndGet();
            if (isJumping) {
                position = target;
            }
        }

        @Override
        public int interact(int objectId, WwTile tile, int optionIndex) {
            interactCalls.incrementAndGet();
            return 0;
        }

        @Override
        public void runChainStep(int kind, int a, int b, int c, int d, int e, int f, int g, int h, int i) {
            chainStepCalls.incrementAndGet();
        }

        @Override
        public void sleepTicks(int ticks) {
            // The player never moves, so there is nothing to wait for.
        }

        @Override
        public boolean shouldCancel() {
            // Cancel rather than hang if the restriction did not stop the walk.
            return !isJumping && walkToCalls.get() + interactCalls.get() + chainStepCalls.get() > 0;
        }
    }
}
