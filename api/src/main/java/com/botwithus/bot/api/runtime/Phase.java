package com.botwithus.bot.api.runtime;

/**
 * Identifies which lifecycle hook of a {@link com.botwithus.bot.api.BotScript}
 * was executing when an event of interest (typically a crash) occurred.
 *
 * <p>The enum is shared between the runtime that produces the value
 * ({@code ScriptRunner}) and any consumer that needs to render or filter on it
 * ({@code ScriptsPanel}, {@code ScriptCrashedEvent}), so the value is part of
 * the stable API surface — not a runtime-internal string.</p>
 */
public enum Phase {
    ON_START,
    ON_LOOP,
    ON_STOP,
    ON_CONFIG_UPDATE
}
