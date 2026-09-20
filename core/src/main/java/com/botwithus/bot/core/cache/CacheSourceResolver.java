package com.botwithus.bot.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Decides which {@link CacheSource} the host should open, in this order:
 *
 * <ol>
 *   <li>{@code -Dnxtcache.path=<dir>} — an operator-named local cache.</li>
 *   <li>{@code -Dnxtcache.live=true} — an operator-named live-only cache.</li>
 *   <li>The cache directory named by the <b>running client's own</b>
 *       {@code preferences.cfg}, when a pid was supplied — see
 *       {@link RunningClientCache}. This is the authoritative answer and it is
 *       the only tier that is right for a user who relocated their cache.</li>
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
 * <p><b>The running-client tier is a {@link Supplier} rather than a resolved
 * value, and that is load-bearing.</b> It defers the {@link ProcessHandle} call
 * until the two override tiers have declined, so naming
 * {@code -Dnxtcache.path} costs no process inspection at all and the override
 * leg stays free of side effects.</p>
 *
 * <p><b>A pid being available is not the same as that tier answering.</b> The
 * client can exit between pipe discovery and this call, its executable path can
 * be unreadable, and its {@code cache_folder} can name a directory that no
 * longer exists — so the candidate list below it covers the running-client
 * tier's <em>failure</em>, not merely its absence.</p>
 *
 * <p>Resolution is pure apart from that probe: the property reader, the
 * candidate list and the running-client lookup are all injected, so the
 * precedence chain is testable without mutating process-global state, owning a
 * game install, or having a client running.</p>
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
    private final Supplier<Optional<Path>> runningClientCache;

    /**
     * Resolver with no running client to ask: overrides, then the platform's
     * standard locations, then live. Callers that hold the client's pid should
     * prefer {@link #CacheSourceResolver(long)}, which is strictly better
     * informed.
     */
    public CacheSourceResolver() {
        this(GameCacheCandidates.forEnvironment(System::getenv), System::getProperty,
                Optional::empty);
    }

    /**
     * Resolver that asks the client running as {@code clientPid} where its cache
     * is before falling back to the known locations.
     *
     * @param clientPid the game client's process id, as carried by the agent's
     *                  pipe and shared-memory names
     */
    public CacheSourceResolver(long clientPid) {
        this(GameCacheCandidates.forEnvironment(System::getenv), System::getProperty,
                () -> RunningClientCache.forPid(clientPid));
    }

    /**
     * Resolver over an explicit candidate list and property reader, with no
     * running client to ask.
     *
     * @param candidates       directories to probe, in preference order
     * @param systemProperties reads a system property by name, {@code null} when unset
     */
    public CacheSourceResolver(List<Path> candidates, UnaryOperator<String> systemProperties) {
        this(candidates, systemProperties, Optional::empty);
    }

    /**
     * Resolver over an explicit candidate list, property reader and
     * running-client lookup.
     *
     * @param candidates         directories to probe, in preference order
     * @param systemProperties   reads a system property by name, {@code null} when unset
     * @param runningClientCache the running client's own cache directory, consulted
     *                           only once both overrides have declined
     */
    public CacheSourceResolver(List<Path> candidates, UnaryOperator<String> systemProperties,
                               Supplier<Optional<Path>> runningClientCache) {
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        this.systemProperties = Objects.requireNonNull(systemProperties, "systemProperties");
        this.runningClientCache =
                Objects.requireNonNull(runningClientCache, "runningClientCache");
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
        Optional<Path> fromRunningClient = runningClientCache.get();
        if (fromRunningClient.isPresent()) {
            return new CacheSource.LocalDirectory(fromRunningClient.get(), false);
        }
        for (Path candidate : candidates) {
            if (holdsGameCache(candidate)) {
                log.info("NXTCache: {} is a known install location holding a cache; no running "
                        + "client named one of its own", candidate);
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
