package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwappableGamevalIndexTest {

    private static final int FIRST = 1;
    private static final int SECOND = 2;

    @TempDir
    Path dir;

    private SqliteGamevalIndex fixture(String name, int marker) {
        return SqliteGamevalIndex.open(GamevalFixtures.writeDb(dir.resolve(name), Optional.empty(), marker));
    }

    @Test
    void aSwap_redirectsLookupsAndClosesTheReplacedIndex() {
        SqliteGamevalIndex first = fixture("a.sqlite", FIRST);
        SqliteGamevalIndex second = fixture("b.sqlite", SECOND);

        try (SwappableGamevalIndex index = new SwappableGamevalIndex(first)) {
            assertEquals(OptionalInt.of(FIRST), index.id(GamevalType.ITEM, GamevalFixtures.MARKER_NAME));
            assertTrue(index.swapTo(second));
            assertAll(
                    () -> assertEquals(OptionalInt.of(SECOND),
                            index.id(GamevalType.ITEM, GamevalFixtures.MARKER_NAME)),
                    () -> assertTrue(first.isClosed()),
                    () -> assertFalse(second.isClosed()));
        }
        assertTrue(second.isClosed());
    }

    @Test
    void aSwapAfterClose_isRefusedAndClosesTheIncomingIndex() {
        SqliteGamevalIndex first = fixture("a.sqlite", FIRST);
        SqliteGamevalIndex late = fixture("b.sqlite", SECOND);
        SwappableGamevalIndex index = new SwappableGamevalIndex(first);
        assertEquals(OptionalInt.of(FIRST), index.id(GamevalType.ITEM, GamevalFixtures.MARKER_NAME));

        index.close();

        assertAll(
                () -> assertFalse(index.swapTo(late)),
                () -> assertTrue(late.isClosed(), "a refused swap must not leak its connection"),
                () -> assertEquals(OptionalInt.of(FIRST),
                        index.id(GamevalType.ITEM, GamevalFixtures.MARKER_NAME),
                        "memoised answers survive close, as SqliteGamevalIndex promises"),
                () -> assertEquals(OptionalInt.empty(), index.id(GamevalType.ITEM, "COLD_NAME")));
    }
}
