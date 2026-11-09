package com.botwithus.bot.core.test;

/**
 * The outcome of choosing which injected agent a live test may attach to.
 *
 * <p>Three outcomes rather than two, because "nothing to attach to" and "must
 * not attach" are different verdicts: the first is a machine with no client
 * up, which a live test has always been free to skip, while the second is a
 * machine where attaching would be a guess, and a guess can drive somebody
 * else's character.</p>
 */
public sealed interface LiveAgentSelection {

    /**
     * Attach to this agent and no other.
     *
     * @param pid      the game process id the agent runs in
     * @param pipeName the agent's pipe, exactly as the pipe scan reported it
     */
    record Attach(long pid, String pipeName) implements LiveAgentSelection {
    }

    /**
     * No agent pipe is visible and none was named. Nothing was guessed and
     * nothing would be driven; the caller decides whether that is a skip or a
     * failure, as each live test did before this seam existed.
     *
     * @param reason a message naming what was looked for
     */
    record NoAgent(String reason) implements LiveAgentSelection {
    }

    /**
     * Attaching would mean picking a client that was not asked for. Always a
     * test failure, never a skip: a skip would let a misconfigured harness run
     * report green without having tested the client it meant to.
     *
     * @param reason a message naming the pid or pipes involved and the fix
     */
    record Refused(String reason) implements LiveAgentSelection {
    }
}
