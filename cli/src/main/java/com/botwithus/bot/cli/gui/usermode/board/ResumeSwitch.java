package com.botwithus.bot.cli.gui.usermode.board;

import java.util.Objects;

/**
 * A card's "Resume after restart" switch: whether the scripts an account was
 * running start again on their own when its game client comes back.
 */
public sealed interface ResumeSwitch {

    /** The client has an account to remember scripts for. */
    record Available(boolean isOn) implements ResumeSwitch { }

    /**
     * The switch cannot apply to this client.
     *
     * @param reason what the card says instead of the switch
     */
    record Unavailable(String reason) implements ResumeSwitch {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Whether the account's scripts will start on their own when it is back. */
    default boolean isOn() {
        return switch (this) {
            case Available available -> available.isOn();
            case Unavailable _ -> false;
        };
    }
}
