package com.botwithus.bot.api.event;

/**
 * Fired when a break begins (logout triggered by fatigue/risk or manual schedule).
 *
 * @param durationSeconds the scheduled break duration in seconds
 * @param fatigue         the fatigue level at break start [0,1]
 * @param risk            the cumulative risk at break start
 * @param timestamp       event creation time in milliseconds since epoch
 */
public record BreakStartedEvent(int durationSeconds, double fatigue, double risk, long timestamp)
        implements GameEvent {

    public BreakStartedEvent(int durationSeconds, double fatigue, double risk) {
        this(durationSeconds, fatigue, risk, System.currentTimeMillis());
    }
}
