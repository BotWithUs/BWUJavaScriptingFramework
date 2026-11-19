package com.botwithus.bot.api.gameui;

/**
 * The agent, or the host on its behalf, refused a plugin-UI call.
 *
 * <p>{@link #getMessage()} is the refusal exactly as it was written, for example
 * {@code ui: unknown handle (destroyed, invalidated or not yours)} or
 * {@code ui type not supported yet: button}, so a script can match on the agent's
 * own wording. A refusal means this request was wrong and the next one may be
 * fine. A dead pipe is a different failure and is not reported as this type.</p>
 */
public class UiRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String method;

    public UiRefusedException(String method, String message) {
        super(message);
        this.method = method;
    }

    public UiRefusedException(String method, String message, Throwable cause) {
        super(message, cause);
        this.method = method;
    }

    /** The {@code ui.*} method that was refused. */
    public String method() {
        return method;
    }
}
