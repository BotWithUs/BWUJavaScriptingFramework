package com.botwithus.bot.cli.settings;

import com.botwithus.bot.core.rpc.ReconnectPolicy;

/**
 * Reads the {@code reconnect.*} settings as a {@link ReconnectPolicy}.
 *
 * <p>The store validates each key on its own, so it accepts combinations the
 * policy refuses, such as a longest wait shorter than the first. Those are
 * clamped rather than refused ({@link ReconnectPolicy#clamped}): the policy is
 * read on the thread that is trying to get a client back, which is the wrong
 * place to fail over a settings typo.</p>
 */
public final class ReconnectPolicySettings {

    private ReconnectPolicySettings() {
    }

    /** The policy the settings describe now. {@code reconnect.maxAttempts = 0} is unlimited. */
    public static ReconnectPolicy read(HostSettings settings) {
        return ReconnectPolicy.clamped(
                settings.get(SettingKeys.RECONNECT_MAX_ATTEMPTS),
                settings.get(SettingKeys.RECONNECT_INITIAL_DELAY_MS),
                settings.get(SettingKeys.RECONNECT_BACKOFF),
                settings.get(SettingKeys.RECONNECT_MAX_DELAY_MS));
    }
}
