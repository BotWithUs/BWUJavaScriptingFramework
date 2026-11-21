package com.botwithus.bot.core.cache;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a host-opened {@link NXTCache} takes its data from, and whether that
 * choice was made by the operator or by the host.
 *
 * <p><b>The host reads the game cache from disk only.</b> There is no network
 * variant: the host never opens the live JS5 service and never enables a live
 * fallback, because traffic to the game's servers belongs to the game and to
 * the launcher, not to us. When no local cache can be found the answer is
 * {@link Unavailable}, and every config-type lookup then fails with its reason.</p>
 *
 * <p>Deciding a source is separate from opening one on purpose. The decision is
 * pure apart from probing the filesystem for {@code js5-*.jcache} files, so the
 * whole precedence chain is unit testable without loading {@code NXTCache.dll}.
 * Opening is then a sealed {@code switch} in {@link CacheOpening} with no
 * further policy in it.</p>
 *
 * <p>{@link #isExplicit()} distinguishes a source the operator named with a
 * {@code -D} override from one the host found by itself. It lets the host log a
 * discovered source loudly while leaving a dev override quiet, and it is the
 * property the override-precedence test asserts.</p>
 */
public sealed interface CacheSource {

    /** {@code true} when a {@code -D} override decided this source rather than the host discovering it. */
    boolean isExplicit();

    /**
     * A directory of sqlite {@code js5-*.jcache} files written by the NXT
     * client, opened local-only: an archive it does not hold is "not found",
     * never fetched.
     */
    record LocalDirectory(Path directory, boolean isExplicit) implements CacheSource {
        public LocalDirectory {
            Objects.requireNonNull(directory, "directory");
        }
    }

    /**
     * No local cache can be used, with the reason a user should see. Opening
     * this fails closed; it never falls back to the network.
     *
     * @param reason     why there is no cache, written for a log a user sends us
     * @param isExplicit {@code true} when an operator override (a refused
     *                   {@code -Dnxtcache.live}) caused it
     */
    record Unavailable(String reason, boolean isExplicit) implements CacheSource {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
