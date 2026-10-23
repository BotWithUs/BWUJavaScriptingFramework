package com.botwithus.bot.cli.gui.nav;

/**
 * The short count at the right end of a sidebar item, such as the number of
 * clients.
 *
 * @param isWarning draws it in the warning colour: the count is of things that need a look
 */
public record NavBadge(String text, boolean isWarning) {

    public static NavBadge count(int n) {
        return new NavBadge(Integer.toString(n), false);
    }

    public static NavBadge problems(int n) {
        return new NavBadge(Integer.toString(n), true);
    }
}
