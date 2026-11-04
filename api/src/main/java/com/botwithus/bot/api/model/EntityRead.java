package com.botwithus.bot.api.model;

/**
 * The answer to a wire-v25 entity read: what the agent said, and whether it said anything at all.
 * It keeps "the entity is silent" and "the read was refused" apart, which a bare empty value cannot.
 *
 * @param status {@link EntityReadStatus#OK} when {@link #value()} is a real reading
 * @param value  the reading; on a refusal, the empty form of the type (never {@code null})
 * @param isTruncated true when {@link #value()} is real but incomplete: a list hit a cap (32 arrows,
 *               64 headbars), a budget or a damaged link, or a text was longer than the 255 bytes
 *               kept. Always false on a refusal
 * @param refusalText what the agent said when it refused, verbatim; empty on an answer. It is what
 *               explains an {@link EntityReadStatus#UNRECOGNISED} status
 * @param <T>    the reading's type
 */
public record EntityRead<T>(EntityReadStatus status, T value, boolean isTruncated, String refusalText) {

    private static final String NO_REFUSAL = "";

    /** A reading the agent answered. */
    public static <T> EntityRead<T> ok(T value, boolean isTruncated) {
        return new EntityRead<>(EntityReadStatus.OK, value, isTruncated, NO_REFUSAL);
    }

    /** A refused read carrying its reason and the empty form of the type. */
    public static <T> EntityRead<T> refused(EntityReadStatus status, T empty, String refusalText) {
        return new EntityRead<>(status, empty, false, refusalText == null ? NO_REFUSAL : refusalText);
    }

    /** True when {@link #status()} is {@link EntityReadStatus#OK}. */
    public boolean isAvailable() {
        return status == EntityReadStatus.OK;
    }
}
