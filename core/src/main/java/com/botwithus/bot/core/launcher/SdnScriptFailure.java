package com.botwithus.bot.core.launcher;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One requested Store script the launcher could not deliver, from the
 * {@code failed} list of a partial delivery or of a refusal.
 *
 * @param scriptId   the catalogue id that failed
 * @param reason     why, as one of the launcher's reason codes; the predicate
 * @param httpStatus the status the download got, for logs only
 * @param cfRay      a reference to quote to support, shown as sent
 */
public record SdnScriptFailure(String scriptId, String reason, OptionalLong httpStatus, Optional<String> cfRay) {

    public SdnScriptFailure {
        Objects.requireNonNull(scriptId, "scriptId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(cfRay, "cfRay");
    }
}
