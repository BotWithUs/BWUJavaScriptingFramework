package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.internal.ImGui;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps a script's UI so a test can see, from inside the sandbox, how often it
 * rendered without throwing and what Dear ImGui thought of the pointer at the end of its last
 * frame. Reading ids draws nothing, so the wrapped UI draws what the UI does.
 */
final class SandboxProbe {

    /**
     * The sandbox's view after one {@code render()}.
     *
     * @param hoveredId the item under the pointer this frame, 0 for none
     * @param activeId  the item being pressed, 0 for none
     * @param watchedId the id of the watched label in the UI's root window
     * @param mouseX    the pointer in sandbox coordinates
     * @param mouseY    the pointer in sandbox coordinates
     */
    record Seen(int hoveredId, int activeId, int watchedId, float mouseX, float mouseY) {
    }

    private final AtomicInteger completedRenders = new AtomicInteger();
    private final AtomicReference<Seen> last = new AtomicReference<>();

    /** {@code ui}, counted, and followed by a look at the pointer and at {@code watchedLabel}'s id. */
    ScriptUI around(ScriptUI ui, String watchedLabel) {
        return () -> {
            ui.render();
            completedRenders.incrementAndGet();
            last.set(new Seen(ImGui.getHoveredID(), ImGui.getActiveID(), ImGui.getID(watchedLabel),
                    ImGui.getIO().getMousePosX(), ImGui.getIO().getMousePosY()));
        };
    }

    /** Renders that returned normally: one that threw is not counted. */
    int completedRenders() {
        return completedRenders.get();
    }

    Optional<Seen> last() {
        return Optional.ofNullable(last.get());
    }
}
