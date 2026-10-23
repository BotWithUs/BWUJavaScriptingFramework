package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds the picker's "Your subscriptions" group from the SDN catalogue. Pure, so
 * the rules are pinned by {@code SubscriptionGroupsTest} rather than by a screenshot:
 *
 * <ul>
 *   <li>A row is shown iff {@code subscribed == TRUE} and the script runs on this host.
 *       A free script the account has not subscribed to is not a subscription.</li>
 *   <li>If the catalogue has entries and <em>every</em> one has {@code subscribed == null},
 *       the launcher predates the field and the group is {@link SubscriptionGroup.Hidden}.
 *       Nothing is inferred from {@code author} / {@code subscriber}.</li>
 *   <li>An empty catalogue is not "too old": there is nothing to be silent about, so it
 *       lists no rows rather than hiding the group.</li>
 * </ul>
 */
public final class SubscriptionGroups {

    private SubscriptionGroups() {}

    /**
     * The group for whatever the catalogue refresher is showing.
     *
     * @param shown the refresher's current result, empty before its first fetch finishes
     * @param toRow turns a qualifying catalogue entry into a row (install state, local copy)
     */
    public static SubscriptionGroup of(Optional<SdnCatalogueResult> shown,
                                       Function<SdnCatalogueEntry, SubscriptionEntry> toRow) {
        if (shown.isEmpty()) {
            return new SubscriptionGroup.Pending();
        }
        return switch (shown.get()) {
            case SdnCatalogueResult.Delivered delivered -> listed(delivered, toRow);
            case SdnCatalogueResult.CourierUnavailable ignored -> unavailable(
                    SubscriptionGroup.Reason.LAUNCHER_NOT_RUNNING, "");
            case SdnCatalogueResult.NotSignedIn ignored -> unavailable(SubscriptionGroup.Reason.NOT_SIGNED_IN, "");
            case SdnCatalogueResult.SubscriptionRequired ignored -> unavailable(
                    SubscriptionGroup.Reason.NO_ACCESS, "");
            case SdnCatalogueResult.Failed failed -> unavailable(SubscriptionGroup.Reason.FAILED, failed.reason());
        };
    }

    /**
     * The entries the group lists, by name; empty when the launcher is too old to
     * report subscriptions at all.
     */
    public static Optional<List<SdnCatalogueEntry>> subscribedHere(List<SdnCatalogueEntry> all) {
        if (!reportsSubscriptions(all)) {
            return Optional.empty();
        }
        return Optional.of(all.stream()
                .filter(e -> Boolean.TRUE.equals(e.subscribed()) && e.runsOnThisHost())
                .sorted(Comparator.comparing((SdnCatalogueEntry e) -> e.name().toLowerCase(Locale.ROOT)))
                .toList());
    }

    /** False iff there are entries and not one of them carries {@code subscribed}. */
    public static boolean reportsSubscriptions(List<SdnCatalogueEntry> all) {
        return all.isEmpty() || all.stream().anyMatch(e -> e.subscribed() != null);
    }

    private static SubscriptionGroup listed(SdnCatalogueResult.Delivered delivered,
                                            Function<SdnCatalogueEntry, SubscriptionEntry> toRow) {
        return subscribedHere(delivered.entries())
                .<SubscriptionGroup>map(entries -> new SubscriptionGroup.Listed(
                        entries.stream().map(toRow).toList(), delivered.stale()))
                .orElseGet(SubscriptionGroup.Hidden::new);
    }

    private static SubscriptionGroup unavailable(SubscriptionGroup.Reason reason, String detail) {
        return new SubscriptionGroup.Unavailable(reason, detail);
    }
}
