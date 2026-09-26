package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import java.util.OptionalInt;

/**
 * One row in the picker's "Your subscriptions" group.
 *
 * @param id       the catalogue id, which {@link ClientActions#startSubscription} takes
 * @param summary  one display line: the tagline, else the description's first line
 * @param localKey the {@link ScriptEntry#key()} of an installed local copy of the same
 *                 script, if there is one. The picker hides that local row so the
 *                 script is listed once, here.
 */
public record SubscriptionEntry(
        String id,
        String name,
        String author,
        String version,
        String summary,
        PriceBadge badge,
        SubscriptionState state,
        OptionalInt localKey) {

    public static SubscriptionEntry of(SdnCatalogueEntry entry, SubscriptionState state, OptionalInt localKey) {
        return new SubscriptionEntry(entry.id(), entry.name(), entry.author(), entry.version(), entry.summary(),
                PriceBadge.of(entry.isFree()), state, localKey);
    }
}
