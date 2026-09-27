package com.botwithus.bot.cli.gui.pages;

import com.botwithus.bot.cli.gui.nav.SecondLine.AccountStatus;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.CourierUnavailable;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.Delivered;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.Failed;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.NotSignedIn;
import com.botwithus.bot.core.sdn.SdnCatalogueResult.SubscriptionRequired;

import java.util.Optional;

/**
 * The Script Store's second line in the sidebar: your sign-in state as the last
 * catalogue answer tells it, never a path. The dot is live only while the
 * launcher is answering for a signed-in account.
 */
public final class StoreSignInLine {

    private StoreSignInLine() {}

    /** @param shown what the catalogue refresher is showing; empty before the first answer */
    public static AccountStatus of(Optional<SdnCatalogueResult> shown) {
        if (shown.isEmpty()) {
            return new AccountStatus("Checking…", false);
        }
        return switch (shown.get()) {
            case Delivered d when d.stale() -> new AccountStatus("Launcher not answering", false);
            case Delivered d -> new AccountStatus("Signed in · " + scripts(d.entries().size()), true);
            case CourierUnavailable ignored -> new AccountStatus("Launcher not running", false);
            case NotSignedIn ignored -> new AccountStatus("Signed out", false);
            case SubscriptionRequired ignored -> new AccountStatus("Signed in · no subscription", true);
            case Failed ignored -> new AccountStatus("Store unavailable", false);
        };
    }

    private static String scripts(int n) {
        return n == 1 ? "1 script" : n + " scripts";
    }
}
