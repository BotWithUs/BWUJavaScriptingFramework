package com.botwithus.bot.api.world;

/**
 * Why the agent refused to start a world change ({@code set_world} {@code reason}).
 *
 * <p>{@link WorldHopRequest#reason()} keeps the agent's string as sent, so a reason this
 * host does not list ({@link #UNKNOWN}) is still readable there.</p>
 */
public enum HopRefusal {
    /** Not in the lobby or the world. */
    NOT_LOGGED_IN("not_logged_in"),
    /** An earlier change has not landed, or the client is mid-transfer. Retry after it settles. */
    HOP_IN_PROGRESS("hop_in_progress"),
    /** The action queue is blocked. */
    ACTIONS_BLOCKED("actions_blocked"),
    /** The client holds no world list yet. */
    WORLD_LIST_EMPTY("world_list_empty"),
    /** The world is not in the client's list. */
    UNKNOWN_WORLD("unknown_world"),
    /** A members world on a free account. */
    MEMBERS_WORLD("members_world"),
    /** Already on that world. */
    ALREADY_ON_WORLD("already_on_world"),
    /** The world-change UI did not resolve; right after reaching the lobby this is normal. */
    WORLD_UI_NOT_FOUND("world_ui_not_found"),
    /** The action queue or the game-thread queue is full. */
    ACTION_QUEUE_FULL("action_queue_full"),
    /** The game thread did not answer the pre-check. */
    GAME_THREAD_TIMEOUT("game_thread_timeout"),
    /** A reason string this host does not list. */
    UNKNOWN("");

    private final String wireName;

    HopRefusal(String wireName) {
        this.wireName = wireName;
    }

    /** The agent's spelling; empty for {@link #UNKNOWN}. */
    public String wireName() {
        return wireName;
    }

    /** The refusal for an agent reason string; {@link #UNKNOWN} for anything unlisted. */
    public static HopRefusal fromWire(String reason) {
        for (HopRefusal r : values()) {
            if (r != UNKNOWN && r.wireName.equals(reason)) {
                return r;
            }
        }
        return UNKNOWN;
    }
}
