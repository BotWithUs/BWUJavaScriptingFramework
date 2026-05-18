package com.botwithus.bot.api.event;

import java.nio.file.Path;

/**
 * Fired when the script loader fails to load a JAR (module-info parse error,
 * missing {@code provides} clause for {@code BotScript}, ServiceLoader
 * instantiation failure, classpath / module-path mismatch, etc.).
 *
 * <p>The loader publishes one event per failed JAR — successful JARs do not
 * emit. Consumers (notification overlay, ScriptsPanel failure cards) bind to
 * this event to surface the failure to the user.</p>
 *
 * @param jar       the JAR path that failed to load
 * @param cause     the throwable that surfaced the failure
 * @param timestamp event creation time in milliseconds since epoch
 */
public record ScriptLoadFailedEvent(Path jar, Throwable cause, long timestamp)
        implements GameEvent {

    public ScriptLoadFailedEvent(Path jar, Throwable cause) {
        this(jar, cause, System.currentTimeMillis());
    }
}
