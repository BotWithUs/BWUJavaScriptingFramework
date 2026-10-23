package com.botwithus.bot.cli.gui.nav;

import java.nio.file.Path;

/**
 * The small line under a sidebar label. There are exactly two kinds, and they
 * are drawn differently on purpose: a local page names the folder it reads from
 * (in mono), and the store names your sign-in state (with a live dot). One look
 * tells local from online.
 */
public sealed interface SecondLine {

    /** The text to draw. */
    String text();

    /** A folder on this PC, shown as a short path with a trailing slash. */
    record FolderPath(String text) implements SecondLine {

        private static final String HOME = "~";
        private static final char SLASH = '/';

        /**
         * The short form of {@code dir}: relative to the working directory when it
         * is inside it ({@code scripts/}), else relative to the user's home
         * ({@code ~/.botwithus/scripts/}), else the full path. Always forward
         * slashes, always ending in one.
         */
        public static FolderPath of(Path dir, Path workingDir, Path home) {
            Path abs = dir.toAbsolutePath().normalize();
            Path cwd = workingDir.toAbsolutePath().normalize();
            Path user = home.toAbsolutePath().normalize();
            String shown;
            if (abs.startsWith(cwd) && !abs.equals(cwd)) {
                shown = slashes(cwd.relativize(abs));
            } else if (abs.startsWith(user)) {
                shown = abs.equals(user) ? HOME : HOME + SLASH + slashes(user.relativize(abs));
            } else {
                shown = slashes(abs);
            }
            return new FolderPath(shown.endsWith(String.valueOf(SLASH)) ? shown : shown + SLASH);
        }

        private static String slashes(Path p) {
            return p.toString().replace('\\', SLASH);
        }
    }

    /**
     * The store account's state, such as "Signed in · 3 subscribed".
     *
     * @param isLive draws the dot in the accent colour; otherwise it is dimmed
     */
    record AccountStatus(String text, boolean isLive) implements SecondLine {}
}
