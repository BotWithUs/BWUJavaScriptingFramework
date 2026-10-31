package com.botwithus.bot.core.gameval;

import com.botwithus.bot.core.cache.GamevalIndexVersion;

import java.nio.file.Path;

/**
 * A game cache that can say which gameval index it carries and bake it into a
 * {@code gameval.sqlite}. The seam between the local-build policy and
 * {@code NXTCache.dll}, so the policy is testable without the DLL or a game
 * install.
 *
 * <p>Used by one thread at a time: probed on the opening thread, then handed to
 * the background build, never both at once.</p>
 */
public interface GamevalCacheSource extends AutoCloseable {

    /** The cache's gameval index version; cheap, decodes nothing. */
    GamevalIndexVersion probe();

    /**
     * Bakes the index into {@code out}. Takes seconds.
     *
     * @throws RuntimeException naming the cause on any failure; {@code out} is left as it was
     */
    void build(Path out);

    /** Releases the underlying cache handle. Never throws. */
    @Override
    void close();
}
