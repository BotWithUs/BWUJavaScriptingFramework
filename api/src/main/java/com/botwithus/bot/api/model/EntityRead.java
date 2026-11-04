package com.botwithus.bot.api.model;

/**
 * The answer to a wire-v25 entity read: what the agent said, and whether it said anything at all.
 * It keeps "the entity is silent" and "the read was refused" apart, which a bare empty value cannot.
 *
 * @param status {@link EntityReadStatus#OK} when {@link #value()} is a real reading
 * @param value  the reading; on a refusal, the empty form of the type (never {@code null})
 * @param isTruncated true when the agent held more than it could send: {@link #value()} is real but
 *               incomplete (a list is missing rows, a text was cut). Always false on a refusal
 * @param <T>    the reading's type
 */
public record EntityRead<T>(EntityReadStatus status, T value, boolean isTruncated) {

    /** A reading the agent answered. */
    public static <T> EntityRead<T> ok(T value, boolean isTruncated) {
        return new EntityRead<>(EntityReadStatus.OK, value, isTruncated);
    }

    /** A refused read carrying its reason and the empty form of the type. */
    public static <T> EntityRead<T> refused(EntityReadStatus status, T empty) {
        return new EntityRead<>(status, empty, false);
    }

    /** True when {@link #status()} is {@link EntityReadStatus#OK}. */
    public boolean isAvailable() {
        return status == EntityReadStatus.OK;
    }
}
