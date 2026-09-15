package com.botwithus.bot.api.gameval;

/**
 * A gameval name did not resolve. Thrown by
 * {@link GamevalIndex#require(GamevalType, String)} — the fail-fast counterpart
 * to {@link GamevalIndex#id(GamevalType, String)}, for the common script case
 * where a missing name means the script cannot proceed anyway.
 *
 * <p>Also thrown when the index itself is unavailable (no {@code gameval.sqlite}
 * on disk), which is why the message names both possibilities.</p>
 */
public final class GamevalNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final GamevalType type;
    private final String gameval;

    public GamevalNotFoundException(GamevalType type, String gameval, boolean indexAvailable) {
        super(indexAvailable
                ? "no " + type.wire() + " named '" + gameval + "' in the gameval index"
                : "gameval index unavailable; cannot resolve " + type.wire()
                        + " '" + gameval + "'");
        this.type = type;
        this.gameval = gameval;
    }

    /** Namespace the lookup was made in. */
    public GamevalType type() {
        return type;
    }

    /** The name that failed to resolve. */
    public String gameval() {
        return gameval;
    }
}
