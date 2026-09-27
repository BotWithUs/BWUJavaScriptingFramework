package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.ScriptCategory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdnCatalogueEntryTest {

    private static final SdnPrice MONTHLY = new SdnPrice(new BigDecimal("4.99"), "monthly");

    private static SdnCatalogueEntry entry(Boolean isFree, SdnPrice price, String category) {
        return new SdnCatalogueEntry("1", "A", "ada", "bob", "1", "2", "", "", "a.B",
                false, true, true, isFree, category, price, null);
    }

    // pricing(): isFree decides; price never does ----------------------------

    @Test
    void pricing_isFreeTrue_isFree() {
        assertEquals(SdnCatalogueEntry.Pricing.FREE, entry(true, null, null).pricing());
    }

    @Test
    void pricing_isFreeTrueWithAPaidHeadlineTier_isStillFree() {
        assertEquals(SdnCatalogueEntry.Pricing.FREE, entry(true, MONTHLY, null).pricing(),
                "a script with a free tier and a paid one is offered for free");
    }

    @Test
    void pricing_isFreeFalse_isPaid() {
        assertEquals(SdnCatalogueEntry.Pricing.PAID, entry(false, MONTHLY, null).pricing());
    }

    @Test
    void pricing_isFreeFalseWithNoPrice_isStillPaid() {
        assertEquals(SdnCatalogueEntry.Pricing.PAID, entry(false, null, null).pricing());
    }

    @Test
    void pricing_isFreeUnknownAndNoPrice_isUnknownNotFree() {
        assertEquals(SdnCatalogueEntry.Pricing.UNKNOWN, entry(null, null, null).pricing(),
                "an older site omits the price for every script, so its absence proves nothing");
    }

    @Test
    void pricing_isFreeUnknownWithAPrice_isUnknownNotPaid() {
        assertEquals(SdnCatalogueEntry.Pricing.UNKNOWN, entry(null, MONTHLY, null).pricing(),
                "a paid headline tier does not rule out a free tier alongside it");
    }

    // scriptCategory() ---------------------------------------------------------

    @Test
    void scriptCategory_mapsTheSlug() {
        assertEquals(ScriptCategory.MINIGAME, entry(null, null, "mini_games").scriptCategory());
    }

    @Test
    void scriptCategory_noSlug_isUncategorized() {
        assertEquals(ScriptCategory.UNCATEGORIZED, entry(null, null, null).scriptCategory());
    }
}
