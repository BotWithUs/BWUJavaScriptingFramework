package com.botwithus.bot.cli.gui;

/** No chrome of our own: Windows' title bar and borders do the job. */
public record NativeChrome() implements WindowChrome {

    @Override
    public float controlsWidth() {
        return 0f;
    }

    @Override
    public void renderControls(float right, float y, float height) {
        // The native title bar has the window buttons.
    }

    @Override
    public void renderDragRegion(float x, float y, float width, float height) {
        // The native title bar moves the window.
    }

    @Override
    public void renderEdges() {
        // The native border resizes the window.
    }
}
