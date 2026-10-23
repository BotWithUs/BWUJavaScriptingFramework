package com.botwithus.bot.cli.gui.pages;

import com.botwithus.bot.cli.gui.nav.SecondLine.AccountStatus;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.CourierUnavailable;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.Delivered;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.Failed;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.NotSignedIn;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.SubscriptionRequired;

import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * The Script Store's second line in the sidebar: your sign-in state as the last
 * catalogue answer tells it, never a path, and how many scripts are new since the
 * Store was last opened. The dot is live only while the launcher is answering for
 * a signed-in account.
 */
public final class StoreSignInLine {

    private static final String SIGNED_IN = "Signed in";

    private StoreSignInLine() {}

    /**
     * @param shown    what the catalogue refresher is showing; empty before the first answer
     * @param countNew how many of the given catalogue ids the user has not been shown yet
     */
    public static AccountStatus of(Optional<SdnCatalogueResult> shown, ToIntFunction<List<String>> countNew) {
        if (shown.isEmpty()) {
            return new AccountStatus("Syncing…", false);
        }
        return switch (shown.get()) {
            case Delivered d when d.stale() -> new AccountStatus("Offline · cached", false);
            case Delivered d -> new AccountStatus(signedIn(countNew.applyAsInt(ids(d))), true);
            case CourierUnavailable ignored -> new AccountStatus("Launcher not running", false);
            case NotSignedIn ignored -> new AccountStatus("Signed out", false);
            case SubscriptionRequired ignored -> new AccountStatus("Subscription needed", false);
            case Failed ignored -> new AccountStatus("Store unavailable", false);
        };
    }

    private static List<String> ids(Delivered delivered) {
        return delivered.entries().stream().map(SdnCatalogueEntry::id).toList();
    }

    private static String signedIn(int newScripts) {
        return newScripts > 0 ? SIGNED_IN + " · " + newScripts + " new" : SIGNED_IN;
    }
}
