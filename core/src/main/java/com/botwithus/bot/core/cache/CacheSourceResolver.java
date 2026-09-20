package com.botwithus.bot.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Decides which {@link CacheSource} the host should open, in this order:
 *
 * <ol>
 *   <li>{@code -Dnxtcache.path=<dir>} — an operator-named local cache.</li>
 *   <li>{@code -Dnxtcache.live=true} — an operator-named live-only cache.</li>
 *   <li>A discovered NXT client cache directory: the first
 *       {@linkplain #defaultCandidates candidate} that actually holds
 *       {@code js5-*.jcache} files.</li>
 *   <li>The live JS5 service, as the always-available fallback.</li>
 * </ol>
 *
 * <p><b>An override is taken at its word and never validated against the
 * discovery predicate.</b> A typo'd {@code -Dnxtcache.path} must fail at the
 * open with the path in the message, not silently resolve to a different cache
 * or to the network — which is precisely the failure mode that would make a dev
 * spend an afternoon wondering why their patched cache had no effect.</p>
 *
 * <p><b>Existence of a candidate directory is not the predicate; holding index
 * files is.</b> {@code %ProgramData%\Jagex\RuneScape} is created by the Jagex
 * launcher before the game has ever downloaded an archive, and sits alongside
 * sibling directories ({@code RuneScape-BETA}, {@code Scratch}) with the same
 * shape. Opening an empty one yields a handle that answers "not found" to every
 * lookup — a worse outcome than the live fallback, and a silent one.</p>
 *
 * <p>Resolution is pure apart from that probe: the property reader and the
 * candidate list are both injected, so the precedence chain is testable without
 * mutating process-global state or owning a game install.</p>
 */
public final class CacheSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(CacheSourceResolver.class);

    /** Operator override naming a local cache directory. */
    public static final String PATH_PROPERTY = "nxtcache.path";

    /** Operator override selecting a live-only cache. */
    public static final String LIVE_PROPERTY = "nxtcache.live";

    /**
     * Glob matching the NXT client's per-index cache files. Deliberately narrower
     * than {@code *.jcache}: the client writes {@code GlobalSettings.jcache},
     * {@code ObjCache.jcache} and {@code ShaderManager.jcache} into the same
     * directory, and none of those carry a decodable archive, so matching them
     * would call a settings-only directory a game cache.
     */
    private static final String INDEX_FILE_GLOB = "js5-*.jcache";


    private final List<Path> candidates;
    private final UnaryOperator<String> systemProperties;

    /** Resolver over the platform's standard locations and the real system properties. */
    public CacheSourceResolver() {
        this(GameCacheCandidates.forEnvironment(System::getenv), System::getProperty);
    }

    /**
     * Resolver over an explicit candidate list and property reader.
     *
     * @param candidates       directories to probe, in preference order
     * @param systemProperties reads a system property by name, {@code null} when unset
     */
    public CacheSourceResolver(List<Path> candidates, UnaryOperator<String> systemProperties) {
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        this.systemProperties = Objects.requireNonNull(systemProperties, "systemProperties");
    }

    /** Applies the precedence documented on this class. Never {@code null}. */
    public CacheSource resolve() {
        String override = systemProperties.apply(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return new CacheSource.LocalDirectory(Path.of(override), true);
        }
        if (Boolean.parseBoolean(systemProperties.apply(LIVE_PROPERTY))) {
            return new CacheSource.Live(true);
        }
        for (Path candidate : candidates) {
            if (holdsGameCache(candidate)) {
                return new CacheSource.LocalDirectory(candidate, false);
            }
        }
        return new CacheSource.Live(false);
    }

    /**
     * {@code true} when {@code directory} exists and holds at least one
     * {@code js5-*.jcache} index file.
     */
    public static boolean holdsGameCache(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, INDEX_FILE_GLOB)) {
            return entries.iterator().hasNext();
        } catch (IOException e) {
            log.debug("cannot list {} while looking for an NXT cache: {}", directory, e.getMessage());
            return false;
        }
    }

}
