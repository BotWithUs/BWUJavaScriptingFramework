package com.botwithus.bot.core.pipe;

/**
 * The outcome of choosing which injected agent to attach to. See {@link AgentPin}.
 *
 * <p>Three outcomes rather than two, because "nothing to attach to" and "must
 * not attach" are different verdicts: the first is a machine with no client
 * up, while the second is a machine where attaching would be a guess, and a
 * guess can drive somebody else's character.</p>
 */
public sealed interface AgentSelection {

    /**
     * Attach to this agent and no other.
     *
     * @param pid      the game process id the agent runs in
     * @param pipeName the agent's pipe, exactly as the pipe scan reported it
     */
    record Attach(long pid, String pipeName) implements AgentSelection {
    }

    /**
     * No agent pipe is visible and none was named. Nothing was guessed.
     *
     * @param reason a message naming what was looked for
     */
    record NoAgent(String reason) implements AgentSelection {
    }

    /**
     * Attaching would mean picking a client that was not asked for.
     *
     * @param reason a message naming the pid or pipes involved and the fix
     */
    record Refused(String reason) implements AgentSelection {
    }
}
