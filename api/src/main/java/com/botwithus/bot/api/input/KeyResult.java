package com.botwithus.bot.api.input;

/**
 * What became of one key press sent with
 * {@link com.botwithus.bot.api.domain.KeyboardAPI#sendKey(int, int)}.
 *
 * <p>Only {@link #SENT} means the client received the key. The three focus
 * results are refusals the agent makes before delivery or, for
 * {@link #FOCUS_LOST}, during a hold.</p>
 */
public enum KeyResult {
    /** The key down and key up were posted to the client's render view. */
    SENT,
    /** The agent accepted the call but could not post the key. Nothing was delivered. */
    NOT_SENT,
    /**
     * The client's render view does not hold the keyboard, so the client would
     * drop the key and the agent did not send it. Arm {@link FocusMode#AGENT}
     * or focus the client.
     */
    NOT_FOCUSED,
    /** The agent has no render view window to send to (the client is not up yet, or is closing). */
    NO_WINDOW,
    /** Focus left the render view during a hold, and the key was released early. */
    FOCUS_LOST
}
