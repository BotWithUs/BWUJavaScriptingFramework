package com.botwithus.bot.core.sdn;

import java.util.Optional;

/**
 * What the user is told when the launcher could not deliver a Store script, keyed
 * by the launcher's reason code. The launcher owns the codes and this host owns the
 * words: the launcher's own message is never shown.
 *
 * <p>Only {@link #NOT_ENTITLED} speaks of a subscription. A refusal for any other
 * reason must not send the user to pay for something they may already have.
 */
public final class SdnReasonText {

    /** The launcher's reason codes this host has words for. */
    public static final String NOT_OWNED = "not_owned";
    public static final String NOT_SIGNED_IN = "not_signed_in";
    public static final String NO_LINK = "no_link";
    public static final String HEARTBEAT_REFUSED = "heartbeat_refused";
    public static final String SESSION_REJECTED = "session_rejected";
    public static final String NOT_ENTITLED = "not_entitled";
    public static final String NO_BUILD = "no_build";
    public static final String UNSUPPORTED = "unsupported";
    public static final String CLOUDFLARE_CHALLENGE = "cloudflare_challenge";
    public static final String SERVER_ERROR = "server_error";
    public static final String NETWORK = "network";
    public static final String TIMEOUT = "timeout";
    /** Written to the file route's error file: the host is not on the launcher's pipe. */
    public static final String HOST_NOT_ON_PIPE = "host_not_on_pipe";

    /** No launcher connection at all. */
    public static final String NOT_CONNECTED_TEXT =
            "This host isn't connected to the launcher. Restart the host from the BotWithUs launcher.";
    /** The launcher was connected but did not answer in time. */
    public static final String TIMED_OUT_TEXT = "The launcher didn't answer in time. Try again.";
    /** The file route waited its whole timeout and nothing came. */
    public static final String FILE_ROUTE_SILENT_TEXT = "The launcher didn't deliver the script in time. "
            + "Restart the host from the launcher if this keeps happening.";
    /** The delivery did not say the same as the request. */
    public static final String MISMATCH_TEXT =
            "The launcher's delivery did not match what was asked for. Try again.";

    private SdnReasonText() {
    }

    /**
     * The text for a refusal.
     *
     * @param reason the launcher's code; empty from a launcher that predates them
     * @param name   the catalogue name of the script it is about
     * @param cfRay  the support reference, quoted when there is one
     */
    public static String of(Optional<String> reason, String name, Optional<String> cfRay) {
        return reason.map(code -> ofCode(code, name, cfRay)).orElseGet(() -> generic(name));
    }

    private static String ofCode(String code, String name, Optional<String> cfRay) {
        return switch (code) {
            case NOT_OWNED -> "This host isn't one the launcher is managing (the launcher may have restarted "
                    + "or updated). Restart the host from the launcher.";
            case NOT_SIGNED_IN -> "Sign in to the BotWithUs launcher, then try again.";
            case NO_LINK -> "The launcher isn't connected to BotWithUs right now. Try again in a moment. "
                    + "If it keeps happening, start a game client from the launcher first.";
            case HEARTBEAT_REFUSED -> "BotWithUs refused this launcher session. Sign out of the launcher "
                    + "and back in, then try again.";
            case SESSION_REJECTED -> "Your launcher login has expired. Sign in again, then try again.";
            case NOT_ENTITLED -> "Subscription needed: your account doesn't have " + name
                    + ". Subscribe on the website, then try again.";
            case NO_BUILD -> name + " has no version for this host yet. Try again later.";
            case UNSUPPORTED -> name + " can't be installed on this version. Update the launcher, then try again.";
            case CLOUDFLARE_CHALLENGE -> "The download was blocked by a network check. Try again in a minute."
                    + cfRay.map(ray -> " If it keeps happening, report it with reference " + ray + ".")
                    .orElse(" If it keeps happening, report it.");
            case SERVER_ERROR -> "The BotWithUs server had a problem. Try again in a few minutes.";
            case NETWORK -> "The launcher couldn't reach BotWithUs. Check your internet connection, then try again.";
            case TIMEOUT -> "The download took too long. Try again. A slow or filtered connection can cause this.";
            case HOST_NOT_ON_PIPE ->
                    "This host is not connected to the launcher. Restart the host from the BotWithUs launcher.";
            default -> generic(name);
        };
    }

    private static String generic(String name) {
        return "The launcher couldn't deliver " + name
                + ". Try again. If it keeps happening, report a problem from the host.";
    }

    /**
     * Whether {@code reason} is about this host, its launcher or its account rather
     * than the script asked for: every other script would get the same answer, so a
     * restore stops at it and does not count it against the script.
     */
    public static boolean isHostReason(String reason) {
        return switch (reason) {
            case NO_LINK, TIMEOUT, NETWORK, NOT_OWNED, NOT_SIGNED_IN, HEARTBEAT_REFUSED, SESSION_REJECTED,
                 CLOUDFLARE_CHALLENGE -> true;
            default -> false;
        };
    }

    /**
     * Whether a refusal for {@code reason} could pass once a game client connects or
     * the network recovers, so a client connecting is worth an early retry.
     */
    public static boolean isLinkReason(String reason) {
        return reason.equals(NO_LINK) || reason.equals(TIMEOUT) || reason.equals(NETWORK);
    }

    /** Whether a refusal for {@code reason} will be the same on every retry in this run. */
    public static boolean isLastingForThisRun(String reason) {
        return reason.equals(NOT_ENTITLED) || reason.equals(NO_BUILD) || reason.equals(UNSUPPORTED);
    }
}
