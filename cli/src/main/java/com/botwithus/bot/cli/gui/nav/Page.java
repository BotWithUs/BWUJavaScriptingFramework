package com.botwithus.bot.cli.gui.nav;

import java.util.Optional;

/**
 * One screen of the app: the body drawn next to the Advanced sidebar, plus
 * what its sidebar item shows. Where the item sits, its label and its icon come
 * from {@link #id()}, so replacing a page never moves it in the navigation.
 *
 * <p>{@link #render()} draws into a region the shell has already opened, with no
 * window padding; a page that wants padding opens its own child. It runs on the
 * render thread every frame the page is visible, and so do {@link #secondLine()}
 * and {@link #badge()} while the sidebar is shown: keep all three cheap.</p>
 */
public interface Page {

    PageId id();

    /** Draws the page body into the current region. */
    void render();

    default NavSection section() {
        return id().section();
    }

    default String icon() {
        return id().icon();
    }

    /** The line under the label: a folder path for local pages, the sign-in state for the store. */
    default Optional<SecondLine> secondLine() {
        return Optional.empty();
    }

    /** A count at the right end of the item, or empty for none. */
    default Optional<NavBadge> badge() {
        return Optional.empty();
    }
}
