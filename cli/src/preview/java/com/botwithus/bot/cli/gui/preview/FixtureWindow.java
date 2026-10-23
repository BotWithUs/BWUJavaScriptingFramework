package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.gui.window.ScreenPoint;
import com.botwithus.bot.cli.gui.window.WindowControls;
import com.botwithus.bot.cli.gui.window.WindowRect;

/**
 * DEV ONLY. A window that stays where it is: the preview draws the frameless
 * chrome over it without touching the real window. Only maximised or not
 * changes, so a scenario can show the restore button.
 */
final class FixtureWindow implements WindowControls {

    private final WindowRect bounds;
    private boolean isMaximised;

    FixtureWindow(WindowRect bounds) {
        this.bounds = bounds;
    }

    @Override
    public WindowRect bounds() {
        return bounds;
    }

    @Override
    public void setBounds(WindowRect bounds) {
        // Fixed: the preview never moves the window.
    }

    @Override
    public ScreenPoint cursor() {
        return new ScreenPoint(bounds.x(), bounds.y());
    }

    @Override
    public boolean isMaximised() {
        return isMaximised;
    }

    @Override
    public boolean isIconified() {
        return false;
    }

    @Override
    public void minimise() {
        // Never minimised in a capture.
    }

    @Override
    public void maximise() {
        isMaximised = true;
    }

    @Override
    public void restore() {
        isMaximised = false;
    }

    @Override
    public void close() {
        // The preview closes itself when the last scenario is captured.
    }
}
