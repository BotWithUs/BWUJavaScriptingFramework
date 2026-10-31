package com.botwithus.bot.core.gameval;

import java.util.Objects;

/**
 * Opens the {@link GamevalCacheSource} a local build reads from, or says why
 * there is none. Never throws: every reason a build cannot happen (no DLL, a
 * DLL without the builder, no local game cache, a cache that will not open) is
 * a working state for the host, reported once and degraded past.
 */
@FunctionalInterface
public interface GamevalSourceOpener {

    /** One attempt to open the source. */
    Opening open();

    /** What {@link #open()} produced. */
    sealed interface Opening {

        /** A source the caller now owns and must close. */
        record Opened(GamevalCacheSource source) implements Opening {
            public Opened {
                Objects.requireNonNull(source, "source");
            }
        }

        /** No source; {@code reason} is a sentence fit for the host log. */
        record Unavailable(String reason) implements Opening {
            public Unavailable {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
