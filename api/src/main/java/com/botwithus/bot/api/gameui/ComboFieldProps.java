package com.botwithus.bot.api.gameui;

import java.util.List;

/** Properties of a {@link ComboField}. */
public final class ComboFieldProps extends UiProps<ComboFieldProps> {

    /** The {@code selected} id that means none. */
    public static final int NO_SELECTION = -1;

    public ComboFieldProps() {
    }

    @Override
    ComboFieldProps self() {
        return this;
    }

    /**
     * Replaces every entry: at most {@link UiLimits#MAX_ENTRIES}, unique ids, each
     * text at most {@link UiLimits#MAX_ENTRY_TEXT_BYTES} bytes.
     */
    public ComboFieldProps entries(List<UiEntry> entries) {
        return putEntries(entries);
    }

    /** Selects the entry with this id. Raises no {@link UiHook#ON_SELECTION_CHANGED}. */
    public ComboFieldProps selected(int id) {
        return put("selected", id);
    }

    /** Selects nothing. */
    public ComboFieldProps noSelection() {
        return put("selected", NO_SELECTION);
    }
}
