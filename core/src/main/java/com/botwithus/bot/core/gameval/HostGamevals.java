package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.core.util.NativeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Opens the gameval index a host process reads, keeping it current with the
 * player's own game cache.
 *
 * <p>The shipped {@code gameval.sqlite} goes stale with every game update, and
 * since 951-1 the live cache carries the index itself (cache index 67). So at
 * startup the host asks the local cache which index version it has and reads, in
 * order of preference:</p>
 * <ol>
 *   <li>the {@code -Dbotwithus.gameval} override, when set — exactly as before,
 *       and nothing is probed or built;</li>
 *   <li>a local build of that version, published earlier by any host;</li>
 *   <li>the shipped DB, when it already is that version;</li>
 *   <li>otherwise the shipped DB <em>now</em>, while the version is built in the
 *       background and swapped in when ready (see {@link SwappableGamevalIndex}).</li>
 * </ol>
 * <p>A cache with no index 67, an {@code NXTCache.dll} without the builder, or no
 * local cache at all reads the shipped DB, with one log line saying why. Where
 * local builds live and how hosts sharing them stay out of each other's way is
 * {@link LocalGamevalStore}'s business.</p>
 *
 * <p>Startup never waits for a build. A build takes seconds (several, on a real
 * cache); scripts starting before the swap read the shipped DB.</p>
 */
public final class HostGamevals {

    /** The {@code meta} key a build stamps with the cache's index version. */
    static final String INDEX_VERSION_KEY = "index_version";

    /** Name of the background thread, so a stuck build is legible in a thread dump. */
    private static final String BUILD_THREAD_NAME = "gameval-local-build";

    /** System property naming an explicit index file; see {@link NativeCache#locateGamevalDb()}. */
    private static final String OVERRIDE_PROPERTY = "botwithus.gameval";
    /** Environment variable the run task turns into {@link #OVERRIDE_PROPERTY}. */
    private static final String OVERRIDE_ENV = "BOTWITHUS_GAMEVAL";

    /** How long a host waits for a peer that holds the build lock, and how often it looks. */
    private static final Duration DEFAULT_PEER_WAIT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_PEER_POLL = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(HostGamevals.class);

    /**
     * How long to wait for another host's build of the same version before giving
     * up for this session, and how often to look for its result.
     */
    record PeerWait(Duration total, Duration poll) {
        PeerWait {
            Objects.requireNonNull(total, "total");
            Objects.requireNonNull(poll, "poll");
        }
    }

    private final Supplier<Optional<GamevalIndex>> override;
    private final Path shippedDb;
    private final LocalGamevalStore store;
    private final GamevalSourceOpener opener;
    private final Function<Path, GamevalIndex> indexOpener;
    private final Executor background;
    private final PeerWait peerWait;

    /**
     * @param override    the override's index when one is configured, else empty;
     *                    consulted first and alone
     * @param shippedDb   the launcher-installed DB; read, never written
     * @param store       where local builds are published
     * @param opener      opens the local game cache to probe and build from
     * @param indexOpener opens an index file, throwing when it cannot
     * @param background  runs the build, sweep and swap off the caller's thread
     * @param peerWait    how long to wait on another host's build
     */
    HostGamevals(Supplier<Optional<GamevalIndex>> override, Path shippedDb, LocalGamevalStore store,
                 GamevalSourceOpener opener, Function<Path, GamevalIndex> indexOpener,
                 Executor background, PeerWait peerWait) {
        this.override = Objects.requireNonNull(override, "override");
        this.shippedDb = Objects.requireNonNull(shippedDb, "shippedDb");
        this.store = Objects.requireNonNull(store, "store");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.indexOpener = Objects.requireNonNull(indexOpener, "indexOpener");
        this.background = Objects.requireNonNull(background, "background");
        this.peerWait = Objects.requireNonNull(peerWait, "peerWait");
    }

    /**
     * The index for a host serving the client running as {@code clientPid}, under
     * {@code ~/.botwithus/native/}. Never null and never throws; see the class
     * comment for what it reads.
     */
    public static GamevalIndex openForHost(long clientPid) {
        NativeCache nativeCache = new NativeCache();
        LocalGamevalStore store = new LocalGamevalStore(nativeCache.resolve(LocalGamevalStore.DIR_NAME),
                ProcessHandle.current().pid(), pid -> ProcessHandle.of(pid).isPresent());
        return new HostGamevals(HostGamevals::configuredOverride,
                nativeCache.resolve(NativeCache.GAMEVAL_DB_NAME), store,
                NxtCacheGamevalSource.forHost(clientPid), SqliteGamevalIndex::open,
                command -> Thread.ofVirtual().name(BUILD_THREAD_NAME).start(command),
                new PeerWait(DEFAULT_PEER_WAIT, DEFAULT_PEER_POLL)).open();
    }

    /**
     * The override's index, opened as the host always has, when either spelling of
     * the override is set. The env var only reaches the host through the run task,
     * which forwards it as the property; it is honoured here as "do not build" too.
     */
    private static Optional<GamevalIndex> configuredOverride() {
        if (isBlank(System.getProperty(OVERRIDE_PROPERTY)) && isBlank(System.getenv(OVERRIDE_ENV))) {
            return Optional.empty();
        }
        log.info("gameval: an explicit index is configured (-D{} / {}); local builds are off",
                OVERRIDE_PROPERTY, OVERRIDE_ENV);
        return Optional.of(SqliteGamevalIndex.openDefaultOrEmpty());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Decides and opens; any build continues on {@code background}. */
    SwappableGamevalIndex open() {
        Optional<GamevalIndex> overridden = override.get();
        if (overridden.isPresent()) {
            return new SwappableGamevalIndex(overridden.get());
        }
        GamevalSourceOpener.Opening opening = opener.open();
        Optional<GamevalCacheSource> source = switch (opening) {
            case GamevalSourceOpener.Opening.Opened opened -> Optional.of(opened.source());
            case GamevalSourceOpener.Opening.Unavailable unavailable -> Optional.empty();
        };
        GamevalPlan.Probe probe = probe(opening);
        GamevalIndex shipped = openShipped();
        GamevalPlan plan = GamevalPlan.decide(probe, store::published, shipped.meta(INDEX_VERSION_KEY));
        return carryOut(plan, source, shipped);
    }

    private static GamevalPlan.Probe probe(GamevalSourceOpener.Opening opening) {
        return switch (opening) {
            case GamevalSourceOpener.Opening.Unavailable unavailable ->
                    new GamevalPlan.Probe.Unavailable(unavailable.reason());
            case GamevalSourceOpener.Opening.Opened opened -> probeOpened(opened.source());
        };
    }

    private static GamevalPlan.Probe probeOpened(GamevalCacheSource source) {
        try {
            return new GamevalPlan.Probe.Probed(source.probe());
        } catch (RuntimeException e) {
            return new GamevalPlan.Probe.Unavailable("probing the local game cache threw ("
                    + e.getMessage() + ")");
        }
    }

    private GamevalIndex openShipped() {
        if (!Files.isRegularFile(shippedDb)) {
            log.debug("gameval: no shipped index at {}", shippedDb);
            return GamevalIndex.empty();
        }
        try {
            return indexOpener.apply(shippedDb);
        } catch (RuntimeException e) {
            log.warn("gameval: the shipped index at {} failed to open", shippedDb, e);
            return GamevalIndex.empty();
        }
    }

    private SwappableGamevalIndex carryOut(GamevalPlan plan, Optional<GamevalCacheSource> source,
                                           GamevalIndex shipped) {
        return switch (plan) {
            case GamevalPlan.UseShipped use -> {
                source.ifPresent(GamevalCacheSource::close);
                log.info("gameval: reading the shipped index; {}", use.reason());
                yield new SwappableGamevalIndex(shipped);
            }
            case GamevalPlan.UseShippedCurrent current -> {
                source.ifPresent(GamevalCacheSource::close);
                log.info("gameval: the shipped index is current (index_version {})", current.version());
                background.execute(() -> store.sweep(current.version()));
                yield new SwappableGamevalIndex(shipped);
            }
            case GamevalPlan.UseLocal local -> {
                source.ifPresent(GamevalCacheSource::close);
                yield useLocal(local, shipped);
            }
            case GamevalPlan.BuildThenSwap build -> buildThenSwap(build.version(), source.orElseThrow(),
                    shipped);
        };
    }

    private SwappableGamevalIndex useLocal(GamevalPlan.UseLocal local, GamevalIndex shipped) {
        GamevalIndex index;
        try {
            index = indexOpener.apply(local.file());
        } catch (RuntimeException e) {
            log.warn("gameval: the local build {} would not open ({}); reading the shipped index, "
                    + "and the next start rebuilds it", local.file(), e.getMessage());
            LocalGamevalStore.deleteQuietly(local.file());
            return new SwappableGamevalIndex(shipped);
        }
        shipped.close();
        log.info("gameval: reading the local build {} (index_version {})", local.file(), local.version());
        background.execute(() -> store.sweep(local.version()));
        return new SwappableGamevalIndex(index);
    }

    private SwappableGamevalIndex buildThenSwap(int version, GamevalCacheSource source, GamevalIndex shipped) {
        log.info("gameval: the local game cache is index_version {} and the shipped index is {}; "
                        + "reading the shipped index while the local one builds",
                version, shipped.meta(INDEX_VERSION_KEY).orElse("unversioned"));
        SwappableGamevalIndex index = new SwappableGamevalIndex(shipped);
        background.execute(() -> refresh(version, source, index));
        return index;
    }

    /** The background half: sweep, build (or wait for a peer's build), swap. */
    private void refresh(int version, GamevalCacheSource source, SwappableGamevalIndex target) {
        try (source) {
            store.sweep(version);
            Optional<Path> file = buildOrAwaitPeer(version, source);
            if (file.isEmpty()) {
                log.info("gameval: no local build of index_version {} this session; the next start "
                        + "picks it up", version);
                return;
            }
            if (target.swapTo(indexOpener.apply(file.get()))) {
                log.info("gameval: now reading the local build {} (index_version {})", file.get(), version);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("gameval: building the local index (index_version {}) failed; keeping the current "
                    + "one: {}", version, e.getMessage());
        }
    }

    private Optional<Path> buildOrAwaitPeer(int version, GamevalCacheSource source) throws IOException {
        Optional<LocalGamevalStore.BuildLock> lock = store.tryLockForBuild();
        if (lock.isEmpty()) {
            log.info("gameval: another host is building the local index; waiting up to {} s for it",
                    peerWait.total().toSeconds());
            return awaitPeer(version);
        }
        LocalGamevalStore.BuildLock held = lock.get();
        try {
            // Re-checked under the lock: a peer may have published between this
            // host's startup check and now, and a second build would be wasted.
            Optional<Path> existing = store.published(version);
            if (existing.isPresent()) {
                return existing;
            }
            return Optional.of(buildAndPublish(version, source));
        } finally {
            held.close();
        }
    }

    private Path buildAndPublish(int version, GamevalCacheSource source) throws IOException {
        Path temp = store.newBuildPath();
        long started = System.nanoTime();
        try {
            source.build(temp);
        } catch (RuntimeException e) {
            LocalGamevalStore.deleteQuietly(temp);
            throw e;
        }
        Path published = store.publish(temp, version);
        log.info("gameval: built index_version {} from the local game cache in {} ms", version,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return published;
    }

    private Optional<Path> awaitPeer(int version) {
        long deadline = System.nanoTime() + peerWait.total().toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Path> published = store.published(version);
            if (published.isPresent()) {
                return published;
            }
            try {
                Thread.sleep(peerWait.poll());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return store.published(version);
    }
}
