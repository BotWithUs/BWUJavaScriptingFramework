package com.botwithus.bot.api.gameui;

import java.util.Arrays;
import java.util.List;

/** Properties of a {@link ListBox}. */
public final class ListBoxProps extends UiProps<ListBoxProps> {

    public ListBoxProps() {
    }

    @Override
    ListBoxProps self() {
        return this;
    }

    /**
     * Replaces every entry: at most {@link UiLimits#MAX_ENTRIES}, unique ids, each
     * text at most {@link UiLimits#MAX_ENTRY_TEXT_BYTES} bytes.
     */
    public ListBoxProps entries(List<UiEntry> entries) {
        return putEntries(entries);
    }

    /**
     * Exactly these entries end up selected; none for an empty list. Raises no
     * {@link UiHook#ON_SELECTION_CHANGED}. A list whose limit is {@code L > 1} holds at
     * most {@code L - 1} (measured on 951-4), and the agent refuses more.
     */
    public ListBoxProps selected(List<Integer> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("ids");
        }
        return put("selected", List.copyOf(ids));
    }

    /** {@link #selected(List)} for a few ids. */
    public ListBoxProps selected(int... ids) {
        if (ids == null) {
            throw new IllegalArgumentException("ids");
        }
        return put("selected", Arrays.stream(ids).boxed().toList());
    }

    /**
     * How many entries can be selected, {@link UiLimits#MIN_SELECTION_LIMIT}..
     * {@link UiLimits#MAX_SELECTION_LIMIT}; {@code 1} is single select.
     */
    public ListBoxProps selectionLimit(int limit) {
        return put("selection_limit", limit);
    }
}
