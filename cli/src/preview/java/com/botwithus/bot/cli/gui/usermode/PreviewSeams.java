package com.botwithus.bot.cli.gui.usermode;

/**
 * The dev preview's reach into package-private Normal-mode state: selecting a
 * view segment, highlighting a picker row and searching the picker, which a
 * real user does by clicking and typing. Lives in the preview source set only; nothing here ships.
 */
public final class PreviewSeams {

    private PreviewSeams() {}

    public static void showNeedsAttention(UserModeRenderer page) {
        page.showView(ClientFilter.View.NEEDS_ATTENTION);
    }

    public static void showRunning(UserModeRenderer page) {
        page.showView(ClientFilter.View.RUNNING);
    }

    /** Highlights row {@code index} of the open picker, as ↑↓ would. */
    public static void highlightPickerRow(UserModeRenderer page, int index) {
        page.highlightPickerRow(index);
    }

    /** Types {@code text} into the picker's search box; call it on the frame the picker opens. */
    public static void searchPicker(UserModeRenderer page, String text) {
        page.searchPicker(text);
    }
}
