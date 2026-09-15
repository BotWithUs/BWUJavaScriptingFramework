package com.botwithus.bot.api.dialog;

import com.botwithus.bot.api.component.ComponentNode;

/**
 * One selectable line of the multi-choice dialog (interface
 * {@code DIALOG_OPTIONS}).
 *
 * <p>The {@link #text() label} is read from a {@code TEXT} leaf deep in the
 * row's subtree, but {@link #row()} is the {@code LAYER} container the game
 * keys the selection on — the component the server expects. Selecting an option
 * targets {@link #row()} via the {@code DIALOGUE} action, never the text leaf.
 * See {@link Dialog} for how the correlation is derived.</p>
 *
 * @param index 1-based option number as shown in-game (the {@code "N."} cell, or
 *              the row's position when no number cell is present)
 * @param text  the visible option label, color tags intact
 * @param row   the option-row component the selection targets
 */
public record DialogOption(int index, String text, ComponentNode row) {

    /** The component id the server expects for this option (the row, not the label). */
    public int componentId() {
        return row.componentId();
    }

    /** The interface the row lives on. */
    public int interfaceId() {
        return row.interfaceId();
    }

    /**
     * Queue the selection of this option. Targets {@link #row()} via the
     * {@code DIALOGUE} action (type 30) — see
     * {@link ComponentNode#selectDialogOption()} for why a plain component click
     * doesn't advance the dialogue.
     */
    public void select() {
        row.selectDialogOption();
    }
}
