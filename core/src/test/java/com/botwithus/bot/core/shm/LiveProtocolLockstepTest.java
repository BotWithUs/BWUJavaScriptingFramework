package com.botwithus.bot.core.shm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the running agent and the running host agree about the wire, by
 * <em>executing</em> the comparison that gates every snapshot read rather than
 * by comparing two reported numbers.
 *
 * <h2>Why this does not read a version</h2>
 *
 * <p>The obvious check is "ask the agent its protocol version, ask the host
 * its protocol version, compare". That check is wrong here, and not subtly:
 * {@link Layout#PROTOCOL_VERSION} is a {@code public static final int}, so
 * javac <b>inlines</b> it into every call site. Disassembling the method that
 * actually rejects a mismatched mapping shows the constant baked in, with no
 * field read at all:</p>
 *
 * <pre>
 * javap -c -p com.botwithus.bot.core.shm.SharedRegion   # validateMagicAndVersion
 *     66: bipush        20
 *     68: if_icmpeq     85
 * </pre>
 *
 * <p>There is no {@code getstatic Layout.PROTOCOL_VERSION} in that method. So
 * a check that reads {@code Layout.PROTOCOL_VERSION} — by reflection, from a
 * test, or out of {@code Layout.class} — reads a field the rejecting code does
 * not consult. Whenever the two copies diverge, the number-comparing check
 * reports agreement the running code will not honour.</p>
 *
 * <p>So this attaches instead. {@link SharedRegion#openFirstAvailable()} runs
 * {@code validateMagicAndVersion}, which throws {@link SharedMemoryException}
 * naming both sides on a mismatch. A successful attach is agreement <b>by
 * construction</b>: the bytes on the wire passed the exact comparison that
 * gates every snapshot read, so there is no number left to be wrong about.</p>
 *
 * <h2>Why this fails rather than skips</h2>
 *
 * <p>The rest of the {@code Live*} family self-skips without an injected
 * agent, which is right for them — they test behaviour that needs a game. This
 * one is a gate: the harness guarantees a live agent before it runs, so "no
 * agent" here means the guarantee broke. Skipping would turn the one check
 * built to catch a silent disagreement into a silent pass.</p>
 *
 * <p><b>Consequence for a bare {@code ./gradlew :core:harnessTest} with no
 * agent injected:</b> this class <em>fails</em> where the rest of the family
 * skips. That is deliberate, and it is the only class in the family that
 * behaves that way.</p>
 *
 * <h2>Which agent it checks</h2>
 *
 * <p>{@code -Dbotwithus.harness.pid=<pid>} pins it to one client, which is what
 * the harness passes: every other phase is scoped to {@code session.pid}, and a
 * gate that certified some other agent on the machine would be certifying
 * something the run does not then test. Without the property it falls back to
 * {@link SharedRegion#openFirstAvailable()} so the class is still usable by
 * hand.</p>
 */
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveProtocolLockstepTest {

    private static final Logger log = LoggerFactory.getLogger(LiveProtocolLockstepTest.class);

    /** Set by bwu-harness to the pid every other phase of the run is scoped to. */
    private static final String PID_PROPERTY = "botwithus.harness.pid";

    @Test
    void attachingRunsTheRejectionPath_ratherThanComparingConstantsThatMayNotBeTheOnesUsed() {
        Optional<SharedRegion> maybeRegion;
        try {
            maybeRegion = openTarget();
        } catch (SharedMemoryException e) {
            // Neutral wording on purpose: SharedMemoryException covers a
            // version rejection AND a mapping that could not be opened at all,
            // and calling the second one a refusal points at a disagreement
            // where the fault was that there was nothing to disagree with. The
            // host's own sentence says which it was, so it is repeated rather
            // than reworded.
            fail("the host could not bind to the running agent's shared region: " + e.getMessage(), e);
            return;
        }

        if (maybeRegion.isEmpty()) {
            fail("no BotWithUs_<pid> pipe visible, so no agent was running to check against. "
                    + "This test is a gate and the harness guarantees a live agent before it runs, "
                    + "so reaching here means that guarantee broke.");
            return;
        }

        try (SharedRegion region = maybeRegion.orElseThrow()) {
            // A REAL READ THROUGH THE MAPPING, not a length.
            //
            // This used to assert currentSnapshot().byteSize() > 0, which could
            // not fail: validateGeometry has already enforced the snapshot size
            // and populateSlices has already sliced to it, so byteSize() returns
            // a Java-side length and would be byte-identical over an unreadable
            // mapping. An assertion that cannot fail is the defect this whole
            // gate exists to catch, sitting inside the gate.
            //
            // frontIdx is a live load from the header the producer writes, and
            // it is a discriminating one: the producer publishes into one of two
            // buffers and stamps 0 or 1, so any other value means the bytes are
            // not what the layout says they are.
            int front = region.frontIdx();
            SnapshotView snapshot = region.snapshot();
            int serverTick = snapshot.serverTick();

            log.info("host attached to the live agent: frontIdx={}, serverTick={}", front, serverTick);
            assertTrue(front == 0 || front == 1,
                    "attach succeeded but the header's frontIdx is " + front
                            + ", which is neither published buffer -- the mapping is not what the layout says");
        }
    }

    private static Optional<SharedRegion> openTarget() {
        String pid = System.getProperty(PID_PROPERTY);
        if (pid == null || pid.isBlank()) {
            return SharedRegion.openFirstAvailable();
        }
        return Optional.of(SharedRegion.open(Long.parseLong(pid.trim())));
    }
}
