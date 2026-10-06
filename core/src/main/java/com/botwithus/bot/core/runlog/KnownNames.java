package com.botwithus.bot.core.runlog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The names the host knows for one run, which the {@link Redactor} replaces
 * before anything reaches disk (spec rule R1).
 *
 * @param accounts account names, account emails and connection labels; each
 *                 becomes {@code Account#n}
 * @param players  display and character names; each becomes {@code Player#n}
 */
public record KnownNames(List<String> accounts, List<String> players) {

    /** No names known. */
    public static final KnownNames NONE = new KnownNames(List.of(), List.of());

    public KnownNames {
        accounts = usable(accounts);
        players = usable(players);
    }

    /** Builds a set from possibly-null, possibly-blank values, dropping those. */
    public static KnownNames of(Collection<String> accounts, Collection<String> players) {
        return new KnownNames(new ArrayList<>(accounts), new ArrayList<>(players));
    }

    private static List<String> usable(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(n -> !n.isEmpty())
                .distinct()
                .toList();
    }
}
