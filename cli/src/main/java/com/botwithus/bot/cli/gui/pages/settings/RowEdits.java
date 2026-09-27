package com.botwithus.bot.cli.gui.pages.settings;

import imgui.type.ImString;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The text boxes' side of instant save. Each box keeps its own buffer so typing
 * is not overwritten by the stored value every frame; the value is written when
 * the box is left or Enter is pressed. A refused value stays in the box with the
 * reason under it until the next edit is taken, so the user can fix it rather
 * than retype it. Render thread only.
 */
final class RowEdits {

    /** Room for a pipe prefix, a number or a hand-written key value. */
    private static final int BUFFER_BYTES = 256;

    private final Map<String, ImString> buffers = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Set<String> editing = new HashSet<>();

    /**
     * The buffer for box {@code id}, showing {@code current} unless the user is
     * typing in it or it holds a refused value.
     */
    ImString buffer(String id, String current) {
        ImString buffer = buffers.computeIfAbsent(id, k -> new ImString(current, BUFFER_BYTES));
        if (!editing.contains(id) && !errors.containsKey(id) && !buffer.get().equals(current)) {
            buffer.set(current);
        }
        return buffer;
    }

    /** Records whether box {@code id} has keyboard focus, as drawn this frame. */
    void track(String id, boolean isActive) {
        if (isActive) {
            editing.add(id);
        } else {
            editing.remove(id);
        }
    }

    /** Records what became of the value box {@code id} committed. */
    void accept(String id, EditResult result) {
        switch (result) {
            case EditResult.Applied _ -> errors.remove(id);
            case EditResult.Refused refused -> errors.put(id, refused.message());
        }
    }

    /** Why the last value committed from box {@code id} was refused, if it was. */
    Optional<String> error(String id) {
        return Optional.ofNullable(errors.get(id));
    }
}
