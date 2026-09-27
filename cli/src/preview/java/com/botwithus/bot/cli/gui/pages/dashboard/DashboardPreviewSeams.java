package com.botwithus.bot.cli.gui.pages.dashboard;

/**
 * The dev preview's reach into the Dashboard's package-private state: holding
 * the page scrolled, which a real user does with the wheel. Lives in the
 * preview source set only; nothing here ships.
 */
public final class DashboardPreviewSeams {

    private DashboardPreviewSeams() {}

    /** Holds the page body {@code fraction} of the way down (0 top, 1 bottom). */
    public static void holdScroll(DashboardPage page, float fraction) {
        page.holdScroll(fraction);
    }
}
