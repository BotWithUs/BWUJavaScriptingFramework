package com.botwithus.bot.api.model;

/**
 * Whether {@link com.botwithus.bot.api.GameAPI#getPlayerAppearance(int)} could read an
 * appearance, and if not, why. Everything except {@link #OK} is a refusal the agent sent back;
 * a dead pipe is not a status, it throws.
 */
public enum AppearanceStatus {
    /** The appearance was read. */
    OK("ok"),
    /** The client is not in the world. */
    NOT_IN_GAME("not_in_game"),
    /** The server index is out of range, or no player is loaded there. */
    NO_PLAYER("no_player"),
    /** The player is loaded but the client has not applied an appearance yet. Try a tick later. */
    APPEARANCE_NOT_LOADED("appearance_not_loaded"),
    /** The appearance record failed the agent's sanity checks. */
    BAD_APPEARANCE_RECORD("bad_appearance_record"),
    /** This client channel (for example beta) has no established offsets for the read. */
    UNSUPPORTED_ON_CLIENT_CHANNEL("unsupported_on_client_channel"),
    /** The game thread did not answer in time. Transient. */
    GAME_THREAD_TIMEOUT("game_thread_timeout"),
    /** The agent refused with a reason this host does not know. */
    UNRECOGNISED("");

    private final String wireCode;

    AppearanceStatus(String wireCode) {
        this.wireCode = wireCode;
    }

    /** The agent's error string for this status; empty for {@link #UNRECOGNISED}. */
    public String wireCode() {
        return wireCode;
    }

    /**
     * The status for an agent error string, matched exactly (the agent sends lowercase snake
     * case). {@link #UNRECOGNISED} for anything else, never {@code null}.
     */
    public static AppearanceStatus fromWireCode(String code) {
        for (AppearanceStatus status : values()) {
            if (status != UNRECOGNISED && status.wireCode.equals(code)) {
                return status;
            }
        }
        return UNRECOGNISED;
    }
}
