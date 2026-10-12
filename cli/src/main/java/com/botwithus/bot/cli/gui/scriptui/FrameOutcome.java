package com.botwithus.bot.cli.gui.scriptui;

/** How one sandboxed frame of a script's UI ended. */
sealed interface FrameOutcome {

    /**
     * The frame completed.
     *
     * @param draw          what it drew, ready to composite into the host
     * @param mouseCursor   the cursor the UI asked for ({@code ImGuiMouseCursor})
     * @param wantsKeyboard the UI has keyboard focus, e.g. a text field is active
     */
    record Drawn(DrawCopy draw, int mouseCursor, boolean wantsKeyboard) implements FrameOutcome {
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
