package com.botwithus.bot.api.script;

/**
 * Options for {@link ClientLauncher#launch(String, LaunchOptions)}.
 *
 * @param characterIndex for a Steam account, which character to play;
 *                       {@link #NO_CHARACTER} otherwise
 */
public record LaunchOptions(int characterIndex) {

    /** No particular character: the account's default. */
    public static final int NO_CHARACTER = -1;

    public LaunchOptions {
        if (characterIndex < NO_CHARACTER) {
            throw new IllegalArgumentException("characterIndex must be " + NO_CHARACTER
                    + " or a character index, was " + characterIndex);
        }
    }

    /** @return options with no particular character */
    public static LaunchOptions defaults() {
        return new LaunchOptions(NO_CHARACTER);
    }
}
