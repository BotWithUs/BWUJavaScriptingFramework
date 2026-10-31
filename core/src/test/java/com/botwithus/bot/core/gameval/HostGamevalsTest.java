package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.core.cache.GamevalIndexVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup flow end to end over real sqlite files in a temp directory, with a
 * fake cache source standing in for {@code NXTCache.dll}. The background executor
 * runs inline unless a test needs it on its own thread, so every assertion sees
 * the finished swap.
 */
class HostGamevalsTest {

    private static final int CACHE_VERSION = 1729;
    private static final int OLD_VERSION = 1700;
    private static final int SHIPPED_MARKER = 1;
    private static final int BUILT_MARKER = 2;
    private static final int PEER_MARKER = 3;
    private static final int OVERRIDE_MARKER = 4;
    private static final int OLD_LOCAL_MARKER = 5;
    private static final long OWN_PID = 4242;
    private static final long DEAD_PID = 999_999;
    private static final Executor INLINE = Runnable::run;
    private static final HostGamevals.PeerWait NO_PEER_WAIT =
            new HostGamevals.PeerWait(Duration.ZERO, Duration.ZERO);
    private static final long TEST_TIMEOUT_SECONDS = 10;

    @TempDir
    Path nativeDir;

    private Path shipped() {
        return nativeDir.resolve("gameval.sqlite");
    }

    private Path localDir() {
        return nativeDir.resolve(LocalGamevalStore.DIR_NAME);
    }

    private LocalGamevalStore store() {
        return new LocalGamevalStore(localDir(), OWN_PID, pid -> pid == OWN_PID);
    }

    private HostGamevals host(GamevalSourceOpener opener, Executor background, HostGamevals.PeerWait wait) {
        return new HostGamevals(Optional::empty, shipped(), store(), opener, SqliteGamevalIndex::open,
                background, wait);
    }

    private HostGamevals host(GamevalSourceOpener opener) {
        return host(opener, INLINE, NO_PEER_WAIT);
    }

    private static GamevalSourceOpener opening(GamevalCacheSource source) {
        return () -> new GamevalSourceOpener.Opening.Opened(source);
    }

    private static OptionalInt marker(GamevalIndex index) {
        return index.id(GamevalType.ITEM, GamevalFixtures.MARKER_NAME);
    }

    private void shipVersion(Optional<String> version) {
        GamevalFixtures.writeDb(shipped(), version, SHIPPED_MARKER);
    }

    @Test
    void sameVersion_readsTheShippedDbAndNeverBuilds() {
        shipVersion(Optional.of(Integer.toString(CACHE_VERSION)));
        GamevalFixtures.FakeSource source = GamevalFixtures.FakeSource.probing(CACHE_VERSION, BUILT_MARKER);

        try (GamevalIndex index = host(opening(source)).open()) {
            assertAll(
                    () -> assertEquals(OptionalInt.of(SHIPPED_MARKER), marker(index)),
                    () -> assertTrue(source.builds().isEmpty(), "built " + source.builds()),
                    () -> assertTrue(source.isClosed(), "the probe handle must be released"),
                    () -> assertFalse(Files.exists(store().publishedPath(CACHE_VERSION))));
        }
    }

    @Test
    void differentVersion_buildsPublishesAndSwapsLeavingTheShippedDbUntouched() {
        shipVersion(Optional.of(Integer.toString(OLD_VERSION)));
        String shippedHash = GamevalFixtures.sha256(shipped());
        GamevalFixtures.FakeSource source = GamevalFixtures.FakeSource.probing(CACHE_VERSION, BUILT_MARKER);

        try (GamevalIndex index = host(opening(source)).open()) {
            Path published = store().publishedPath(CACHE_VERSION);
            assertAll(
                    () -> assertEquals(OptionalInt.of(BUILT_MARKER), marker(index)),
                    () -> assertEquals(Optional.of("1729"), index.meta(HostGamevals.INDEX_VERSION_KEY)),
                    () -> assertEquals(1, source.builds().size()),
                    () -> assertTrue(Files.isRegularFile(published)),
                    () -> assertEquals(shippedHash, GamevalFixtures.sha256(shipped()),
                            "the launcher's shipped DB must never be written"),
                    () -> assertTrue(source.isClosed()),
                    () -> assertEquals(1, countFiles(localDir()) - lockFiles(),
                            "only the published file may remain: no .build or .partial"));
        }
    }

    @Test
    void aLaterStart_readsThePublishedBuildInsteadOfRebuilding() {
        shipVersion(Optional.empty());
        GamevalFixtures.FakeSource first = GamevalFixtures.FakeSource.probing(CACHE_VERSION, BUILT_MARKER);
        host(opening(first)).open().close();
        GamevalFixtures.FakeSource second = GamevalFixtures.FakeSource.probing(CACHE_VERSION, PEER_MARKER);

        try (GamevalIndex index = host(opening(second)).open()) {
            assertAll(
                    () -> assertEquals(OptionalInt.of(BUILT_MARKER), marker(index)),
                    () -> assertTrue(second.builds().isEmpty(), "a published build is reused"));
        }
    }

    @Test
    void notFound_keepsTheShippedDbAndWritesNothing() {
        shipVersion(Optional.empty());
        GamevalFixtures.FakeSource source =
                new GamevalFixtures.FakeSource(new GamevalIndexVersion.Absent(), BUILT_MARKER, false);

        try (GamevalIndex index = host(opening(source)).open()) {
            assertAll(
                    () -> assertEquals(OptionalInt.of(SHIPPED_MARKER), marker(index)),
                    () -> assertTrue(source.builds().isEmpty()),
                    () -> assertFalse(Files.exists(localDir()), "nothing may be created"));
        }
    }

    @Test
    void aMissingSymbol_skipsBuildingAndStillReadsTheShippedDb() {
        shipVersion(Optional.empty());
        GamevalSourceOpener oldDll = () -> new GamevalSourceOpener.Opening.Unavailable(
                "this NXTCache.dll predates nxt_build_gameval_db");

        try (GamevalIndex index = host(oldDll).open()) {
            assertAll(
                    () -> assertEquals(OptionalInt.of(SHIPPED_MARKER), marker(index)),
                    () -> assertFalse(Files.exists(localDir())));
        }
    }

    @Test
    void anOverride_isReadAsIsAndNothingIsProbedBuiltOrWritten() {
        shipVersion(Optional.empty());
        Path override = GamevalFixtures.writeDb(nativeDir.resolve("override.sqlite"),
                Optional.of(Integer.toString(OLD_VERSION)), OVERRIDE_MARKER);
        String overrideHash = GamevalFixtures.sha256(override);
        String shippedHash = GamevalFixtures.sha256(shipped());
        GamevalSourceOpener forbidden = () -> {
            throw new AssertionError("an override must not open the game cache");
        };
        HostGamevals host = new HostGamevals(() -> Optional.of(SqliteGamevalIndex.open(override)),
                shipped(), store(), forbidden, SqliteGamevalIndex::open, INLINE, NO_PEER_WAIT);

        try (GamevalIndex index = host.open()) {
            assertEquals(OptionalInt.of(OVERRIDE_MARKER), marker(index));
        }
        assertAll(
                () -> assertEquals(overrideHash, GamevalFixtures.sha256(override)),
                () -> assertEquals(shippedHash, GamevalFixtures.sha256(shipped())),
                () -> assertFalse(Files.exists(localDir())));
    }

    @Test
    void aFailedBuild_keepsTheShippedDbAndLeavesNoTempFiles() {
        shipVersion(Optional.empty());
        GamevalFixtures.FakeSource source = new GamevalFixtures.FakeSource(
                new GamevalIndexVersion.Present(CACHE_VERSION), BUILT_MARKER, true);

        try (GamevalIndex index = host(opening(source)).open()) {
            assertAll(
                    () -> assertEquals(OptionalInt.of(SHIPPED_MARKER), marker(index)),
                    () -> assertEquals(1, source.builds().size()),
                    () -> assertFalse(Files.exists(store().publishedPath(CACHE_VERSION))),
                    () -> assertEquals(0, countFiles(localDir()) - lockFiles()));
        }
    }

    @Test
    void heldOpenFiles_areNeitherReplacedNorCorrupted() throws IOException {
        // Another host is reading the shipped DB and an old local build while this
        // host builds a new version. On Windows sqlite holds both without share-delete.
        shipVersion(Optional.of(Integer.toString(OLD_VERSION)));
        Files.createDirectories(localDir());
        Path oldLocal = GamevalFixtures.writeDb(store().publishedPath(OLD_VERSION),
                Optional.of(Integer.toString(OLD_VERSION)), OLD_LOCAL_MARKER);
        String shippedHash = GamevalFixtures.sha256(shipped());
        GamevalFixtures.FakeSource source = GamevalFixtures.FakeSource.probing(CACHE_VERSION, BUILT_MARKER);

        try (SqliteGamevalIndex otherHostShipped = SqliteGamevalIndex.open(shipped());
             SqliteGamevalIndex otherHostLocal = SqliteGamevalIndex.open(oldLocal);
             GamevalIndex index = host(opening(source)).open()) {
            assertAll(
                    () -> assertEquals(OptionalInt.of(BUILT_MARKER), marker(index)),
                    () -> assertEquals(OptionalInt.of(SHIPPED_MARKER), marker(otherHostShipped)),
                    () -> assertEquals(OptionalInt.of(OLD_LOCAL_MARKER), marker(otherHostLocal)),
                    () -> assertEquals(shippedHash, GamevalFixtures.sha256(shipped())));
        }
    }

    @Test
    void anOldLocalBuildNobodyHolds_isSweptAtStartup() {
        shipVersion(Optional.empty());
        GamevalFixtures.FakeSource older = GamevalFixtures.FakeSource.probing(OLD_VERSION, OLD_LOCAL_MARKER);
        host(opening(older)).open().close();
        GamevalFixtures.FakeSource newer = GamevalFixtures.FakeSource.probing(CACHE_VERSION, BUILT_MARKER);

        host(opening(newer)).open().close();

        assertAll(
                () -> assertFalse(Files.exists(store().publishedPath(OLD_VERSION))),
                () -> assertTrue(Files.exists(store().publishedPath(CACHE_VERSION))));
    }

    @Test
    void aPeerHoldingTheBuildLock_isWaitedForAndItsResultUsedWithoutASecondBuild() throws Exception {
        shipVersion(Optional.empty());
        GamevalFixtures.FakeSource source = GamevalFixtures.FakeSource.probing(CACHE_VERSION, BUILT_MARKER);
        CountDownLatch backgroundDone = new CountDownLatch(1);
        Executor onItsOwnThread = command -> Thread.ofVirtual().start(() -> {
            command.run();
            backgroundDone.countDown();
        });
        HostGamevals.PeerWait wait = new HostGamevals.PeerWait(
                Duration.ofSeconds(TEST_TIMEOUT_SECONDS), Duration.ofMillis(10));
        LocalGamevalStore peer = store();

        LocalGamevalStore.BuildLock peerLock = peer.tryLockForBuild().orElseThrow();
        try (SwappableGamevalIndex index = host(opening(source), onItsOwnThread, wait).open()) {
            assertEquals(OptionalInt.of(SHIPPED_MARKER), marker(index), "startup must not wait");
            Path peerBuild = peer.newBuildPath();
            GamevalFixtures.writeDb(peerBuild, Optional.of("1729"), PEER_MARKER);
            peer.publish(peerBuild, CACHE_VERSION);
            assertTrue(backgroundDone.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertAll(
                    () -> assertEquals(OptionalInt.of(PEER_MARKER), marker(index)),
                    () -> assertTrue(source.builds().isEmpty(), "the peer's build is reused"));
        } finally {
            peerLock.close();
        }
    }

    @Test
    void scriptsStartingBeforeTheSwap_readTheShippedDb() throws Exception {
        shipVersion(Optional.empty());
        GamevalFixtures.FakeSource source = GamevalFixtures.FakeSource.probing(CACHE_VERSION, BUILT_MARKER);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Executor gated = command -> Thread.ofVirtual().start(() -> {
            awaitQuietly(release);
            command.run();
            done.countDown();
        });

        try (SwappableGamevalIndex index = host(opening(source), gated, NO_PEER_WAIT).open()) {
            assertEquals(OptionalInt.of(SHIPPED_MARKER), marker(index));
            release.countDown();
            assertTrue(done.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(OptionalInt.of(BUILT_MARKER), marker(index));
        }
    }

    @Test
    void aDeadProcessesBuildTempFile_isSweptButALiveOnesIsKept() throws IOException {
        Files.createDirectories(localDir());
        Path dead = Files.writeString(localDir().resolve(DEAD_PID + "-1.build.partial"), "x");
        Path live = Files.writeString(localDir().resolve(OWN_PID + "-1.build"), "x");

        store().sweep(CACHE_VERSION);

        assertAll(
                () -> assertFalse(Files.exists(dead)),
                () -> assertTrue(Files.exists(live)));
    }

    @Test
    void publishingOverAnExistingVersion_keepsTheExistingFileAndDropsTheTemp() throws IOException {
        Files.createDirectories(localDir());
        Path existing = GamevalFixtures.writeDb(store().publishedPath(CACHE_VERSION),
                Optional.of("1729"), PEER_MARKER);
        String existingHash = GamevalFixtures.sha256(existing);
        Path temp = GamevalFixtures.writeDb(store().newBuildPath(), Optional.of("1729"), BUILT_MARKER);

        Path published = store().publish(temp, CACHE_VERSION);

        assertAll(
                () -> assertEquals(existing, published),
                () -> assertEquals(existingHash, GamevalFixtures.sha256(existing)),
                () -> assertFalse(Files.exists(temp)));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int lockFiles() {
        return Files.exists(localDir().resolve("build.lock")) ? 1 : 0;
    }

    private static int countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return (int) entries.count();
        }
    }
}
