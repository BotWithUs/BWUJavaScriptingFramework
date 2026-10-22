package com.botwithus.bot.cli.gui.scriptui;

/** How one sandboxed frame of a script's UI ended. */
sealed interface FrameOutcome {

    /**
     * The frame completed.
     *
     * @param draw          what it drew, ready to composite into the host
     * @param mouseCursor   the cursor the UI asked for ({@code ImGuiMouseCursor})
     * @param wantsKeyboard a text field of the UI's is taking typing, or one of its items is active
     * @param isBackgroundPress the left button went down this frame over the UI's own
     *                      windows but on none of its items: a press the host may
     *                      take for itself, to move a frameless window
     * @param drawsOwnChrome what the UI's {@code drawsOwnChrome()} answered this frame,
     *                      false if it threw
     */
    record Drawn(DrawCopy draw, int mouseCursor, boolean wantsKeyboard, boolean isBackgroundPress,
                 boolean drawsOwnChrome) implements FrameOutcome {
    }

    /** {@code render()} threw. The frame was left unfinished; the context is discarded. */
    record Threw(RuntimeException error) implements FrameOutcome {
    }

    /** Dear ImGui asserted. The render thread is parked for good; context and thread are abandoned. */
    record Asserted(ImGuiAssertion assertion) implements FrameOutcome {
    }

    /** {@code render()} threw an {@link Error}; rethrown on the UI thread, as before sandboxing. */
    record Crashed(Error error) implements FrameOutcome {
    }
}
