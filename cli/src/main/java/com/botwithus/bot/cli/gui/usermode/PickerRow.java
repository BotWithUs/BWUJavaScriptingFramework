package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionEntry;

import java.util.Locale;

/** One selectable row in the Start Script picker: an installed local script, or a subscription. */
sealed interface PickerRow {

    record Local(ScriptEntry entry) implements PickerRow {}

    record Subscribed(SubscriptionEntry entry) implements PickerRow {}

    /** Whether the search text {@code query} (lower-case, stripped) finds this row. */
    default boolean matches(String query) {
        if (query.isEmpty()) {
            return true;
        }
        String hay = switch (this) {
            case Local local -> {
                ScriptInfo s = local.entry().info();
                yield s.name() + ' ' + s.categoryLabel() + ' ' + s.description();
            }
            case Subscribed sub -> {
                SubscriptionEntry s = sub.entry();
                yield s.name() + ' ' + s.author() + ' ' + s.summary() + ' ' + s.badge().label();
            }
        };
        return hay.toLowerCase(Locale.ROOT).contains(query);
    }

    default boolean isSubscription() {
        return switch (this) {
            case Local ignored -> false;
            case Subscribed ignored -> true;
        };
    }

    /** Whether choosing this row now would do anything; false while its install is in flight. */
    default boolean isChoosable() {
        return switch (this) {
            case Local ignored -> true;
            case Subscribed sub -> sub.entry().state().isChoosable();
        };
    }
}
