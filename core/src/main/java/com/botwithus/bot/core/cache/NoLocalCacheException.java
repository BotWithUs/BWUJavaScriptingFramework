package com.botwithus.bot.core.cache;

import java.io.IOException;

/**
 * No local game cache could be opened, so the host has no cache at all. The
 * host never substitutes the network for a missing local cache; this is what
 * it reports instead.
 */
public final class NoLocalCacheException extends IOException {

    private static final long serialVersionUID = 1L;

    /** @param reason why no local cache is available, as {@link CacheSource.Unavailable} gives it */
    public NoLocalCacheException(String reason) {
        super("no local game cache: " + reason);
    }
}
