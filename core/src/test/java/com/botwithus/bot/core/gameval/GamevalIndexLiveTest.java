package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.ComponentRef;
import com.botwithus.bot.core.util.NativeCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drift alarm for the deployed {@code gameval.sqlite}. Skips cleanly when no
 * index is present, so CI without the baked file stays green; point it at one
 * with {@code -Dbotwithus.gameval=<path>} or by dropping the file in
 * {@code ~/.botwithus/native/}.
 *
 * <p>The tuples below are the ones the rest of the host and its scripts lean
 * on. A failure here means the index was rebuilt from a game update that
 * renumbered something — which is the signal to re-check the scripts that name
 * it, not to edit this test.</p>
 */
class GamevalIndexLiveTest {

    private static final int YEW_LOGS = 1515;
    private static final int BANK_INTERFACE = 517;
    private static final int BANK_INV_BUTTON_COMPONENT = 39;
    private static final int WOODCUTTING_WOODBOX_LASTUSED_TIER = 10903;

    private static SqliteGamevalIndex index;

    @BeforeAll
    static void open() {
        Optional<Path> db = NativeCache.locateGamevalDb();
        assumeTrue(db.isPresent(),
                "No gameval.sqlite found (set -Dbotwithus.gameval=<path>); skipping");
        index = SqliteGamevalIndex.open(db.get());
    }

    @AfterAll
    static void close() {
        if (index != null) {
            index.close();
        }
    }

    @Test
    void carriesABuildStamp() {
        assertEquals(Optional.of("1"), index.meta("schema_version"));
        assertTrue(index.meta("built").isPresent(), "index should record when it was built");
        assertTrue(index.meta("rows").isPresent(), "index should record its row count");
    }

    @Test
    void resolvesPinnedEntityNames() {
        assertEquals(OptionalInt.of(YEW_LOGS), index.id(GamevalType.ITEM, "YEW_LOGS"));
        assertEquals(OptionalInt.of(BANK_INTERFACE), index.interfaceId("BANK"));
        assertEquals(Optional.of("YEW_LOGS"), index.gameval(GamevalType.ITEM, YEW_LOGS));
    }

    @Test
    void resolvesPinnedComponentName() {
        ComponentRef ref = index.component("BANK__BANK_INV_BUTTON").orElseThrow();
        assertEquals(BANK_INTERFACE, ref.interfaceId());
        assertEquals(BANK_INV_BUTTON_COMPONENT, ref.componentId());
    }

    @Test
    void resolvesPinnedVariableNames() {
        assertEquals(OptionalInt.of(WOODCUTTING_WOODBOX_LASTUSED_TIER),
                index.id(GamevalType.VARP, "WOODCUTTING_WOODBOX_LASTUSED_TIER"));
        // The varbit table was absent from the first Atlas bake; assert it is
        // populated so a regression in the build script is caught here.
        assertTrue(index.id(GamevalType.VARBIT, "ZAROS_SPELLBOOK").isPresent(),
                "varbit names should be indexed");
    }

    @Test
    void everyTypeHasNames() {
        for (GamevalType type : GamevalType.values()) {
            assertFalseEmpty(type);
        }
    }

    private static void assertFalseEmpty(GamevalType type) {
        assertTrue(!index.startingWith(type, "", 1).isEmpty(),
                () -> "no names indexed for type '" + type.wire() + "' — is the group "
                        + "missing from build_gameval_db.py's input?");
    }
}
