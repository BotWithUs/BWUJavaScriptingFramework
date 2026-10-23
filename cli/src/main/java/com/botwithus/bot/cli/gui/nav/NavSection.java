package com.botwithus.bot.cli.gui.nav;

import com.botwithus.bot.cli.gui.Icons;

/**
 * The groups of the Advanced sidebar, in the order they are drawn.
 *
 * <p>Local things and online things are different systems, so they sit in
 * different places: the two top sections stack from the top, while the Online
 * block and the footer are pinned to the bottom, the Online block drawn in its
 * own outlined box.</p>
 */
public enum NavSection {

    /** Everything about the connected game clients. */
    CLIENTS("Clients", ""),
    /** Scripts read from folders on this PC; each item's second line is its folder. */
    ON_THIS_PC("On this PC", Icons.HARD_DRIVE),
    /** The Script Store; its second line is the sign-in state, never a path. */
    ONLINE("Online", Icons.CLOUD),
    /** Settings, pinned last with no heading of its own. */
    FOOTER("", "");

    private final String heading;
    private final String icon;

    NavSection(String heading, String icon) {
        this.heading = heading;
        this.icon = icon;
    }

    /** The section heading, or empty when the section is drawn without one. */
    public String heading() {
        return heading;
    }

    /** The Font Awesome glyph drawn before the heading, or empty for none. */
    public String icon() {
        return icon;
    }

    public boolean hasHeading() {
        return !heading.isEmpty();
    }

    /** Drawn under the flexible gap, anchored to the bottom of the sidebar. */
    public boolean isPinnedToBottom() {
        return this == ONLINE || this == FOOTER;
    }

    /** Drawn inside an outlined block so it reads as a separate system. */
    public boolean isBoxed() {
        return this == ONLINE;
    }
}
