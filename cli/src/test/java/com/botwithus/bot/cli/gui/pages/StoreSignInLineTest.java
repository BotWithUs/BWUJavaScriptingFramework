package com.botwithus.bot.cli.gui.pages;

import com.botwithus.bot.cli.gui.nav.SecondLine.AccountStatus;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoreSignInLineTest {

    private static AccountStatus lineFor(SdnCatalogueResult result) {
        return StoreSignInLine.of(Optional.of(result));
    }

    @Test
    void beforeTheFirstAnswer_itIsStillChecking() {
        assertEquals(new AccountStatus("Checking…", false), StoreSignInLine.of(Optional.empty()));
    }

    @Test
    void aFreshCatalogue_isSignedInAndLive() {
        assertEquals(new AccountStatus("Signed in · 0 scripts", true),
                lineFor(new SdnCatalogueResult.Delivered(List.of(), false)));
    }

    @Test
    void aStaleCatalogue_saysTheLauncherIsNotAnswering() {
        assertEquals(new AccountStatus("Launcher not answering", false),
                lineFor(new SdnCatalogueResult.Delivered(List.of(), true)));
    }

    @Test
    void everyFailure_hasItsOwnLine_andNoLiveDot() {
        assertEquals(new AccountStatus("Launcher not running", false),
                lineFor(new SdnCatalogueResult.CourierUnavailable()));
        assertEquals(new AccountStatus("Signed out", false), lineFor(new SdnCatalogueResult.NotSignedIn()));
        assertEquals(new AccountStatus("Store unavailable", false), lineFor(new SdnCatalogueResult.Failed("boom")));
    }

    @Test
    void signedInWithoutASubscription_isStillSignedIn() {
        assertEquals(new AccountStatus("Signed in · no subscription", true),
                lineFor(new SdnCatalogueResult.SubscriptionRequired()));
    }
}
