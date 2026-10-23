package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionMatchTest {

    private static SdnCatalogueEntry entry(String name, String scriptClass) {
        return new SdnCatalogueEntry("1", name, "a", "b", "1", "2", "", "", scriptClass, false, true, true, false,
                null, null, null);
    }

    // ── Local scripts: class only ─────────────────────────────────

    @Test
    void isLoadedCopy_scriptClassPresent_comparesClassNameExactly() {
        SdnCatalogueEntry e = entry("Woodcutter", "com.example.Woodcutter");

        assertTrue(SubscriptionMatch.isLoadedCopy("com.example.Woodcutter", e));
        assertFalse(SubscriptionMatch.isLoadedCopy("com.other.Woodcutter", e));
    }

    @Test
    void isLoadedCopy_scriptClassBlank_neverMatchesEvenBySameName() {
        // A same-named local JAR by another author must not stand in for the subscription.
        assertFalse(SubscriptionMatch.isLoadedCopy("com.other.Woodcutter", entry("Woodcutter", "")));
        assertFalse(SubscriptionMatch.isLoadedCopy("Woodcutter", entry("Woodcutter", "")));
    }

    @Test
    void isLoadedCopy_scriptClassNull_neverMatches() {
        assertFalse(SubscriptionMatch.isLoadedCopy("x.Y", entry("Woodcutter", null)));
    }

    // ── A fresh delivery: whatever its jar provided ────────────────────────

    /** A jar's sole script starts, whatever the catalogue names: it is the v2 build of a v1 entry. */
    @Test
    void scriptToStart_oneScriptInTheJar_isThatScript() {
        BotScript only = new Alpha();
        SdnCatalogueEntry shared = entry("CraftWithUs", "com.example.agentv1.CraftWithUs");

        assertEquals(Optional.of(only), SubscriptionMatch.scriptToStart(shared, List.of(only), NAME));
    }

    @Test
    void scriptToStart_nothingPaired_isEmpty() {
        assertEquals(Optional.empty(),
                SubscriptionMatch.scriptToStart(entry("Alpha", Alpha.class.getName()), List.of(), NAME));
    }

    @Test
    void scriptToStart_severalInTheJar_prefersTheCataloguesClassThenItsName() {
        BotScript alpha = new Alpha();
        BotScript beta = new Beta();

        assertAll(
                () -> assertEquals(Optional.of(beta), SubscriptionMatch.scriptToStart(
                        entry("Alpha", Beta.class.getName()), List.of(alpha, beta), NAME)),
                () -> assertEquals(Optional.of(alpha), SubscriptionMatch.scriptToStart(
                        entry("alpha", "com.example.agentv1.Alpha"), List.of(alpha, beta), NAME)));
    }

    @Test
    void scriptToStart_severalInTheJarAndNoneNamed_isEmptyRatherThanAGuess() {
        assertEquals(Optional.empty(), SubscriptionMatch.scriptToStart(
                entry("Gamma", ""), List.of(new Alpha(), new Beta()), NAME));
    }

    private static final Function<BotScript, String> NAME = s -> s.getClass().getSimpleName();

    private static final class Alpha implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    private static final class Beta implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }
}
