package com.botwithus.bot.api.model;

/**
 * Whether one of the wire-v25 entity reads got an answer, and if not, why. Everything except
 * {@link #OK} is a refusal the agent sent back; a dead pipe is not a status, it throws.
 */
public enum EntityReadStatus {
    /** The agent answered. */
    OK("ok"),
    /** The client is not in the world. */
    NOT_IN_WORLD("not_in_world"),
    /** The index is out of range, or nothing of that kind is loaded there. */
    NOT_FOUND("not_found"),
    /** This client channel (for example beta) has no offsets for the read. A normal answer there. */
    CHANNEL_UNSUPPORTED("channel_unsupported"),
    /** The game thread did not answer in time. Transient. */
    GAME_THREAD_TIMEOUT("game_thread_timeout"),
    /** The agent refused with a reason this host does not know. */
    UNRECOGNISED("");

    private final String wireCode;

    EntityReadStatus(String wireCode) {
        this.wireCode = wireCode;
    }

    /** The agent's error string for this status; empty for {@link #UNRECOGNISED}. */
    public String wireCode() {
        return wireCode;
    }

    /**
     * The status for an agent error string, matched exactly (the agent sends lowercase snake case).
     * {@link #UNRECOGNISED} for anything else, never {@code null}.
     */
    public static EntityReadStatus fromWireCode(String code) {
        for (EntityReadStatus status : values()) {
            if (status != UNRECOGNISED && status.wireCode.equals(code)) {
                return status;
            }
        }
        return UNRECOGNISED;
    }
}
