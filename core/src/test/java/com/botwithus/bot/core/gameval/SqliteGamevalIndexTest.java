package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalNotFoundException;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.ComponentRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the gameval reader against a fixture index built in-test, so this
 * runs unconditionally in CI without the ~70 MB baked file. The DDL below is a
 * verbatim copy of what {@code build_gameval_db.py} emits — if the two drift,
 * these tests keep passing while production breaks, so change both together.
 *
 * @see GamevalIndexLiveTest for the drift check against the real file
 */
class SqliteGamevalIndexTest {

    /** {@code BANK__BANK_INV_BUTTON}: interface 517, component 39, packed. */
    private static final int BANK_INV_BUTTON_PACKED = (517 << 16) | 39;
    private static final int YEW_LOGS = 1515;
    private static final int HAMMER_THREADS = 32;
    private static final int HAMMER_ITERATIONS = 200;

    private SqliteGamevalIndex index;

    @BeforeEach
    void open(@TempDir Path dir) throws SQLException {
        Path db = dir.resolve("gameval.sqlite");
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = con.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE gameval (
                        etype TEXT    NOT NULL,
                        eid   INTEGER NOT NULL,
                        name  TEXT    NOT NULL,
                        PRIMARY KEY (etype, eid)
                    ) WITHOUT ROWID""");
            st.executeUpdate("CREATE INDEX idx_gameval_lookup ON gameval(etype, name)");
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
            st.executeUpdate("INSERT INTO meta VALUES ('schema_version', '1'), ('rows', '9'),"
                    + " ('groups', '5'), ('built', '2026-08-08 20:18:14')");
            st.executeUpdate("INSERT INTO gameval (etype, eid, name) VALUES"
                    + " ('item', 1515, 'YEW_LOGS'),"
                    + " ('item', 1517, 'MAPLE_LOGS'),"
                    + " ('item', 995, 'COINS'),"
                    + " ('npc', 0, 'HANS'),"
                    + " ('loc', 2, 'MCANNONCAVE'),"
                    + " ('interface', 517, 'BANK'),"
                    + " ('component', " + BANK_INV_BUTTON_PACKED + ", 'BANK__BANK_INV_BUTTON'),"
                    + " ('component', 33882112, 'BANK__ALL'),"
                    + " ('varbit', 0, 'ZAROS_SPELLBOOK')");
        }
        index = SqliteGamevalIndex.open(db);
    }

    @AfterEach
    void close() {
        if (index != null) {
            index.close();
        }
    }

    @Test
    void resolvesNameToId() {
        assertEquals(OptionalInt.of(YEW_LOGS), index.id(GamevalType.ITEM, "YEW_LOGS"));
        assertEquals(OptionalInt.of(0), index.id(GamevalType.NPC, "HANS"));
        assertEquals(OptionalInt.of(517), index.interfaceId("BANK"));
    }

    @Test
    void nameLookupIsCaseInsensitive() {
        assertEquals(OptionalInt.of(YEW_LOGS), index.id(GamevalType.ITEM, "yew_logs"));
        assertEquals(OptionalInt.of(YEW_LOGS), index.id(GamevalType.ITEM, "Yew_Logs"));
    }

    @Test
    void namesAreScopedToTheirType() {
        // HANS is an npc, not an item: the same name must not leak across types.
        assertEquals(OptionalInt.empty(), index.id(GamevalType.ITEM, "HANS"));
    }

    @Test
    void unknownNameResolvesEmpty() {
        assertEquals(OptionalInt.empty(), index.id(GamevalType.ITEM, "NOT_A_REAL_NAME"));
    }

    @Test
    void requireThrowsOnUnknownName() {
        GamevalNotFoundException e = assertThrows(GamevalNotFoundException.class,
                () -> index.require(GamevalType.ITEM, "NOT_A_REAL_NAME"));
        assertEquals(GamevalType.ITEM, e.type());
        assertEquals("NOT_A_REAL_NAME", e.gameval());
        assertTrue(e.getMessage().contains("NOT_A_REAL_NAME"));
    }

    @Test
    void resolvesIdBackToName() {
        assertEquals(Optional.of("YEW_LOGS"), index.gameval(GamevalType.ITEM, YEW_LOGS));
        assertEquals(Optional.empty(), index.gameval(GamevalType.ITEM, 999999));
    }

    @Test
    void componentNameSplitsIntoInterfaceAndComponent() {
        ComponentRef ref = index.component("BANK__BANK_INV_BUTTON").orElseThrow();
        assertEquals(517, ref.interfaceId());
        assertEquals(39, ref.componentId());

        ComponentRef root = index.component("BANK__ALL").orElseThrow();
        assertEquals(517, root.interfaceId());
        assertEquals(0, root.componentId());
    }

    @Test
    void unknownComponentNameIsEmpty() {
        assertEquals(Optional.empty(), index.component("BANK__NOT_A_THING"));
    }

    @Test
    void missesAreCachedAsReadilyAsHits() {
        // A script polling a name that will never resolve must not hit sqlite on
        // every loop iteration. Prime one hit and one miss, then close the
        // connection: answers that still come back cannot have reached the file.
        assertEquals(OptionalInt.of(YEW_LOGS), index.id(GamevalType.ITEM, "YEW_LOGS"));
        assertEquals(OptionalInt.empty(), index.id(GamevalType.ITEM, "NOPE"));
        assertEquals(Optional.of("HANS"), index.gameval(GamevalType.NPC, 0));
        assertEquals(Optional.empty(), index.gameval(GamevalType.NPC, 4242));

        index.close();

        assertEquals(OptionalInt.of(YEW_LOGS), index.id(GamevalType.ITEM, "YEW_LOGS"));
        assertEquals(OptionalInt.empty(), index.id(GamevalType.ITEM, "NOPE"));
        assertEquals(Optional.of("HANS"), index.gameval(GamevalType.NPC, 0));
        assertEquals(Optional.empty(), index.gameval(GamevalType.NPC, 4242));
    }

    @Test
    void prefixScanIsOrderedAndBounded() {
        List<GamevalEntry> all = index.startingWith(GamevalType.COMPONENT, "BANK__", 10);
        assertEquals(List.of("BANK__ALL", "BANK__BANK_INV_BUTTON"),
                all.stream().map(GamevalEntry::gameval).toList());
        assertEquals(GamevalType.COMPONENT, all.getFirst().type());

        assertEquals(1, index.startingWith(GamevalType.COMPONENT, "BANK__", 1).size());
        assertTrue(index.startingWith(GamevalType.ITEM, "ZZZ", 10).isEmpty());
        assertTrue(index.startingWith(GamevalType.ITEM, "", 0).isEmpty());
    }

    @Test
    void emptyPrefixListsTheWholeType() {
        assertEquals(List.of("COINS", "MAPLE_LOGS", "YEW_LOGS"),
                index.startingWith(GamevalType.ITEM, "", 10).stream()
                        .map(GamevalEntry::gameval).toList());
    }

    @Test
    void exposesBuildStamp() {
        assertEquals(Optional.of("1"), index.meta("schema_version"));
        assertEquals(Optional.of("2026-08-08 20:18:14"), index.meta("built"));
        assertEquals(Optional.empty(), index.meta("no_such_key"));
        assertTrue(index.isAvailable());
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion(@TempDir Path dir) throws SQLException {
        Path db = dir.resolve("future.sqlite");
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = con.createStatement()) {
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
            st.executeUpdate("INSERT INTO meta VALUES ('schema_version', '99')");
        }
        GamevalIndexException e = assertThrows(GamevalIndexException.class,
                () -> SqliteGamevalIndex.open(db));
        assertTrue(e.getMessage().contains("schema_version"));
    }

    @Test
    void survivesConcurrentReaders() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(HAMMER_THREADS);
        AtomicInteger mismatches = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < HAMMER_THREADS; ++t) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < HAMMER_ITERATIONS; ++i) {
                        if (index.id(GamevalType.ITEM, "YEW_LOGS").orElse(-1) != YEW_LOGS) {
                            mismatches.incrementAndGet();
                        }
                        if (!index.gameval(GamevalType.NPC, 0).equals(Optional.of("HANS"))) {
                            mismatches.incrementAndGet();
                        }
                        if (index.id(GamevalType.ITEM, "MISSING_" + i).isPresent()) {
                            mismatches.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "concurrent readers did not finish");
        assertNull(failure.get(), () -> "reader threw: " + failure.get());
        assertEquals(0, mismatches.get(), "concurrent readers disagreed on a lookup");
    }

    @Test
    void emptyIndexResolvesNothingAndNeverThrows() {
        GamevalIndex empty = GamevalIndex.empty();
        assertFalse(empty.isAvailable());
        assertEquals(OptionalInt.empty(), empty.id(GamevalType.ITEM, "YEW_LOGS"));
        assertEquals(Optional.empty(), empty.gameval(GamevalType.ITEM, YEW_LOGS));
        assertEquals(Optional.empty(), empty.component("BANK__BANK_INV_BUTTON"));
        assertEquals(List.of(), empty.startingWith(GamevalType.ITEM, "Y", 5));
        assertEquals(Optional.empty(), empty.meta("built"));
        assertThrows(GamevalNotFoundException.class,
                () -> empty.require(GamevalType.ITEM, "YEW_LOGS"));
        empty.close();
    }

    @Test
    void emptyIndexIsNotIdentityComparable() {
        // empty() hands out a fresh instance each call by design (no shared
        // mutable static); callers must test isAvailable(), never identity.
        assertNotSame(GamevalIndex.empty(), GamevalIndex.empty());
        assertSame(GamevalType.ITEM, GamevalType.fromWire("item").orElseThrow());
    }
}
