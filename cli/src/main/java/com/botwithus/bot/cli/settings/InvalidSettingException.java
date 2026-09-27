package com.botwithus.bot.cli.settings;

import java.io.Serial;

/**
 * A value, or a key name, that {@link HostSettings} refuses. The message is
 * written for the user: it names the key and the rule, e.g.
 * {@code "defaultTimeout must be a whole number from 500 to 300000, got 'abc'"}.
 */
public final class InvalidSettingException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String settingName;

    InvalidSettingException(String settingName, String reason, Throwable cause) {
        super(settingName + " " + reason, cause);
        this.settingName = settingName;
    }

    InvalidSettingException(String settingName, String reason) {
        super(settingName + " " + reason);
        this.settingName = settingName;
    }

    /** The key the refused value was for, or the unknown name itself. */
    public String settingName() {
        return settingName;
    }
}
