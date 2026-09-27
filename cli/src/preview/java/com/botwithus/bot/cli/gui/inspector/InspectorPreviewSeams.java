package com.botwithus.bot.cli.gui.inspector;

import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

/**
 * The dev preview's reach into the open inspector's form, to stage the edits a
 * real user would type, so the unsaved state can be captured. Lives in the
 * preview source set only; nothing here ships.
 */
public final class InspectorPreviewSeams {

    private InspectorPreviewSeams() {}

    /**
     * Sets an int-backed field (int, item id, or choice index). Returns false
     * until the inspector has drawn its first frame.
     */
    public static boolean stageEdit(InspectorDock dock, String key, int value) {
        ConfigEdits edits = dock.edits();
        ImInt field = edits != null ? edits.intOf(key) : null;
        if (field == null) {
            return false;
        }
        field.set(value);
        return true;
    }

    /** Sets a text field. Returns false until the inspector has drawn its first frame. */
    public static boolean stageEdit(InspectorDock dock, String key, String value) {
        ConfigEdits edits = dock.edits();
        ImString field = edits != null ? edits.stringOf(key) : null;
        if (field == null) {
            return false;
        }
        field.set(value);
        return true;
    }

    /** Sets a toggle. Returns false until the inspector has drawn its first frame. */
    public static boolean stageEdit(InspectorDock dock, String key, boolean value) {
        ConfigEdits edits = dock.edits();
        ImBoolean field = edits != null ? edits.boolOf(key) : null;
        if (field == null) {
            return false;
        }
        field.set(value);
        return true;
    }
}
