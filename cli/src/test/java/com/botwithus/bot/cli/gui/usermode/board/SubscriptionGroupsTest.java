package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionGroupsTest {

    private static final boolean V2 = true;
    private static final boolean V1_ONLY = false;

    private static final Function<SdnCatalogueEntry, SubscriptionEntry> ROW =
            e -> SubscriptionEntry.of(e, new SubscriptionState.NotInstalled(), OptionalInt.empty());

    private static SdnCatalogueEntry entry(String name, Boolean subscribed, Boolean isFree, boolean v2) {
        return new SdnCatalogueEntry("id-" + name, name, "author", "me", "1.0", "2", "tagline", "",
                "com.example." + name, !v2, v2, subscribed, isFree);
    }

    private static List<String> names(SubscriptionGroup group) {
        return group.entries().stream().map(SubscriptionEntry::name).toList();
    }

    private static SubscriptionGroup delivered(SdnCatalogueEntry... entries) {
        return SubscriptionGroups.of(Optional.of(new SdnCatalogueResult.Delivered(List.of(entries), false)), ROW);
    }

    // ── Which rows ─────────────────────────────────────────────────────────

    @Test
    void of_listsSubscribedEntriesOnly() {
        SubscriptionGroup group = delivered(
                entry("Paid", true, false, V2),
                entry("Lapsed", false, false, V2),
                entry("Unknown", null, false, V2));

        assertInstanceOf(SubscriptionGroup.Listed.class, group);
        assertEquals(List.of("Paid"), names(group));
    }

    @Test
    void of_freeScriptNotSubscribedTo_isExcluded() {
        SubscriptionGroup group = delivered(
                entry("FreeSubscribed", true, true, V2),
                entry("FreeBrowsable", false, true, V2));

        assertEquals(List.of("FreeSubscribed"), names(group));
    }

    @Test
    void of_subscribedButV1Only_isExcluded() {
        SubscriptionGroup group = delivered(
                entry("Here", true, false, V2),
                entry("OldAgent", true, false, V1_ONLY));

        assertEquals(List.of("Here"), names(group));
    }

    @Test
    void of_rowsAreSortedByName() {
        SubscriptionGroup group = delivered(
                entry("charlie", true, false, V2),
                entry("Alpha", true, true, V2),
                entry("bravo", true, false, V2));

        assertEquals(List.of("Alpha", "bravo", "charlie"), names(group));
    }

    // ── Old launcher ───────────────────────────────────────────────────────

    @Test
    void of_everyEntryMissingSubscribed_hidesTheGroup() {
        SubscriptionGroup group = delivered(
                entry("A", null, null, V2),
                entry("B", null, true, V2));

        assertInstanceOf(SubscriptionGroup.Hidden.class, group);
        assertTrue(group.isHidden());
        assertTrue(group.entries().isEmpty());
    }

    @Test
    void of_everyEntryMissingSubscribed_neverGuessesFromAuthorOrSubscriber() {
        // author == subscriber would read as "owned" under the old heuristic; it must not surface.
        SdnCatalogueEntry own = new SdnCatalogueEntry("1", "Mine", "me", "me", "1.0", "2", "", "", "a.B",
                false, true, null, null);

        assertTrue(delivered(own).isHidden());
    }

    @Test
    void of_oneEntryReportsSubscribed_showsTheGroup() {
        SubscriptionGroup group = delivered(
                entry("A", null, null, V2),
                entry("B", false, true, V2));

        assertFalse(group.isHidden());
        assertTrue(group.entries().isEmpty());
    }

    @Test
    void of_emptyCatalogue_listsNothingRatherThanHiding() {
        SubscriptionGroup group = delivered();

        SubscriptionGroup.Listed listed = assertInstanceOf(SubscriptionGroup.Listed.class, group);
        assertTrue(listed.entries().isEmpty());
    }

    @Test
    void reportsSubscriptions_allNull_isFalse() {
        assertFalse(SubscriptionGroups.reportsSubscriptions(List.of(entry("A", null, true, V2))));
        assertTrue(SubscriptionGroups.reportsSubscriptions(List.of(entry("A", false, true, V2))));
    }

    // ── Badge ──────────────────────────────────────────────────────────────

    @Test
    void priceBadge_mapsIsFreeThreeWays() {
        assertEquals(PriceBadge.FREE, PriceBadge.of(Boolean.TRUE));
        assertEquals(PriceBadge.PAID, PriceBadge.of(Boolean.FALSE));
        assertEquals(PriceBadge.NONE, PriceBadge.of(null));
    }

    @Test
    void of_rowBadgeFollowsIsFree() {
        SubscriptionGroup group = delivered(
                entry("Free", true, true, V2),
                entry("Paid", true, false, V2),
                entry("Unsaid", true, null, V2));

        List<PriceBadge> badges = group.entries().stream().map(SubscriptionEntry::badge).toList();
        assertEquals(List.of(PriceBadge.FREE, PriceBadge.PAID, PriceBadge.NONE), badges);
    }

    // ── Other catalogue states ─────────────────────────────────────────────

    @Test
    void of_nothingFetchedYet_isPending() {
        assertInstanceOf(SubscriptionGroup.Pending.class, SubscriptionGroups.of(Optional.empty(), ROW));
    }

    @Test
    void of_staleDelivery_isListedAndMarkedStale() {
        SubscriptionGroup group = SubscriptionGroups.of(Optional.of(new SdnCatalogueResult.Delivered(
                List.of(entry("A", true, false, V2)), true)), ROW);

        SubscriptionGroup.Listed listed = assertInstanceOf(SubscriptionGroup.Listed.class, group);
        assertTrue(listed.stale());
        assertEquals(List.of("A"), names(group));
    }

    @Test
    void of_launcherStates_areUnavailableWithTheirOwnReason() {
        assertEquals(SubscriptionGroup.Reason.LAUNCHER_NOT_RUNNING,
                reasonOf(new SdnCatalogueResult.CourierUnavailable()));
        assertEquals(SubscriptionGroup.Reason.NOT_SIGNED_IN, reasonOf(new SdnCatalogueResult.NotSignedIn()));
        assertEquals(SubscriptionGroup.Reason.NO_ACCESS, reasonOf(new SdnCatalogueResult.SubscriptionRequired()));
        assertEquals(SubscriptionGroup.Reason.FAILED, reasonOf(new SdnCatalogueResult.Failed("boom")));
    }

    @Test
    void of_failed_carriesTheLauncherMessage() {
        SubscriptionGroup group = SubscriptionGroups.of(Optional.of(new SdnCatalogueResult.Failed("boom")), ROW);

        assertEquals("boom", assertInstanceOf(SubscriptionGroup.Unavailable.class, group).detail());
    }

    private static SubscriptionGroup.Reason reasonOf(SdnCatalogueResult result) {
        SubscriptionGroup group = SubscriptionGroups.of(Optional.of(result), ROW);
        return assertInstanceOf(SubscriptionGroup.Unavailable.class, group).reason();
    }
}
