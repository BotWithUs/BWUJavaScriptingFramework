package com.botwithus.bot.cli;

/**
 * Where a connected client is in the game: at the login screen, in the lobby, or
 * in a world.
 *
 * <p>The agent reports this as the client's raw game-state code, both in the
 * {@code get_login_state} and {@code get_account_info} replies and in
 * {@code LoginStateChangeEvent}. Only the three settled codes have a meaning here;
 * every other code, including {@code 0} while the agent cannot resolve the client
 * yet, is {@link #UNKNOWN}.</p>
 */
public enum GameState {

    LOGIN_SCREEN(10),
    LOBBY(20),
    IN_GAME(30),
    UNKNOWN(0);

    private final int wireCode;

    GameState(int wireCode) {
        this.wireCode = wireCode;
    }

    /** The agent's code for this state; {@code 0} for {@link #UNKNOWN}. */
    public int wireCode() {
        return wireCode;
    }

    /** Maps an agent game-state code, answering {@link #UNKNOWN} for any unsettled code. */
    public static GameState fromWire(int code) {
        for (GameState state : values()) {
            if (state != UNKNOWN && state.wireCode == code) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
