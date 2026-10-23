package com.botwithus.bot.cli.gui.window;

/**
 * What the frameless chrome asks of the native window. Implemented over GLFW by
 * {@link GlfwWindow}; the dev preview supplies a fixed stand-in.
 */
public interface WindowControls {

    /** The window's outer bounds in screen pixels, as it is now (maximised or not). */
    WindowRect bounds();

    /** Moves and resizes the window to {@code bounds}. */
    void setBounds(WindowRect bounds);

    /** The mouse cursor in screen pixels. */
    ScreenPoint cursor();

    boolean isMaximised();

    boolean isIconified();

    void minimise();

    void maximise();

    /** Brings a maximised window back to its normal bounds. */
    void restore();

    /** Asks the window to close, as the native title bar's close button does. */
    void close();
}
