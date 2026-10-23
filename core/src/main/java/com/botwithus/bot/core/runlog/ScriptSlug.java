package com.botwithus.bot.core.runlog;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The directory name a script's run logs live under: the script name in lower
 * case, every character outside {@code [a-z0-9-]} turned into {@code -}, runs of
 * {@code -} collapsed, leading and trailing {@code -} stripped, at most
 * {@value #MAX_LENGTH} characters. {@code "[BWU] Agility!"} becomes
 * {@code "bwu-agility"}.
 *
 * <p>Every host derives the same slug from the same name, so a report bundle can
 * find a script's logs whichever host wrote them.</p>
 */
public final class ScriptSlug {

    /** Longest slug the spec allows. */
    public static final int MAX_LENGTH = 48;

    /** Used when a name has no character that survives, so a run still gets a directory. */
    static final String FALLBACK = "script";

    private static final Pattern DISALLOWED = Pattern.compile("[^a-z0-9-]");
    private static final Pattern DASH_RUN = Pattern.compile("-{2,}");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");

    private ScriptSlug() {
    }

    /** The slug for {@code scriptName}; never empty. */
    public static String of(String scriptName) {
        String name = scriptName == null ? "" : scriptName;
        String slug = DISALLOWED.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = DASH_RUN.matcher(slug).replaceAll("-");
        slug = EDGE_DASHES.matcher(slug).replaceAll("");
        if (slug.length() > MAX_LENGTH) {
            // Cutting can expose a dash at the new end; strip it again.
            slug = EDGE_DASHES.matcher(slug.substring(0, MAX_LENGTH)).replaceAll("");
        }
        return slug.isEmpty() ? FALLBACK : slug;
    }
}
