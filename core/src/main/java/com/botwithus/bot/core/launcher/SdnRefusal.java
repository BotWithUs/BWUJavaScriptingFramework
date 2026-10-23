package com.botwithus.bot.core.launcher;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Why the launcher answered {@code sdn_not_delivered}, as far as it said.
 *
 * <p>A launcher that predates typed reasons says nothing, which reads as an
 * empty {@link #reason()}: the host then shows its generic text. The launcher's
 * {@code message} is never kept here, because it is for logs and never shown.
 *
 * @param reason     one of the launcher's reason codes; the predicate
 * @param scriptId   the script the refusal is about, for a per-script reason
 * @param httpStatus the status the download got, for logs only
 * @param cfRay      a reference to quote to support, shown as sent
 * @param failed     every requested script that failed, when nothing was delivered
 */
public record SdnRefusal(Optional<String> reason, Optional<String> scriptId, OptionalLong httpStatus,
                         Optional<String> cfRay, List<SdnScriptFailure> failed) {

    public SdnRefusal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(scriptId, "scriptId");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(cfRay, "cfRay");
        failed = List.copyOf(failed);
    }

    /** A refusal from a launcher that gave no reason. */
    public static SdnRefusal unexplained() {
        return new SdnRefusal(Optional.empty(), Optional.empty(), OptionalLong.empty(), Optional.empty(), List.of());
    }
}
