package com.botwithus.bot.core.launcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The {@code <scope>} in the launcher service's pipe names (ADR 2.1): the first
 * 8 bytes of SHA-256({@code "<user SID>|<session id>"}), as 16 lower-case hex
 * characters. It separates users and logon sessions; it is not a secret and
 * not a security boundary.
 */
public final class ServiceScope {

    /** How many bytes of the digest the scope keeps. */
    private static final int SCOPE_BYTES = 8;

    private ServiceScope() {
    }

    /**
     * The scope for a user and a logon session.
     *
     * @param sid       the user's SID string, for example {@code S-1-5-21-1-2-3-1001};
     *                  upper-cased here with invariant rules
     * @param sessionId the logon session id
     * @return 16 lower-case hex characters
     */
    public static String compute(String sid, long sessionId) {
        String input = sid.toUpperCase(Locale.ROOT) + "|" + sessionId;
        byte[] digest = sha256(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, SCOPE_BYTES);
    }

    /**
     * This process's scope, read from its token and session through Panama.
     *
     * @return the identity and the scope computed from it
     */
    public static ScopeIdentity current() {
        return new ScopeNative().read();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime provides SHA-256", e);
        }
    }

    /**
     * Who this process runs as, for naming the service's pipe and finding its
     * user-stopped flag.
     *
     * @param sid       the token user's SID string
     * @param sessionId the logon session id
     */
    public record ScopeIdentity(String sid, long sessionId) {

        /** @return the real (non-development) scope for this identity */
        public String scope() {
            return compute(sid, sessionId);
        }
    }
}
