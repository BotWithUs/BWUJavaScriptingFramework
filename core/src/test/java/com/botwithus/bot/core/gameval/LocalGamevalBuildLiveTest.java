package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.core.cache.CacheSource;
import com.botwithus.bot.core.cache.CacheSourceResolver;
import com.botwithus.bot.core.cache.GameCacheCandidates;
import com.botwithus.bot.core.cache.GamevalIndexVersion;
import com.botwithus.bot.core.cache.NXTCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives a real {@code NXTCache.dll} against the player's real local game cache
 * (read-only), found as the host finds it, into a temp directory, and checks the result through the reader
 * the host uses. Opt-in: {@code ./gradlew :core:gamevalLocalBuildLiveTest
 * -Dnxtcache.dll=<an NXTCache.dll with nxt_build_gameval_db>}, optionally with
 * {@code -Dbotwithus.gameval.cache=<cache dir>}.
 *
 * <p>The expected ids are content, not contract: a game update that renumbers
 * {@code BANK} or the Exit-to-Lobby button fails this test, and the right
 * response is to check the scripts that name them.</p>
 */
class LocalGamevalBuildLiveTest {

    static final String GATE_PROPERTY = "botwithus.gameval.build.live";
    static final String CACHE_PROPERTY = "botwithus.gameval.cache";

    private static final String GAMEVAL_JCACHE = "js5-67.jcache";
    private static final int EXPECTED_ETYPES = 41;
    private static final int BANK_INTERFACE = 517;
    private static final int ESCAPE_MENU_INTERFACE = 1433;
    private static final int LOGOUT_LOBBY_COMPONENT = 69;
    private static final int LOGOUT_LOBBY_PACKED =
            (ESCAPE_MENU_INTERFACE << GamevalIndex.INTERFACE_ID_SHIFT) | LOGOUT_LOBBY_COMPONENT;

    private static Path cacheDir;

    @BeforeAll
    static void gate() {
        assumeTrue(Boolean.getBoolean(GATE_PROPERTY), "opt-in: run :core:gamevalLocalBuildLiveTest");
        // Same discovery the host uses when no client pid is at hand: the first known
        // install location that actually holds index files.
        String explicit = System.getProperty(CACHE_PROPERTY);
        cacheDir = explicit != null ? Path.of(explicit)
                : GameCacheCandidates.forEnvironment(System::getenv).stream()
                        .filter(CacheSourceResolver::holdsGameCache).findFirst().orElse(null);
        assumeTrue(cacheDir != null && Files.isRegularFile(cacheDir.resolve(GAMEVAL_JCACHE)),
                "no local game cache with " + GAMEVAL_JCACHE);
    }

    @Test
    void theRealBuilder_producesAnIndexTheHostReads(@TempDir Path out) throws Exception {
        String before = GamevalFixtures.sha256(cacheDir.resolve(GAMEVAL_JCACHE));
        assertTrue(NXTCache.supportsGamevalBuild(), "this NXTCache.dll lacks the builder");
        Path db = out.resolve("gameval.sqlite");
        int version;
        try (NXTCache cache = NXTCache.openLocal(cacheDir)) {
            version = assertInstanceOf(GamevalIndexVersion.Present.class, cache.gamevalIndexVersion()).value();
            long started = System.nanoTime();
            cache.buildGamevalDb(db);
            System.out.printf("nxt_build_gameval_db from %s: %d ms (index_version %d)%n", cacheDir,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), version);
        }
        try (SqliteGamevalIndex index = SqliteGamevalIndex.open(db)) {
            assertReadsLive(index, Integer.toString(version));
        }
        assertAll(
                () -> assertEquals(EXPECTED_ETYPES, distinctEtypes(db)),
                () -> assertEquals(before, GamevalFixtures.sha256(cacheDir.resolve(GAMEVAL_JCACHE)),
                        "the game cache must only be read"));
    }

    @Test
    void theHostFlow_buildsSwapsAndReusesWithTheRealDll(@TempDir Path nativeDir) {
        LocalGamevalStore store = new LocalGamevalStore(nativeDir.resolve(LocalGamevalStore.DIR_NAME),
                ProcessHandle.current().pid(), pid -> ProcessHandle.of(pid).isPresent());
        GamevalSourceOpener opener = NxtCacheGamevalSource.forResolver(
                () -> new CacheSource.LocalDirectory(cacheDir, true));
        HostGamevals.PeerWait noWait = new HostGamevals.PeerWait(Duration.ZERO, Duration.ZERO);
        Path shipped = nativeDir.resolve("gameval.sqlite");

        try (GamevalIndex index = new HostGamevals(Optional::empty, shipped, store, opener,
                SqliteGamevalIndex::open, Runnable::run, noWait).open()) {
            String version = index.meta(HostGamevals.INDEX_VERSION_KEY).orElseThrow();
            assertReadsLive(index, version);
            assertTrue(store.published(Integer.parseInt(version)).isPresent());
        }
        assertTrue(Files.notExists(shipped), "the shipped path must never be written");
    }

    private static void assertReadsLive(GamevalIndex index, String version) {
        assertAll(
                () -> assertEquals(Optional.of(version), index.meta(HostGamevals.INDEX_VERSION_KEY)),
                () -> assertEquals(Optional.of(Integer.toString(EXPECTED_ETYPES)), index.meta("groups")),
                () -> assertEquals(OptionalInt.of(BANK_INTERFACE), index.interfaceId("BANK")),
                () -> assertEquals(OptionalInt.of(LOGOUT_LOBBY_PACKED),
                        index.id(GamevalType.COMPONENT, "ESCAPE_MENU__LOGOUT_LOBBY")));
    }

    private static int distinctEtypes(Path db) throws SQLException, IOException {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + db.toRealPath());
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT etype) FROM gameval")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
