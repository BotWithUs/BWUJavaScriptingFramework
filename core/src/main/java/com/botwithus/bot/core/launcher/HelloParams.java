package com.botwithus.bot.core.launcher;

import java.util.Objects;
import java.util.Optional;

/**
 * What this host says about itself in {@code hello}.
 *
 * @param clientVersion the host's version, for the service's logs
 * @param hostPid       this process's id; the service refuses any other
 * @param label         a display name for the launcher's banner
 */
public record HelloParams(String clientVersion, long hostPid, Optional<String> label) {

    public HelloParams {
        Objects.requireNonNull(clientVersion, "clientVersion");
        Objects.requireNonNull(label, "label");
        label = label.map(HelloParams::truncate);
    }

    /** The service keeps at most 64 code points of a label; send no more than it keeps. */
    private static String truncate(String label) {
        int limit = LauncherProtocol.MAX_HOST_LABEL_CODE_POINTS;
        if (label.codePointCount(0, label.length()) <= limit) {
            return label;
        }
        return label.substring(0, label.offsetByCodePoints(0, limit));
    }
}
