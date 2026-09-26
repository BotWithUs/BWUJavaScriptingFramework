package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionMatchTest {

    private static SdnCatalogueEntry entry(String name, String scriptClass) {
        return new SdnCatalogueEntry("1", name, "a", "b", "1", "2", "", "", scriptClass, false, true, true, false);
    }

    @Test
    void isSameScript_scriptClassPresent_comparesClassNameExactly() {
        SdnCatalogueEntry e = entry("Woodcutter", "com.example.Woodcutter");

        assertTrue(SubscriptionMatch.isSameScript("com.example.Woodcutter", "Anything", e));
        assertFalse(SubscriptionMatch.isSameScript("com.other.Woodcutter", "Woodcutter", e),
                "a matching name must not override a different class");
    }

    @Test
    void isSameScript_scriptClassBlank_fallsBackToNameIgnoringCase() {
        SdnCatalogueEntry e = entry("Woodcutter", "");

        assertTrue(SubscriptionMatch.isSameScript("x.Y", "woodcutter", e));
        assertFalse(SubscriptionMatch.isSameScript("x.Y", "Fletcher", e));
    }

    @Test
    void isSameScript_scriptClassNull_fallsBackToName() {
        assertTrue(SubscriptionMatch.isSameScript("x.Y", "Woodcutter", entry("Woodcutter", null)));
    }

    @Test
    void isSameScript_blankNameAndClass_matchesNothing() {
        assertFalse(SubscriptionMatch.isSameScript("x.Y", "", entry("", "")));
    }
}
