package com.botwithus.bot.cli.gui.pages;

import com.botwithus.bot.cli.gui.nav.SecondLine.AccountStatus;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoreSignInLineTest {

    private static final ToIntFunction<List<String>> NOTHING_NEW = ids -> 0;

    private static AccountStatus lineFor(SdnCatalogueResult result) {
        return StoreSignInLine.of(Optional.of(result), NOTHING_NEW);
    }

    private static SdnCatalogueEntry entry(String id) {
        return new SdnCatalogueEntry(id, id, "author", "me", "1.0", "2", "", "", "", false, true, true, true,
                null, null, null);
    }

    @Test
    void beforeTheFirstAnswer_itIsSyncing() {
        assertEquals(new AccountStatus("Syncing…", false), StoreSignInLine.of(Optional.empty(), NOTHING_NEW));
    }

    @Test
    void aFreshCatalogue_withNothingNew_isSignedInAndLive() {
        assertEquals(new AccountStatus("Signed in", true),
                lineFor(new SdnCatalogueResult.Delivered(List.of(entry("a")), false)));
    }

    @Test
    void aFreshCatalogue_countsTheScriptsNotSeenYet() {
        List<List<String>> asked = new ArrayList<>();
        ToIntFunction<List<String>> twoNew = ids -> {
            asked.add(ids);
            return 2;
        };

        AccountStatus line = StoreSignInLine.of(Optional.of(
                new SdnCatalogueResult.Delivered(List.of(entry("a"), entry("b"), entry("c")), false)), twoNew);

        assertEquals(new AccountStatus("Signed in · 2 new", true), line);
        assertEquals(List.of(List.of("a", "b", "c")), asked, "asked about every catalogue id");
    }

    @Test
    void aStaleCatalogue_isOfflineAndCached() {
        assertEquals(new AccountStatus("Offline · cached", false),
                lineFor(new SdnCatalogueResult.Delivered(List.of(), true)));
    }

    @Test
    void everyFailure_hasItsOwnLine_andNoLiveDot() {
        assertEquals(new AccountStatus("Launcher not running", false),
                lineFor(new SdnCatalogueResult.CourierUnavailable()));
        assertEquals(new AccountStatus("Signed out", false), lineFor(new SdnCatalogueResult.NotSignedIn()));
        assertEquals(new AccountStatus("Subscription needed", false),
                lineFor(new SdnCatalogueResult.SubscriptionRequired()));
        assertEquals(new AccountStatus("Store unavailable", false), lineFor(new SdnCatalogueResult.Failed("boom")));
    }
}
