package com.botwithus.bot.api.event;

/**
 * Fired after each queued bot action is executed on the game thread.
 *
 * @param actionId  the action type ID
 * @param param1    the first action parameter
 * @param param2    the second action parameter
 * @param param3    the third action parameter
 * @param timestamp event creation time in milliseconds since epoch
 */
public record ActionExecutedEvent(int actionId, int param1, int param2, int param3, long timestamp)
        implements GameEvent {

    public ActionExecutedEvent(int actionId, int param1, int param2, int param3) {
        this(actionId, param1, param2, param3, System.currentTimeMillis());
    }
}
