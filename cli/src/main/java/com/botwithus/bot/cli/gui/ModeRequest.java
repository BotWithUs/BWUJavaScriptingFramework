package com.botwithus.bot.cli.gui;

/**
 * A mode switch asked for while a frame is being drawn, applied once the frame
 * is done.
 *
 * <p>"View log" and a toast's "Details" run inside {@link Shell#render}, and the
 * mode that call returns is written back afterwards. Setting the mode directly
 * from inside the frame was therefore overwritten by the frame's own result, and
 * the switch was lost. Requests go here instead and win over that result.</p>
 */
final class ModeRequest {

    private AppMode pending;

    /** Asks for {@code mode} to take effect when the current frame ends. */
    void request(AppMode mode) {
        pending = mode;
    }

    /**
     * The mode for the next frame: the one requested during this frame if any,
     * otherwise {@code rendered}. Consumes the request.
     */
    AppMode resolve(AppMode rendered) {
        AppMode next = pending != null ? pending : rendered;
        pending = null;
        return next;
    }
}
