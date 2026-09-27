package com.botwithus.bot.cli.gui.usermode;

/**
 * The dev preview's reach into package-private Normal-mode state: selecting a
 * view segment and highlighting a picker row, which a real user does by
 * clicking. Lives in the preview source set only; nothing here ships.
 */
public final class PreviewSeams {

    private PreviewSeams() {}

    public static void showNeedsAttention(UserModeRenderer page) {
        page.showView(ClientFilter.View.NEEDS_ATTENTION);
    }

    /** Highlights row {@code index} of the open picker, as ↑↓ would. */
    public static void highlightPickerRow(UserModeRenderer page, int index) {
        page.highlightPickerRow(index);
    }
}
