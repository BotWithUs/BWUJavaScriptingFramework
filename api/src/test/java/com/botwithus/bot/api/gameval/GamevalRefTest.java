package com.botwithus.bot.api.gameval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link GamevalRef}'s resolution order: the index wins when it knows the
 * name, the fallback id is used when it doesn't or when no index is deployed.
 */
class GamevalRefTest {

    private static final String BANK_ITEMS = "BANK__BANK_INV";
    private static final int INDEXED_HASH = 33882313;
    private static final int FALLBACK_HASH = 33882300;
    private static final GamevalRef ITEMS = new GamevalRef(GamevalType.COMPONENT, BANK_ITEMS, FALLBACK_HASH);

    @Test
    void resolve_nameInIndex_returnsIndexedId() {
        GamevalIndex index = new SingleEntryIndex(GamevalType.COMPONENT, BANK_ITEMS, INDEXED_HASH);
        assertEquals(INDEXED_HASH, ITEMS.resolve(index));
    }

    @Test
    void resolve_nameMissingFromIndex_returnsFallback() {
        GamevalIndex index = new SingleEntryIndex(GamevalType.COMPONENT, "BANK__SCROLLBAR", INDEXED_HASH);
        assertEquals(FALLBACK_HASH, ITEMS.resolve(index));
    }

    @Test
    void resolve_sameNameOtherType_returnsFallback() {
        GamevalIndex index = new SingleEntryIndex(GamevalType.INTERFACE, BANK_ITEMS, INDEXED_HASH);
        assertEquals(FALLBACK_HASH, ITEMS.resolve(index));
    }

    @Test
    void resolve_noIndexDeployed_returnsFallback() {
        assertEquals(FALLBACK_HASH, ITEMS.resolve(GamevalIndex.empty()));
    }

    @Test
    void constructor_missingTypeOrName_throws() {
        assertThrows(IllegalArgumentException.class, () -> new GamevalRef(null, BANK_ITEMS, FALLBACK_HASH));
        assertThrows(IllegalArgumentException.class,
                () -> new GamevalRef(GamevalType.COMPONENT, " ", FALLBACK_HASH));
    }

    /** An index holding exactly one name. */
    private record SingleEntryIndex(GamevalType type, String name, int id) implements GamevalIndex {

        @Override
        public OptionalInt id(GamevalType lookupType, String gameval) {
            return lookupType == type && name.equalsIgnoreCase(gameval) ? OptionalInt.of(id) : OptionalInt.empty();
        }

        @Override
        public Optional<String> gameval(GamevalType lookupType, int lookupId) {
            return lookupType == type && lookupId == id ? Optional.of(name) : Optional.empty();
        }

        @Override
        public List<GamevalEntry> startingWith(GamevalType lookupType, String prefix, int limit) {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<String> meta(String key) {
            return Optional.empty();
        }
    }
}
