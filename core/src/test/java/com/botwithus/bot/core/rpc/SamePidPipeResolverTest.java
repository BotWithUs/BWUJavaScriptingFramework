package com.botwithus.bot.core.rpc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which pipe a dropped connection is allowed to reconnect to.
 *
 * <p>The defect: {@code ReconnectController} closed over the pipe name captured
 * at construction. Pipes are {@code BotWithUs_<pid>}, so a game that restarted
 * under a new pid left the controller retrying a name that could never exist —
 * forever, because {@code ReconnectPolicy.DEFAULT} sets
 * {@code maxAttempts = Integer.MAX_VALUE}.</p>
 *
 * <p>The opposite mistake matters just as much: re-resolving must NOT adopt
 * whatever {@code BotWithUs_*} pipe is visible. The pid is the connection's
 * identity — the SHM snapshot mapping, the connections map key and every live
 * entity flyweight are bound to it — so adopting another game's pipe would
 * serve plausible, wrong numbers instead of failing.</p>
 */
class SamePidPipeResolverTest {

    private static final String OUR_PIPE = "BotWithUs_4242";
    private static final long OUR_PID = 4242L;

    private static SamePidPipeResolver resolver(List<String> visible, boolean alive) {
        Supplier<List<String>> scan = () -> visible;
        LongPredicate liveness = pid -> alive && pid == OUR_PID;
        return new SamePidPipeResolver(OUR_PIPE, scan, liveness);
    }

    @Test
    void findsOurOwnPipeWhenItIsListening() {
        PipeResolution result = resolver(List.of(OUR_PIPE), true).resolve(1);

        PipeResolution.Found found = assertInstanceOf(PipeResolution.Found.class, result);
        assertEquals(OUR_PIPE, found.pipeName());
    }

    /**
     * The agent dropped the client but the game is still running, so the accept
     * loop will re-listen on the same name. This is the recoverable case.
     */
    @Test
    void waitsWhileProcessAliveAndPipeNotYetBack() {
        PipeResolution result = resolver(List.of(), true).resolve(1);

        assertInstanceOf(PipeResolution.NotYet.class, result);
    }

    /**
     * The whole point of re-resolving: do not keep chasing a name whose process
     * is gone. Nothing will ever re-open it.
     */
    @Test
    void givesUpWhenTheGameProcessHasExited() {
        PipeResolution result = resolver(List.of(), false).resolve(1);

        PipeResolution.Gone gone = assertInstanceOf(PipeResolution.Gone.class, result);
        assertTrue(gone.detail().contains(String.valueOf(OUR_PID)),
                "the reason must name the pid so the log is actionable");
    }

    /**
     * The defect's mirror image, and the more dangerous one. Another game is
     * running and its pipe is visible; we must not take it. Our SHM mapping,
     * connection key and flyweights all still point at {@link #OUR_PID}.
     */
    @Test
    void refusesToAdoptAnotherGamesPipe() {
        PipeResolution result = resolver(List.of("BotWithUs_9999"), false).resolve(1);

        assertInstanceOf(PipeResolution.Gone.class, result,
                "adopting another pid would silently serve stale shared memory");
    }

    /** Several candidates, none ours, our process gone — still not an invitation to choose. */
    @Test
    void refusesToChooseAmongSeveralForeignPipes() {
        PipeResolution result =
                resolver(List.of("BotWithUs_1", "BotWithUs_2", "BotWithUs_3"), false).resolve(1);

        assertInstanceOf(PipeResolution.Gone.class, result);
    }

    /** Ours among others is fine — we match on identity, not on position. */
    @Test
    void findsOurPipeAmongSeveralCandidates() {
        PipeResolution result =
                resolver(List.of("BotWithUs_1", OUR_PIPE, "BotWithUs_3"), true).resolve(1);

        PipeResolution.Found found = assertInstanceOf(PipeResolution.Found.class, result);
        assertEquals(OUR_PIPE, found.pipeName());
    }

    /** The Windows pipe namespace is case-insensitive; our matching must be too. */
    @Test
    void matchesOurPipeCaseInsensitively() {
        PipeResolution result = resolver(List.of("botwithus_4242"), true).resolve(1);

        assertInstanceOf(PipeResolution.Found.class, result);
    }

    /**
     * A live process that never re-opens its pipe — or a pid the OS recycled
     * onto something unrelated — must not strand us waiting forever.
     */
    @Test
    void stopsWaitingAfterTheGraceWindowEvenWhileProcessAlive() {
        SamePidPipeResolver r = resolver(List.of(), true);

        assertInstanceOf(PipeResolution.NotYet.class,
                r.resolve(SamePidPipeResolver.MAX_WAIT_ATTEMPTS - 1));
        assertInstanceOf(PipeResolution.Gone.class,
                r.resolve(SamePidPipeResolver.MAX_WAIT_ATTEMPTS));
    }

    @Test
    void rejectsAPipeNameCarryingNoPid() {
        assertThrows(IllegalArgumentException.class,
                () -> new SamePidPipeResolver("BotWithUs", List::of, pid -> true));
    }
}
