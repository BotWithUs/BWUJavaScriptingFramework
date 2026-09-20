package com.botwithus.bot.core.cache;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a host-opened {@link NXTCache} takes its data from, and whether that
 * choice was made by the operator or by the host.
 *
 * <p>Deciding a source is separate from opening one on purpose. The decision is
 * pure apart from probing the filesystem for {@code js5-*.jcache} files, so the
 * whole precedence chain — override beats discovery beats live — is unit
 * testable without loading {@code NXTCache.dll} or reaching a network. Opening
 * is then a sealed {@code switch} in
 * {@link NXTCache#openForHost()} with no further policy in it.</p>
 *
 * <p>{@link #isExplicit()} distinguishes a source the operator named with a
 * {@code -D} override from one the host found by itself. It is not decoration:
 * it is what lets the host log a discovered source loudly while leaving a dev
 * override quiet, and it is the property the override-precedence test asserts.</p>
 */
public sealed interface CacheSource {

    /** {@code true} when a {@code -D} override named this source rather than the host discovering it. */
    boolean isExplicit();

    /**
     * A directory of sqlite {@code js5-*.jcache} files written by the NXT
     * client, opened with live-JS5 fallback for archives it does not hold.
     */
    record LocalDirectory(Path directory, boolean isExplicit) implements CacheSource {
        public LocalDirectory {
            Objects.requireNonNull(directory, "directory");
        }
    }

    /**
     * The live JS5 service, with no local cache behind it. Always current and
     * always available without a game install, at the cost of a network
     * round-trip per archive.
     */
    record Live(boolean isExplicit) implements CacheSource {
    }
}
