package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionMatchTest {

    private static SdnCatalogueEntry entry(String name, String scriptClass) {
        return new SdnCatalogueEntry("1", name, "a", "b", "1", "2", "", "", scriptClass, false, true, true, false,
                null, null, null);
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

    // ── A fresh delivery: class or name ────────────────────────────────────

    @Test
    void isDeliveryOf_scriptClassPresent_matchesTheClassWhateverTheName() {
        SdnCatalogueEntry e = entry("Woodcutter", "com.example.Woodcutter");

        assertTrue(SubscriptionMatch.isDeliveryOf("com.example.Woodcutter", "Anything", e));
        assertFalse(SubscriptionMatch.isDeliveryOf("com.other.Fletcher", "Fletcher", e));
    }

    @Test
    void isDeliveryOf_agentV1ClassOnTheEntry_matchesTheAgentV2BuildByName() {
        // A shared entry names the v1 class; the v2 build of the same script has another.
        SdnCatalogueEntry e = entry("CraftWithUs", "net.botwithus.plugins.crafting.CraftWithUs");

        assertTrue(SubscriptionMatch.isDeliveryOf("com.botwithus.scripts.craftwithus.CraftWithUs", "CraftWithUs", e));
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
