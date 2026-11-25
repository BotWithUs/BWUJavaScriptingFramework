package com.botwithus.bot.api.input;

/**
 * What "the render view holds the keyboard" means to the client, as set with
 * {@link com.botwithus.bot.api.domain.KeyboardAPI#setFocusMode(FocusMode)}.
 */
public enum FocusMode {
    /**
     * The client sees the operating system's real focus answer. The default. A
     * key sent while the client is in the background is refused as
     * {@link KeyResult#NOT_FOCUSED}.
     */
    REAL,
    /**
     * The agent makes the client's own focus check answer "the render view"
     * where the real answer is "no window", so keys reach a background client.
     * A real focus on another window of the client is never overridden. Only the
     * client is affected: the real foreground window and every other process are
     * untouched. While armed, a background client runs at its foreground frame
     * rate.
     */
    AGENT
}
