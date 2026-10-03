package com.botwithus.bot.core.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Whether this run may use the development overrides for the launcher
 * service: a sandboxed service scope ({@code BWU_DEV_SVC_SCOPE}) and an
 * automatic answer to a close request (for tests that must not be clicked by a
 * person).
 *
 * <p>Both of these must hold:</p>
 * <ul>
 *   <li>the system property {@value #PROPERTY} is {@code true};</li>
 *   <li>the system property {@value #JPACKAGE_PROPERTY} is absent. Every
 *       jpackage launcher sets it, so the packaged {@code BotWithUs.exe}
 *       never honours either override, whatever JVM options it is given.</li>
 * </ul>
 *
 * <p>Java has no compile-time debug build, so unlike the launcher and the
 * native host this gate is checked at run time. It is not a security boundary
 * (launcher ADR 0007, section 2.8): any program the user runs can reach the
 * service's pipe without it. It exists so that a development override cannot
 * reach a user's installed host by accident.</p>
 *
 * @param isEnabled whether the overrides are honoured
 */
public record DevGate(boolean isEnabled) {

    /** The opt-in system property. */
    public static final String PROPERTY = "botwithus.dev.serviceScope";
    /** Set by every jpackage launcher; its presence disables the gate. */
    public static final String JPACKAGE_PROPERTY = "jpackage.app-path";
    /** The launcher's own variable for a sandboxed service scope. */
    public static final String SCOPE_VARIABLE = "BWU_DEV_SVC_SCOPE";
    /** The system property naming a close-request answer for tests. */
    public static final String CLOSE_ANSWER_PROPERTY = "botwithus.dev.closeRequestAnswer";
    /** The scope prefix the launcher adds to an override. */
    public static final String DEV_SCOPE_PREFIX = "dev";
    /** Longest override value the launcher accepts. */
    public static final int MAX_SCOPE_VALUE_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(DevGate.class);

    /** @return the gate as this process's system properties set it */
    public static DevGate fromSystemProperties() {
        return from(System::getProperty);
    }

    /**
     * @param properties reads a system property; {@code null} when unset
     * @return the gate those properties set
     */
    public static DevGate from(UnaryOperator<String> properties) {
        boolean isOptedIn = Boolean.parseBoolean(properties.apply(PROPERTY));
        boolean isPackaged = properties.apply(JPACKAGE_PROPERTY) != null;
        if (isOptedIn && isPackaged) {
            log.warn("{} is set, but this is a packaged host; the development overrides stay off", PROPERTY);
        }
        return new DevGate(isOptedIn && !isPackaged);
    }

    /**
     * The service scope override, validated exactly as the launcher's
     * {@code DevScopeOverride} validates it: 1 to 32 ASCII letters and digits,
     * which become {@code "dev" + value}. Anything else is ignored with a warning.
     *
     * @param environment reads an environment variable; {@code null} when unset
     * @return the overriding scope, when the gate is open and the value is valid
     */
    public Optional<String> scopeOverride(UnaryOperator<String> environment) {
        String value = environment.apply(SCOPE_VARIABLE);
        if (value == null) {
            return Optional.empty();
        }
        if (!isEnabled) {
            log.debug("{} is set but {} is not; using the real service scope", SCOPE_VARIABLE, PROPERTY);
            return Optional.empty();
        }
        if (!isValidScopeValue(value)) {
            log.warn("{} must be 1 to {} ASCII letters and digits; ignoring it", SCOPE_VARIABLE,
                    MAX_SCOPE_VALUE_LENGTH);
            return Optional.empty();
        }
        log.warn("Development service scope in effect: {}{}", DEV_SCOPE_PREFIX, value);
        return Optional.of(DEV_SCOPE_PREFIX + value);
    }

    /**
     * The answer a test asks the close-request prompt to give by itself.
     *
     * @param properties reads a system property
     * @return the answer, when the gate is open and the property names one
     */
    public Optional<CloseDecision> closeRequestAnswer(UnaryOperator<String> properties) {
        String value = properties.apply(CLOSE_ANSWER_PROPERTY);
        if (value == null || !isEnabled) {
            return Optional.empty();
        }
        for (CloseDecision decision : CloseDecision.values()) {
            if (decision.wire().equals(value.toLowerCase(Locale.ROOT))) {
                return Optional.of(decision);
            }
        }
        log.warn("{}={} is not closing, declined or later; ignoring it", CLOSE_ANSWER_PROPERTY, value);
        return Optional.empty();
    }

    static boolean isValidScopeValue(String value) {
        if (value.isEmpty() || value.length() > MAX_SCOPE_VALUE_LENGTH) {
            return false;
        }
        return value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
    }
}
