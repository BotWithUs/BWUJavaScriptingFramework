package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which scripts' own UIs are popped out of the drawer into a window of their
 * own. A script's UI is usually laid out for more room than the drawer has, so
 * "Settings" pops it out; the drawer's Script UI tab can bring it back in, and
 * pop it out again. Each window belongs to one script runner, is drawn every
 * frame while open whatever the drawer shows, and closes once its runner is
 * disposed or its client gone.
 *
 * <p>The session also remembers a script that was popped out and not brought
 * back: when its window closed only because the runner went away (a reload, a
 * reconnect), opening its Script UI tab again pops it straight back out. Closing
 * the window with its X counts as bringing it back. The memory lives as long as
 * the host process; nothing is written to disk.</p>
 *
 * <p>Render thread only.</p>
 */
final class ScriptUiPopouts {

    private static final String ID_PREFIX = "###script-ui-popout-";

    private final Set<InspectorSubject> open = new LinkedHashSet<>();
    private final Set<InspectorSubject> remembered = new HashSet<>();

    /**
     * The window a subject's UI pops out into. A management script has one
     * runner and one UI whichever target its settings form is on, so the
     * target is dropped: one window per runner, not one per picker entry.
     */
    static InspectorSubject windowOf(InspectorSubject subject) {
        return switch (subject) {
            case ClientScript script -> script;
            case ManagementScript script -> script.withSettingsFor(Optional.empty());
        };
    }

    /**
     * The ImGui id suffix for a subject's window: stable across frames and
     * title changes, and distinct for the same script on two clients.
     */
    static String imguiIdOf(InspectorSubject subject) {
        return switch (windowOf(subject)) {
            case ClientScript script -> ID_PREFIX + "client-" + script.clientId() + "-" + script.scriptName();
            case ManagementScript script -> ID_PREFIX + "management-" + script.scriptName();
        };
    }

    /** Opens the subject's UI in its own window, and remembers that for the session. */
    void popOut(InspectorSubject subject) {
        InspectorSubject window = windowOf(subject);
        open.add(window);
        remembered.add(window);
    }

    /** The user closed the window or brought the UI back: draw it in the drawer again, and forget it. */
    void bringBack(InspectorSubject subject) {
        InspectorSubject window = windowOf(subject);
        open.remove(window);
        remembered.remove(window);
    }

    boolean isPoppedOut(InspectorSubject subject) {
        return open.contains(windowOf(subject));
    }

    /**
     * Called when the drawer is about to show the subject's Script UI tab: a
     * UI the user left popped out, whose window closed because its runner went
     * away, pops back out rather than drawing in the drawer.
     */
    void reopenIfRemembered(InspectorSubject subject) {
        InspectorSubject window = windowOf(subject);
        if (remembered.contains(window)) {
            open.add(window);
        }
    }

    /**
     * The open windows' scripts as they are now, in the order they were popped
     * out. A window whose runner is disposed or gone, or whose script no longer
     * draws a UI, is closed; the session still remembers it.
     */
    List<InspectorTarget> resolve(InspectorSource source) {
        List<InspectorTarget> shown = new ArrayList<>(open.size());
        for (InspectorSubject window : List.copyOf(open)) {
            Optional<InspectorTarget> target = source.resolve(window);
            if (target.isPresent() && target.get().hasCustomUi() && !target.get().isGone().getAsBoolean()) {
                shown.add(target.get());
            } else {
                open.remove(window);
            }
        }
        return shown;
    }
}
