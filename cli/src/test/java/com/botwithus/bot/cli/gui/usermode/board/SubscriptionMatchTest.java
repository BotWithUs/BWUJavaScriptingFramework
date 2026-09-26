package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionMatchTest {

    private static SdnCatalogueEntry entry(String name, String scriptClass) {
        return new SdnCatalogueEntry("1", name, "a", "b", "1", "2", "", "", scriptClass, false, true, true, false);
    }

    // ── Already-loaded scripts: class only ─────────────────────────────────

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

    // ── A fresh delivery: class, else name ─────────────────────────────────

    @Test
    void isDeliveryOf_scriptClassPresent_comparesClassNameExactly() {
        SdnCatalogueEntry e = entry("Woodcutter", "com.example.Woodcutter");

        assertTrue(SubscriptionMatch.isDeliveryOf("com.example.Woodcutter", "Anything", e));
        assertFalse(SubscriptionMatch.isDeliveryOf("com.other.Woodcutter", "Woodcutter", e),
                "a matching name must not override a different class");
    }

    @Test
    void isDeliveryOf_scriptClassBlank_fallsBackToNameIgnoringCase() {
        SdnCatalogueEntry e = entry("Woodcutter", "");

        assertTrue(SubscriptionMatch.isDeliveryOf("x.Y", "woodcutter", e));
        assertFalse(SubscriptionMatch.isDeliveryOf("x.Y", "Fletcher", e));
    }

    @Test
    void isDeliveryOf_blankNameAndClass_matchesNothing() {
        assertFalse(SubscriptionMatch.isDeliveryOf("x.Y", "", entry("", "")));
    }
}
