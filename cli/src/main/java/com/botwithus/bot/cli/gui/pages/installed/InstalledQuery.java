package com.botwithus.bot.cli.gui.pages.installed;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The page's filters: which scripts to show, from which source, matching what text.
 *
 * @param search matched, ignoring case, against the name, the category and the JAR name
 */
public record InstalledQuery(Show show, SourceFilter source, String search) {

    /** The All / Running / Not running / Problems control. */
    public enum Show { ALL, RUNNING, NOT_RUNNING, PROBLEMS }

    /** The Source control. */
    public enum SourceFilter { ANY, STORE, LOCAL }

    /** Everything, from anywhere, unsearched. */
    public static final InstalledQuery DEFAULT = new InstalledQuery(Show.ALL, SourceFilter.ANY, "");

    public InstalledQuery {
        Objects.requireNonNull(show, "show");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(search, "search");
    }

    public InstalledQuery withShow(Show next) {
        return new InstalledQuery(next, source, search);
    }

    public InstalledQuery withSource(SourceFilter next) {
        return new InstalledQuery(show, next, search);
    }

    public InstalledQuery withSearch(String next) {
        return new InstalledQuery(show, source, next);
    }

    /** Any filter is narrowing the list, so "Clear filters" has something to clear. */
    public boolean isFiltered() {
        return !equals(DEFAULT);
    }

    /** The scripts that pass every filter, in their original order. */
    public List<InstalledScript> apply(List<InstalledScript> scripts) {
        String needle = search.strip().toLowerCase(Locale.ROOT);
        return scripts.stream().filter(s -> shows(show, s) && isFrom(s) && contains(s, needle)).toList();
    }

    /** How many of {@code scripts} a {@code show} segment holds, before any other filter: its count. */
    public static int count(Show show, List<InstalledScript> scripts) {
        return (int) scripts.stream().filter(s -> shows(show, s)).count();
    }

    private static boolean shows(Show show, InstalledScript s) {
        return switch (show) {
            case ALL -> true;
            case RUNNING -> s.isActive();
            case NOT_RUNNING -> !s.isActive();
            case PROBLEMS -> s.hasProblem();
        };
    }

    private boolean isFrom(InstalledScript s) {
        return switch (source) {
            case ANY -> true;
            case STORE -> s.provenance().source() == ScriptSource.STORE;
            case LOCAL -> s.provenance().source() == ScriptSource.LOCAL;
        };
    }

    private static boolean contains(InstalledScript s, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return Stream.of(s.name(), s.identity().categoryLabel(), s.provenance().jarName().orElse(""))
                .anyMatch(field -> field.toLowerCase(Locale.ROOT).contains(needle));
    }
}
