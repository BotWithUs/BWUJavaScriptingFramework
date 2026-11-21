package com.botwithus.bot.core.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One finished alert, handed to the launcher to send. The host has already decided
 * everything about it: the words, whether it is a problem, and which services get
 * it. The launcher only renders and sends it.
 *
 * @param kind      what it is about, for the launcher's logs and status: an
 *                  {@link AlertKind#id()}, {@link #KIND_BURST} or {@link #KIND_HELD}
 * @param message   the title, body and whether it reports a problem
 * @param services  where it goes; never empty
 * @param dedupeKey the same for every send of this one message, a replay included,
 *                  so the launcher can drop a copy it has already queued; always present
 *                  in what this host composes, optional on the wire
 * @param at        when it was composed; the launcher drops it if it is still
 *                  waiting to go out long after this
 */
public record AlertDelivery(String kind, AlertMessage message, Set<AlertService> services,
                            Optional<String> dedupeKey, Instant at) {

    /** The kind of a message that collects several alerts from one burst. */
    public static final String KIND_BURST = "burst";
    /** The kind of the message sent when quiet hours end, for what they held back. */
    public static final String KIND_HELD = "held";

    /** Hex digits of the digest kept in a dedupe key: 128 bits, ample for one minute of alerts. */
    static final int DEDUPE_HEX_CHARS = 32;
    private static final String DIGEST = "SHA-256";
    private static final String FIELD_SEPARATOR = "\n";
    private static final String KEY_SEPARATOR = "-";

    public AlertDelivery {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        Objects.requireNonNull(at, "at");
        if (services.isEmpty()) {
            throw new IllegalArgumentException("a delivery needs at least one service");
        }
        services = Set.copyOf(EnumSet.copyOf(services));
    }

    /**
     * A delivery whose dedupe key is worked out from everything it says, so that
     * composing the same message again, or sending this one twice, gives the same key.
     */
    public static AlertDelivery compose(String kind, AlertMessage message, Set<AlertService> services, Instant at) {
        return new AlertDelivery(kind, message, services, Optional.of(dedupeKey(kind, message, services, at)), at);
    }

    /** {@code <kind>-<first 32 hex digits of SHA-256 over the message, services and time>}. */
    static String dedupeKey(String kind, AlertMessage message, Set<AlertService> services, Instant at) {
        String names = EnumSet.copyOf(services).stream().map(AlertService::id).collect(Collectors.joining(","));
        String canonical = String.join(FIELD_SEPARATOR, kind, message.title(), message.body(),
                String.valueOf(message.isProblem()), names, String.valueOf(at.toEpochMilli()));
        return kind + KEY_SEPARATOR + HexFormat.of().formatHex(sha256(canonical)).substring(0, DEDUPE_HEX_CHARS);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance(DIGEST).digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has " + DIGEST, e);
        }
    }
}
